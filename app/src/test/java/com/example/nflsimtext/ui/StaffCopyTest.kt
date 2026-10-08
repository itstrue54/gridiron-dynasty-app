package com.example.nflsimtext.ui

import com.nflsim.engine.model.GmProfile
import com.nflsim.engine.offseason.StaffJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Staff screens' words for a general manager's style and for each job. */
class StaffCopyTest {

    @Test
    fun `a general manager in the middle is described in two lines, one at the edges in four`() {
        assertEquals(2, gmStyle(GmProfile()).size)
        val reckless = GmProfile(aggression = 0.9f, winNowVsFuture = 0.9f, loyaltyToOwnPlayers = 0.1f, riskTolerance = 0.9f)
        assertEquals(
            listOf(
                "Builds to win now: keeps veterans and spends ahead.",
                "Will put a big share of the cap on one star.",
                "Lets his own walk rather than overpay.",
                "Carries dead money and bets on bounce-backs.",
            ),
            gmStyle(reckless),
        )
    }

    @Test
    fun `every job says what it does`() {
        StaffJob.ALL.forEach { assertTrue(it.label, duties(it).isNotBlank()) }
        assertTrue(duties(StaffJob.ALL.last()).contains(StaffJob.ALL.last().group!!.name))
    }

    @Test
    fun `the hub's staff button counts the jobs open`() {
        assertEquals("Hire and fire your staff first", staffButton(0))
        assertEquals("Hire and fire your staff first (1 job open)", staffButton(1))
        assertEquals("Hire and fire your staff first (3 jobs open)", staffButton(3))
    }

    @Test
    fun `the over-the-cap warning names both ways out and what happens otherwise`() {
        val note = overCapNote()
        assertTrue(note.contains("restructure") && note.contains("release") && note.contains("front office restructures"))
        assertEquals("$2.4M", capMoney(2_400))
        assertEquals("$850k", capMoney(850))
    }

    @Test
    fun `a pool's makeup leaves out the sources with nobody`() {
        assertEquals("6 new", poolMakeup(0 to "out of work", 0 to "let go this spring", 6 to "new"))
        assertEquals("1 out of work and 6 new", poolMakeup(1 to "out of work", 0 to "let go this spring", 6 to "new"))
        assertEquals(
            "3 out of work, 2 let go this spring, 4 coordinators and 6 new",
            poolMakeup(3 to "out of work", 2 to "let go this spring", 4 to "coordinators", 6 to "new"),
        )
        assertEquals("Nobody", poolMakeup(0 to "out of work", 0 to "new"))
    }

    @Test
    fun `a general manager hired in the window starts from next season`() {
        assertEquals("from 2027", gmTenure(2027, 2026))
        assertEquals("since 2027", gmTenure(2027, 2027))
        assertEquals("since 2024", gmTenure(2024, 2027))
    }

    @Test
    fun `over the cap, the open places wait for the club to get under`() {
        assertEquals("Sign or promote somebody in Free agents to fill the spot.", openSpotsAdvice(1, overCap = false))
        assertEquals(
            "Get under the cap first, then sign or promote somebody in Free agents to fill the spots.",
            openSpotsAdvice(2, overCap = true),
        )
    }

    @Test
    fun `cap room reads as room, or as how far over the club is`() {
        assertEquals("$4.2M over the cap: you can't sign anyone until you're under.", roomLine(-4_200, 700, prorated = true))
        assertEquals("$12.5M under the cap. A man signed now costs $700k, the minimum for the weeks left.", roomLine(12_500, 700, prorated = true))
        assertEquals("$850k under the cap. A man signed now costs $840k, the minimum.", roomLine(850, 840, prorated = false))
    }

    @Test
    fun `the restructure advice reads as a move`() {
        assertEquals("Best: restructure all of it", restructureAdvice("All of it"))
        assertEquals("Best: restructure half", restructureAdvice("Half"))
        assertEquals("Best: leave it", restructureAdvice(null))
    }
}
