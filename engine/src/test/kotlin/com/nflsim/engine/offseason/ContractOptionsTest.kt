package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.offseason.ContractOptions.Structure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The best way to write a deal, from whatever ways the club can afford. */
class ContractOptionsTest {

    private val league = LeagueGenerator.generate(2026, 33L)
    private val player = league.players.filter { it.teamId != null }.maxBy { it.capHit(2026) }
    private val all = ContractOptions.deals(asking = 20_000, preferred = 4, year = 2026, tuning = league.tuning)
    private val cap = CapManagement.capFor(2026)

    @Test
    fun `with only the cap-light ways affordable, it picks one rather than crashing`() {
        // The Demands screen passes only the deals the club can afford; a
        // standard deal that did not fit crashed it (seen on the phone).
        val affordable = all.filter { it.structure != Structure.STANDARD }
        val advice = ContractOptions.bestDeal(player, affordable, 4, 30_000, cap, 2026, league.tuning)
        assertTrue(advice.pick in affordable)
        assertTrue(advice.pick.structure != Structure.STANDARD)
    }

    @Test
    fun `with every way affordable and room to spare, it writes it standard`() {
        val young = league.players.filter { it.age(2026) <= 25 }.first()
        val advice = ContractOptions.bestDeal(young, all, 4, cap, cap, 2026, league.tuning)
        assertEquals(Structure.STANDARD, advice.pick.structure)
    }
}
