package com.nflsim.engine.season

import com.nflsim.engine.model.Conference
import com.nflsim.engine.model.Division
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.rng.shuffled
import kotlinx.serialization.Serializable

@Serializable
data class Matchup(val week: Int, val home: TeamId, val away: TeamId) {
    fun involves(id: TeamId): Boolean = home == id || away == id
    fun opponentOf(id: TeamId): TeamId? = when (id) {
        home -> away
        away -> home
        else -> null
    }
}

@Serializable
data class Schedule(val year: Int, val games: List<Matchup>) {
    fun week(n: Int): List<Matchup> = games.filter { it.week == n }
    fun forTeam(id: TeamId): List<Matchup> = games.filter { it.involves(id) }
    fun byeWeek(id: TeamId): Int? =
        (1..WEEKS).firstOrNull { w -> week(w).none { it.involves(id) } }

    companion object {
        const val WEEKS = 18
        const val GAMES_PER_TEAM = 17
    }
}

/**
 * Builds a season that looks like the real thing.
 *
 * Structure per team (docs/SPEC.md 6):
 *   6  division games, home and away against all three rivals
 *   4  against one other division in the conference, on a three year rotation
 *   4  against one division in the other conference, on a four year rotation
 *   2  against same-place finishers in the two remaining conference divisions
 *   1  a seventeenth game against the other conference, by prior finish
 *  ---
 *   17
 *
 * The rotations are driven by the year, so a dynasty naturally cycles through
 * opponents the way a real one does rather than facing the same teams forever.
 */
object ScheduleGenerator {

    fun generate(
        league: League,
        year: Int,
        rng: Rng,
        /** Prior-year finishing order within each division, best first. Null in year one. */
        priorFinish: Map<Pair<Conference, Division>, List<TeamId>>? = null,
    ): Schedule {
        val byDivision = league.teams.groupBy { it.conference to it.division }
        val finish = priorFinish ?: byDivision.mapValues { (_, teams) -> teams.map { it.id } }

        val pairs = mutableListOf<Pair<TeamId, TeamId>>()   // ordered: home to away
        fun add(home: TeamId, away: TeamId) { pairs += home to away }

        val divisions = Division.entries

        // The intra-conference rotation must be MUTUAL. Picking a partner with
        // an offset formula lets division 0 choose 1 while 1 chooses 2, which
        // leaves division 1 playing eight extra games. These three pairings are
        // the complete set for four divisions, cycling every three years.
        val pairings = listOf(
            listOf(0 to 1, 2 to 3),
            listOf(0 to 2, 1 to 3),
            listOf(0 to 3, 1 to 2),
        )[Math.floorMod(year, 3)]

        // ---- 6 division games -------------------------------------------
        byDivision.forEach { (_, teams) ->
            for (i in teams.indices) for (j in i + 1 until teams.size) {
                add(teams[i].id, teams[j].id)
                add(teams[j].id, teams[i].id)
            }
        }

        Conference.entries.forEach { conf ->
            // ---- 4 against the paired division in the same conference -----
            pairings.forEach { (a, b) ->
                crossDivision(
                    byDivision[conf to divisions[a]]!!,
                    byDivision[conf to divisions[b]]!!,
                    ::add,
                )
            }

            // ---- 2 same-place finishers ----------------------------------
            // The two divisions a team does NOT face as a block. With pairings
            // {0,1} and {2,3}, those are exactly the cross-group matchups.
            val (groupA, groupB) = pairings
            listOf(groupA.first, groupA.second).forEach { d ->
                listOf(groupB.first, groupB.second).forEach { e ->
                    val mine = finish[conf to divisions[d]] ?: return@forEach
                    val theirs = finish[conf to divisions[e]] ?: return@forEach
                    mine.forEachIndexed { place, teamId ->
                        val opponent = theirs.getOrNull(place) ?: return@forEachIndexed
                        if ((place + d + e) % 2 == 0) add(teamId, opponent)
                        else add(opponent, teamId)
                    }
                }
            }
        }

        // ---- 4 against a division in the other conference ----------------
        divisions.forEachIndexed { i, div ->
            val target = divisions[Math.floorMod(i + year, divisions.size)]
            crossDivision(
                byDivision[Conference.AMERICAN to div]!!,
                byDivision[Conference.CONTINENTAL to target]!!,
                ::add,
            )
        }

        // ---- the seventeenth game ----------------------------------------
        // A different other-conference division from the block above, matched
        // by prior finish. Offsetting by one keeps the mapping a bijection, so
        // every team gets exactly one. Hosting alternates by year.
        divisions.forEachIndexed { i, div ->
            val target = divisions[Math.floorMod(i + year + 1, divisions.size)]
            val american = finish[Conference.AMERICAN to div] ?: return@forEachIndexed
            val continental = finish[Conference.CONTINENTAL to target] ?: return@forEachIndexed
            american.forEachIndexed { place, teamId ->
                val opponent = continental.getOrNull(place) ?: return@forEachIndexed
                if (year % 2 == 0) add(teamId, opponent) else add(opponent, teamId)
            }
        }

        // Every team must come out at exactly seventeen. Catch it here rather
        // than letting it surface as a 9-7-2 record five screens later.
        val counts = pairs.flatMap { listOf(it.first, it.second) }.groupingBy { it }.eachCount()
        val wrong = counts.filterValues { it != Schedule.GAMES_PER_TEAM }
        require(wrong.isEmpty()) {
            "schedule built the wrong number of games: " +
                wrong.entries.joinToString { "${league.team(it.key).abbrev}=${it.value}" }
        }

        val matchups = assignWeeks(league.teams, pairs, rng)
        return Schedule(year, matchups)
    }

    /** Eight games between two divisions: everyone plays everyone once. */
    private fun crossDivision(a: List<Team>, b: List<Team>, add: (TeamId, TeamId) -> Unit) {
        a.forEachIndexed { i, teamA ->
            b.forEachIndexed { j, teamB ->
                if ((i + j) % 2 == 0) add(teamA.id, teamB.id) else add(teamB.id, teamA.id)
            }
        }
    }

    /**
     * Drops the matchups into weeks.
     *
     * Byes go first: four teams a week from week five, which keeps the number
     * of available teams even so every week can pair up completely.
     *
     * Then each week needs a PERFECT MATCHING on the available teams using only
     * games not yet played. Filling greedily does not work - it strands games
     * that no later week can take, because two teams who still owe each other a
     * game end up with their remaining weeks already booked. This backtracks,
     * always branching on the team with the fewest remaining options, which is
     * what makes the search finish quickly instead of exploring the whole tree.
     */
    private fun assignWeeks(
        teams: List<Team>,
        pairs: List<Pair<TeamId, TeamId>>,
        rng: Rng,
    ): List<Matchup> {
        val ids = teams.map { it.id }

        repeat(MAX_ATTEMPTS) {
            val order = ids.shuffled(rng)
            val byeWeek = mutableMapOf<TeamId, Int>()
            order.forEachIndexed { i, id ->
                byeWeek[id] = FIRST_BYE_WEEK + (i / TEAMS_PER_BYE_WEEK)
            }

            val remaining = pairs.toMutableList()
            val scheduled = mutableListOf<Matchup>()
            var ok = true

            for (week in 1..Schedule.WEEKS) {
                val available = ids.filter { byeWeek[it] != week }
                val matching = perfectMatching(available, remaining, rng)
                if (matching == null) { ok = false; break }
                matching.forEach { game ->
                    scheduled += Matchup(week, game.first, game.second)
                    remaining.remove(game)
                }
            }

            if (ok && remaining.isEmpty()) {
                return scheduled.sortedWith(compareBy({ it.week }, { it.home.v }))
            }
        }
        // No silent fallback. A generator that quietly produces a 20 game
        // season is worse than one that stops - the first version of this had
        // one, and it turned a broken rotation into plausible-looking
        // standings that took a full season print-out to notice.
        error("could not build a valid schedule for the supplied matchups")
    }

    /** Pairs everyone available this week, or returns null if it cannot. */
    private fun perfectMatching(
        available: List<TeamId>,
        remaining: List<Pair<TeamId, TeamId>>,
        rng: Rng,
    ): List<Pair<TeamId, TeamId>>? {
        val pool = available.toSet()
        val adjacency = mutableMapOf<TeamId, MutableList<Pair<TeamId, TeamId>>>()
        remaining.forEach { game ->
            if (game.first in pool && game.second in pool) {
                adjacency.getOrPut(game.first) { mutableListOf() } += game
                adjacency.getOrPut(game.second) { mutableListOf() } += game
            }
        }

        val used = mutableSetOf<TeamId>()
        val chosen = mutableListOf<Pair<TeamId, TeamId>>()

        fun free(game: Pair<TeamId, TeamId>) = game.first !in used && game.second !in used

        fun search(): Boolean {
            val unpaired = available.filter { it !in used }
            if (unpaired.isEmpty()) return true

            // Branch on the most constrained team. Without this the search
            // wanders; with it, it finishes almost immediately.
            val pivot = unpaired.minByOrNull { team ->
                adjacency[team]?.count(::free) ?: 0
            } ?: return false

            val options = adjacency[pivot]?.filter(::free)?.shuffled(rng) ?: return false
            if (options.isEmpty()) return false

            for (game in options) {
                used += game.first
                used += game.second
                chosen += game
                if (search()) return true
                chosen.removeAt(chosen.size - 1)
                used -= game.first
                used -= game.second
            }
            return false
        }

        return if (search()) chosen.toList() else null
    }

    private const val MAX_ATTEMPTS = 60
    private const val FIRST_BYE_WEEK = 5
    private const val TEAMS_PER_BYE_WEEK = 4
}
