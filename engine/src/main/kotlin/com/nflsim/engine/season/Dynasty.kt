package com.nflsim.engine.season

import com.nflsim.engine.model.Conference
import com.nflsim.engine.model.League
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.sim.GameResult
import com.nflsim.engine.sim.GameSimulator
import com.nflsim.engine.sim.GameTeam
import com.nflsim.engine.stats.StatLine
import com.nflsim.engine.tuning.TuningTable
import kotlinx.serialization.Serializable

@Serializable
enum class DynastyPhase(val label: String) {
    PRESEASON("Preseason"),
    REGULAR_SEASON("Regular Season"),
    PLAYOFFS("Playoffs"),
    OFFSEASON("Offseason"),
}

/**
 * A save file in progress.
 *
 * SeasonSimulator plays a whole year in one call, which is what a calibration
 * run wants. A person wants to advance one week, look at what happened, and
 * decide something - so the state has to be resumable between weeks rather
 * than living inside a loop.
 */
@Serializable
data class Dynasty(
    val league: League,
    val year: Int,
    val seed: Long,
    val userTeam: Int,
    val schedule: Schedule,
    val week: Int = 1,
    val phase: DynastyPhase = DynastyPhase.REGULAR_SEASON,
    val results: List<GameOutcome> = emptyList(),
    val playerStats: Map<Int, StatLine> = emptyMap(),
    /** Last season's lines, for the comeback award. */
    val previousStats: Map<Int, StatLine> = emptyMap(),
    val playoffs: List<PlayoffGame> = emptyList(),
    val champion: Int? = null,
    /** The user's most recent game, kept for the box score screen. */
    val lastGame: GameResult? = null,
    /** What happened between seasons, for the news screen. */
    val lastOffseason: com.nflsim.engine.offseason.OffseasonReport? = null,
) {
    val userTeamId: TeamId get() = TeamId(userTeam)
    val team get() = league.team(userTeamId)

    val isSeasonOver: Boolean get() = phase == DynastyPhase.OFFSEASON

    fun standings(): Standings = Standings(league, results, SplitMixRng(seed))

    fun record(id: TeamId = userTeamId): TeamRecord = standings().record(id)

    /** The user's next opponent, or null on a bye or once the season ends. */
    fun nextGame(): Matchup? =
        if (phase != DynastyPhase.REGULAR_SEASON) null
        else schedule.week(week).firstOrNull { it.involves(userTeamId) }

    fun resultsForWeek(n: Int): List<GameOutcome> = results.filter { it.week == n }

    fun userResults(): List<GameOutcome> = results.filter { it.involves(userTeamId) }
}

/**
 * Moves a dynasty forward. Every advance is a pure function of the state and
 * the seed, so a save reloaded mid-season continues identically.
 */
object DynastyEngine {

    fun start(
        league: League,
        year: Int,
        seed: Long,
        userTeam: TeamId,
    ): Dynasty {
        val schedule = ScheduleGenerator.generate(
            league, year, SplitMixRng(seed).split("schedule|$year"))
        return Dynasty(
            league = league, year = year, seed = seed,
            userTeam = userTeam.v, schedule = schedule,
        )
    }

    /** Plays the current week, or the next playoff round. */
    fun advance(dynasty: Dynasty, tuning: TuningTable = TuningTable.REALISTIC): Dynasty =
        when (dynasty.phase) {
            DynastyPhase.PRESEASON -> dynasty.copy(phase = DynastyPhase.REGULAR_SEASON)
            DynastyPhase.REGULAR_SEASON -> advanceWeek(dynasty, tuning)
            DynastyPhase.PLAYOFFS -> runPlayoffs(dynasty, tuning)
            DynastyPhase.OFFSEASON -> rollOver(dynasty)
        }

    /**
     * The offseason, and into the next year. This is the step that turns a
     * season into a dynasty: players age, some retire, a draft class arrives,
     * and the roster you finish with is not the roster you started with.
     */
    private fun rollOver(dynasty: Dynasty): Dynasty {
        val (next, report) = com.nflsim.engine.offseason.OffseasonEngine.run(dynasty)
        return next.copy(lastOffseason = report)
    }

    private fun teamsFor(league: League): Map<TeamId, GameTeam> =
        league.teams.associate { team ->
            team.id to GameTeam(
                team = team,
                roster = league.roster(team.id),
                offScheme = SchemeCatalog[team.offenseScheme],
                defScheme = SchemeCatalog[team.defenseScheme],
                aggression = 0.35f + (team.id.v % 7) * 0.06f,
            )
        }

    private fun advanceWeek(dynasty: Dynasty, tuning: TuningTable): Dynasty {
        val teams = teamsFor(dynasty.league)
        val root = SplitMixRng(dynasty.seed)
        val week = dynasty.week

        var stats = dynasty.playerStats
        val outcomes = dynasty.results.toMutableList()
        var userGame: GameResult? = dynasty.lastGame

        dynasty.schedule.week(week).forEach { matchup ->
            val rng = root.split(
                "y=${dynasty.year}|w=$week|h=${matchup.home.v}|a=${matchup.away.v}")
            val g = GameSimulator(
                teams.getValue(matchup.home), teams.getValue(matchup.away), tuning).simulate(rng)
            outcomes += GameOutcome(week, matchup.home, matchup.away, g.homeScore, g.awayScore)
            stats = merge(stats, g.boxScore.players)
            if (matchup.involves(dynasty.userTeamId)) userGame = g
        }

        val nextWeek = week + 1
        return dynasty.copy(
            week = nextWeek,
            results = outcomes,
            playerStats = stats,
            lastGame = userGame,
            phase = if (nextWeek > Schedule.WEEKS) DynastyPhase.PLAYOFFS
                    else DynastyPhase.REGULAR_SEASON,
        )
    }

    private fun runPlayoffs(dynasty: Dynasty, tuning: TuningTable): Dynasty {
        val season = SeasonSimulator(dynasty.league, dynasty.year, dynasty.seed, tuning)
        // Replay the year to reach the bracket. Deterministic, so the regular
        // season comes out identical to what has already been played.
        val full = season.simulate()
        return dynasty.copy(
            phase = DynastyPhase.OFFSEASON,
            playoffs = full.playoffs,
            champion = full.champion?.v,
        )
    }

    private fun merge(a: Map<Int, StatLine>, b: Map<Int, StatLine>): Map<Int, StatLine> {
        if (a.isEmpty()) return b
        val out = a.toMutableMap()
        b.forEach { (id, line) -> out[id] = (out[id] ?: StatLine()) + line }
        return out
    }

    /** Playoff seeds for a conference, once the regular season is complete. */
    fun seeds(dynasty: Dynasty, conference: Conference): List<TeamId> =
        dynasty.standings().seeds(conference)
}
