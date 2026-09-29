package com.nflsim.engine.model

import kotlinx.serialization.Serializable

@Serializable
enum class Conference(val label: String) {
    AMERICAN("American"), CONTINENTAL("Continental")
}

@Serializable
enum class Division { EAST, NORTH, SOUTH, WEST }

@Serializable
data class Stadium(
    val name: String,
    val domed: Boolean = false,
    val altitudeFt: Int = 0,
    val capacity: Int = 68_000,
    /** 0..100. Drives road false starts and communication penalties. */
    val crowdNoise: Int = 70,
    /** The region its weather comes from (climate.json, SPEC 5.10). A domed stadium plays indoors. */
    val climate: String = "temperate",
)

@Serializable
data class Team(
    val id: TeamId,
    val city: String,
    val nickname: String,
    val abbrev: String,
    val conference: Conference,
    val division: Division,
    val stadium: Stadium,
    /** 0..100. Affects free agent appeal and revenue. */
    val marketSize: Int = 50,
    /** Scheme ids from schemes.json. */
    val offenseScheme: String,
    val defenseScheme: String,
    val roster: List<PlayerId> = emptyList(),
    /** SPEC 7: the men the club trains who do not count against the 53. */
    val practiceSquad: List<PlayerId> = emptyList(),
    val staff: Staff = Staff.UNASSIGNED,
    val finances: TeamFinances = TeamFinances(),
    /** How this front office behaves in the market (SPEC 8.2). */
    val gm: GmProfile = GmProfile(),
    /** SPEC 5.5: the club's pins over its automatic depth chart. */
    val depthPins: DepthPins = DepthPins(),
    /** SPEC 5.4: the tendencies the club's coordinators call from. */
    val gamePlan: GamePlan = GamePlan(),
    /**
     * SPEC 4.6: the positions the club's scouts watch closely before the
     * draft. Empty means it spreads them across the board instead, and knows
     * a little about everyone.
     */
    val scoutingFocus: Set<Position> = emptySet(),
) {
    val name: String get() = "$city $nickname"

    override fun toString(): String = name
}

/** The shape teams.json parses into, before ids and rosters are assigned. */
@Serializable
data class TeamSeed(
    val city: String,
    val nickname: String,
    val abbrev: String,
    val conference: Conference,
    val division: Division,
    val stadium: Stadium,
    val marketSize: Int = 50,
)
