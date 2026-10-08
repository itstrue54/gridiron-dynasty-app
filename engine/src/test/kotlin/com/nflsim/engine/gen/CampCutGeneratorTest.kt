package com.nflsim.engine.gen

import com.nflsim.engine.ratings.overall
import com.nflsim.engine.season.PracticeSquads
import com.nflsim.engine.season.Transactions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A new league's camp cuts: its practice squads are chosen from them, and the rest are its street. */
class CampCutGeneratorTest {

    private val league = LeagueGenerator.generate(2026, 77L)
    private val street = Transactions.freeAgents(league)
    private val squads = league.teams.flatMap { it.practiceSquad }.map { league.player(it) }
    private val rostered = league.teams.flatMap { it.roster }.maxOf { it.v }
    private val cuts = (rostered + 1)..(rostered + league.teams.size * PracticeSquads.SIZE + league.tuning.ai.freeAgentPool)

    @Test
    fun `the squads are chosen from the camp cuts, not generated for them`() {
        assertTrue(squads.all { it.id.v in cuts }, "every squad man was one of the camp cuts")
        assertEquals(league.tuning.ai.freeAgentPool, street.size, "and the cuts nobody chose are the street")
        assertTrue(street.all { it.id.v in cuts && it.teamId == null && it.contract == null })
    }

    @Test
    fun `the squads take the better men and leave the rest`() {
        fun median(l: List<Int>) = l.sorted()[l.size / 2]
        val squad = median(squads.map { overall(it) })
        val wire = median(street.map { overall(it) })
        assertTrue(squad >= wire + 2, "squads' median $squad, the street's $wire")
        assertTrue(street.map { overall(it) }.max() < 70, "nobody on the street is a starter")
    }

    @Test
    fun `the street holds every position a roster does, in about a roster's proportions`() {
        val positions = street.groupingBy { it.position }.eachCount()
        assertEquals(RosterGenerator.TEMPLATE.map { it.first }.toSet(), positions.keys)
        assertTrue(positions.getValue(com.nflsim.engine.model.Position.WR) > positions.getValue(com.nflsim.engine.model.Position.C) * 2)
    }

    @Test
    fun `camp cuts are young, as undrafted men are`() {
        val ages = (squads + street).map { it.age(league.year) }.sorted()
        assertTrue(ages[ages.size / 2] <= 24, "median age ${ages[ages.size / 2]}")
    }

    @Test
    fun `the same seed makes the same squads and street`() {
        val again = LeagueGenerator.generate(2026, 77L)
        assertEquals(league.teams.map { it.practiceSquad }, again.teams.map { it.practiceSquad })
        assertEquals(street, Transactions.freeAgents(again))
    }
}
