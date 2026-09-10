package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/**
 * The entire save file. Everything else hangs off this.
 *
 * Teams and players are stored as lists rather than maps so the format stays
 * simple across serializers; the by-id indexes are built on demand.
 */
@Serializable
data class League(
    val saveVersion: Int = CURRENT_SAVE_VERSION,
    val seed: Long,
    val year: Int,
    val teams: List<Team>,
    val players: List<Player>,
    val coaches: Map<CoachId, Coach> = emptyMap(),
) {
    val teamsById: Map<TeamId, Team> by lazy { teams.associateBy { it.id } }
    val playersById: Map<PlayerId, Player> by lazy { players.associateBy { it.id } }

    fun team(id: TeamId): Team = teamsById[id] ?: error("no team $id")
    fun player(id: PlayerId): Player = playersById[id] ?: error("no player $id")
    fun coach(id: CoachId): Coach = coaches[id] ?: error("no coach $id")

    fun roster(teamId: TeamId): List<Player> = team(teamId).roster.map { player(it) }

    fun divisions(): Map<Pair<Conference, Division>, List<Team>> =
        teams.groupBy { it.conference to it.division }

    val freeAgents: List<Player> get() = players.filter { it.teamId == null }

    companion object {
        const val CURRENT_SAVE_VERSION = 1
        const val TEAM_COUNT = 32
        const val ROSTER_SIZE = 53
    }
}
