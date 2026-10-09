package com.nflsim.engine.offseason

import com.nflsim.engine.model.League
import com.nflsim.engine.model.PickAsset
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall

/**
 * What a club does with what camp showed (SPEC 7 phase 11): lets go the
 * veterans camp has made too dear for what they now are - or trades them,
 * when another club would start them - before it cuts to 53 by the
 * roster's count.
 *
 * Three reasons to let a man go, judged by the club's GM:
 * - he went backwards in camp and is now paid more than he is worth;
 * - camp passed him by: he is no longer a starter, and his cap hit is over
 *   what he is worth by the GM's patience;
 * - the club is over next year's cap as it stands, and he is the dearest
 *   for what he gives, starter or not.
 * Only a man paid well over the minimum, and a saving worth having, moves anyone. A man hurt, or signed this year,
 * is kept: a club does not cut the man it has just paid, and a hurt man is
 * not cut in camp. A man who would start for a club with the room goes there
 * for its latest pick next year rather than to the street.
 */
object CampDecisions {

    enum class Why { REGRESSED, PASSED_BY, NEXT_YEARS_CAP }

    /** One move a club makes, or would: a cut, or a trade when [to] is set. */
    data class Move(
        val player: Player,
        val team: TeamId,
        val why: Why,
        /** What letting him go saves this year and next, and what it leaves in dead money this year. */
        val savingNow: Int,
        val savingNext: Int,
        val deadNow: Int,
        /** How far camp moved him, in his club's scheme. */
        val campChange: Int,
        /** His cap hit this year. */
        val capHit: Int,
        val to: TeamId? = null,
        val pick: PickAsset? = null,
    )

    /** Everything a club needs to judge its camp: the men, its dead money, and the picks the league holds. */
    class Context(
        val league: League,
        val players: List<Player>,
        /** Each man as he reported, before camp developed him. */
        val reported: Map<Int, Player>,
        val year: Int,
        val deadMoney: Map<Int, Int>,
        val picks: List<PickAsset>,
        val scheme: (TeamId?, Position) -> Scheme,
        val price: (Player, Scheme) -> Int,
    ) {
        val byTeam: Map<TeamId, List<Player>> = players.filter { it.teamId != null }.groupBy { it.teamId!! }
        fun ovr(p: Player, team: TeamId) = overall(p, scheme(team, p.position))
    }

    /** The moves [team] makes on what camp showed, best first; [receivers] are the clubs that may trade for its men. */
    fun plan(ctx: Context, team: Team, receivers: List<Team>): List<Move> {
        val t = ctx.league.tuning.ai
        val roster = ctx.byTeam[team.id].orEmpty().toMutableList()
        val nextCap = CapManagement.capFor(ctx.year + 1)
        val nextCommitted = roster.sumOf { it.contract?.capHit(ctx.year + 1) ?: 0 }
        val squeezed = nextCommitted > nextCap
        val moves = mutableListOf<Move>()
        val traded = mutableSetOf<TeamId>()
        while (moves.size < t.campMaxMoves) {
            val starters = roster.groupBy { it.position }.mapValues { (pos, men) ->
                men.sortedByDescending { ctx.ovr(it, team.id) }.take(TeamNeeds.requiredStarters(pos)).map { it.id.v }.toSet()
            }
            val candidates = roster.mapNotNull { p -> judge(ctx, team, p, roster, starters[p.position].orEmpty(), squeezed) }
            val move = candidates.maxByOrNull { it.savingNow + it.savingNext * t.campNextYearWeight } ?: break
            roster.remove(move.player)
            val buyer = receivers.filter { it.id != team.id && it.id !in traded }
                .mapNotNull { club -> tradeFor(ctx, move.player, club)?.let { club to it } }
                .maxByOrNull { it.second.first }
            moves += if (buyer == null) move else {
                traded += buyer.first.id
                move.copy(to = buyer.first.id, pick = buyer.second.second)
            }
        }
        return moves
    }

    /** Why [team] would let [p] go now, if it would. */
    private fun judge(ctx: Context, team: Team, p: Player, roster: List<Player>, starters: Set<Int>, squeezed: Boolean): Move? {
        val t = ctx.league.tuning.ai
        val contract = p.contract ?: return null
        if (p.injuryWeeks > 0 || p.status != PlayerStatus.ACTIVE || contract.signedYear >= ctx.year) return null
        if (roster.count { it.position == p.position } <= TeamNeeds.requiredStarters(p.position)) return null
        val hit = p.capHit(ctx.year)
        if (hit < com.nflsim.engine.model.Contract.MIN_BASE_SALARY * t.campMinHitMultiple) return null
        val dead = contract.deadCap(ctx.year)
        val savingNow = hit - dead.thisYear
        val savingNext = (contract.capHit(ctx.year + 1)) - dead.nextYear
        val worth = ctx.price(p, ctx.scheme(team.id, p.position))
        val change = ctx.reported[p.id.v]?.let { ctx.ovr(p, team.id) - ctx.ovr(it, team.id) } ?: 0
        val starter = p.id.v in starters
        val overpaid = hit > worth * team.gm.patience * t.campPatience
        val why = when {
            !starter && hit > worth && change <= -t.campRegressDrop -> Why.REGRESSED
            !starter && overpaid -> Why.PASSED_BY
            squeezed && hit > worth * team.gm.patience && savingNext >= t.capMeaningfulSaving &&
                roster.count { it.position == p.position } > TeamNeeds.requiredStarters(p.position) + 1 -> Why.NEXT_YEARS_CAP
            else -> return null
        }
        // A camp move saves this year too: nobody cuts in August to pay more now.
        if (savingNow <= 0 || savingNow + savingNext * t.campNextYearWeight < t.campMinSaving) return null
        return Move(p, team.id, why, savingNow, savingNext, dead.thisYear, change, hit)
    }

    /**
     * Whether [club] would trade for [p] rather than see him cut: he beats
     * its weakest starter at his position by the tuning's edge, it has the
     * room for his cap hit and a place on its roster, and it holds a late
     * pick next year to send. How much he would help, and the pick.
     */
    private fun tradeFor(ctx: Context, p: Player, club: Team): Pair<Int, PickAsset>? {
        val t = ctx.league.tuning.ai
        val roster = ctx.byTeam[club.id].orEmpty()
        if (roster.size >= OffseasonEngine.OFFSEASON_ROSTER_LIMIT) return null
        val need = TeamNeeds.requiredStarters(p.position)
        val weakest = roster.filter { it.position == p.position }.map { ctx.ovr(it, club.id) }
            .sortedDescending().getOrNull(need - 1) ?: 0
        val gain = ctx.ovr(p, club.id) - weakest
        if (gain < t.campTradeEdge) return null
        val space = CapManagement.spaceFor(roster, ctx.year, ctx.deadMoney[club.id.v] ?: 0, club.finances.carryover)
        if (space < p.capHit(ctx.year)) return null
        val pick = ctx.picks.filter { it.owner == club.id.v && it.year == ctx.year + 1 && it.round >= LATE_ROUND }
            .maxWithOrNull(compareBy({ it.round }, { it.compensatory })) ?: return null
        return gain to pick
    }

    data class Result(
        val players: List<Player>,
        val deadMoney: Map<Int, Int>,
        val picks: List<PickAsset>,
        val releases: List<Release>,
        val trades: List<TradeMove>,
        val pickTrades: List<PickTrade>,
    )

    /** Every club but [skip] makes its camp moves, the clubs in league order; [skip] can't be traded to either. */
    fun run(ctx: Context, skip: TeamId?): Result {
        var current = Result(ctx.players, ctx.deadMoney, ctx.picks, emptyList(), emptyList(), emptyList())
        val receivers = ctx.league.teams.filter { it.id != skip }
        ctx.league.teams.forEach { team ->
            if (team.id == skip) return@forEach
            val now = Context(ctx.league, current.players, ctx.reported, ctx.year, current.deadMoney, current.picks, ctx.scheme, ctx.price)
            plan(now, team, receivers).forEach { move -> current = apply(now, current, move) }
        }
        return current
    }

    /** [move] made: the man cut at his dead money, or sent to his new club for its pick. */
    fun apply(ctx: Context, from: Result, move: Move): Result {
        val p = move.player
        val dead = from.deadMoney.toMutableMap()
        dead[move.team.v] = (dead[move.team.v] ?: 0) + move.deadNow
        val ovr = ctx.ovr(p, move.team)
        if (move.to == null) {
            val players = from.players.map { if (it.id == p.id) it.copy(teamId = null, contract = null, status = PlayerStatus.FREE_AGENT) else it }
            val release = Release(p.id.v, p.name, p.position.label, move.team.v, ovr, move.savingNow, move.deadNow)
            return from.copy(players = players, deadMoney = dead, releases = from.releases + release)
        }
        // Traded: his contract goes as it stands, and his old club eats what is left of his bonus (ADR-010).
        val bonus = p.contract?.deadCap(ctx.year)?.thisYear ?: 0
        dead[move.team.v] = (dead[move.team.v] ?: 0) - move.deadNow + bonus
        val players = from.players.map { if (it.id == p.id) it.copy(teamId = move.to, yearsInSystem = 0, yearsWithClub = 0) else it }
        val pick = move.pick!!
        val picks = from.picks.map { if (it == pick) it.copy(owner = move.team.v) else it }
        return from.copy(
            players = players, deadMoney = dead, picks = picks,
            trades = from.trades + TradeMove(p.id.v, p.name, p.position.label, move.team.v, move.to.v,
                ctx.ovr(p, move.to), bonus, CAMP_REASON),
            pickTrades = from.pickTrades + PickTrade(pick.year, pick.round, pick.original, move.to.v, move.team.v, CAMP_REASON),
        )
    }

    /** The latest round a camp trade is paid in. */
    private const val LATE_ROUND = 6

    /** What the wire says of a camp trade. */
    const val CAMP_REASON = "traded at camp rather than cut"
}
