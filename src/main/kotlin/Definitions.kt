package org.jetbrains.kotlin.types.play

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
