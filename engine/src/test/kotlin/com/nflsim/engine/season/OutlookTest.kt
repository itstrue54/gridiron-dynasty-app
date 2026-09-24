package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The preseason view of every club, for choosing one. */
class OutlookTest {

    private val league = LeagueGenerator.generate(2026, 41L)

    @Test
    fun `every club has an outlook, six contenders and eight rebuilding`() {
        val outlook = Outlook.of(league)
        assertEquals(league.teams.map { it.id }.toSet(), outlook.keys)
        val counts = outlook.values.groupingBy { it }.eachCount()
        assertEquals(6, counts[Outlook.CONTENDER])
        assertEquals(8, counts[Outlook.HOPEFUL])
        assertEquals(10, counts[Outlook.MIDDLE])
        assertEquals(8, counts[Outlook.REBUILD])
    }

    @Test
    fun `the strongest lineup is a contender and the weakest is rebuilding`() {
        val outlook = Outlook.of(league)
        val byStrength = league.teams.sortedByDescending { Outlook.strength(league, it.id) }
        assertEquals(Outlook.CONTENDER, outlook.getValue(byStrength.first().id))
        assertEquals(Outlook.REBUILD, outlook.getValue(byStrength.last().id))
        assertTrue(Outlook.strength(league, byStrength.first().id) > Outlook.strength(league, byStrength.last().id))
    }

    @Test
    fun `the same league reads the same way twice`() {
        assertEquals(Outlook.of(league), Outlook.of(LeagueGenerator.generate(2026, 41L)))
    }
}
