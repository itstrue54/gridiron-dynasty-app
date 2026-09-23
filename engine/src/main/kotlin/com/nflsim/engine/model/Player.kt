package com.nflsim.engine.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlayerStatus { ACTIVE, IR, PUP, SUSPENDED, PRACTICE_SQUAD, FREE_AGENT, RETIRED }

@Serializable
data class Player(
    val id: PlayerId,
    val firstName: String,
    val lastName: String,
    val position: Position,
    val archetype: Archetype,
    val birthYear: Int,
    val heightIn: Int,
    val weightLb: Int,
    val college: String,
    /** Optional. Real rosters have them; generated players may not. */
    val jersey: Int? = null,
    val ratings: Ratings,
    val traits: HiddenTraits,
    val teamId: TeamId? = null,
    val contract: Contract? = null,
    val status: PlayerStatus = PlayerStatus.ACTIVE,
    /** Seasons spent in the current scheme. Drives familiarity. */
    val yearsInSystem: Int = 0,
    /** Seasons with his current club. Older saves carry none and fall back on years in the scheme. */
    val yearsWithClub: Int? = null,
    /** Accrued seasons, for free agency eligibility. */
    val accruedSeasons: Int = 0,
    /** Consecutive franchise or transition tags from his club; the CBA escalates each. */
    val timesTagged: Int = 0,
    /** Pro Bowls from the league's own vote; the fifth-year option's tiers count them. */
    val proBowls: Int = 0,
    /** 0..100, resets weekly. */
    val fatigue: Int = 0,
    /** Games he will still miss through injury; 0 is available. */
    val injuryWeeks: Int = 0,
    /** This season's wear and tear, 0..100: snaps build it, the week between games eases it. */
    val wear: Int = 0,
    /** 0..100. */
    val morale: Int = 75,
    /** -100..100: how he is playing this month, not how good he is. Resets each season. */
    val form: Int = 0,
    /** Everything he has done, season by season (SPEC 4, 9.2). */
    val careerStats: CareerStats = CareerStats(),
) {
    init {
        require(archetype.group == position.group) {
            "$archetype is a ${archetype.group} archetype, cannot be assigned to $position"
        }
    }

    val name: String get() = "$firstName $lastName"
    /** Seasons with his current club, which a new scheme does not reset. */
    val clubYears: Int get() = yearsWithClub ?: yearsInSystem

    fun age(inYear: Int): Int = inYear - birthYear

    fun capHit(year: Int): Int = contract?.capHit(year) ?: 0

    val isFreeAgent: Boolean get() = teamId == null || contract == null

    operator fun get(id: RatingId): Int = ratings[id]

    override fun toString(): String = "$name ($position, ${archetype.label})"
}
