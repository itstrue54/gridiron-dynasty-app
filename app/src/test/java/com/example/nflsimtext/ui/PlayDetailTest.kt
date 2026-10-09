package com.example.nflsimtext.ui

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Position
import com.nflsim.engine.sim.DefenderPlay
import com.nflsim.engine.sim.PlayLog
import com.nflsim.engine.sim.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The last play's calls and its defender, as the box at the top says them. */
class PlayDetailTest {

    private val league = LeagueGenerator.generate(2026, 37L)
    private val home = league.teams[0]
    private val away = league.teams[1]
    private val corner = league.roster(away.id).first { it.position == Position.CB }

    @Test
    fun `both calls by club, and the defender by position, name and club`() {
        val play = PlayLog(1, 600, Side.HOME, 3, 7, 40, 0, 0, "Catch for 31.",
            offenseCall = "Shotgun Doubles - Double Slants", defenseCall = "Nickel 4-2-5 - Cover 2",
            defender = corner.id.v, defenderPlay = DefenderPlay.BEATEN)
        val detail = playDetail(play, league, home, away)!!
        assertEquals(listOf("${home.abbrev} ran Shotgun Doubles - Double Slants", "${away.abbrev} played Nickel 4-2-5 - Cover 2"), detail.calls)
        assertEquals("CB ${corner.name}, ${away.abbrev}: beaten in coverage", detail.defender)
        assertEquals(false, detail.good)
    }

    @Test
    fun `a line logged before plays kept their calls says nothing more`() {
        assertNull(playDetail(PlayLog(1, 600, Side.HOME, 1, 10, 25, 0, 0, "A run for 4."), league, home, away))
    }
}
