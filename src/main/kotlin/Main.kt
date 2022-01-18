
import kotlinx.collections.immutable.*

sealed interface KsType {
    fun normalizeStep(): KsType
}

inline fun <reified T: KsType, Arg> makeNormalized(constructor: (Arg) -> T, arg: Arg): KsType =
    constructor(arg).normalizeStep()

inline fun <reified T: KsType, A, B> makeNormalized(constructor: (A, B) -> T, a: A, b: B): KsType =
    constructor(a, b).normalizeStep()

data class KsProjection(val outBound: KsType, val inBound: KsType = outBound)

data class KsFlexible(val from: KsType, val to: KsType): KsType {
    override fun normalizeStep(): KsType = when {
        from == to -> from
        from is KsFlexible || to is KsFlexible -> {
            val adjFrom = if (from is KsFlexible) from.from else from
            val adjTo = if (to is KsFlexible) to.to else to
            copy(adjFrom, adjTo)
        }
        else -> this
    }
}
data class KsNullable(val base: KsType): KsType {
    override fun normalizeStep(): KsType = when(base) {
        is KsFlexible ->
            makeNormalized(::KsFlexible,
                makeNormalized(::KsNullable, base.from),
                makeNormalized(::KsNullable, base.to)
            )
        is KsNullable -> base
        else -> this
    }
}

data class KsUnion(val args: PersistentSet<KsType>): KsType {

    fun handleArg(arg: KsType, resArgs: PersistentSet.Builder<KsType>) {
        when (arg) {
            is KsFlexible, is KsNullable -> throw IllegalStateException()
            is KsUnion -> {
                resArgs += arg.args
            }
            else -> resArgs += arg
        }
    }

    override fun normalizeStep(): KsType {
        val resArgs = persistentHashSetBuilder<KsType>()
        var nullableResult: Boolean = false

        val iterator = args.iterator()

        for (arg in iterator) {
            when (arg) {
                is KsFlexible -> {
                    val (f, nf) = iterator.partitionTo(
                        persistentHashSetBuilder(),
                        persistentHashSetBuilder()
                    ) { it is KsFlexible }
                    @Suppress("UNCHECKED_CAST") (f as PersistentSet.Builder<KsFlexible>)
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
                    handleArg(arg.base, resArgs)
                    nullableResult = true
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
        }.let {
            if (nullableResult) KsNullable(it) else it
        }

    }
}
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

    override fun normalizeStep(): KsType {
        val resArgs = persistentHashSetBuilder<KsType>()
        var nullableResult: Boolean = true

        val iterator = args.iterator()
        for (arg in iterator) {
            if (arg !is KsNullable) nullableResult = false
            when (arg) {
                is KsFlexible -> {
                    val (f, nf) = iterator.partitionTo(
                        persistentHashSetBuilder(),
                        persistentHashSetBuilder()
                    ) { it is KsFlexible }
                    @Suppress("UNCHECKED_CAST") (f as PersistentSet.Builder<KsFlexible>)
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
                is KsNullable -> handleArg(arg.base, resArgs)
                is KsUnion -> {
                    val (u, nu) = iterator.partitionTo(
                        persistentHashSetBuilder(),
                        persistentHashSetBuilder()
                    ) { it is KsUnion }
                    @Suppress("UNCHECKED_CAST") (u as PersistentSet.Builder<KsUnion>)

                    u += arg
                    nu += resArgs

                    return makeNormalized(::KsUnion,
                        u.map { it.args + nu }
                            .productTo(persistentSetOf())
                            .mapTo(persistentSetOf()) {
                                makeNormalized(::KsIntersection, it)
                            }
                    )
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
        }.let {
            if (nullableResult) KsNullable(it) else it
        }
    }
}

data class KsConstructor(val name: String): KsType {
    override fun normalizeStep(): KsType = this
}
data class KsApplication(val constructor: KsConstructor, val args: PersistentList<KsProjection>): KsType {
    override fun normalizeStep(): KsType = when {
        args.isEmpty() -> constructor
        else -> copy(constructor, args)
    }
}



suspend fun main() {
    println("hello world")
}
