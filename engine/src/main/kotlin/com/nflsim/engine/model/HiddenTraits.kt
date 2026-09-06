package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/**
 * How a player's career curve bends. Multiplies offseason progression gains.
 */
@Serializable
enum class DevCurve(val multiplier: Float, val label: String) {
    SLOW(0.70f, "Slow"),
    NORMAL(1.00f, "Normal"),
    QUICK(1.30f, "Quick"),
    SUPERSTAR(1.55f, "Superstar"),
    X_FACTOR(1.80f, "X-Factor");
}

/**
 * The things a scout cannot measure at the combine.
 *
 * These are NEVER displayed as raw numbers. They surface as letter grades and
 * ranges that tighten as evidence accumulates - see docs/SPEC.md 4.5 and 4.6.
 * Getting these wrong is supposed to be how you lose a draft pick.
 */
@Serializable
data class HiddenTraits(
    val developmentCurve: DevCurve = DevCurve.NORMAL,
    /** Shifts the position's normal peak age. -3 = peaks early, +3 = ages well. */
    val peakAgeOffset: Int = 0,
    val workEthic: Int = 50,
    val footballIq: Int = 50,
    val coachability: Int = 50,
    /** Softens the penalty for playing out of scheme. Never boosts a good fit. */
    val schemeVersatility: Int = 50,
    /** Separate from INJURY_RESIST: this governs recurrence of old injuries. */
    val injuryProneness: Int = 50,
    val clutch: Int = 50,
    val bigGame: Int = 50,
    /** Low consistency = high game-to-game variance. */
    val consistency: Int = 50,
    val ego: Int = 50,
    /** Raises the odds of a hometown discount at re-signing time. */
    val loyalty: Int = 50,
    val penaltyProne: Int = 50,
    val durabilityUnderLoad: Int = 50,
) {
    companion object {
        val AVERAGE = HiddenTraits()
    }
}
