package com.example.nflsimtext.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The depth chart's game-day words. */
class DepthChartCopyTest {

    @Test
    fun `a man's depth reads as his place at his position`() {
        assertEquals("1st of 3 at QB", depthLabel(1, 3, "QB"))
        assertEquals("2nd of 6 at WR", depthLabel(2, 6, "WR"))
        assertEquals("3rd of 5 at CB", depthLabel(3, 5, "CB"))
        assertEquals("4th of 6 at LB", depthLabel(4, 6, "LB"))
        assertEquals("11th of 12 at WR", depthLabel(11, 12, "WR"))
    }

    @Test
    fun `the game-day note says the rule, and why a club short of linemen dresses fewer`() {
        val full = gameDayNote(48, 9)
        assertTrue(full.startsWith("48 dress on game day"))
        assertFalse(full.contains("Only"))
        val short = gameDayNote(46, 7)
        assertTrue(short.contains("Only 7 linemen are fit, so no more than 47 can dress."))
    }
}
