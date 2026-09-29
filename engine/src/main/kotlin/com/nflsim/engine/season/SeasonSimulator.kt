package com.nflsim.engine.season

import com.nflsim.engine.model.Conference
import com.nflsim.engine.model.League
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.sim.GameSimulator
import com.nflsim.engine.sim.GameTeam
import com.nflsim.engine.stats.StatLine
import com.nflsim.engine.tuning.TuningTable
import kotlinx.serialization.Serializable

@Serializable
enum class PlayoffRound(val label: String) {
    WILD_CARD("Wild Card"),
    DIVISIONAL("Divisional"),
    CONFERENCE("Conference Championship"),
    FINAL("Championship"),
}

@Serializable
data class PlayoffGame(
    val round: PlayoffRound,
    val conference: Conference?,
    val home: TeamId,
    val away: TeamId,
    val homeSeed: Int,
    val awaySeed: Int,
    val homeScore: Int,
    val awayScore: Int,
    /** The game's box score, until the dynasty archives it. */
    val box: com.nflsim.engine.stats.BoxScore? = null,
    /** Its play-by-play, likewise, until the dynasty keeps the user's and drops the rest. */
    val plays: List<com.nflsim.engine.sim.PlayLog> = emptyList(),
) {
    val winner: TeamId get() = if (homeScore >= awayScore) home else away
    val loser: TeamId get() = if (homeScore >= awayScore) away else home
}

@Serializable
data class SeasonResult(
    val year: Int,
    val results: List<GameOutcome>,
    val records: Map<Int, TeamRecord>,
    val seeds: Map<String, List<Int>>,
    val playoffs: List<PlayoffGame>,
    val champion: TeamId?,
    /** Regular season only. Leaderboards and awards are decided on these. */
    val playerStats: Map<Int, StatLine>,
    /** Postseason, kept apart - a deep run should not win a rushing title. */
    val playoffStats: Map<Int, StatLine> = emptyMap(),
    val awards: Awards,
    /** Regular-season injuries that cost games. */
    val injuries: List<com.nflsim.engine.sim.Injury> = emptyList(),
) {
    fun record(id: TeamId): TeamRecord = records[id.v] ?: TeamRecord(id)
    fun statsFor(id: PlayerId): StatLine = playerStats[id.v] ?: StatLine()
    fun seedsFor(conference: Conference): List<TeamId> =
        (seeds[conference.name] ?: emptyList()).map { TeamId(it) }
}

/**
 * A full season: eighteen weeks, then the tournament.
 *
 * Every game gets its own RNG stream keyed by week and matchup, so a single
 * game can be re-simmed in isolation and come out identical - which is what
 * makes a bug report from week seven reproducible in a thirty year dynasty.
 */
class SeasonSimulator(
    private val league: League,
    private val year: Int,
    private val seed: Long,
    private val tuning: TuningTable = league.tuning,
) {

    /** Postseason teams, dressed from the league as the regular season left it. */
    private var gameTeams: Map<TeamId, GameTeam> = emptyMap()

    fun simulate(): SeasonResult {
        val root = SplitMixRng(seed)
        val schedule = ScheduleGenerator.generate(league, year, root.split("schedule|$year"))

        val outcomes = mutableListOf<GameOutcome>()
        var stats = mapOf<Int, StatLine>()
        val injuries = mutableListOf<com.nflsim.engine.sim.Injury>()

        // A season starts healthy and fresh; injuries and wear carry week to week.
        var current = WeekRunner.healthy(league)
        for (week in 1..Schedule.WEEKS) {
            val teams = WeekRunner.teams(current, tuning)
            val played = mutableListOf<com.nflsim.engine.sim.GameResult>()
            schedule.week(week).forEach { matchup ->
                val rng = root.split("y=$year|w=$week|h=${matchup.home.v}|a=${matchup.away.v}")
                val g = GameSimulator(teams.getValue(matchup.home), teams.getValue(matchup.away), tuning,
                    overtime = com.nflsim.engine.sim.Overtime.REGULAR_SEASON,
                    weather = com.nflsim.engine.sim.Weather.draw(teams.getValue(matchup.home).team.stadium, week, rng.split("weather")))
                    .simulate(rng)
                outcomes += GameOutcome(week, matchup.home, matchup.away, g.homeScore, g.awayScore)
                stats = merge(stats, g.boxScore.players)
                injuries += g.injuries
                played += g
            }
            current = WeekRunner.afterWeek(current, played, tuning, weeksLeft = Schedule.WEEKS - week)
        }
        return finish(outcomes, stats, current, root).copy(injuries = injuries)
    }

    /**
     * The postseason from a regular season already played, with the league as
     * it stands after it. [caller] calls [callerTeam]'s snaps in each of its
     * playoff games (SPEC 5.4); null leaves them to its coordinators.
     */
    fun postseason(
        outcomes: List<GameOutcome>,
        stats: Map<Int, StatLine>,
        current: League,
        caller: com.nflsim.engine.sim.SnapCaller? = null,
        callerTeam: TeamId? = null,
    ): SeasonResult {
        this.caller = caller
        this.callerTeam = callerTeam
        try {
            return finish(outcomes, stats, current, SplitMixRng(seed))
        } finally {
            this.caller = null
            this.callerTeam = null
        }
    }

    private var caller: com.nflsim.engine.sim.SnapCaller? = null
    private var callerTeam: TeamId? = null

    private fun finish(outcomes: List<GameOutcome>, stats: Map<Int, StatLine>, current: League, root: Rng): SeasonResult {
        gameTeams = WeekRunner.teams(current, tuning)
        var postseason = mapOf<Int, StatLine>()
        val standings = Standings(league, outcomes, root.split("tiebreak|$year"))
        val seedsByConference = Conference.entries.associate {
            it.name to standings.seeds(it).map { id -> id.v }
        }

        val playoffs = mutableListOf<PlayoffGame>()
        val finalists = Conference.entries.map { conference ->
            val seeded = standings.seeds(conference)
            runConference(conference, seeded, root, playoffs) { s -> postseason = merge(postseason, s) }
        }

        val title = playGame(
            PlayoffRound.FINAL, null,
            finalists[0], 1, finalists[1], 2,
            root.split("y=$year|final"),
        ) { s -> postseason = merge(postseason, s) }
        playoffs += title

        val awards = AwardVoting.decide(league, standings.records, stats, year)

        return SeasonResult(
            year = year,
            results = outcomes,
            records = standings.records.mapKeys { it.key.v },
            seeds = seedsByConference,
            playoffs = playoffs,
            champion = title.winner,
            playerStats = stats,
            playoffStats = postseason,
            awards = awards,
        )
    }

    /** Wild card, divisional with reseeding, then the conference title. */
    private fun runConference(
        conference: Conference,
        seeded: List<TeamId>,
        root: Rng,
        into: MutableList<PlayoffGame>,
        collect: (Map<Int, StatLine>) -> Unit,
    ): TeamId {
        val seedOf = seeded.withIndex().associate { (i, id) -> id to i + 1 }

        // Wild card weekend: 2v7, 3v6, 4v5. The top seed rests.
        val wildCard = listOf(1 to 6, 2 to 5, 3 to 4).map { (hi, lo) ->
            playGame(PlayoffRound.WILD_CARD, conference,
                seeded[hi], seedOf.getValue(seeded[hi]),
                seeded[lo], seedOf.getValue(seeded[lo]),
                root.split("$conference|wc|$hi"), collect)
        }
        into += wildCard

        // Reseed: the top seed always draws the lowest survivor.
        val survivors = (listOf(seeded[0]) + wildCard.map { it.winner })
            .sortedBy { seedOf.getValue(it) }

        val divisional = listOf(
            survivors[0] to survivors[3],
            survivors[1] to survivors[2],
        ).mapIndexed { i, (high, low) ->
            playGame(PlayoffRound.DIVISIONAL, conference,
                high, seedOf.getValue(high), low, seedOf.getValue(low),
                root.split("$conference|div|$i"), collect)
        }
        into += divisional

        val championshipTeams = divisional.map { it.winner }.sortedBy { seedOf.getValue(it) }
        val championship = playGame(PlayoffRound.CONFERENCE, conference,
            championshipTeams[0], seedOf.getValue(championshipTeams[0]),
            championshipTeams[1], seedOf.getValue(championshipTeams[1]),
            root.split("$conference|title"), collect)
        into += championship

        return championship.winner
    }

    /**
     * Playoff games cannot end level: a tie goes to overtime (SPEC 5.10), and
     * the rare one overtime cannot settle replays until it does not.
     */
    private fun playGame(
        round: PlayoffRound,
        conference: Conference?,
        home: TeamId,
        homeSeed: Int,
        away: TeamId,
        awaySeed: Int,
        rng: Rng,
        collect: (Map<Int, StatLine>) -> Unit,
    ): PlayoffGame {
        // The user's game, if he is calling it. Overtime all but settles a
        // game; the rare one it cannot, the coordinators replay.
        val calling = caller?.takeIf { callerTeam == home || callerTeam == away }
        val side = if (callerTeam == home) com.nflsim.engine.sim.Side.HOME else com.nflsim.engine.sim.Side.AWAY
        calling?.kickoff(title(round, conference), home, away)
        var attempt = 0
        while (true) {
            val live = calling?.takeIf { attempt == 0 }
            val g = GameSimulator(gameTeams.getValue(home), gameTeams.getValue(away), tuning,
                caller = live, callerSide = live?.let { side }, overtime = com.nflsim.engine.sim.Overtime.PLAYOFFS,
                // The same weather for a replay: it is the same afternoon.
                weather = com.nflsim.engine.sim.Weather.draw(gameTeams.getValue(home).team.stadium,
                    Schedule.WEEKS + round.ordinal + 1, rng.split("weather")))
                .simulate(rng.split("ot=$attempt"))
            collect(g.boxScore.players)
            if (g.homeScore != g.awayScore || attempt >= MAX_OVERTIME) {
                val homeScore = if (g.homeScore == g.awayScore) g.homeScore + 3 else g.homeScore
                calling?.final(homeScore, g.awayScore)
                return PlayoffGame(round, conference, home, away, homeSeed, awaySeed,
                    homeScore, g.awayScore, box = g.boxScore, plays = g.playByPlay)
            }
            attempt++
        }
    }

    private fun merge(a: Map<Int, StatLine>, b: Map<Int, StatLine>): Map<Int, StatLine> {
        if (a.isEmpty()) return b
        val out = a.toMutableMap()
        b.forEach { (id, line) -> out[id] = (out[id] ?: StatLine()) + line }
        return out
    }

    /** "Wild card round", "AFC championship", or the league's name for its title game. */
    private fun title(round: PlayoffRound, conference: Conference?): String = when (round) {
        PlayoffRound.WILD_CARD -> "Wild card round"
        PlayoffRound.DIVISIONAL -> "Divisional round"
        PlayoffRound.CONFERENCE -> "${league.names.conference(conference ?: Conference.entries.first())} championship"
        PlayoffRound.FINAL -> league.names.championship.ifBlank { "The championship" }
    }

    private companion object {
        const val MAX_OVERTIME = 3
    }
}
