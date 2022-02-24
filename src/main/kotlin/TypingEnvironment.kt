package org.jetbrains.kotlin.types.play

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.collections.immutable.persistentListOf
import kotlin.reflect.*

abstract class TypingEnvironment {
    companion object {
        fun defaultSubtypingRelation(from: KsConstructor, to: KsConstructor): SubtypingRelation = when {
            from == to -> SubtypingRelation.Equivalent
            from == KsConstructor.Any -> SubtypingRelation.Supertype
            from == KsConstructor.Nothing -> SubtypingRelation.Subtype
            to == KsConstructor.Any -> SubtypingRelation.Subtype
            to == KsConstructor.Nothing -> SubtypingRelation.Supertype
            else -> SubtypingRelation.Unrelated
        }
    }

    abstract infix fun KsConstructor.subtypingRelationTo(that: KsConstructor): SubtypingRelation
    infix fun KsConstructor.subtypeOf(that: KsConstructor): Boolean =
        SubtypingRelation.Subtype in this.subtypingRelationTo(that)
    infix fun KsConstructor.supertypeOf(that: KsConstructor): Boolean =
        SubtypingRelation.Supertype in this.subtypingRelationTo(that)

    inline infix fun KsType.subtypeOf(that: KsType): Boolean =
        SubtypingRelation.Subtype in this.subtypingRelationTo(that)
    inline infix fun KsType.supertypeOf(that: KsType): Boolean =
        SubtypingRelation.Supertype in this.subtypingRelationTo(that)

    inline infix fun KsType.subtypingRelationTo(that: KsType): SubtypingRelation =
        this.subtypingRelationTo(this@TypingEnvironment, that)

    abstract fun KsConstructor.getEffectiveSupertypeByConstructor(that: KsConstructor): KsBaseType
    abstract fun KsTypeApplication.remapTypeArguments(subtype: KsTypeApplication): KsTypeApplication

    abstract fun declsiteVariance(constructor: KsConstructor, index: Int): Variance
}

object EmptyEnvironment: TypingEnvironment() {
    override fun KsConstructor.subtypingRelationTo(that: KsConstructor): SubtypingRelation =
        defaultSubtypingRelation(this, that)

    override fun KsConstructor.getEffectiveSupertypeByConstructor(that: KsConstructor): KsBaseType {
        if (that == KsConstructor.Any) return KsConstructor.Any
        else throw IllegalStateException()
    }

    override fun KsTypeApplication.remapTypeArguments(subtype: KsTypeApplication): KsTypeApplication {
        return this
    }

    override fun declsiteVariance(constructor: KsConstructor, index: Int): Variance {
        return Variance.Invariant
    }
}

class DeclEnvironment: TypingEnvironment() {

    data class KsTypeParameter(
        val constructor: KsConstructor,
        val variance: Variance = Variance.Invariant,
        val bounds: PersistentSet<KsType> = persistentHashSetOf()
    )

    data class KsTypeDeclaration(
        val constructor: KsConstructor,
        val params: PersistentList<KsTypeParameter>,
        val supertypes: PersistentSet<KsBaseType>
    )

    val decls: MutableMap<KsConstructor, KsTypeDeclaration> = mutableMapOf()

    fun addDeclaration(decl: KsTypeDeclaration) {
        // TODO: remap all supertypes, for now you have to specify them explicitly
        decls[decl.constructor] = decl
    }
    private fun inject(kClass: KClassifier): KsType = when(kClass) {
        is KClass<*> -> {
            addDeclaration(kClass)
            KsConstructor(env, kClass.qualifiedName!!)
        }
        is KTypeParameter -> KsConstructor(env, kClass.name)
        else -> TODO()
    }
    private fun inject(variance: KVariance): Variance = when(variance) {
        KVariance.INVARIANT -> Variance.Invariant
        KVariance.IN -> Variance.Contravariant
        KVariance.OUT -> Variance.Covariant
    }
    private fun inject(tp: KTypeParameter): KsTypeParameter =
            KsTypeParameter(
                KsConstructor(tp.name),
                inject(tp.variance),
                tp.upperBounds.mapTo(persistentHashSetOf()) { inject(it) }
            )
    private fun inject(ktype: KType): KsType {
        var res: KsType = inject(ktype.classifier!!)
        if (ktype.arguments.isNotEmpty()) {
            res = KsTypeApplication(this, res as KsConstructor, ktype.arguments.mapTo(persistentListOf()) { (variance, type) ->
                if (variance == null || type == null) KsProjection.Star
                else KsProjection(inject(variance), inject(type))
            })
        }
        if (ktype.isMarkedNullable) {
            res = KsNullable(this, res)
        }
        return res
    }
    private val declarationStubs: MutableSet<KsConstructor> = mutableSetOf()
    fun addDeclaration(kClass: KClass<*>) {
        val constructor = KsConstructor(kClass.qualifiedName!!)
        if (constructor in decls || constructor in declarationStubs) return
        declarationStubs.add(constructor)
        val params = kClass.typeParameters.mapTo(persistentListOf()) { inject(it) }
        // TODO: remap all supertypes
        val supertypes = kClass.supertypes.mapTo(persistentHashSetOf()) { inject(it) as KsBaseType }
        addDeclaration(KsTypeDeclaration(constructor, params, supertypes))
        declarationStubs.remove(constructor)
    }

    operator fun KClass<*>.provideDelegate(thisRef: Any?, kProperty: KProperty<*>): Box<KsConstructor> {
        addDeclaration(this)
        return Box(KsConstructor(qualifiedName!!))
    }

    override fun KsConstructor.subtypingRelationTo(that: KsConstructor): SubtypingRelation {
        val tryEmpty = defaultSubtypingRelation(this, that)
        if (tryEmpty != SubtypingRelation.Unrelated) return tryEmpty

        val thisSupertypes = decls[this]?.supertypes ?: return SubtypingRelation.Unrelated
        val thatSupertypes = decls[that]?.supertypes ?: return SubtypingRelation.Unrelated

        if (that in thisSupertypes) return SubtypingRelation.Subtype
        if (this in thatSupertypes) return SubtypingRelation.Supertype

        if (thisSupertypes.any { it.constructor == that })
            return SubtypingRelation.Subtype

        if (thatSupertypes.any { it.constructor == this })
            return SubtypingRelation.Supertype

        return SubtypingRelation.Unrelated
    }

    override fun KsConstructor.getEffectiveSupertypeByConstructor(that: KsConstructor): KsBaseType {
        return decls[this]?.supertypes?.find { it.constructor == that }!!
    }

    override fun KsTypeApplication.remapTypeArguments(subtype: KsTypeApplication): KsTypeApplication {
        var me: KsType = this
        val originalDecl = decls[subtype.constructor] ?: throw IllegalArgumentException()
        val originalParams = originalDecl.params

        check(originalParams.size == subtype.args.size)

        for((p, a) in originalParams.zip(subtype.args)) {
            me = me.replace(this@DeclEnvironment, p.constructor, a)
        }

        check(me is KsTypeApplication)

        return me
    }

    override fun declsiteVariance(constructor: KsConstructor, index: Int): Variance {
        return decls[constructor]?.params?.get(index)?.variance ?: Variance.Invariant // for underdefined environments
    }

}

