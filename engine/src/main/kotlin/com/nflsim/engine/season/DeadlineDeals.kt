package com.nflsim.engine.season

import com.nflsim.engine.model.League
import com.nflsim.engine.model.NewsEvent
import com.nflsim.engine.model.NewsKind
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.model.Transaction
import com.nflsim.engine.model.TransactionKind
import com.nflsim.engine.offseason.ContenderTrades
import com.nflsim.engine.rng.Rng

/**
 * The league's clubs trading at the deadline (SPEC 8.4). As the window
 * closes - before week [TradeDesk.DEADLINE_WEEK]'s games - contenders buy
 * from clubs going the other way, by the offseason's own rules
 * (ContenderTrades): each values everything on its own timeline and a deal
 * happens only when both come out ahead.
 *
 * In the season only the men on the active roster move, and a deal must
 * leave both clubs within the 53: a star comes for a player back, with or
 * without a pick. The user's club is never dealt for; its trades are its own.
 */
object DeadlineDeals {

    data class Result(val league: League, val news: List<NewsEvent>)

    fun run(dynasty: Dynasty, words: Rng): Result {
        val league = dynasty.league
        val book = TradeDesk.inSeason(dynasty)
        val standings = dynasty.standings()
        val user = dynasty.userTeamId
        val week = dynasty.week
        // Only active men trade, and never the user's.
        val inPlay = league.players.filter { it.teamId != null && it.teamId != user && it.status == PlayerStatus.ACTIVE }
        val result = ContenderTrades.run(
            league, inPlay, book.deadMoney,
            scheme = { team, pos -> book.scheme(team ?: user, pos) },
            winPct = { id -> standings.record(id).winPct.toFloat() },
            year = league.year,
            picks = league.picks,
            order = book.order,
            draftYear = book.draftYear,
            rosterLimit = Transactions.ROSTER_LIMIT,
            except = user,
        )
        if (result.moves.isEmpty()) return Result(league, emptyList())

        val traded = result.players.associateBy { it.id }
        val players = league.players.map { traded[it.id] ?: it }
        val wire = result.moves.map { m ->
            Transaction.of(league.year, week, TransactionKind.TRADED, TeamId(m.to),
                players.first { it.id.v == m.player }, other = TeamId(m.from))
        }
        val next = TradeDesk.settle(league, players, result.moves.map { PlayerId(it.player) }.toSet(),
            result.picks, result.deadMoney, wire)

        // A line for each star, naming what went the other way.
        val news = result.moves.filter { it.reason == ContenderTrades.STAR_REASON }.map { star ->
            val buyer = league.team(TeamId(star.to))
            val seller = league.team(TeamId(star.from))
            val back = result.moves.filter { it.reason != ContenderTrades.STAR_REASON && it.from == star.to && it.to == star.from }
                .map { "${it.position} ${it.name}" }
            val picks = result.pickTrades.count { it.from == star.to && it.to == star.from }
            val price = (back + if (picks > 0) listOf(if (picks == 1) "a pick" else "$picks picks") else emptyList())
                .joinToString(" and ").ifBlank { "a package" }
            NewsEvent(week, NewsKind.TRADE,
                Headlines.write("trade.deadline", words, "buyer" to buyer.abbrev, "seller" to seller.abbrev,
                    "pos" to star.position, "player" to star.name, "price" to price),
                star.player, buyer.id.v)
        }
        return Result(next, news)
    }
}
