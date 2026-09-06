package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/**
 * Every attribute a player has. Stored 0..99.
 *
 * Note there is no OVERALL here on purpose - overall is derived, and it depends
 * on the scheme the player is in. See docs/SPEC.md section 4.4.
 */
@Serializable
enum class RatingId {
    // Universal physical
    SPEED, ACCELERATION, AGILITY, STRENGTH, JUMPING, STAMINA, INJURY_RESIST, TOUGHNESS,

    // Universal mental
    AWARENESS, PLAY_RECOGNITION, DISCIPLINE,

    // Quarterback
    THROW_POWER, THROW_ACC_SHORT, THROW_ACC_MID, THROW_ACC_DEEP,
    THROW_ON_RUN, THROW_UNDER_PRESSURE, BREAK_SACK, PLAY_ACTION, SCRAMBLING,

    // Ball carrier
    CARRYING, BALL_SECURITY, BREAK_TACKLE, TRUCKING, ELUSIVENESS,
    JUKE_MOVE, SPIN_MOVE, STIFF_ARM, VISION,

    // Receiving
    CATCHING, CATCH_IN_TRAFFIC, SPECTACULAR_CATCH,
    ROUTE_SHORT, ROUTE_MID, ROUTE_DEEP, RELEASE,

    // Blocking
    RUN_BLOCK, PASS_BLOCK, IMPACT_BLOCK, LEAD_BLOCK,
    RUN_BLOCK_POWER, RUN_BLOCK_FINESSE, PASS_BLOCK_POWER, PASS_BLOCK_FINESSE,

    // Defense
    POWER_MOVES, FINESSE_MOVES, BLOCK_SHEDDING, PURSUIT, TACKLE, HIT_POWER,
    MAN_COVERAGE, ZONE_COVERAGE, PRESS,

    // Kicking
    KICK_POWER, KICK_ACCURACY, PUNT_POWER, PUNT_ACCURACY;

    val isPhysical: Boolean
        get() = this in PHYSICAL

    val isMental: Boolean
        get() = this in MENTAL

    companion object {
        val PHYSICAL = setOf(SPEED, ACCELERATION, AGILITY, STRENGTH, JUMPING, STAMINA)
        val MENTAL = setOf(AWARENESS, PLAY_RECOGNITION, DISCIPLINE, VISION)
        val COUNT = entries.size
    }
}
