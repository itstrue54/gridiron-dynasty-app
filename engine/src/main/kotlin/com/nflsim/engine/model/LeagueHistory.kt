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
    /** Every game's box score: full for [BOX_SCORE_SEASONS], team totals after (SPEC 9.2). */
    val games: List<ArchivedGame> = emptyList(),
) {
    fun season(year: Int): SeasonRecord? = seasons.firstOrNull { it.year == year }

    fun archived(vararg played: ArchivedGame): LeagueHistory = copy(games = games + played)

    /**
     * Player lines dropped from every season more than [BOX_SCORE_SEASONS]
     * behind [year]; the team totals stay forever.
     */
    fun compressedFor(year: Int): LeagueHistory = copy(games = games.map {
        // Play-by-play is the current season's only; the year turning over ends it.
        val game = if (it.plays.isEmpty()) it else it.copy(plays = emptyList())
        if (game.year > year - BOX_SCORE_SEASONS || game.box.players.isEmpty()) game
        else game.copy(box = game.box.copy(players = emptyMap()), awayPlayers = emptyList())
    })

    companion object {
        /** SPEC 9.2: box scores are kept full for the last five seasons. */
        const val BOX_SCORE_SEASONS = 5
    }

    val champions: List<Pair<Int, Int>>
        get() = seasons.mapNotNull { r -> r.champion?.let { r.year to it } }
}

/** One game as the record keeps it (SPEC 9.2). */
@Serializable
data class ArchivedGame(
    val year: Int,
    /** 1-18 in the regular season; the playoff rounds follow as 19-22. */
    val week: Int,
    val home: Int,
    val away: Int,
    val homeScore: Int,
    val awayScore: Int,
    /** Player lines are empty once the game is more than five seasons old. */
    val box: com.nflsim.engine.stats.BoxScore,
    /**
     * Which of the box's players were the away side's. A box score does not
     * say, and a man's club today is not necessarily the one he played for.
     */
    val awayPlayers: List<Int> = emptyList(),
    /**
     * The play-by-play, for the user's own games this season (SPEC 9.2 keeps
     * play-by-play for the current season). Every club's would be forty
     * thousand plays re-encoded every week for logs nobody reads.
     */
    val plays: List<com.nflsim.engine.sim.PlayLog> = emptyList(),
) {
    fun involves(team: Int): Boolean = home == team || away == team

    /** The box's player lines for one side of the game. */
    fun linesFor(team: Int): Map<Int, com.nflsim.engine.stats.StatLine> {
        val away = awayPlayers.toSet()
        return box.players.filterKeys { (it in away) == (team == this.away) }
    }

    companion object {
        /** Filed while every player is still with the club he played for. */
        fun of(year: Int, week: Int, league: League, home: Int, away: Int,
               homeScore: Int, awayScore: Int, box: com.nflsim.engine.stats.BoxScore,
               plays: List<com.nflsim.engine.sim.PlayLog> = emptyList()) = ArchivedGame(
            year, week, home, away, homeScore, awayScore, box,
            awayPlayers = box.players.keys.filter { league.playersById[PlayerId(it)]?.teamId?.v == away },
            plays = plays,
        )
    }
}
