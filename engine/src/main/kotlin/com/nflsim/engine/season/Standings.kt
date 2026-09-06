package com.nflsim.engine.season

import com.nflsim.engine.model.Conference
import com.nflsim.engine.model.Division
import com.nflsim.engine.model.League
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.rng.Rng
import kotlinx.serialization.Serializable

@Serializable
data class GameOutcome(
    val week: Int,
    val home: TeamId,
    val away: TeamId,
    val homeScore: Int,
    val awayScore: Int,
) {
    val winner: TeamId? get() = when {
        homeScore > awayScore -> home
        awayScore > homeScore -> away
        else -> null
    }
    val isTie: Boolean get() = homeScore == awayScore
    fun involves(id: TeamId) = home == id || away == id
    fun opponentOf(id: TeamId): TeamId? = when (id) {
        home -> away
        away -> home
        else -> null
    }
    fun scoreFor(id: TeamId): Int = if (id == home) homeScore else awayScore
    fun scoreAgainst(id: TeamId): Int = if (id == home) awayScore else homeScore
}

@Serializable
data class TeamRecord(
    val team: TeamId,
    val wins: Int = 0,
    val losses: Int = 0,
    val ties: Int = 0,
    val pointsFor: Int = 0,
    val pointsAgainst: Int = 0,
    val divisionWins: Int = 0,
    val divisionLosses: Int = 0,
    val divisionTies: Int = 0,
    val conferenceWins: Int = 0,
    val conferenceLosses: Int = 0,
    val conferenceTies: Int = 0,
) {
    val games: Int get() = wins + losses + ties
    val winPct: Double get() = if (games == 0) 0.0 else (wins + ties * 0.5) / games
    val divisionPct: Double get() {
        val g = divisionWins + divisionLosses + divisionTies
        return if (g == 0) 0.0 else (divisionWins + divisionTies * 0.5) / g
    }
    val conferencePct: Double get() {
        val g = conferenceWins + conferenceLosses + conferenceTies
        return if (g == 0) 0.0 else (conferenceWins + conferenceTies * 0.5) / g
    }
    val pointDifferential: Int get() = pointsFor - pointsAgainst
    val recordText: String get() = if (ties == 0) "$wins-$losses" else "$wins-$losses-$ties"
}

/**
 * Records, and the order they go in.
 *
 * The tiebreaker cascade is written as an ordered list of named comparators
 * rather than one long comparison chain, because "why did my 11-6 team miss
 * the playoffs" is a question the game has to be able to answer out loud.
 */
class Standings(
    private val league: League,
    private val results: List<GameOutcome>,
    private val rng: Rng,
) {

    val records: Map<TeamId, TeamRecord> = buildRecords()

    fun record(id: TeamId): TeamRecord = records[id] ?: TeamRecord(id)

    private fun buildRecords(): Map<TeamId, TeamRecord> {
        val out = league.teams.associate { it.id to TeamRecord(it.id) }.toMutableMap()
        results.forEach { g ->
            listOf(g.home, g.away).forEach { id ->
                val opponent = g.opponentOf(id)!!
                val me = league.team(id)
                val them = league.team(opponent)
                val sameDivision = me.conference == them.conference && me.division == them.division
                val sameConference = me.conference == them.conference
                val won = g.winner == id
                val tied = g.isTie

                out[id] = out[id]!!.let { r ->
                    r.copy(
                        wins = r.wins + if (won) 1 else 0,
                        losses = r.losses + if (!won && !tied) 1 else 0,
                        ties = r.ties + if (tied) 1 else 0,
                        pointsFor = r.pointsFor + g.scoreFor(id),
                        pointsAgainst = r.pointsAgainst + g.scoreAgainst(id),
                        divisionWins = r.divisionWins + if (sameDivision && won) 1 else 0,
                        divisionLosses = r.divisionLosses + if (sameDivision && !won && !tied) 1 else 0,
                        divisionTies = r.divisionTies + if (sameDivision && tied) 1 else 0,
                        conferenceWins = r.conferenceWins + if (sameConference && won) 1 else 0,
                        conferenceLosses = r.conferenceLosses + if (sameConference && !won && !tied) 1 else 0,
                        conferenceTies = r.conferenceTies + if (sameConference && tied) 1 else 0,
                    )
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------

    data class TiebreakStep(val name: String, val detail: String)

    /** Orders a group of teams, best first, and says why. */
    fun order(teams: List<TeamId>, sameDivision: Boolean): Pair<List<TeamId>, List<TiebreakStep>> {
        if (teams.size <= 1) return teams to emptyList()
        val explanation = mutableListOf<TiebreakStep>()

        val sorted = teams.sortedWith(Comparator { a, b ->
            val byWinPct = record(b).winPct.compareTo(record(a).winPct)
            if (byWinPct != 0) return@Comparator byWinPct
            compareTied(a, b, sameDivision, explanation)
        })
        return sorted to explanation
    }

    private fun compareTied(
        a: TeamId,
        b: TeamId,
        sameDivision: Boolean,
        explanation: MutableList<TiebreakStep>,
    ): Int {
        for (step in cascade(sameDivision)) {
            val result = step.compare(a, b)
            if (result != 0) {
                val winner = if (result < 0) a else b
                explanation += TiebreakStep(step.name,
                    "${league.team(winner).abbrev} over ${league.team(if (winner == a) b else a).abbrev}")
                return result
            }
        }
        // Genuinely inseparable. The rulebook says a coin flip, so do that -
        // deterministically, from the league seed.
        explanation += TiebreakStep("coin flip", "no tiebreaker separated them")
        return if (rng.nextBoolean()) -1 else 1
    }

    private inner class Step(val name: String, val value: (TeamId) -> Double) {
        /** Negative when a ranks ahead of b. */
        fun compare(a: TeamId, b: TeamId): Int = value(b).compareTo(value(a))
    }

    private fun cascade(sameDivision: Boolean): List<Step> = buildList {
        add(Step("head to head") { headToHead(it) })
        if (sameDivision) add(Step("division record") { record(it).divisionPct })
        add(Step("common games") { commonGames(it) })
        add(Step("conference record") { record(it).conferencePct })
        add(Step("strength of victory") { strengthOfVictory(it) })
        add(Step("strength of schedule") { strengthOfSchedule(it) })
        add(Step("point differential") { record(it).pointDifferential.toDouble() })
        add(Step("points scored") { record(it).pointsFor.toDouble() })
    }

    // Head to head is evaluated against the whole tied group in the real rules;
    // this pairwise form is the common case and is what a two team tie needs.
    private var headToHeadGroup: List<TeamId> = emptyList()

    private fun headToHead(id: TeamId): Double {
        val opponents = headToHeadGroup.filter { it != id }
        if (opponents.isEmpty()) return 0.0
        val games = results.filter { g ->
            g.involves(id) && opponents.any { g.involves(it) }
        }
        if (games.isEmpty()) return 0.0
        val wins = games.count { it.winner == id }
        val ties = games.count { it.isTie }
        return (wins + ties * 0.5) / games.size
    }

    private fun commonGames(id: TeamId): Double {
        val mine = opponentsOf(id)
        val theirs = headToHeadGroup.filter { it != id }.flatMap { opponentsOf(it) }.toSet()
        val common = mine.intersect(theirs)
        if (common.isEmpty()) return 0.0
        val games = results.filter { g -> g.involves(id) && g.opponentOf(id) in common }
        if (games.isEmpty()) return 0.0
        val wins = games.count { it.winner == id }
        val ties = games.count { it.isTie }
        return (wins + ties * 0.5) / games.size
    }

    private fun opponentsOf(id: TeamId): Set<TeamId> =
        results.filter { it.involves(id) }.mapNotNull { it.opponentOf(id) }.toSet()

    private fun strengthOfVictory(id: TeamId): Double {
        val beaten = results.filter { it.winner == id }.mapNotNull { it.opponentOf(id) }
        if (beaten.isEmpty()) return 0.0
        return beaten.map { record(it).winPct }.average()
    }

    private fun strengthOfSchedule(id: TeamId): Double {
        val opponents = results.filter { it.involves(id) }.mapNotNull { it.opponentOf(id) }
        if (opponents.isEmpty()) return 0.0
        return opponents.map { record(it).winPct }.average()
    }

    // ---------------------------------------------------------------

    fun division(conference: Conference, division: Division): List<TeamId> {
        val teams = league.teams
            .filter { it.conference == conference && it.division == division }
            .map { it.id }
        headToHeadGroup = teams
        return order(teams, sameDivision = true).first
    }

    fun divisionWinners(conference: Conference): List<TeamId> =
        Division.entries.map { division(conference, it).first() }

    /**
     * Seeds one conference: four division winners by record, then the three
     * best remaining teams.
     */
    fun seeds(conference: Conference): List<TeamId> {
        val winners = divisionWinners(conference)
        headToHeadGroup = winners
        val seededWinners = order(winners, sameDivision = false).first

        val rest = league.teams
            .filter { it.conference == conference && it.id !in winners }
            .map { it.id }
        headToHeadGroup = rest
        val wildCards = order(rest, sameDivision = false).first.take(WILD_CARDS)

        return seededWinners + wildCards
    }

    fun conferenceOrder(conference: Conference): List<TeamId> {
        val teams = league.teams.filter { it.conference == conference }.map { it.id }
        headToHeadGroup = teams
        return order(teams, sameDivision = false).first
    }

    /** Why this team is seeded where it is, in words. */
    fun explainSeeding(conference: Conference): List<TiebreakStep> {
        val teams = league.teams.filter { it.conference == conference }.map { it.id }
        headToHeadGroup = teams
        return order(teams, sameDivision = false).second
    }

    companion object {
        const val PLAYOFF_SEEDS = 7
        const val WILD_CARDS = 3
    }
}
