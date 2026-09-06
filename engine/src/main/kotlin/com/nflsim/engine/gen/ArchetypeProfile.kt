package com.nflsim.engine.gen

import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.Archetype.*
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.RatingId.*

/**
 * What makes a body type look different on the rating sheet.
 *
 * These are offsets applied before the overall is normalised to its target,
 * so two 80-overall backs with different archetypes end up with genuinely
 * different sheets rather than the same numbers under a different label.
 */
object ArchetypeProfile {

    private val profiles: Map<Archetype, Map<RatingId, Int>> = mapOf(
        // ---- Quarterback ----
        POCKET_PASSER to mapOf(THROW_ACC_MID to 7, THROW_ACC_DEEP to 5, THROW_POWER to 4,
            AWARENESS to 5, SCRAMBLING to -14, SPEED to -12, THROW_ON_RUN to -8),
        FIELD_GENERAL to mapOf(AWARENESS to 10, PLAY_RECOGNITION to 9, THROW_ACC_SHORT to 7,
            PLAY_ACTION to 5, THROW_POWER to -5, SCRAMBLING to -8, SPEED to -7),
        IMPROVISER to mapOf(BREAK_SACK to 10, THROW_ON_RUN to 9, SCRAMBLING to 6,
            THROW_UNDER_PRESSURE to 6, AWARENESS to -6, THROW_ACC_DEEP to -4),
        DUAL_THREAT to mapOf(SCRAMBLING to 14, SPEED to 13, ACCELERATION to 10, ELUSIVENESS to 8,
            THROW_ON_RUN to 6, THROW_ACC_MID to -6, AWARENESS to -5),
        GUNSLINGER to mapOf(THROW_POWER to 13, THROW_ACC_DEEP to 9, THROW_UNDER_PRESSURE to 4,
            AWARENESS to -8, DISCIPLINE to -7, THROW_ACC_SHORT to -4),

        // ---- Running back ----
        POWER_BACK to mapOf(TRUCKING to 12, BREAK_TACKLE to 10, STRENGTH to 9, CARRYING to 5,
            ELUSIVENESS to -10, JUKE_MOVE to -9, SPEED to -6, CATCHING to -6),
        ONE_CUT_ZONE to mapOf(VISION to 11, ACCELERATION to 8, AGILITY to 6, BALL_SECURITY to 4,
            TRUCKING to -9, STRENGTH to -6),
        ELUSIVE to mapOf(ELUSIVENESS to 12, JUKE_MOVE to 11, SPIN_MOVE to 8, AGILITY to 8,
            TRUCKING to -11, STRENGTH to -9, PASS_BLOCK to -6),
        RECEIVING_BACK to mapOf(CATCHING to 14, ROUTE_SHORT to 12, ROUTE_MID to 8, PASS_BLOCK to 5,
            TRUCKING to -10, BREAK_TACKLE to -6, CARRYING to -3),
        WORKHORSE to mapOf(STAMINA to 12, CARRYING to 9, BALL_SECURITY to 8, BREAK_TACKLE to 6,
            ELUSIVENESS to -5, SPEED to -4),

        // ---- Wide receiver ----
        X_CONTESTED to mapOf(CATCH_IN_TRAFFIC to 12, SPECTACULAR_CATCH to 10, JUMPING to 9,
            STRENGTH to 7, RELEASE to 5, SPEED to -6, ELUSIVENESS to -5),
        Z_ROUTE_TECH to mapOf(ROUTE_MID to 12, ROUTE_SHORT to 10, ROUTE_DEEP to 7, RELEASE to 8,
            AWARENESS to 5, STRENGTH to -5),
        SLOT to mapOf(ROUTE_SHORT to 13, AGILITY to 10, RELEASE to 8, CATCH_IN_TRAFFIC to 6,
            ROUTE_DEEP to -8, JUMPING to -5, STRENGTH to -6),
        DEEP_THREAT to mapOf(SPEED to 14, ROUTE_DEEP to 12, ACCELERATION to 9,
            ROUTE_SHORT to -9, CATCH_IN_TRAFFIC to -8, STRENGTH to -6),
        YAC_RECEIVER to mapOf(ELUSIVENESS to 12, BREAK_TACKLE to 9, ACCELERATION to 8,
            ROUTE_SHORT to 7, ROUTE_DEEP to -7, SPECTACULAR_CATCH to -5),

        // ---- Tight end ----
        INLINE_BLOCKER to mapOf(RUN_BLOCK to 13, IMPACT_BLOCK to 11, STRENGTH to 9, PASS_BLOCK to 7,
            SPEED to -10, ROUTE_MID to -9, CATCHING to -6),
        MOVE_TE to mapOf(SPEED to 8, ROUTE_MID to 7, ACCELERATION to 6, RUN_BLOCK to 3,
            STRENGTH to -4),
        RECEIVING_TE to mapOf(CATCHING to 12, ROUTE_MID to 11, CATCH_IN_TRAFFIC to 9, ROUTE_DEEP to 6,
            RUN_BLOCK to -13, IMPACT_BLOCK to -11, STRENGTH to -6),
        H_BACK to mapOf(LEAD_BLOCK to 12, IMPACT_BLOCK to 8, AWARENESS to 6, CATCHING to 4,
            ROUTE_DEEP to -9, SPEED to -6),

        // ---- Offensive line ----
        ZONE_BLOCKER to mapOf(RUN_BLOCK_FINESSE to 12, AGILITY to 10, ACCELERATION to 7,
            AWARENESS to 5, RUN_BLOCK_POWER to -9, STRENGTH to -7),
        POWER_MAULER to mapOf(RUN_BLOCK_POWER to 13, STRENGTH to 11, IMPACT_BLOCK to 8,
            RUN_BLOCK to 6, AGILITY to -10, RUN_BLOCK_FINESSE to -8, ACCELERATION to -6),
        PASS_PROTECTOR to mapOf(PASS_BLOCK to 12, PASS_BLOCK_FINESSE to 10, PASS_BLOCK_POWER to 7,
            AWARENESS to 6, RUN_BLOCK_POWER to -9, IMPACT_BLOCK to -6),
        ATHLETIC_PULLING to mapOf(AGILITY to 12, SPEED to 9, LEAD_BLOCK to 9, ACCELERATION to 8,
            STRENGTH to -6, PASS_BLOCK_POWER to -5),

        // ---- EDGE ----
        SPEED_RUSHER to mapOf(FINESSE_MOVES to 13, ACCELERATION to 11, SPEED to 10, AGILITY to 8,
            STRENGTH to -10, BLOCK_SHEDDING to -7, POWER_MOVES to -8),
        POWER_RUSHER to mapOf(POWER_MOVES to 13, STRENGTH to 11, BLOCK_SHEDDING to 8,
            FINESSE_MOVES to -9, SPEED to -8, AGILITY to -6),
        RUN_STOPPING_EDGE to mapOf(BLOCK_SHEDDING to 12, TACKLE to 10, STRENGTH to 9,
            PLAY_RECOGNITION to 7, FINESSE_MOVES to -8, SPEED to -6),
        COVERAGE_OLB to mapOf(ZONE_COVERAGE to 14, MAN_COVERAGE to 10, SPEED to 8, PURSUIT to 7,
            POWER_MOVES to -10, STRENGTH to -8),

        // ---- Defensive tackle ----
        NOSE_1TECH to mapOf(STRENGTH to 14, BLOCK_SHEDDING to 10, TACKLE to 5,
            ACCELERATION to -11, FINESSE_MOVES to -10, PURSUIT to -8, SPEED to -9),
        PENETRATOR_3TECH to mapOf(FINESSE_MOVES to 12, ACCELERATION to 11, POWER_MOVES to 6,
            PURSUIT to 6, STRENGTH to -6, BLOCK_SHEDDING to -4),
        TWO_GAP_5TECH to mapOf(BLOCK_SHEDDING to 13, STRENGTH to 10, PLAY_RECOGNITION to 8,
            TACKLE to 6, FINESSE_MOVES to -9, ACCELERATION to -7),
        INTERIOR_RUSHER to mapOf(POWER_MOVES to 12, FINESSE_MOVES to 10, ACCELERATION to 8,
            BLOCK_SHEDDING to -6, TACKLE to -5, STRENGTH to -3),

        // ---- Linebacker ----
        FIELD_GENERAL_MLB to mapOf(PLAY_RECOGNITION to 13, AWARENESS to 12, TACKLE to 7,
            PURSUIT to 5, MAN_COVERAGE to -6, SPEED to -5),
        COVERAGE_LB to mapOf(ZONE_COVERAGE to 13, MAN_COVERAGE to 11, SPEED to 9, AGILITY to 7,
            HIT_POWER to -9, BLOCK_SHEDDING to -8, STRENGTH to -6),
        BLITZING_LB to mapOf(POWER_MOVES to 12, ACCELERATION to 10, FINESSE_MOVES to 9, PURSUIT to 6,
            ZONE_COVERAGE to -11, MAN_COVERAGE to -9),
        THUMPER to mapOf(HIT_POWER to 13, TACKLE to 11, STRENGTH to 10, BLOCK_SHEDDING to 8,
            ZONE_COVERAGE to -12, MAN_COVERAGE to -11, SPEED to -7),

        // ---- Cornerback ----
        MAN_PRESS to mapOf(MAN_COVERAGE to 13, PRESS to 12, SPEED to 8, STRENGTH to 5,
            ZONE_COVERAGE to -11, PLAY_RECOGNITION to -6),
        ZONE_CB to mapOf(ZONE_COVERAGE to 13, PLAY_RECOGNITION to 11, AWARENESS to 8,
            MAN_COVERAGE to -9, PRESS to -8),
        SLOT_CB to mapOf(AGILITY to 12, ACCELERATION to 10, MAN_COVERAGE to 7, TACKLE to 6,
            PRESS to -7, JUMPING to -6, SPEED to -3),
        BALLHAWK to mapOf(CATCHING to 14, ZONE_COVERAGE to 10, JUMPING to 9, PLAY_RECOGNITION to 8,
            PRESS to -8, TACKLE to -7, MAN_COVERAGE to -4),

        // ---- Safety ----
        FREE_SAFETY to mapOf(ZONE_COVERAGE to 12, SPEED to 10, PLAY_RECOGNITION to 9, CATCHING to 7,
            HIT_POWER to -9, TACKLE to -6, STRENGTH to -6),
        STRONG_SAFETY to mapOf(TACKLE to 11, HIT_POWER to 10, STRENGTH to 8, PURSUIT to 6,
            ZONE_COVERAGE to -7, SPEED to -5),
        BOX_SAFETY to mapOf(HIT_POWER to 12, TACKLE to 11, BLOCK_SHEDDING to 8, PLAY_RECOGNITION to 6,
            ZONE_COVERAGE to -11, MAN_COVERAGE to -10, SPEED to -6),
        HYBRID_NICKEL to mapOf(MAN_COVERAGE to 10, AGILITY to 9, ZONE_COVERAGE to 8, PURSUIT to 7,
            STRENGTH to -6, HIT_POWER to -5),

        SPECIALIST to emptyMap(),
    )

    fun offsetsFor(archetype: Archetype): Map<RatingId, Int> = profiles[archetype] ?: emptyMap()

    /** Every archetype must have a profile, even an empty one. Asserted in tests. */
    fun covered(): Set<Archetype> = profiles.keys
}
