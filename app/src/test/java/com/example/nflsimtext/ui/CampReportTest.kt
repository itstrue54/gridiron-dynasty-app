package com.example.nflsimtext.ui

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.PositionGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What camp did, group by group, as the staff reads it. */
class CampReportTest {

    private val league = LeagueGenerator.generate(2026, 33L)
    private val qbs = league.players.filter { it.position == Position.QB }.take(3)
    private val wrs = league.players.filter { it.position == Position.WR }.take(2)

    @Test
    fun `each group's average move, its riser and its faller`() {
        val before = qbs + wrs
        // A read that moved: +4, -2 and 0 for the quarterbacks, -1 and -3 for the receivers.
        val moved = mapOf(qbs[0].id.v to 4, qbs[1].id.v to -2, qbs[2].id.v to 0, wrs[0].id.v to -1, wrs[1].id.v to -3)
        val after = before.map { it.copy(jersey = 99) }   // the same men, as camp left them
        val read = { p: com.nflsim.engine.model.Player -> 70 + if (p.jersey == 99) moved.getValue(p.id.v) else 0 }
        val report = campReport(before, after, read)
        assertEquals(listOf(PositionGroup.QB, PositionGroup.WR), report.map { it.group })
        val qb = report[0]
        assertEquals(3, qb.men)
        assertEquals(2f / 3f, qb.change, 0.001f)
        assertEquals(qbs[0].id to 4, qb.riser!!.first.id to qb.riser!!.second)
        assertEquals(qbs[1].id to -2, qb.faller!!.first.id to qb.faller!!.second)
        // Nobody rose among the receivers.
        assertNull(report[1].riser)
        assertEquals(-2f, report[1].change, 0.001f)
    }

    @Test
    fun `a man signed after reporting day is not in camp's report`() {
        assertEquals(listOf(PositionGroup.QB), campReport(qbs, qbs + wrs) { 70 }.map { it.group })
    }

    @Test
    fun `changes read with their sign`() {
        assertEquals("+1.5", signed(1.5f))
        assertEquals("-2", signed(-2f))
        assertEquals("0", signed(0.04f))
        assertEquals("+0.7", signed(2f / 3f))
    }
}
