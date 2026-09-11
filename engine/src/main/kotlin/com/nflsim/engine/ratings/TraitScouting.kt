package com.nflsim.engine.ratings

import com.nflsim.engine.rng.SplitMixRng

/**
 * What a club can see of a hidden trait (SPEC 4.5, 4.6). Never a number: a
 * question mark until the club has seen enough of him, then a range of
 * letter grades, a grade, and at last the true grade.
 *
 * Confidence starts with the scouting department and rises with each year
 * in the building, to 0.95 and never 1. A scout's miss is per player and
 * stable within a save - drawn from the player's id and the trait rather
 * than stored, so it needs no save field, and it shrinks only as
 * confidence rises.
 */
object TraitScouting {

    fun confidence(yearsWithClub: Int, scoutingDept: Int): Float =
        (BASE + DEPT * ((scoutingDept - 40) / 40f).coerceIn(0f, 1f) + PER_YEAR * yearsWithClub)
            .coerceAtMost(CEILING)

    fun grade(trueValue: Int, confidence: Float, playerId: Int, trait: String): String {
        if (confidence < RANGE_AT) return "?"
        if (confidence >= EXACT_AT) return letter(trueValue.toFloat())
        val miss = SplitMixRng(playerId.toLong() * 31 + trait.hashCode()).gaussian()
        val half = BAND * (1f - confidence)
        val estimate = trueValue + miss * half
        if (confidence >= GRADE_AT) return letter(estimate)
        val low = letter(estimate - half)
        val high = letter(estimate + half)
        return if (low == high) low else "$low-$high"
    }

    fun letter(value: Float): String = when {
        value >= 80f -> "A"
        value >= 65f -> "B"
        value >= 50f -> "C"
        value >= 35f -> "D"
        else -> "F"
    }

    /** SPEC 4.6's thresholds: a range from 0.4, a grade from 0.7, the truth from 0.9. */
    private const val RANGE_AT = 0.4f
    private const val GRADE_AT = 0.7f
    private const val EXACT_AT = 0.9f
    private const val CEILING = 0.95f

    /** A new arrival reads 0.2-0.4 by scouting department; each year in the building adds 0.25. */
    private const val BASE = 0.2f
    private const val DEPT = 0.2f
    private const val PER_YEAR = 0.25f

    /** Half-width of the band in trait points at zero confidence; traits span wider than ratings. */
    private const val BAND = 30f
}
