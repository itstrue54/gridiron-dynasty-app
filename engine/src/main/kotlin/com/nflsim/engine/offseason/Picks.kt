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

    /**
     * Compensatory picks for next year's draft (NFL rules). A club that loses
     * more or better free agents than it signs gets picks at the end of rounds
     * 3-7, the round set by what the player it lost signed for. Only players
     * whose contracts ran out count - a released player is not a compensatory
     * free agent. Each one a club signs cancels the most valuable it lost that
     * was worth no more; at most four picks a club and 32 a league, the most
     * valuable kept.
     */
    fun compensatory(
        signings: List<Signing>,
        previousTeam: Map<Int, TeamId>,
        cap: Int,
        draftYear: Int,
    ): List<PickAsset> {
        fun round(value: Int): Int? = COMP_ROUNDS.firstOrNull { (share, _) -> value >= cap * share }?.second

        val moved = signings.filter { s ->
            val from = previousTeam[s.player] ?: return@filter false
            from.v != s.team && round(s.value) != null
        }
        val lost = moved.groupBy { previousTeam.getValue(it.player).v }
        val gained = moved.groupBy { it.team }

        val awards = lost.flatMap { (club, losses) ->
            val left = losses.sortedByDescending { it.value }.toMutableList()
            gained[club].orEmpty().sortedByDescending { it.value }.forEach { gain ->
                val cancel = left.firstOrNull { it.value <= gain.value } ?: left.lastOrNull()
                if (cancel != null) left.remove(cancel)
            }
            left.take(COMP_CLUB_MAX).map { club to it.value }
        }

        return awards.sortedByDescending { it.second }
            .take(COMP_LEAGUE_MAX)
            .groupBy { round(it.second)!! }
            .flatMap { (round, inRound) ->
                inRound.mapIndexed { i, (club, _) ->
                    PickAsset(draftYear, round, club, club, compensatory = true, compOrder = i)
                }
            }
    }

    /** Compensatory picks the league hands out a year, and the most one club can get. */
    private const val COMP_LEAGUE_MAX = 32
    private const val COMP_CLUB_MAX = 4

    /** What a lost free agent signed for, as a share of the cap, and the round it earns back. */
    private val COMP_ROUNDS = listOf(0.05f to 3, 0.035f to 4, 0.025f to 5, 0.015f to 6, 0.008f to 7)
}
