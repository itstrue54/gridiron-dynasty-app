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
}
