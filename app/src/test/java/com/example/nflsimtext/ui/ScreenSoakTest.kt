package com.example.nflsimtext.ui

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.offseason.OffseasonEngine
import com.nflsim.engine.season.ContractDisputes
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import com.nflsim.engine.season.TradeDesk
import com.nflsim.engine.season.TradeOffers
import com.nflsim.engine.season.Transactions
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What every screen asks of the engine, asked at every week and every
 * offseason stop of a few dynasties, each run from a different club. A
 * screen is only as safe as the worst state it can be opened in: the
 * Demands screen crashed on a club whose cap fitted only a cap-light deal,
 * after weeks of play nobody had tested it in. This plays those weeks.
 */
class ScreenSoakTest {

    private fun season(d: Dynasty) {
        val league = d.league
        val user = d.userTeamId
        // Demands: every ask, its advice and its deals.
        ContractDisputes.pending(league, user, d.playerStats).forEach { ask ->
            ContractDisputes.advise(league, user, ask)
            ContractDisputes.deals(league, ask)
        }
        // Trades: the week's calls, each judged as the screen does.
        if (TradeDesk.open(d)) {
            val book = TradeDesk.inSeason(d)
            TradeOffers.thisWeek(d).forEach { TradeDesk.evaluate(book, user, it.proposal) }
        }
        // Free agents: the street, the other squads, the price.
        Transactions.freeAgents(league)
        Transactions.poachable(league, user)
        Transactions.price(d.weeksLeft)
        // Every player card: the lens, the fit, whoever he is.
        league.players.forEach { lensOf(d, it).view(com.nflsim.engine.ratings.overall(it)) }
        // The last game: each play's top-of-screen box and the field's banner.
        d.lastGame?.let { g ->
            val home = league.team(g.home)
            val away = league.team(g.away)
            val plays = g.playByPlay
            for (n in 0..plays.size) {
                lastPlays(plays, n)
                plays.getOrNull(n)?.let { next ->
                    possessionChange(plays.take(n), next.offense, next.quarter, next.homeScore, next.awayScore)
                    playDetail(next, league, home, away)
                    eventOf(plays, n)
                    downAndDistance(next)
                }
            }
            defenderTally(plays, com.nflsim.engine.sim.Side.HOME)
            defenderRows(g.boxScore.players, { id -> league.playersById[PlayerId(id)]?.name ?: "" })
        }
    }

    private fun offseason(d: Dynasty): Dynasty {
        val contracts = OffseasonEngine.runToContracts(d)
        contracts.expiring.forEach { e -> contracts.recommend(e); contracts.deals(e) }
        val market = contracts.toFreeAgency(null)
        market.candidates.forEach { market.advice(it) }
        market.tagged.size
        val draft = market.decide(null)
        draft.boardFor(d.userTeamId)
        TradeOffers.atDraft(d, draft.tradeBook).forEach { TradeDesk.evaluate(draft.tradeBook, d.userTeamId, it.proposal) }
        val camp = draft.toCutdown()
        camp.suggested
        camp.roster.forEach { camp.whyCut(it); camp.value(it); camp.deadIfCut(it) }
        camp.pool.take(30).forEach { camp.value(it) }
        return camp.decide(null).first
    }

    @Test
    fun `every screen's questions answer, week by week and stop by stop, from several clubs`() {
        var weeks = 0
        var offseasons = 0
        listOf(33L to 0, 41L to 7, 52L to 19, 63L to 28).forEach { (seed, club) ->
            val league = LeagueGenerator.generate(2026, seed)
            var d = DynastyEngine.start(league, 2026, seed, league.teams[club].id)
            while (d.year < 2028) {
                if (d.phase == DynastyPhase.OFFSEASON) {
                    d = offseason(d)
                    offseasons++
                } else {
                    season(d)
                    weeks++
                    d = DynastyEngine.advance(d)
                }
            }
        }
        assertTrue("$weeks weeks, $offseasons offseasons", weeks > 100 && offseasons >= 4)
    }
}
