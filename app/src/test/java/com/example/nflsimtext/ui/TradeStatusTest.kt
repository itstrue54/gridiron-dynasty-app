package com.example.nflsimtext.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The trade screen's pinned bar: where the deal stands, in one line. */
class TradeStatusTest {

    @Test
    fun `an empty table says so`() {
        assertEquals("Nothing on the table yet.", tradeStatus(0, 0, accepted = null, firstReason = null))
    }

    @Test
    fun `a deal they would take says so, with what is on the table`() {
        assertEquals("Sending 2, getting 1. They'd take it.", tradeStatus(2, 1, accepted = true, firstReason = null))
    }

    @Test
    fun `a deal they would not take leads with what stands in the way`() {
        assertEquals("Sending 1, getting 1. Philadelphia Ironsides want more for it.",
            tradeStatus(1, 1, accepted = false, firstReason = "Philadelphia Ironsides want more for it."))
        assertEquals("Sending 0, getting 1. They'd say no.", tradeStatus(0, 1, accepted = false, firstReason = null))
    }
}
