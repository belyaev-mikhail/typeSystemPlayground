
import kotlinx.collections.immutable.*
import kotlin.reflect.KClass

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

sealed interface KsType {
    fun normalizeStep(): KsType
    fun subtypingRelationTo(env: TypingEnvironment, that: KsType): SubtypingRelation

    companion object {
        val Top = KsNullable.Any
        val Bottom = KsConstructor.Nothing
    }
}

abstract class TypingEnvironment {
    abstract infix fun KsConstructor.subtypeOf(that: KsConstructor): Boolean
    infix fun KsConstructor.supertypeOf(that: KsConstructor): Boolean = that subtypeOf this



}

inline fun <reified T: KsType, Arg> makeNormalized(constructor: (Arg) -> T, arg: Arg): KsType =
    constructor(arg).normalizeStep()

inline fun <reified T: KsType, A, B> makeNormalized(constructor: (A, B) -> T, a: A, b: B): KsType =
    constructor(a, b).normalizeStep()

private fun parenthesize(type: KsType) = when(type) {
    is KsConstructor, is KsTypeApplication -> "$type"
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

data class KsFlexible(val from: KsType, val to: KsType): KsType {
    override fun subtypingRelationTo(env: TypingEnvironment, that: KsType): SubtypingRelation {
        if (that is KsFlexible) {
            return subtypingRelationTo(env, that.from) or subtypingRelationTo(env, that.to)
        }
        return from.subtypingRelationTo(env, that) or to.subtypingRelationTo(env, that)
    }

    override fun normalizeStep(): KsType = when {
        from == to -> from
        from is KsFlexible || to is KsFlexible -> {
            val adjFrom = if (from is KsFlexible) from.from else from
            val adjTo = if (to is KsFlexible) to.to else to
            copy(adjFrom, adjTo)
        }
        else -> this
    }

    override fun toString(): String {
        return "${parenthesize(from)}..${parenthesize(to)}"
    }
}
fun KsFlexible(env: TypingEnvironment,
               from: KsType,
               to: KsType): KsType = makeNormalized(::KsFlexible, from, to)

data class KsNullable(val base: KsType): KsType {
    override fun subtypingRelationTo(env: TypingEnvironment, that: KsType): SubtypingRelation {
        if (that is KsFlexible) return that.subtypingRelationTo(env, this).invert()
        if (that is KsNullable) return base.subtypingRelationTo(env, that.base)

        val toBase = base.subtypingRelationTo(env, that)
        return when {
            toBase contains SubtypingRelation.Supertype -> SubtypingRelation.Supertype
            else -> SubtypingRelation.Unrelated
        }
    }

    override fun normalizeStep(): KsType = when(base) {
        is KsFlexible ->
            makeNormalized(::KsFlexible,
                makeNormalized(::KsNullable, base.from),
                makeNormalized(::KsNullable, base.to)
            )
        is KsNullable -> base
        else -> this
    }

    override fun toString(): String = "${parenthesize(base)}?"

    companion object {
        val Any = KsNullable(KsConstructor.Any)
        val Nothing = KsNullable(KsConstructor.Nothing)
    }
}
fun KsNullable(env: TypingEnvironment,
               base: KsType): KsType = makeNormalized(::KsNullable, base)

data class KsUnion(val args: PersistentSet<KsType>): KsType {
    override fun subtypingRelationTo(env: TypingEnvironment, that: KsType): SubtypingRelation {
        if (that is KsFlexible) return that.subtypingRelationTo(env, this).invert()
        if (that is KsNullable) return that.subtypingRelationTo(env, this).invert()

        if (that in args) return SubtypingRelation.Supertype
        if (that !is KsUnion) {
            if (args.any { it.subtypingRelationTo(env, that) contains SubtypingRelation.Supertype })
                return SubtypingRelation.Supertype
            else if (args.all { it.subtypingRelationTo(env, that) contains SubtypingRelation.Subtype })
                return SubtypingRelation.Subtype
            else return SubtypingRelation.Unrelated
        } else {
            var result: SubtypingRelation = SubtypingRelation.Unrelated
            if (that.args.all { subtypingRelationTo(env, it) contains SubtypingRelation.Supertype })
                result = result or SubtypingRelation.Supertype
            if (args.all { that.subtypingRelationTo(env, it) contains SubtypingRelation.Supertype })
                result = result or SubtypingRelation.Subtype
            return result
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

    fun handleProjections(i: Iterable<KsProjection>) = KsProjection(
        inBound = makeNormalized(::KsIntersection, i.mapTo(persistentHashSetOf()) { it.inBound }),
        outBound = makeNormalized(::KsUnion, i.mapTo(persistentHashSetOf()) { it.outBound })
    )

    override fun normalizeStep(): KsType {
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
                    return makeNormalized(::KsFlexible,
                        makeNormalized(::KsUnion, nf.build().addAll(
                            f.mapTo(persistentHashSetBuilder()) { it.from })),
                        makeNormalized(::KsUnion, nf.build().addAll(
                            f.mapTo(persistentHashSetBuilder()) { it.to }))
                    )
                }
                is KsNullable -> {
                    val (n, nn) = iterator.partitionInstanceOf(KsNullable::class)
                    n += arg
                    nn += resArgs
                    // What?
                    return makeNormalized(
                        ::KsNullable,
                        makeNormalized(
                            ::KsUnion,
                            n.mapTo(persistentHashSetOf()) { it.base }.addAll(nn)
                        )
                    )
                }

                is KsTypeApplication -> {
                    val (me, notMe) = iterator.partitionTo(
                        persistentHashSetBuilder(),
                        persistentHashSetBuilder(),
                    ) { it is KsTypeApplication && it.constructor == arg.constructor }

                    if (me.isEmpty()) handleArg(arg, resArgs)
                    else {
                        me += arg
                        notMe += resArgs

                        @Suppress("UNCHECKED_CAST") (me as PersistentSet.Builder<KsTypeApplication>)

                        val reviso = makeNormalized(::KsTypeApplication,
                            arg.constructor,
                            arg.args.indices.mapTo(persistentListOf()) { ix ->
                                handleProjections(me.map { it.args[ix] })
                            }
                        )

                        return makeNormalized(::KsIntersection,
                            notMe.build().add(reviso)
                        )
                    }
                }
                else -> handleArg(arg, resArgs)
            }

        }
        resArgs.build()
        val finalArgs = resArgs.build()
        return when {
            finalArgs === args -> this
            finalArgs.size == 1 -> finalArgs.single()
            else -> KsUnion(resArgs.build())
        }
    }



    override fun toString(): String {
        return args.joinToString(" | ")
    }
}
fun KsUnion(env: TypingEnvironment, args: PersistentSet<KsType>): KsType =
    makeNormalized(::KsUnion, args)
fun KsUnion(env: TypingEnvironment, vararg args: KsType): KsType =
    makeNormalized(::KsUnion, persistentHashSetOf(*args))
data class KsIntersection(val args: PersistentSet<KsType>): KsType {
    fun handleArg(arg: KsType, resArgs: PersistentSet.Builder<KsType>) {
        when (arg) {
            is KsFlexible, is KsNullable, is KsUnion -> throw IllegalStateException()
            is KsIntersection -> {
                resArgs += arg.args
            }
            else -> resArgs += arg
        }
    }

    fun handleProjections(i: Iterable<KsProjection>) = KsProjection(
        inBound = makeNormalized(::KsUnion, i.mapTo(persistentHashSetOf()) { it.inBound }),
        outBound = makeNormalized(::KsIntersection, i.mapTo(persistentHashSetOf()) { it.outBound })
    )

    override fun normalizeStep(): KsType {
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
                    return makeNormalized(::KsFlexible,
                        makeNormalized(::KsIntersection, nf.build().addAll(
                            f.mapTo(persistentHashSetBuilder()) { it.from })),
                        makeNormalized(::KsIntersection, nf.build().addAll(
                            f.mapTo(persistentHashSetBuilder()) { it.to }))
                    )
                }
                is KsUnion -> {
                    val (u, nu) = iterator.partitionInstanceOf(KsUnion::class)
                    u += arg
                    nu += resArgs

                    return makeNormalized(::KsUnion,
                        u.map { it.args }
                            .productTo(persistentHashSetOf())
                            .mapTo(persistentHashSetOf()) {
                                makeNormalized(::KsIntersection, it.addAll(nu))
                            }
                    )
                }
                is KsNullable -> {
                    val (n, nn) = iterator.partitionInstanceOf(KsNullable::class)
                    n += arg
                    nn += resArgs

                    val banged = n.mapTo(persistentHashSetOf()) { it.base }

                    if (nn.isNotEmpty())
                        return makeNormalized(
                            ::KsIntersection,
                            banged.addAll(nn)
                        )
                    else return makeNormalized(
                        ::KsNullable,
                        makeNormalized(
                            ::KsIntersection,
                            banged
                        )
                    )
                }

                is KsTypeApplication -> {
                    val (me, notMe) = iterator.partitionInstanceOf(KsTypeApplication::class) {
                        it.constructor == arg.constructor
                    }

                    if (me.isEmpty()) handleArg(arg, resArgs)
                    else {
                        me += arg
                        notMe += resArgs

                        @Suppress("UNCHECKED_CAST") (me as PersistentSet.Builder<KsTypeApplication>)

                        val reviso = makeNormalized(::KsTypeApplication,
                            arg.constructor,
                            arg.args.indices.mapTo(persistentListOf()) { ix ->
                                handleProjections(me.map { it.args[ix] })
                            }
                        )

                        return makeNormalized(::KsIntersection,
                            notMe.build().add(reviso)
                        )
                    }
                }

                else -> handleArg(arg, resArgs)
            }

        }
        resArgs.build()
        val finalArgs = resArgs.build()
        return when {
            finalArgs === args -> this
            finalArgs.size == 1 -> finalArgs.single()
            else -> KsIntersection(resArgs.build())
        }
    }

    override fun toString(): String {
        return args.joinToString(" & ")
    }
}
fun KsIntersection(env: TypingEnvironment, args: PersistentSet<KsType>): KsType =
    makeNormalized(::KsIntersection, args)
fun KsIntersection(env: TypingEnvironment, vararg args: KsType): KsType =
    makeNormalized(::KsIntersection, persistentHashSetOf(*args))

data class KsConstructor(val name: String): KsType {
    override fun normalizeStep(): KsType = this

    override fun toString(): String {
        return name
    }

    companion object {
        val Any = KsConstructor("Any")
        val Nothing = KsConstructor("Nothing")
    }
}
fun KsConstructor(env: TypingEnvironment, name: String): KsType =
    makeNormalized(::KsConstructor, name)

data class KsTypeApplication
    internal constructor(val constructor: KsConstructor,
                         val args: PersistentList<KsProjection>): KsType {
    override fun normalizeStep(): KsType = when {
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
    makeNormalized(::KsTypeApplication, constructor, args)
fun KsTypeApplication(env: TypingEnvironment,
                      constructor: KsConstructor,
                      vararg args: KsProjection): KsType =
    KsTypeApplication(env, constructor, persistentListOf(*args))

suspend fun main() {
    val env = object : TypingEnvironment() {
        override fun KsConstructor.subtypeOf(that: KsConstructor) = false
    }
    println(
        KsUnion(
            env,
            KsConstructor(env, "T"),
            KsNullable(env, KsTypeApplication(env, KsConstructor("A"), KsProjection.Star)),
        ),
    )
    println(
        KsIntersection(
            env,
            KsUnion(
                env,
                KsConstructor(env, "T"),
                KsNullable(env, KsTypeApplication(env, KsConstructor("A"), KsProjection.Star)),
            ),
            KsNullable(env, KsConstructor(env, "TT"))
        )
    )
}
