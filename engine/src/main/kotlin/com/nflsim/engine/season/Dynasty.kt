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
    /** The season's news, newest last. Kept for the year, not forever. */
    val news: List<com.nflsim.engine.model.NewsEvent> = emptyList(),
    /** What happened between seasons, for the news screen. */
    val lastOffseason: com.nflsim.engine.offseason.OffseasonReport? = null,
    /**
     * Whether the user has handed his in-season roster moves to his front
     * office: filling places reserve opens, topping up the practice squad,
     * cutting the stopgap when a man comes back. Off by default - the moves
     * are his to make.
     */
    val frontOfficeRoster: Boolean = false,
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

    /** The game week a move made now comes before, for the wire; 0 out of season. */
    val wireWeek: Int get() = when (phase) {
        DynastyPhase.REGULAR_SEASON -> week
        DynastyPhase.PLAYOFFS -> Schedule.WEEKS + 1
        else -> 0
    }

    /** Game cheques left to pay a man signed today (CBA Article 26). */
    val weeksLeft: Int get() = when (phase) {
        DynastyPhase.REGULAR_SEASON -> Schedule.WEEKS - week + 1
        DynastyPhase.PLAYOFFS -> 1
        else -> Schedule.WEEKS
    }
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
    /**
     * [onGame] hears each game of a regular-season week as it finishes, as
     * (games done, games in the week), so a screen can show how far along
     * it is (SPEC 11). It is told, never asked: it cannot change the week.
     */
    fun advance(
        dynasty: Dynasty,
        tuning: TuningTable = dynasty.league.tuning,
        onGame: (done: Int, total: Int) -> Unit = { _, _ -> },
        /**
         * Calls the user's club's snaps in its regular-season game, or in
         * each of its playoff games (SPEC 5.4); null leaves them to its
         * coordinators. It may block while the user decides.
         */
        caller: com.nflsim.engine.sim.SnapCaller? = null,
    ): Dynasty =
        when (dynasty.phase) {
            DynastyPhase.PRESEASON -> dynasty.copy(phase = DynastyPhase.REGULAR_SEASON)
            DynastyPhase.REGULAR_SEASON -> advanceWeek(dynasty, tuning, onGame, caller)
            DynastyPhase.PLAYOFFS -> runPlayoffs(dynasty, tuning, caller)
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
                offScheme = SchemeCatalog.tuned(team.offenseScheme, league.tuning),
                defScheme = SchemeCatalog.tuned(team.defenseScheme, league.tuning),
                aggression = com.nflsim.engine.model.GamePlan.defaultAggression(team.id.v),
                staffPlan = com.nflsim.engine.gen.Tendencies.of(team.staff, league.coaches),
                adjustments = league.coaches[team.staff.headCoach]?.ratings?.adjustments ?: 50,
            )
        }

    /** How much of the season's news a save carries. */
    const val NEWS_KEPT = 240

    private fun advanceWeek(
        dynasty: Dynasty,
        tuning: TuningTable,
        onGame: (Int, Int) -> Unit,
        caller: com.nflsim.engine.sim.SnapCaller?,
    ): Dynasty {
        val teams = WeekRunner.teams(dynasty.league, tuning)
        val played = mutableListOf<GameResult>()
        val root = SplitMixRng(dynasty.seed)
        val week = dynasty.week

        var stats = dynasty.playerStats
        val outcomes = dynasty.results.toMutableList()
        var userGame: GameResult? = dynasty.lastGame

        val games = dynasty.schedule.week(week)
        games.forEachIndexed { done, matchup ->
            val rng = root.split(
                "y=${dynasty.year}|w=$week|h=${matchup.home.v}|a=${matchup.away.v}")
            val mine = matchup.involves(dynasty.userTeamId)
            val side = if (matchup.home == dynasty.userTeamId) com.nflsim.engine.sim.Side.HOME else com.nflsim.engine.sim.Side.AWAY
            val g = GameSimulator(
                teams.getValue(matchup.home), teams.getValue(matchup.away), tuning,
                caller = if (mine) caller else null, callerSide = if (mine) side else null,
                overtime = com.nflsim.engine.sim.Overtime.REGULAR_SEASON,
                weather = com.nflsim.engine.sim.Weather.draw(teams.getValue(matchup.home).team.stadium, week, rng.split("weather")),
            ).simulate(rng)
            outcomes += GameOutcome(week, matchup.home, matchup.away, g.homeScore, g.awayScore)
            stats = merge(stats, g.boxScore.players)
            if (matchup.involves(dynasty.userTeamId)) userGame = g
            played += g
            onGame(done + 1, games.size)
        }

        // The week, as news. A career mark counts the seasons behind a man as
        // well as this one, so it is news the week it turns over and not again.
        fun careerAnd(line: com.nflsim.engine.stats.StatLine?, id: Int): com.nflsim.engine.stats.StatLine {
            val career = dynasty.league.playersById[com.nflsim.engine.model.PlayerId(id)]
                ?.careerStats?.seasons?.fold(com.nflsim.engine.stats.StatLine()) { sum, s -> sum + s.stats }
                ?: com.nflsim.engine.stats.StatLine()
            return if (line == null) career else career + line
        }
        val before = stats.keys.associateWith { careerAnd(dynasty.playerStats[it], it) }
        val after = stats.mapValues { (id, line) -> careerAnd(line, id) }
        val filed = NewsDesk.forWeek(
            league = dynasty.league,
            week = week,
            results = played,
            before = before,
            after = after,
            standings = Standings(dynasty.league, outcomes, root.split("news|$week")),
            alreadySaid = dynasty.news,
            rng = root.split("headlines|${dynasty.year}|$week"),
        )

        val nextWeek = week + 1
        val afterGames = WeekRunner.afterWeek(
            // A user who has handed his roster to the front office is managed
            // like every other club; otherwise his places wait for him.
            dynasty.league, played, tuning,
            if (dynasty.frontOfficeRoster) null else dynasty.userTeamId,
            weeksLeft = Schedule.WEEKS - week,
        ).let { l ->
            l.copy(history = l.history.archived(*played.map { g ->
                // Filed against the league as it was when they played.
                com.nflsim.engine.model.ArchivedGame.of(
                    dynasty.year, week, dynasty.league, g.home.v, g.away.v, g.homeScore, g.awayScore, g.boxScore,
                    plays = if (g.home == dynasty.userTeamId || g.away == dynasty.userTeamId) g.playByPlay
                        else emptyList())
            }.toTypedArray()))
        }
        // Who has noticed what he is paid (SPEC 10.1). The league's clubs
        // answer at once; the user's are asked and left to decide.
        val disputes = ContractDisputes.afterWeek(
            afterGames, nextWeek, dynasty.userTeamId, stats, root.split("disputes|${dynasty.year}|$week"))
        // What other clubs did to the user's this week. Signing a man off his
        // practice squad is theirs to do and his to hear about: it is on the
        // wire, and nobody reads the wire for news of their own club.
        val words = root.split("headlines|poached|${dynasty.year}|$week")
        val raided = disputes.league.transactions.drop(dynasty.league.transactions.size)
            .filter {
                it.kind == com.nflsim.engine.model.TransactionKind.SIGNED_OFF_SQUAD &&
                    it.other == dynasty.userTeam
            }
            .map { line ->
                val by = disputes.league.teams.firstOrNull { it.id.v == line.team }?.abbrev ?: "?"
                com.nflsim.engine.model.NewsEvent(
                    nextWeek, com.nflsim.engine.model.NewsKind.POACHED,
                    Headlines.write("poached", words, "by" to by, "pos" to line.position, "player" to line.name),
                    line.player, dynasty.userTeam,
                )
            }
        return dynasty.copy(
            league = disputes.league,
            week = nextWeek,
            results = outcomes,
            playerStats = stats,
            news = (dynasty.news + filed + disputes.news + raided).takeLast(NEWS_KEPT),
            lastGame = userGame,
            phase = if (nextWeek > Schedule.WEEKS) DynastyPhase.PLAYOFFS
                    else DynastyPhase.REGULAR_SEASON,
        )
    }

    private fun runPlayoffs(dynasty: Dynasty, tuning: TuningTable, caller: com.nflsim.engine.sim.SnapCaller?): Dynasty {
        // The bracket from the season actually played, with the league as it
        // stands after it: injuries, wear and anything changed mid-season.
        val full = SeasonSimulator(dynasty.league, dynasty.year, dynasty.seed, tuning)
            .postseason(dynasty.results, dynasty.playerStats, dynasty.league, caller, dynasty.userTeamId)
        val archive = full.playoffs.mapNotNull { p ->
            p.box?.let { box ->
                com.nflsim.engine.model.ArchivedGame.of(
                    dynasty.year, Schedule.WEEKS + p.round.ordinal + 1, dynasty.league,
                    p.home.v, p.away.v, p.homeScore, p.awayScore, box,
                    plays = if (p.home == dynasty.userTeamId || p.away == dynasty.userTeamId) p.plays
                        else emptyList())
            }
        }
        return dynasty.copy(
            league = dynasty.league.copy(history = dynasty.league.history.archived(*archive.toTypedArray())),
            phase = DynastyPhase.OFFSEASON,
            // The boxes live in the archive; the bracket keeps the scores.
            playoffs = full.playoffs.map { it.copy(box = null, plays = emptyList()) },
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
