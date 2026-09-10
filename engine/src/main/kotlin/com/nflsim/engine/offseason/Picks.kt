package com.nflsim.engine.offseason

import com.nflsim.engine.model.PickAsset
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.season.GameOutcome
import com.nflsim.engine.season.PlayoffGame
import com.nflsim.engine.season.PlayoffRound

/**
 * Draft picks as assets a club owns (SPEC 8.4), and the order they are used in.
 *
 * NFL rules: a club can trade its picks for the coming draft and the two after
 * it, so every club always holds three drafts' worth. The order follows the
 * league's draft rules - clubs that missed the playoffs first, worst record
 * first, then playoff clubs by the round they went out in, the champion last.
 */
object Picks {

    /** Drafts a club holds picks for: the coming one and the two after. */
    const val WINDOW = 3

    /** Every club's own picks for these drafts. */
    fun own(teams: List<TeamId>, years: Iterable<Int>): List<PickAsset> =
        years.flatMap { year ->
            (1..DraftRunner.ROUNDS).flatMap { round -> teams.map { PickAsset(year, round, it.v, it.v) } }
        }

    /**
     * After a draft that year's picks are spent and a new year joins the
     * window. Any missing year is filled, so a league without picks gets all
     * three.
     */
    fun rollOver(picks: List<PickAsset>, teams: List<TeamId>, draftYear: Int): List<PickAsset> {
        val held = picks.filter { it.year > draftYear }
        val missing = ((draftYear + 1)..(draftYear + WINDOW)).filter { y -> held.none { it.year == y } }
        return held + own(teams, missing)
    }

    /** Who uses each regular pick in a draft, by round and original club. */
    fun owners(picks: List<PickAsset>, year: Int): Map<Pair<Int, Int>, Int> =
        picks.filter { it.year == year && !it.compensatory }
            .associate { (it.round to it.original) to it.owner }

    /**
     * The NFL's draft order. Clubs that missed the playoffs pick first, worst
     * record first; playoff clubs follow by the round they went out in, the
     * champion last. Ties go to the club whose opponents won less - strength
     * of schedule, the league's first draft tiebreak.
     */
    fun draftOrder(
        teams: List<TeamId>,
        winPct: (TeamId) -> Double,
        results: List<GameOutcome>,
        playoffs: List<PlayoffGame>,
    ): List<TeamId> {
        val exit = mutableMapOf<TeamId, Int>()
        playoffs.forEach { game -> exit[game.loser] = game.round.ordinal + 1 }
        playoffs.firstOrNull { it.round == PlayoffRound.FINAL }
            ?.let { exit[it.winner] = PlayoffRound.entries.size + 1 }

        fun schedule(id: TeamId): Double {
            val opponents = results.filter { it.involves(id) }.mapNotNull { it.opponentOf(id) }
            return if (opponents.isEmpty()) 0.0 else opponents.map { winPct(it) }.average()
        }

        return teams.sortedWith(compareBy({ exit[it] ?: 0 }, { winPct(it) }, { schedule(it) }, { it.v }))
    }
}
