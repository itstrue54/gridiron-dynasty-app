package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Contract
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/** SPEC 8.3: cheap this year, dearer every year after. */
class RestructureTest {

    private val league = LeagueGenerator.generate(2026, 15L)
    private val club = league.teams.first().id

    private fun withDeal(contract: Contract): Pair<com.nflsim.engine.model.League, com.nflsim.engine.model.PlayerId> {
        val man = league.roster(club).first()
        return league.copy(players = league.players.map {
            if (it.id == man.id) it.copy(contract = contract) else it
        }) to man.id
    }

    @Test
    fun `it frees room now and puts it on every year of the deal`() {
        val deal = Contract(years = 4, baseSalary = listOf(12_000, 14_000, 16_000, 18_000),
            signingBonus = 8_000, signedYear = 2026)
        val (l, id) = withDeal(deal)
        val before = l.player(id).capHit(2026)
        val preview = Transactions.restructurePreview(l.player(id), 2026)!!
        assertTrue(preview.frees > 0)
        assertTrue(preview.addsPerYear > 0)
        // This year's dead money does not move; next year's does, because the
        // bonus is still on the books and the base that paid for it is gone.
        assertTrue(preview.deadAfter > preview.deadBefore,
            "dead money next year: ${preview.deadBefore} to ${preview.deadAfter}")

        val done = Transactions.restructure(l, club, id) as Transactions.Outcome.Done
        val after = done.league.player(id)
        assertEquals(before - preview.frees, after.capHit(2026))
        // Every later year carries what this year shed, and the deal is the same length.
        assertEquals(deal.years, after.contract!!.years)
        assertTrue(after.contract!!.capHit(2027) > deal.capHit(2027))
        assertTrue(done.league.transactions.single().amount == preview.frees)
    }

    @Test
    fun `a man on the minimum has nothing to move, and neither has a last year`() {
        val minimum = Contract(years = 3, baseSalary = List(3) { Contract.MIN_BASE_SALARY }, signedYear = 2026)
        val (onMin, minId) = withDeal(minimum)
        assertNull(Transactions.restructurePreview(onMin.player(minId), 2026))
        assertTrue(Transactions.restructure(onMin, club, minId) is Transactions.Outcome.Refused)

        val lastYear = Contract(years = 1, baseSalary = listOf(9_000), signedYear = 2026)
        val (ending, endId) = withDeal(lastYear)
        assertNull(Transactions.restructurePreview(ending.player(endId), 2026))
    }

    @Test
    fun `the money does not vanish - what is freed now is paid later`() {
        val deal = Contract(years = 5, baseSalary = listOf(10_000, 10_000, 10_000, 10_000, 10_000),
            signedYear = 2026)
        val (l, id) = withDeal(deal)
        val preview = Transactions.restructurePreview(l.player(id), 2026)!!
        val done = Transactions.restructure(l, club, id) as Transactions.Outcome.Done
        val after = done.league.player(id).contract!!
        val total = (2026..2030).sumOf { after.capHit(it) }
        val was = (2026..2030).sumOf { deal.capHit(it) }
        // The same money, moved about - give or take the thousands that
        // integer proration drops when a bonus does not divide by the years.
        assertTrue(total in (was - deal.years)..was, "was $was, now $total")
        assertTrue(preview.frees < deal.capHit(2026))
    }

    @Test
    fun `three sizes of restructure, and the advice is to leave it when there is room`() {
        val deal = Contract(years = 4, baseSalary = listOf(12_000, 14_000, 16_000, 18_000), signedYear = 2026)
        val (l, id) = withDeal(deal)
        val options = com.nflsim.engine.offseason.ContractOptions.restructures(l.player(id), 2026)
        assertEquals(3, options.size)
        assertTrue(options.zipWithNext().all { (a, b) -> a.preview.frees < b.preview.frees },
            "a bigger restructure frees more: ${options.map { it.preview.frees }}")

        val cap = com.nflsim.engine.offseason.CapManagement.capFor(2026)
        val roomy = com.nflsim.engine.offseason.ContractOptions.bestRestructure(options, cap / 5, cap, l.tuning)
        assertEquals(null, roomy.pick, "with room to spare, the advice is not to")
        assertTrue(roomy.why.startsWith("Leave it"), roomy.why)

        val tight = com.nflsim.engine.offseason.ContractOptions.bestRestructure(options, 0, cap, l.tuning)
        assertTrue(tight.pick != null, "with no room, the advice is to move something")
    }
}
