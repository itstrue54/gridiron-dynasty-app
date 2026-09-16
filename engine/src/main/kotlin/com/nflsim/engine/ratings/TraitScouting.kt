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
        ScoutingLens.ownPlayer(yearsWithClub, scoutingDept)

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

    /** SPEC 4.6's thresholds, shared with the ratings lens. */
    private const val RANGE_AT = ScoutingLens.RANGE_AT
    private const val GRADE_AT = ScoutingLens.GRADE_AT
    private const val EXACT_AT = ScoutingLens.EXACT_AT

    /** Half-width of the band in trait points at zero confidence; traits span wider than ratings. */
    private const val BAND = 30f
}
