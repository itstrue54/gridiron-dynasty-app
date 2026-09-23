package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/**
 * A player's true attribute values. The user never sees these directly -
 * everything on screen goes through a ScoutingLens (docs/SPEC.md 4.6).
 *
 * Backed by an IntArray indexed by RatingId.ordinal. A calibration run reads
 * these tens of millions of times, so the flat array is worth the small amount
 * of ceremony.
 */
@Serializable
class Ratings(val values: IntArray) {

    init {
        require(values.size == RatingId.COUNT) {
            "expected ${RatingId.COUNT} ratings, got ${values.size}"
        }
    }

    operator fun get(id: RatingId): Int = values[id.ordinal]

    /** Every rating moved by the same amount, for a man in or out of form. */
    fun shifted(points: Int): Ratings {
        if (points == 0) return this
        val copy = IntArray(values.size) { (values[it] + points).coerceIn(MIN, MAX) }
        return Ratings(copy)
    }

    /** Returns a copy with the given ratings replaced. */
    fun with(vararg changes: Pair<RatingId, Int>): Ratings {
        val copy = values.copyOf()
        for ((id, v) in changes) copy[id.ordinal] = v.coerceIn(MIN, MAX)
        return Ratings(copy)
    }

    /**
     * Applies an aging/progression delta. Physical and mental ratings move at
     * different rates and in different directions - see docs/SPEC.md 7.1.
     */
    fun applyDelta(physical: Float, mental: Float, other: Float = 0f): Ratings {
        val copy = IntArray(RatingId.COUNT)
        for (id in RatingId.entries) {
            val delta = when {
                id.isPhysical -> physical
                id.isMental -> mental
                else -> other
            }
            copy[id.ordinal] = (values[id.ordinal] + delta).toInt().coerceIn(MIN, MAX)
        }
        return Ratings(copy)
    }

    fun toMap(): Map<RatingId, Int> = RatingId.entries.associateWith { values[it.ordinal] }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Ratings && values.contentEquals(other.values))

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String =
        "Ratings(spd=${this[RatingId.SPEED]}, str=${this[RatingId.STRENGTH]}, awr=${this[RatingId.AWARENESS]}, ...)"

    companion object {
        const val MIN = 1
        const val MAX = 99

        fun uniform(value: Int): Ratings =
            Ratings(IntArray(RatingId.COUNT) { value.coerceIn(MIN, MAX) })

        /** Convenience for tests and generators: a baseline plus named overrides. */
        fun of(base: Int = 50, vararg changes: Pair<RatingId, Int>): Ratings =
            uniform(base).with(*changes)
    }
}
