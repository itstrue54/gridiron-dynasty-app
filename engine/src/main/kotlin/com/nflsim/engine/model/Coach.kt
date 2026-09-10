package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/** docs/SPEC.md section 4.7. */
@Serializable
enum class CoachRole { HEAD_COACH, OFFENSIVE_COORDINATOR, DEFENSIVE_COORDINATOR, SPECIAL_TEAMS_COORDINATOR, POSITION_COACH }

/** All 0..100. docs/SPEC.md section 4.7. */
@Serializable
data class CoachRatings(
    val development: Int,
    val gameplan: Int,
    val adjustments: Int,
    val discipline: Int,
    val motivation: Int,
    val evaluation: Int,
) {
    init {
        val all = listOf(development, gameplan, adjustments, discipline, motivation, evaluation)
        require(all.all { it in 0..100 }) { "coach ratings must be 0..100, got $all" }
    }
}

/**
 * docs/SPEC.md section 4.7.
 *
 * [scheme] is a String key into `schemes.json`, not a `SchemeId` type - the
 * spec's `SchemeId` is stale, Team already keys schemes by String.
 *
 * [tendencies] (the ~20-field play-calling system, SPEC 5.4) is deliberately
 * omitted for now; it belongs with the coaching carousel in M8.
 */
@Serializable
data class Coach(
    val id: CoachId,
    val name: String,
    val age: Int,
    val role: CoachRole,
    val scheme: String,
    val ratings: CoachRatings,
    /** Mentor, for coaching-tree flavor. */
    val tree: CoachId? = null,
    val hotSeat: Int = 0,
    val contractYearsLeft: Int = 1,
)

/** docs/SPEC.md section 4.7. */
@Serializable
data class Staff(
    val headCoach: CoachId,
    val offCoordinator: CoachId,
    val defCoordinator: CoachId,
    val stCoordinator: CoachId,
    val positionCoaches: Map<PositionGroup, CoachId>,
    val scoutingDept: Int = 50,
    val trainingStaff: Int = 50,
    val medicalStaff: Int = 50,
) {
    companion object {
        /**
         * A club with nobody hired. Saves written before staffs existed carry
         * no staff field at all, so they decode into this and the save
         * migration hires them a real one (docs/SPEC.md 9.1).
         *
         * Generated coach ids start at 1, so 0 resolves to nobody and every
         * rating lookup falls back to league average rather than throwing.
         */
        val UNASSIGNED = Staff(
            headCoach = CoachId(0),
            offCoordinator = CoachId(0),
            defCoordinator = CoachId(0),
            stCoordinator = CoachId(0),
            positionCoaches = emptyMap(),
        )
    }
}
