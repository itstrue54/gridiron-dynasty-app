package com.nflsim.engine.ratings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraitScoutingTest {

    @Test
    fun `a trait is a question mark until the club has seen enough`() {
        val arrival = TraitScouting.confidence(yearsWithClub = 0, scoutingDept = 40)
        assertEquals("?", TraitScouting.grade(75, arrival, 1, "coachability"))
    }

    @Test
    fun `confidence rises with time in the building and never reaches one`() {
        val years = (0..10).map { TraitScouting.confidence(it, 60) }
        assertTrue(years.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(0.95f, years.last())
        assertTrue(TraitScouting.confidence(0, 80) > TraitScouting.confidence(0, 40),
            "a better department should see more of a new arrival")
    }

    @Test
    fun `a well known player shows his true grade`() {
        assertEquals("A", TraitScouting.grade(85, 0.95f, 7, "coachability"))
        assertEquals("F", TraitScouting.grade(20, 0.95f, 7, "coachability"))
    }

    @Test
    fun `a scout's read is stable per player and uncertain early`() {
        val reads = (1..200).map { TraitScouting.grade(72, 0.72f, it, "coachability") }
        assertEquals(reads, (1..200).map { TraitScouting.grade(72, 0.72f, it, "coachability") },
            "the same player should read the same way until confidence moves")
        assertTrue(reads.toSet().size > 1, "at middling confidence scouts should miss on some players")
        assertTrue((1..200).any { "-" in TraitScouting.grade(72, 0.45f, it, "coachability") },
            "at low confidence reads should be ranges")
    }
}
