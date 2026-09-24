package com.example.nflsimtext.ui

import com.nflsim.engine.sim.PlayLines
import com.nflsim.engine.sim.PlayLog
import com.nflsim.engine.sim.Side
import org.junit.Assert.assertEquals
import com.example.nflsimtext.ui.components.PlayEvent
import org.junit.Test

/** The game log marks turnovers by reading the line, so every way of writing one must read as one. */
class PlayEventTest {

    private fun play(text: String) = PlayLog(
        quarter = 2, clock = 300, offense = Side.HOME, down = 2, distance = 7, yardLine = 40,
        homeScore = 7, awayScore = 3, text = text,
    )

    @Test
    fun `every interception and fumble line is marked as a turnover`() {
        (PlayLines.templates.getValue("pass.interception") + PlayLines.templates.getValue("run.fumble"))
            .forEach { template ->
                assertEquals(template, PlayEvent.TURNOVER, eventOf(listOf(play(template)), 0))
            }
    }

    @Test
    fun `an ordinary completion is not`() {
        PlayLines.templates.getValue("pass.complete").forEach { template ->
            assertEquals(template, null, eventOf(listOf(play(template)), 0))
        }
    }
}
