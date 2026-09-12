package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/**
 * A club's game plan (SPEC 5.4's user control): the tendencies its
 * coordinators call from. You do not call plays; you set these, and the play
 * caller does the rest. Every lever is an override - null means the scheme's
 * value, or the league's usual figure - so an untouched plan plays exactly
 * as a club with no plan at all.
 */
@Serializable
data class GamePlan(
    /** Share of plays that are passes before down, distance and score move it. */
    val passRate: Float? = null,
    /** Play action on early downs. */
    val playActionRate: Float? = null,
    /** Deep shots. Unset, it follows play action: DEEP_SHOT_SHARE of it. */
    val deepShotRate: Float? = null,
    /** How hard the offense leans to the pass when trailing, as a multiple of the usual lean. */
    val trailingPassScale: Float? = null,
    /** Added to the pass rate in a two-minute drill when not leading. */
    val twoMinutePassBoost: Float? = null,
    val blitzRate: Float? = null,
    /** 0 is all zone, 1 all man. */
    val manZoneSplit: Float? = null,
    /** How often the defense doubles the opponent's top receiver. */
    val doubleTeamRate: Float? = null,
    /** 0 punts every fourth down, 1 goes for it whenever it can. Unset, the head coach's. */
    val fourthDownAggression: Float? = null,
) {
    companion object {
        const val DEEP_SHOT_SHARE = 0.65f
        const val TWO_MINUTE_BOOST = 0.28f
        const val DOUBLE_TEAM_RATE = 0.12f

        /** A club's head-coach fourth-down aggression when its plan sets none. */
        fun defaultAggression(teamId: Int): Float = 0.35f + (teamId % 7) * 0.06f
    }
}
