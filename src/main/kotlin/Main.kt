package org.jetbrains.kotlin.types.play

import kotlinx.collections.immutable.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

enum class SubtypingRelation {

    Subtype, Supertype, Equivalent, Unrelated;

    fun invert(): SubtypingRelation = when(this) {
        Subtype -> Supertype
        Supertype -> Subtype
        else -> this
    }

    infix fun or(that: SubtypingRelation): SubtypingRelation = when {
        this == that -> this
        this == Equivalent -> Equivalent
        this == Unrelated -> that
        this == Subtype && that == Supertype -> Equivalent
        else -> that or this
    }

    infix fun and(that: SubtypingRelation): SubtypingRelation = when {
        this == that -> this
        this == Equivalent -> that
        this == Unrelated -> Unrelated
        this == Subtype && that == Supertype -> Unrelated
        else -> that and this
    }

    infix operator fun contains(that: SubtypingRelation): Boolean = when {
        this == that -> true
        this == Equivalent -> true
        that == Unrelated -> true
        else -> false
    }
}

enum class Variance { Covariant, Contravariant, Invariant }

sealed interface KsType {
    fun normalizeWithStructure(env: TypingEnvironment): KsType
    fun normalizeWithSubtyping(env: TypingEnvironment): KsType = this
    fun subtypingRelationTo(env: TypingEnvironment, that: KsType): SubtypingRelation

    companion object {
        val Top = KsNullable.Any
        val Bottom = KsConstructor.Nothing
    }
}

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

inline fun <reified T: KsType, Arg> makeNormalized(env: TypingEnvironment,
                                                   constructor: (Arg) -> T, arg: Arg): KsType =
    constructor(arg).normalizeWithStructure(env).normalizeWithSubtyping(env)

inline fun <reified T: KsType, A, B> makeNormalized(env: TypingEnvironment,constructor: (A, B) -> T, a: A, b: B): KsType =
    constructor(a, b).normalizeWithStructure(env).normalizeWithSubtyping(env)

private fun parenthesize(type: KsType) = when(type) {
    is KsConstructor, is KsTypeApplication, is KsNullable -> "$type"
    else -> "($type)"
}

private inline fun <E: Any, reified T: E> Iterator<E>.partitionInstanceOf(kClass: KClass<T>, predicate: (T) -> Boolean = {true}) =
    partitionTo(persistentHashSetBuilder(), persistentHashSetBuilder()) { it is T && predicate(it) }
        as Pair<PersistentSet.Builder<T>, PersistentSet.Builder<E>>

data class KsProjection(val outBound: KsType, val inBound: KsType = outBound) {
    override fun toString(): String {
        when {
            outBound == inBound -> return "$outBound"
            outBound == KsType.Top && inBound == KsType.Bottom -> return "*"
            outBound == KsType.Top -> return "in ${parenthesize(inBound)}"
            inBound == KsType.Bottom -> return "out ${parenthesize(outBound)}"
            else -> return "{in ${parenthesize(inBound)}, out ${parenthesize(outBound)}}"
        }
    }

    companion object {
        fun In(type: KsType) = KsProjection(KsType.Top, type)
        fun Out(type: KsType) = KsProjection(type, KsType.Bottom)
        val Star = KsProjection(KsType.Top, KsType.Bottom)
    }
}

fun KsProjection(variance: Variance, type: KsType) = when(variance) {
    Variance.Covariant -> KsProjection.Out(type)
    Variance.Contravariant -> KsProjection.In(type)
    Variance.Invariant -> KsProjection(type)
}

data class KsFlexible(val from: KsType, val to: KsType): KsType {
    override fun subtypingRelationTo(env: TypingEnvironment, that: KsType): SubtypingRelation {
        if (that is KsFlexible) {
            return subtypingRelationTo(env, that.from) or subtypingRelationTo(env, that.to)
        }
        return from.subtypingRelationTo(env, that) or to.subtypingRelationTo(env, that)
    }

    override fun normalizeWithStructure(env: TypingEnvironment): KsType = when {
        from == to -> from
        from is KsFlexible || to is KsFlexible -> {
            val adjFrom = if (from is KsFlexible) from.from else from
            val adjTo = if (to is KsFlexible) to.to else to
            copy(adjFrom, adjTo)
        }
        else -> this
    }

    override fun normalizeWithSubtyping(env: TypingEnvironment): KsType = with(env) {
        if (!(to supertypeOf from)) throw IllegalStateException("Incorrect flexible type: $this")
        this@KsFlexible
    }

    override fun toString(): String {
        return "${parenthesize(from)}..${parenthesize(to)}"
    }
}
fun KsFlexible(
    environment: TypingEnvironment,
    from: KsType,
    to: KsType
): KsType = makeNormalized(environment, ::KsFlexible, from, to)

data class KsNullable(val base: KsType): KsType {
    override fun subtypingRelationTo(env: TypingEnvironment, that: KsType): SubtypingRelation = with(env) {
        if (that is KsFlexible) return that.subtypingRelationTo(this@KsNullable).invert()
        if (that is KsNullable) return base.subtypingRelationTo(that.base)

        return when {
            base supertypeOf that -> SubtypingRelation.Supertype
            else -> SubtypingRelation.Unrelated
        }
    }

    override fun normalizeWithStructure(env: TypingEnvironment): KsType = when(base) {
        is KsFlexible ->
            KsFlexible(env, KsNullable(env, base.from), KsNullable(env, base.to))
        is KsNullable -> base
        else -> this
    }

    override fun toString(): String = "${parenthesize(base)}?"

    companion object {
        val Any = KsNullable(KsConstructor.Any)
        val Nothing = KsNullable(KsConstructor.Nothing)
    }
}
fun KsNullable(env: TypingEnvironment, base: KsType): KsType = makeNormalized(env, ::KsNullable, base)

data class KsUnion(val args: PersistentSet<KsType>): KsType {
    override fun subtypingRelationTo(env: TypingEnvironment, that: KsType): SubtypingRelation = with (env) {
        when (that) {
            is KsFlexible, is KsNullable -> return that.subtypingRelationTo(this@KsUnion).invert()
            else -> {}
        }
        if (that in args) return SubtypingRelation.Supertype
        if (that is KsUnion) {
            if (args == that.args) return SubtypingRelation.Equivalent
            if (args.containsAll(that.args)) return SubtypingRelation.Supertype
            if (that.args.containsAll(args)) return SubtypingRelation.Subtype

            var result: SubtypingRelation = SubtypingRelation.Unrelated

            // forall thatElement in that.args exists thisElement in args such that
            //          thisElement >: thatElement
            infix fun PersistentSet<KsType>.superArgs(that: PersistentSet<KsType>): Boolean {
                return that.all { thatElement ->
                    this.any { thisElement -> thisElement supertypeOf thatElement }
                }
            }
            if (args superArgs that.args)
                result = result or SubtypingRelation.Supertype
            if (that.args superArgs args)
                result = result or SubtypingRelation.Subtype
            return result
        } else {
            if (args.any { it supertypeOf that })
                return SubtypingRelation.Supertype
            else if (args.all { it subtypeOf that })
                return SubtypingRelation.Subtype
            else return SubtypingRelation.Unrelated
        }
    }

    fun handleArg(arg: KsType, resArgs: PersistentSet.Builder<KsType>) {
        when (arg) {
            is KsFlexible, is KsNullable -> throw IllegalStateException()
            is KsUnion -> {
                resArgs += arg.args
            }
            else -> resArgs += arg
        }
    }

    fun handleProjections(env: TypingEnvironment, i: Iterable<KsProjection>) = KsProjection(
        inBound = KsIntersection(env, i.mapTo(persistentHashSetOf()) { it.inBound }),
        outBound = KsUnion(env, i.mapTo(persistentHashSetOf()) { it.outBound })
    )

    private fun make(args: PersistentSet<KsType>) = when(args.size) {
        0 -> KsConstructor.Any
        1 -> args.first()
        else -> copy(args = args)
    }

    override fun normalizeWithStructure(env: TypingEnvironment): KsType {
        require(args.size > 0)
        if (args.size == 1) return args.first()

        val resArgs = persistentHashSetBuilder<KsType>()
        val iterator = args.iterator()

        for (arg in iterator) {
            when (arg) {
                is KsFlexible -> {
                    val (f, nf) = iterator.partitionInstanceOf(KsFlexible::class)
                    f += arg
                    nf += resArgs
                    // What?
                    return KsFlexible(
                        env,
                        KsUnion(
                            env,
                            nf.build() + f.mapTo(persistentHashSetBuilder()) { it.from }
                        ),
                        KsUnion(
                            env,
                            nf.build() + f.mapTo(persistentHashSetBuilder()) { it.to }
                        )
                    )
                }
                is KsNullable -> {
                    val (n, nn) = iterator.partitionInstanceOf(KsNullable::class)
                    n += arg
                    nn += resArgs
                    // What?
                    return KsNullable(
                        env,
                        KsUnion(
                            env,
                            n.mapTo(persistentHashSetOf()) { it.base }.addAll(nn)
                        )
                    )
                }

                is KsTypeApplication -> {
                    val (generics, notGenerics) = iterator.partitionInstanceOf(KsTypeApplication::class)

                    generics += arg
                    notGenerics += resArgs

                    val nonAppUnion = when {
                        notGenerics.isEmpty() -> null
                        else -> when (val res = KsUnion(env, notGenerics.build())) {
                            is KsTypeApplication -> {
                                generics += res
                                null
                            }
                            else -> res
                        }
                    }

                    val byConstructor = generics.groupByTo(mutableMapOf()) { it.constructor }
                    val applications = byConstructor.mapTo(persistentHashSetBuilder()) { (c, g) ->
                        val representative = g.first()
                        if (g.size > 1) {
                            KsTypeApplication(
                                env,
                                c,
                                representative.args.indices.mapTo(persistentListOf()) { ix ->
                                    handleProjections(env, g.map { it.args[ix] })
                                }
                            )
                        } else representative
                    }

                    val appUnion = make(applications.build())
                    if (nonAppUnion == null) return appUnion

                    // fragile: we may get results that are exactly ones we had in the first place,
                    // resulting in infinite recursion
                    if (appUnion in args && nonAppUnion in args) {
                        return make(persistentHashSetOf(appUnion, nonAppUnion))
                    }

                    return KsUnion(env, appUnion, nonAppUnion)
                }
                else -> handleArg(arg, resArgs)
            }

        }
        return make(resArgs.build())
    }

    override fun normalizeWithSubtyping(env: TypingEnvironment): KsType = with(env) {
        val subtypes = args.filterTo(persistentHashSetOf()) { l ->
            args.any { l != it && l subtypeOf it }
        }
        if (subtypes.isEmpty()) return this@KsUnion

        val newArgs = args.removeAll(subtypes)

        return make(newArgs)
    }

    override fun toString(): String {
        return args.joinToString(" | ")
    }
}
fun KsUnion(env: TypingEnvironment, args: PersistentSet<KsType>): KsType =
    makeNormalized(env, ::KsUnion, args)
fun KsUnion(env: TypingEnvironment, vararg args: KsType): KsType =
    makeNormalized(env, ::KsUnion, persistentHashSetOf(*args))
data class KsIntersection(val args: PersistentSet<KsType>): KsType {
    override fun subtypingRelationTo(env: TypingEnvironment, that: KsType): SubtypingRelation = with(env) {
        when (that) {
            is KsFlexible, is KsNullable, is KsUnion ->
                return that.subtypingRelationTo(this@KsIntersection).invert()
            else -> {}
        }

        if (that in args) return SubtypingRelation.Subtype

        if (that is KsIntersection) {
            if (args == that.args) return SubtypingRelation.Equivalent
            if (args.containsAll(that.args)) return SubtypingRelation.Subtype
            if (that.args.containsAll(args)) return SubtypingRelation.Supertype

            infix fun PersistentSet<KsType>.subArgs(that: PersistentSet<KsType>): Boolean {
                return that.all { thatElement ->
                    this.any { thisElement -> thisElement subtypeOf thatElement }
                }
            }

            var result: SubtypingRelation = SubtypingRelation.Unrelated
            if (args subArgs that.args)
                result = result or SubtypingRelation.Subtype
            if (that.args subArgs args)
                result = result or SubtypingRelation.Supertype
            return result
        } else {
            if (args.any { it subtypeOf that }) return SubtypingRelation.Subtype
            if (args.all { it supertypeOf that }) return SubtypingRelation.Supertype
            return SubtypingRelation.Unrelated
        }
    }


    fun handleArg(arg: KsType, resArgs: PersistentSet.Builder<KsType>) {
        when (arg) {
            is KsFlexible, is KsNullable, is KsUnion -> throw IllegalStateException()
            is KsIntersection -> {
                resArgs += arg.args
            }
            else -> resArgs += arg
        }
    }


    fun handleProjections(env: TypingEnvironment, i: Iterable<KsProjection>) = KsProjection(
        inBound = KsUnion(env, i.mapTo(persistentHashSetOf()) { it.inBound }),
        outBound = KsIntersection(env, i.mapTo(persistentHashSetOf()) { it.outBound })
    )

    private fun make(args: PersistentSet<KsType>) = when(args.size) {
        0 -> KsConstructor.Any
        1 -> args.first()
        else -> copy(args = args)
    }

    override fun normalizeWithStructure(env: TypingEnvironment): KsType {
        require(args.size > 0)
        if (args.size == 1) return args.first()

        val resArgs = persistentHashSetBuilder<KsType>()

        val iterator = args.iterator()
        for (arg in iterator) {
            when (arg) {
                is KsFlexible -> {
                    val (f, nf) = iterator.partitionInstanceOf(KsFlexible::class)
                    f += arg
                    nf += resArgs
                    // What?
                    return KsFlexible(
                        env,
                        KsIntersection(env, nf.build().addAll(
                            f.mapTo(persistentHashSetBuilder()) { it.from })),
                        KsIntersection(env, nf.build().addAll(
                            f.mapTo(persistentHashSetBuilder()) { it.to }))
                    )
                }
                is KsUnion -> {
                    val (u, nu) = iterator.partitionInstanceOf(KsUnion::class)
                    u += arg
                    nu += resArgs

                    return KsUnion(env, u.map { it.args }
                        .productTo(persistentHashSetOf())
                        .mapTo(persistentHashSetOf()) {
                            makeNormalized(env, ::KsIntersection, it.addAll(nu))
                        }
                    )
                }
                is KsNullable -> {
                    val (n, nn) = iterator.partitionInstanceOf(KsNullable::class)
                    n += arg
                    nn += resArgs

                    val banged = n.mapTo(persistentHashSetOf()) { it.base }

                    if (nn.isNotEmpty())
                        return KsIntersection(
                            env,
                            banged.addAll(nn)
                        )
                    else return KsNullable(
                        env,
                        KsIntersection(
                            env,
                            banged
                        )
                    )
                }

                is KsTypeApplication -> {
                    val (generics, notGenerics) = iterator.partitionInstanceOf(KsTypeApplication::class)

                    generics += arg
                    notGenerics += resArgs

                    val nonAppIntersection = when {
                        notGenerics.isEmpty() -> null
                        else -> when (val res = KsIntersection(env, notGenerics.build())) {
                            is KsTypeApplication -> {
                                generics += res
                                null
                            }
                            else -> res
                        }
                    }

                    val byConstructor = generics.groupByTo(mutableMapOf()) { it.constructor }
                    val applications = byConstructor.mapTo(persistentHashSetBuilder()) { (c, g) ->
                        val representative = g.first()
                        if (g.size > 1) {
                            KsTypeApplication(
                                env,
                                c,
                                representative.args.indices.mapTo(persistentListOf()) { ix ->
                                    handleProjections(env, g.map { it.args[ix] })
                                }
                            )
                        } else representative
                    }

                    val appIntersection = make(applications.build())
                    if (nonAppIntersection == null) return appIntersection

                    // fragile: we may get results that are exactly ones we had in the first place,
                    // resulting in infinite recursion
                    if (appIntersection in args && nonAppIntersection in args) {
                        return make(persistentHashSetOf(appIntersection, nonAppIntersection))
                    }

                    return KsIntersection(env, appIntersection, nonAppIntersection)
                }

                else -> handleArg(arg, resArgs)
            }

        }
        return make(resArgs.build())
    }

    override fun normalizeWithSubtyping(env: TypingEnvironment): KsType = with(env) {
        val supertypes = args.filterTo(persistentHashSetOf()) { l ->
            args.any { l != it && l supertypeOf it }
        }
        if (supertypes.isEmpty()) return this@KsIntersection

        val newArgs = args.removeAll(supertypes)

        return make(newArgs)
    }

    override fun toString(): String {
        return args.joinToString(" & ")
    }
}
fun KsIntersection(env: TypingEnvironment, args: PersistentSet<KsType>): KsType =
    makeNormalized(env, ::KsIntersection, args)
fun KsIntersection(env: TypingEnvironment, vararg args: KsType): KsType =
    makeNormalized(env, ::KsIntersection, persistentHashSetOf(*args))

sealed interface KsBaseType: KsType {
    val constructor: KsConstructor
}

data class KsConstructor(val name: String): KsBaseType {
    override val constructor: KsConstructor
        get() = this

    override fun normalizeWithStructure(env: TypingEnvironment): KsType = this

    override fun subtypingRelationTo(env: TypingEnvironment, that: KsType): SubtypingRelation =
        with(env) {
            when(that) {
                is KsFlexible, is KsNullable, is KsUnion, is KsIntersection ->
                    return that.subtypingRelationTo(this@KsConstructor).invert()
                else -> {}
            }

            if (that is KsConstructor) {
                that as KsConstructor
                return this@KsConstructor subtypingRelationTo that
            } else if (that is KsTypeApplication) {
                when (this@KsConstructor subtypingRelationTo that.constructor) {
                    SubtypingRelation.Supertype -> return SubtypingRelation.Supertype
                    SubtypingRelation.Unrelated -> return SubtypingRelation.Unrelated
                    else -> {
                        val effective = getEffectiveSupertypeByConstructor(that.constructor)
                        return that.subtypingRelationTo(effective).invert()
                    }
                }
            } else {
                throw IllegalStateException("Unknown type: $that")
            }
        }

    override fun toString(): String {
        return name
    }

    companion object {
        val Any = KsConstructor("Any")
        val Nothing = KsConstructor("Nothing")
    }
}
fun KsConstructor(env: TypingEnvironment, name: String): KsType =
    makeNormalized(env, ::KsConstructor, name)

data class KsTypeApplication
    internal constructor(override val constructor: KsConstructor,
                         val args: PersistentList<KsProjection>): KsBaseType {
    override fun subtypingRelationTo(env: TypingEnvironment, that: KsType): SubtypingRelation =
        with(env) {
            when(that) {
                is KsFlexible, is KsNullable, is KsUnion, is KsIntersection, is KsConstructor ->
                    that.subtypingRelationTo(this@KsTypeApplication).invert()
                is KsTypeApplication -> {
                    when(constructor subtypingRelationTo that.constructor) {
                        SubtypingRelation.Equivalent -> {
                            var res = SubtypingRelation.Equivalent
                            check (args.size == that.args.size)
                            for ((l, r) in (args zip that.args)) {
                                res = res and (l.inBound subtypingRelationTo r.inBound).invert()
                                res = res and (l.outBound subtypingRelationTo r.outBound)
                                if (res == SubtypingRelation.Unrelated) break
                            }
                            return res
                        }
                        SubtypingRelation.Supertype -> {
                            val actualBase =
                                that.constructor.getEffectiveSupertypeByConstructor(constructor)
                            when (actualBase) {
                                is KsConstructor -> SubtypingRelation.Supertype
                                is KsTypeApplication -> {
                                    val mappedBase = actualBase.remapTypeArguments(that)
                                    check(mappedBase.constructor == constructor)
                                    val eqCase = subtypingRelationTo(mappedBase) // recurse to equivalent case
                                    eqCase and SubtypingRelation.Supertype
                                }
                            }
                        }
                        SubtypingRelation.Subtype ->
                            that.subtypingRelationTo(this@KsTypeApplication).invert()
                        else -> SubtypingRelation.Unrelated
                    }
                }
                //else -> throw IllegalStateException("Unrecognized type: $that")
            }
        }

    override fun normalizeWithStructure(env: TypingEnvironment): KsType = when {
        args.isEmpty() -> constructor
        else -> copy(constructor, args)
    }

    override fun toString(): String {
        return "$constructor<${args.joinToString()}>"
    }
}
fun KsTypeApplication(env: TypingEnvironment,
                      constructor: KsConstructor,
                      args: PersistentList<KsProjection>): KsType =
    makeNormalized(env, ::KsTypeApplication, constructor, args)
fun KsTypeApplication(env: TypingEnvironment,
                      constructor: KsConstructor,
                      vararg args: KsProjection): KsType =
    KsTypeApplication(env, constructor, persistentListOf(*args))

context(TypingEnvironment)
inline val env get() = this@TypingEnvironment
//class KsTypeBuilder(val env: TypingEnvironment) {
context(TypingEnvironment)
infix fun KsType.or(that: KsType) = KsUnion(env, this, that)
context(TypingEnvironment)
infix fun KsType.and(that: KsType) = KsIntersection(env, this, that)
context(TypingEnvironment)
operator fun KsType.rangeTo(that: KsType) = KsFlexible(env, this, that)

context(TypingEnvironment)
fun union(vararg args: KsType) = KsUnion(env, *args)

context(TypingEnvironment)
fun intersect(vararg args: KsType) = KsIntersection(env, *args)

context(TypingEnvironment)
fun inp(type: KsType): KsProjection = KsProjection.In(type)

context(TypingEnvironment)
fun outp(type: KsType): KsProjection = KsProjection.Out(type)

context(TypingEnvironment)
fun invp(type: KsType): KsProjection = KsProjection(type)

context(TypingEnvironment)
val Star get() = KsProjection.Star

context(TypingEnvironment)
val Any get() = KsConstructor.Any

context(TypingEnvironment)
val Nothing get() = KsConstructor.Nothing

context(TypingEnvironment)
fun type(name: String): KsConstructor = KsConstructor(env, name) as KsConstructor

context(TypingEnvironment)
operator fun KsConstructor.invoke(vararg args: KsProjection): KsTypeApplication =
    KsTypeApplication(env, this, *args) as KsTypeApplication

context(TypingEnvironment)
operator fun KsConstructor.invoke(vararg args: KsType): KsTypeApplication {
    val argsWithVariance = args.withIndex().mapTo(persistentListOf()) { (i, it) ->
        KsProjection(declsiteVariance(this, i), it)
    }
    val result = KsTypeApplication(env, this, argsWithVariance)
    check(result is KsTypeApplication)
    return result
}

inline operator fun TypingEnvironment.getValue(thisRef: Any?, prop: KProperty<*>): KsConstructor =
    type(prop.name)

context(TypingEnvironment)
val KsType.q get() = KsNullable(env, this)

context(TypingEnvironment)
inline infix fun KsType.subtypeOf(that: KsType): Boolean =
    SubtypingRelation.Subtype in this.subtypingRelationTo(that)

context(TypingEnvironment)
inline infix fun KsType.supertypeOf(that: KsType): Boolean =
    SubtypingRelation.Supertype in this.subtypingRelationTo(that)

context(TypingEnvironment)
inline infix fun KsType.subtypingRelationTo(that: KsType): SubtypingRelation =
    this.subtypingRelationTo(env, that)
//}


inline fun <T> checkEquals(expected: T, actual: T) {
    check(expected == actual) {
        """ |Equality comparison failed:
            |Expected: $expected
            |Actual: $actual
        """.trimMargin()
    }
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

class MapToSet<K, V>(val inner: MutableMap<K, MutableSet<V>> = mutableMapOf()): Map<K, MutableSet<V>> by inner {
    override fun get(key: K): MutableSet<V> = inner.getOrPut(key) { mutableSetOf() }
    override fun getOrDefault(key: K, defaultValue: MutableSet<V>): MutableSet<V> =
        inner.getOrDefault(key, defaultValue)

    override fun equals(other: Any?): Boolean = inner.equals(other)
    override fun hashCode(): Int = inner.hashCode()
    override fun toString(): String = inner.toString()
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

fun KsType.replace(env: TypingEnvironment, what: KsConstructor, withWhat: KsProjection): KsType {
    fun KsType.replace(): KsType = replace(env, what, withWhat)
    return when(this) {
        what -> withWhat.outBound
        is KsConstructor -> this
        is KsTypeApplication ->
            when (constructor) {
                what -> withWhat.outBound
                else -> KsTypeApplication(
                    env,
                    constructor,
                    args.mapTo(persistentListOf()) {
                        if (it == KsProjection.Star) it
                        else KsProjection(
                            outBound = when (val outBound = it.outBound) {
                                what -> withWhat.outBound
                                else -> outBound.replace()
                            },
                            inBound = when (val inBound = it.inBound) {
                                what -> withWhat.inBound
                                else -> inBound.replace()
                            },
                        )
                    }
                )
            }
        is KsFlexible -> KsFlexible(env, from.replace(), to.replace())
        is KsIntersection -> KsIntersection(env, args.mapTo(persistentHashSetOf()) { it.replace() })
        is KsUnion -> KsUnion(env, args.mapTo(persistentHashSetOf()) { it.replace() })
        is KsNullable -> KsNullable(env, base.replace())
    }
}

fun KsType.replace(env: TypingEnvironment, what: KsConstructor, withWhat: KsType): KsType =
    replace(env, what, KsProjection(withWhat))

inline fun <C, T> withContext(context: C, body: context(C) () -> T ) = body(context)

suspend fun main() {
    val env = DeclEnvironment()
    with (env) {
        val T by env
        val A by env
        val TT by env

        checkEquals(KsUnion(persistentHashSetOf(T, A(outp(T)))), T or A(outp(T)))
        checkEquals(KsNullable(KsUnion(persistentHashSetOf(T, A(outp(T))))), T or A(outp(T)) or T.q)
        checkEquals(T, T or T)
        checkEquals(T, T and T)
        checkEquals(T and TT, TT and T)
        checkEquals(T.q, T.q.q.q.q.q)
        checkEquals(T or TT or A, TT or A or T)
        checkEquals(KsNullable(KsUnion(persistentHashSetOf(T, A(Star)))), T or A(Star).q)
        checkEquals(
            KsUnion(
                persistentHashSetOf(
                    KsIntersection(
                        persistentHashSetOf(TT, T)
                    ),
                    KsIntersection(
                        persistentHashSetOf(TT, A(Star))
                    )
                )
            ),
            (T or A(Star).q) and TT
        )
        checkEquals(
            A(Star),
            A(Star) or A(T)
        )
        checkEquals(
            A(T),
            A(Star) and A(T)
        )
        println(A(TT) or A(T))
        println(A(T) or A(T.q))

        println(A(T) and A(T.q))
        checkEquals(TT and T, (TT..TT.q.q.q.q.q) and T)
        checkEquals(TT, TT and TT)

        val a = T or Nothing.q
        val b = T.q
        checkEquals(SubtypingRelation.Subtype, T subtypingRelationTo a)
        checkEquals(SubtypingRelation.Subtype, T subtypingRelationTo a)
        checkEquals(SubtypingRelation.Equivalent, a subtypingRelationTo b)
        checkEquals(SubtypingRelation.Unrelated, (TT or T) subtypingRelationTo (T.q))

        checkEquals(SubtypingRelation.Subtype, (T and TT) subtypingRelationTo (T or TT))

        val List by env
        val MutableList by env

        addDeclaration(
            DeclEnvironment.KsTypeDeclaration(
                List,
                persistentListOf(DeclEnvironment.KsTypeParameter(T, Variance.Covariant)),
                persistentHashSetOf()
            )
        )

        checkEquals(List(outp(T)), List(T)) // variance injection check

        addDeclaration(
            DeclEnvironment.KsTypeDeclaration(
                MutableList,
                persistentListOf(DeclEnvironment.KsTypeParameter(T)),
                persistentHashSetOf(List(outp(T)))
            )
        )

        println(List(TT))
        println(List(List(TT)))
        println(MutableList(MutableList(TT)))

        println(List(TT) subtypingRelationTo MutableList(TT))
        checkEquals(List(TT), List(TT) or MutableList(TT))

        checkEquals(MutableList(TT), List(TT) and MutableList(TT))
        checkEquals(MutableList(MutableList(TT)), List(List(TT)) and MutableList(MutableList(TT)))

        println(A(inp(T)) and A(inp(TT)))
    }
}
