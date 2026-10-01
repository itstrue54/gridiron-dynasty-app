package com.nflsim.engine.ratings

import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable

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

    fun confidence(yearsWithClub: Int, scoutingDept: Int, t: TuningTable.Scouting): Float =
        ScoutingLens.ownPlayer(yearsWithClub, scoutingDept, t)

    fun grade(trueValue: Int, confidence: Float, playerId: Int, trait: String, t: TuningTable.Scouting): String {
        if (confidence < t.rangeAt) return "?"
        if (confidence >= t.exactAt) return letter(trueValue.toFloat())
        val miss = SplitMixRng(playerId.toLong() * 31 + trait.hashCode()).gaussian()
        val half = t.traitBand * (1f - confidence)
        val estimate = trueValue + miss * half
        if (confidence >= t.gradeAt) return letter(estimate)
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
}
