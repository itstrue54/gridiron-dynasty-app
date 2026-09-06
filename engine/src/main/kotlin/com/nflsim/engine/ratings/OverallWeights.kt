package com.nflsim.engine.ratings

import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.RatingId.*

/**
 * What actually matters at each position. Weights sum to 1.0 so an overall is
 * directly comparable to the rating scale.
 *
 * Overall is DERIVED, never stored (docs/SPEC.md 4.4). That is deliberate: it
 * means overall changes when the scheme changes, and it means no sim code can
 * take a lazy shortcut by reading "overall" instead of the attribute that
 * actually governs the play.
 */
object OverallWeights {

    private val QB = mapOf(
        THROW_ACC_SHORT to .14f, THROW_ACC_MID to .13f, THROW_ACC_DEEP to .09f,
        THROW_POWER to .07f, AWARENESS to .14f, PLAY_RECOGNITION to .10f,
        THROW_UNDER_PRESSURE to .09f, PLAY_ACTION to .06f, BREAK_SACK to .05f,
        THROW_ON_RUN to .05f, SCRAMBLING to .04f, SPEED to .04f,
    )

    private val RB = mapOf(
        SPEED to .13f, VISION to .13f, ACCELERATION to .12f, AGILITY to .10f,
        BREAK_TACKLE to .10f, ELUSIVENESS to .09f, CARRYING to .08f,
        BALL_SECURITY to .07f, TRUCKING to .05f, CATCHING to .05f,
        PASS_BLOCK to .04f, AWARENESS to .04f,
    )

    private val FB = mapOf(
        LEAD_BLOCK to .28f, IMPACT_BLOCK to .18f, STRENGTH to .14f, RUN_BLOCK to .12f,
        CARRYING to .08f, AWARENESS to .08f, CATCHING to .07f, BREAK_TACKLE to .05f,
    )

    private val WR = mapOf(
        CATCHING to .15f, ROUTE_MID to .13f, SPEED to .12f, ROUTE_SHORT to .11f,
        ROUTE_DEEP to .10f, RELEASE to .08f, CATCH_IN_TRAFFIC to .08f,
        ACCELERATION to .07f, AGILITY to .06f, SPECTACULAR_CATCH to .05f, JUMPING to .05f,
    )

    private val TE = mapOf(
        CATCHING to .15f, RUN_BLOCK to .14f, ROUTE_SHORT to .10f, ROUTE_MID to .10f,
        PASS_BLOCK to .09f, STRENGTH to .09f, CATCH_IN_TRAFFIC to .09f,
        AWARENESS to .09f, IMPACT_BLOCK to .08f, SPEED to .07f,
    )

    private val OFFENSIVE_TACKLE = mapOf(
        PASS_BLOCK to .22f, RUN_BLOCK to .15f, PASS_BLOCK_FINESSE to .12f,
        PASS_BLOCK_POWER to .10f, STRENGTH to .10f, AWARENESS to .10f,
        AGILITY to .07f, RUN_BLOCK_POWER to .07f, RUN_BLOCK_FINESSE to .07f,
    )

    private val INTERIOR_OL = mapOf(
        RUN_BLOCK to .20f, PASS_BLOCK to .18f, STRENGTH to .14f, AWARENESS to .12f,
        RUN_BLOCK_POWER to .10f, PASS_BLOCK_POWER to .09f, IMPACT_BLOCK to .07f,
        RUN_BLOCK_FINESSE to .06f, AGILITY to .04f,
    )

    private val EDGE = mapOf(
        FINESSE_MOVES to .16f, POWER_MOVES to .15f, BLOCK_SHEDDING to .12f,
        SPEED to .11f, ACCELERATION to .09f, PURSUIT to .09f, TACKLE to .09f,
        STRENGTH to .08f, PLAY_RECOGNITION to .06f, AGILITY to .05f,
    )

    private val DT = mapOf(
        BLOCK_SHEDDING to .18f, STRENGTH to .17f, POWER_MOVES to .15f, TACKLE to .11f,
        PLAY_RECOGNITION to .10f, FINESSE_MOVES to .10f, PURSUIT to .08f,
        ACCELERATION to .06f, AWARENESS to .05f,
    )

    private val LB = mapOf(
        TACKLE to .15f, PLAY_RECOGNITION to .15f, PURSUIT to .12f, ZONE_COVERAGE to .11f,
        SPEED to .10f, BLOCK_SHEDDING to .09f, AWARENESS to .09f, HIT_POWER to .07f,
        MAN_COVERAGE to .07f, ACCELERATION to .05f,
    )

    private val CB = mapOf(
        MAN_COVERAGE to .20f, ZONE_COVERAGE to .15f, SPEED to .15f, ACCELERATION to .10f,
        AGILITY to .09f, PRESS to .09f, PLAY_RECOGNITION to .09f, CATCHING to .05f,
        JUMPING to .04f, TACKLE to .04f,
    )

    private val S = mapOf(
        ZONE_COVERAGE to .17f, PLAY_RECOGNITION to .15f, SPEED to .12f, TACKLE to .11f,
        MAN_COVERAGE to .10f, PURSUIT to .10f, AWARENESS to .09f, HIT_POWER to .08f,
        ACCELERATION to .05f, CATCHING to .03f,
    )

    private val K = mapOf(KICK_ACCURACY to .50f, KICK_POWER to .45f, AWARENESS to .05f)
    private val P = mapOf(PUNT_ACCURACY to .50f, PUNT_POWER to .45f, AWARENESS to .05f)
    private val LS = mapOf(AWARENESS to .40f, STRENGTH to .30f, TACKLE to .30f)

    private val byPosition: Map<Position, Map<RatingId, Float>> = mapOf(
        Position.QB to QB,
        Position.RB to RB,
        Position.FB to FB,
        Position.WR to WR,
        Position.TE to TE,
        Position.LT to OFFENSIVE_TACKLE,
        Position.RT to OFFENSIVE_TACKLE,
        Position.LG to INTERIOR_OL,
        Position.C to INTERIOR_OL,
        Position.RG to INTERIOR_OL,
        Position.EDGE to EDGE,
        Position.DT to DT,
        Position.LB to LB,
        Position.CB to CB,
        Position.S to S,
        Position.K to K,
        Position.P to P,
        Position.LS to LS,
    )

    fun forPosition(position: Position): Map<RatingId, Float> =
        byPosition[position] ?: error("no overall weights defined for $position")
}
