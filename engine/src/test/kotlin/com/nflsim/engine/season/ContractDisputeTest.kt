package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.DemandState
import com.nflsim.engine.model.NewsKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 10.1: a man who has noticed what he is paid, and what his club says. */
class ContractDisputeTest {

    private val league = LeagueGenerator.generate(2026, 61L)
    private val club = league.teams.first().id

    /** Puts a good veteran on a minimum deal, which is what a dispute is about. */
    private fun underpaidStar(): Pair<com.nflsim.engine.model.League, com.nflsim.engine.model.PlayerId> {
        val man = league.roster(club).filter { it.accruedSeasons >= 3 }
            .maxByOrNull { com.nflsim.engine.ratings.overall(it) }!!
        val cheap = man.copy(
            contract = com.nflsim.engine.model.Contract(
                years = 2, baseSalary = listOf(900, 900), signedYear = 2026),
            demand = DemandState.PENDING,
        )
        return league.copy(players = league.players.map { if (it.id == man.id) cheap else it }) to man.id
    }

    @Test
    fun `paying him writes a market deal and settles it`() {
        val (l, id) = underpaidStar()
        val ask = ContractDisputes.pending(l, club).single()
        assertTrue(ask.market > ask.paid, "he should be worth more than he is on")
        val done = ContractDisputes.extend(l, club, id) as Transactions.Outcome.Done
        val after = done.league.player(id)
        assertEquals(DemandState.SETTLED, after.demand)
        assertEquals(ask.years, after.contract!!.years)
        assertTrue(after.morale > l.player(id).morale, "settling should lift him")
        assertTrue(done.league.transactions.any { it.player == id.v }, "the wire should carry the deal")
        assertTrue(ContractDisputes.pending(done.league, club).isEmpty())
    }

    @Test
    fun `telling him no costs him morale and is remembered`() {
        val (l, id) = underpaidStar()
        val done = ContractDisputes.refuse(l, club, id) as Transactions.Outcome.Done
        val after = done.league.player(id)
        assertEquals(DemandState.REFUSED, after.demand)
        assertTrue(after.morale < l.player(id).morale, "a refusal should cost him")
    }

    @Test
    fun `a club with no room cannot pay him`() {
        val (l, id) = underpaidStar()
        // Spend the cap: everyone else on the roster gets a huge deal.
        val broke = l.copy(players = l.players.map {
            if (it.teamId == club && it.id != id) it.copy(
                contract = com.nflsim.engine.model.Contract(
                    years = 3, baseSalary = listOf(6_000, 6_000, 6_000), signedYear = 2026)) else it
        })
        val outcome = ContractDisputes.extend(broke, club, id)
        assertTrue(outcome is Transactions.Outcome.Refused, "$outcome")
    }

    @Test
    fun `a season produces demands, and the league's own clubs answer them`() {
        var d = DynastyEngine.start(league, 2026, 61L, club)
        while (d.phase == DynastyPhase.PRESEASON || d.phase == DynastyPhase.REGULAR_SEASON) {
            d = DynastyEngine.advance(d)
        }
        val states = d.league.players.filter { it.teamId != null }.groupingBy { it.demand }.eachCount()
        val asked = (states[DemandState.PENDING] ?: 0) + (states[DemandState.SETTLED] ?: 0) +
            (states[DemandState.REFUSED] ?: 0)
        assertTrue(asked in 3..40, "a season should bring a handful of demands, got $asked")
        assertTrue((states[DemandState.PENDING] ?: 0) <= ContractDisputes.pending(d.league, club).size,
            "only the user's club should be left waiting")
        assertTrue(d.news.any { it.kind == NewsKind.DISPUTE } || asked > 0)

        // Nobody carries it into the next season.
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d = DynastyEngine.advance(d)
        assertTrue(d.league.players.all { it.demand == DemandState.NONE }, "the spring settles everything")
    }
}
