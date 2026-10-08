package com.nflsim.engine.gen

import com.nflsim.engine.ratings.overall
import com.nflsim.engine.season.PracticeSquads
import com.nflsim.engine.season.Transactions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A new league's street: someone to sign before the first offseason has left anyone behind. */
class StreetGeneratorTest {

    private val league = LeagueGenerator.generate(2026, 77L)
    private val street = Transactions.freeAgents(league)

    @Test
    fun `a new league starts with the street an offseason leaves`() {
        assertEquals(league.tuning.ai.freeAgentPool, street.size)
        assertTrue(street.all { it.teamId == null && it.contract == null && PracticeSquads.unattached(it) })
    }

    @Test
    fun `the street holds every position a roster does, in about a roster's proportions`() {
        val positions = street.groupingBy { it.position }.eachCount()
        assertEquals(RosterGenerator.TEMPLATE.map { it.first }.toSet(), positions.keys)
        // Six receivers to a roster, one centre: the street keeps that shape.
        assertTrue(positions.getValue(com.nflsim.engine.model.Position.WR) > positions.getValue(com.nflsim.engine.model.Position.C) * 4)
    }

    @Test
    fun `street men are rated and aged like undrafted camp bodies`() {
        val ratings = street.map { overall(it) }.sorted()
        val ages = street.map { it.age(league.year) }.sorted()
        assertTrue(ratings[ratings.size / 2] in 50..62, "median overall ${ratings[ratings.size / 2]}")
        assertTrue(ratings.last() < 70, "nobody on the street is a starter: best ${ratings.last()}")
        assertTrue(ages[ages.size / 2] <= 24, "median age ${ages[ages.size / 2]}")
    }

    @Test
    fun `the street takes ids after everyone else, and the same seed makes the same street`() {
        val others = league.players.filterNot(PracticeSquads::unattached).maxOf { it.id.v }
        assertTrue(street.all { it.id.v > others })
        assertEquals(street, Transactions.freeAgents(LeagueGenerator.generate(2026, 77L)))
    }
}
