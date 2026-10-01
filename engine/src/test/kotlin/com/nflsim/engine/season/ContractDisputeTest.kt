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
        // The spring settles everything; what the new season opens with is
        // camp's - the holdouts (SPEC 10.4), each of them in the week's news.
        val held = d.news.mapNotNull { it.player }.toSet()
        assertTrue(d.league.players.all { it.demand == DemandState.NONE || it.id.v in held }, "the spring settles everything")
    }

    @Test
    fun `an offer above what he will take is signed, below it is turned down`() {
        val (l, id) = underpaidStar()
        val man = l.player(id)
        val floor = ContractDisputes.reservation(man, l.tuning)
        assertTrue(floor in 0.74f..1f, "his floor was $floor")

        // Under his floor: no deal, and his agent says what it is.
        val low = ContractDisputes.offer(l, club, id, floor - 0.05f) as Transactions.Outcome.Done
        assertEquals(DemandState.PENDING, low.league.player(id).demand, "the demand stays on the desk")
        assertTrue(low.note.contains("will not go below"), low.note)
        // And his agent says it himself, figure and all.
        val agent = com.nflsim.engine.narrative.Banter.agentName(man.id.v)
        assertTrue(low.note.lines().last().endsWith("$agent, ${man.lastName}'s agent"), low.note)
        assertTrue(low.league.player(id).morale < man.morale, "a lowball costs him a little")

        // At it: he signs, for less than the market.
        val market = ContractDisputes.pending(l, club).single().market
        val done = ContractDisputes.offer(l, club, id, floor) as Transactions.Outcome.Done
        val signed = done.league.player(id)
        assertEquals(DemandState.SETTLED, signed.demand)
        assertTrue(signed.contract!!.averagePerYear < market, "he took a discount: ${signed.contract}")
        assertTrue(done.league.transactions.any { it.player == id.v })
    }

    @Test
    fun `an ego holds out for more than a loyal man does`() {
        val man = league.roster(club).first()
        val tuning = league.tuning
        val proud = man.copy(traits = man.traits.copy(ego = 95, loyalty = 20))
        val settled = man.copy(traits = man.traits.copy(ego = 20, loyalty = 95))
        assertTrue(
            ContractDisputes.reservation(proud, tuning) > ContractDisputes.reservation(settled, tuning),
            "ego ${ContractDisputes.reservation(proud, tuning)} vs loyal " +
                "${ContractDisputes.reservation(settled, tuning)}",
        )
    }

    @Test
    fun `a club cannot offer what it has not got`() {
        val (l, id) = underpaidStar()
        val broke = l.copy(players = l.players.map {
            if (it.teamId == club && it.id != id) it.copy(
                contract = com.nflsim.engine.model.Contract(
                    years = 3, baseSalary = listOf(6_000, 6_000, 6_000), signedYear = 2026)) else it
        })
        assertTrue(ContractDisputes.offer(broke, club, id, 1f) is Transactions.Outcome.Refused)
    }

    @Test
    fun `the advice pays him when there is room and says how to make it when there is not`() {
        val (l, id) = underpaidStar()
        val ask = ContractDisputes.pending(l, club).single()
        val advice = ContractDisputes.advise(l, club, ask)
        assertTrue(advice.deal != null && advice.headline.startsWith("Pay him"), advice.headline)
        // And the deal it recommends is one he signs, written as advised.
        val done = ContractDisputes.extend(l, club, id, years = advice.deal!!.years,
            structure = advice.deal!!.structure) as Transactions.Outcome.Done
        assertEquals(advice.deal!!.contract, done.league.player(id).contract)

        val broke = l.copy(players = l.players.map {
            if (it.teamId == club && it.id != id) it.copy(
                contract = com.nflsim.engine.model.Contract(
                    years = 3, baseSalary = listOf(6_000, 6_000, 6_000), signedYear = 2026)) else it
        })
        val none = ContractDisputes.advise(broke, club, ContractDisputes.pending(broke, club).single())
        assertEquals(null, none.deal)
        assertTrue(none.why.contains("Restructure"), none.why)
    }

    @Test
    fun `the front office answers the way the league's clubs do`() {
        val (l, id) = underpaidStar()
        val paid = ContractDisputes.frontOfficeAnswer(l, club, id) as Transactions.Outcome.Done
        assertEquals(DemandState.SETTLED, paid.league.player(id).demand, "with room, it pays him")

        val broke = l.copy(players = l.players.map {
            if (it.teamId == club && it.id != id) it.copy(
                contract = com.nflsim.engine.model.Contract(
                    years = 3, baseSalary = listOf(6_000, 6_000, 6_000), signedYear = 2026)) else it
        })
        val no = ContractDisputes.frontOfficeAnswer(broke, club, id) as Transactions.Outcome.Done
        assertEquals(DemandState.REFUSED, no.league.player(id).demand, "without room, it tells him no")
    }
}
