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
    val ratings: Ratings,
    val traits: HiddenTraits,
    val teamId: TeamId? = null,
    val status: PlayerStatus = PlayerStatus.ACTIVE,
    /** Seasons spent in the current scheme. Drives familiarity. */
    val yearsInSystem: Int = 0,
    /** Accrued seasons, for free agency eligibility. */
    val accruedSeasons: Int = 0,
    /** 0..100, resets weekly. */
    val fatigue: Int = 0,
    /** 0..100. */
    val morale: Int = 75,
) {
    init {
        require(archetype.group == position.group) {
            "$archetype is a ${archetype.group} archetype, cannot be assigned to $position"
        }
    }

    val name: String get() = "$firstName $lastName"

    fun age(inYear: Int): Int = inYear - birthYear

    operator fun get(id: RatingId): Int = ratings[id]

    override fun toString(): String = "$name ($position, ${archetype.label})"
}
