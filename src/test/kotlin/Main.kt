package org.jetbrains.kotlin.types.play

import kotlinx.collections.immutable.persistentHashSetOf
import kotlin.test.*

class Main {
    @Test
    fun smokeTest() {
        with(EmptyEnvironment) {
            val T by this
            val A by this
            val TT by this

            assertEquals(KsUnion(persistentHashSetOf(T, A(outp(T)))), T or A(outp(T)))
            assertEquals(KsNullable(KsUnion(persistentHashSetOf(T, A(outp(T))))), T or A(outp(T)) or T.q)
            assertEquals(T, T or T)
            assertEquals(T, T and T)
            assertEquals(T and TT, TT and T)
            assertEquals(T or TT, TT or T)
            assertEquals(T.q, T.q.q.q.q.q)
            assertEquals(T or TT or A, TT or A or T)
            assertEquals(KsNullable(KsUnion(persistentHashSetOf(T, A(Star)))), T or A(Star).q)
            assertEquals(
                KsUnion(
                    persistentHashSetOf(
                        KsIntersection(persistentHashSetOf(T, TT)),
                        KsIntersection(persistentHashSetOf(T, A))
                    )
                ),
                (TT or A) and T
            )

            assertEquals(T or Nothing.q, T.q)
            assertEquals(Nothing.q or T, T.q)
        }
    }
}