import kotlinx.collections.immutable.*
import kotlin.collections.filterTo

inline fun <T> persistentSetBuilder(): PersistentSet.Builder<T> = persistentSetOf<T>().builder()
inline fun <T> persistentHashSetBuilder(): PersistentSet.Builder<T> = persistentHashSetOf<T>().builder()
inline fun <T> persistentListBuilder(): PersistentList.Builder<T> = persistentListOf<T>().builder()

inline fun <
        T,
        C: Iterable<T>,
        MC1: MutableCollection<T>,
        MC2: MutableCollection<T>
        > C.partitionTo(left: MC1, right: MC2, body: (T) -> Boolean): Pair<MC1, MC2> {
    for (e in this) {
        if (body(e)) left.add(e)
        else right.add(e)
    }
    return Pair(left, right)
}

inline fun <
        T,
        C: Sequence<T>,
        MC1: MutableCollection<T>,
        MC2: MutableCollection<T>
        > C.partitionTo(left: MC1, right: MC2, body: (T) -> Boolean): Pair<MC1, MC2> {
    for (e in this) {
        if (body(e)) left.add(e)
        else right.add(e)
    }
    return Pair(left, right)
}

inline fun <
        T,
        C: Iterator<T>,
        MC1: MutableCollection<T>,
        MC2: MutableCollection<T>
        > C.partitionTo(left: MC1, right: MC2, body: (T) -> Boolean): Pair<MC1, MC2> {
    for (e in this) {
        if (body(e)) left.add(e)
        else right.add(e)
    }
    return Pair(left, right)
}

fun <T, C: PersistentCollection<T>> Iterable<Iterable<T>>.productTo(c: C): List<C> =
    fold(mutableListOf(c)) { acc: MutableList<C>, set ->
        mutableListOf<C>().apply {
            for (list in acc) {
                for (element in set) {
                    add(list.add(element) as C)
                }
            }
        }
    }

inline fun <T, R, C : PersistentCollection<R>> Iterable<T>.mapTo(destination: C, transform: (T) -> R): C =
    mapTo(destination.builder(), transform).build() as C

inline fun <T, C : PersistentCollection<T>> Iterable<T>.filterTo(destination: C, predicate: (T) -> Boolean): C =
    filterTo(destination.builder(), predicate).build() as C
