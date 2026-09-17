package com.nflsim.engine.model

import com.nflsim.engine.season.Awards
import com.nflsim.engine.season.TeamRecord
import kotlinx.serialization.Serializable

/** Who led the league in something, kept with his name for when he is gone. */
@Serializable
data class LeaderEntry(val category: String, val player: Int, val name: String, val value: Int)

/**
 * A season, after it is over (SPEC 9.2: standings, awards and results are kept
 * forever; play-by-play is not). No play data, no box scores - the aggregates
 * a league remembers about a year.
 */
@Serializable
data class SeasonRecord(
    val year: Int,
    val champion: Int? = null,
    val standings: List<TeamRecord> = emptyList(),
    val awards: Awards? = null,
    val leaders: List<LeaderEntry> = emptyList(),
)

/**
 * A career that has ended. A retired player leaves the league entirely, so
 * what he did has to live here or be lost - which is also what a hall of fame
 * is voted from.
 */
@Serializable
data class RetiredCareer(
    val player: Int,
    val name: String,
    val position: String,
    val age: Int,
    val overall: Int,
    val proBowls: Int = 0,
    val lastTeam: Int? = null,
    val career: CareerStats = CareerStats(),
    /** The year he left. */
    val year: Int = 0,
)

/**
 * A career the league decided to keep. The numbers are frozen at induction:
 * what he did, and what the league said about him while he did it.
 */
@Serializable
data class HallOfFamer(
    val player: Int,
    val name: String,
    val position: String,
    val inducted: Int,
    val retired: Int,
    val seasons: Int,
    val proBowls: Int = 0,
    /** The number he is remembered by: yards thrown, run, caught, or tackles. */
    val headline: Int = 0,
    /** What the vote was worth, in very good seasons. */
    val score: Float = 0f,
)

/** What the league remembers (SPEC 4.7's `history`). */
@Serializable
data class LeagueHistory(
    val seasons: List<SeasonRecord> = emptyList(),
    val retired: List<RetiredCareer> = emptyList(),
    val hallOfFame: List<HallOfFamer> = emptyList(),
) {
    fun season(year: Int): SeasonRecord? = seasons.firstOrNull { it.year == year }

    val champions: List<Pair<Int, Int>>
        get() = seasons.mapNotNull { r -> r.champion?.let { r.year to it } }
}
