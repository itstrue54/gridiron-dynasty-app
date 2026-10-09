package com.example.nflsimtext.ui

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import com.nflsim.engine.sim.DefenderPlay
import com.nflsim.engine.sim.GameSimulator
import com.nflsim.engine.sim.PlayLog
import com.nflsim.engine.sim.Side
import com.nflsim.engine.stats.StatLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The defenders' tally, worst first. */
class DefenderTallyTest {

    @Test
    fun `defenders read worst first, and a man with no reps counted is left out`() {
        val lines = mapOf(
            1 to StatLine(coverageWins = 3, sacks = 1),
            2 to StatLine(timesBeaten = 2, coverageWins = 1),
            3 to StatLine(defensiveFlags = 1, timesBeaten = 1),
            4 to StatLine(tackles = 6),
            5 to StatLine(timesBeaten = 2, stuffs = 2, interceptions = 1),
        )
        val rows = defenderRows(lines, { "Man $it" })
        // Three men lost two reps each: the one who made fewest plays reads first.
        assertEquals(listOf("Man 3", "Man 2", "Man 5", "Man 1"), rows.map { it.cells[0] })
        assertEquals(listOf("Man 3", "0", "1", "1"), rows[0].cells)
        assertEquals(listOf("Man 5", "3", "2", "0"), rows[2].cells)
    }

    private fun rep(offense: Side, defender: Int?, what: DefenderPlay?) =
        PlayLog(2, 300, offense, 1, 10, 40, 0, 0, "x", defender = defender, defenderPlay = what)

    @Test
    fun `today's tally counts only the side's own defenders, each rep where the box score puts it`() {
        val plays = listOf(
            rep(Side.AWAY, 7, DefenderPlay.SACK),
            rep(Side.AWAY, 7, DefenderPlay.BEATEN),
            rep(Side.AWAY, 8, DefenderPlay.FLAG),
            rep(Side.AWAY, 8, DefenderPlay.COVERED),
            rep(Side.AWAY, 8, DefenderPlay.STUFF),
            rep(Side.AWAY, 9, DefenderPlay.INTERCEPTION),
            rep(Side.AWAY, null, null),
            // The other club's defender, on the snaps the home side had the ball.
            rep(Side.HOME, 20, DefenderPlay.BEATEN),
        )
        val today = defenderTally(plays, Side.HOME)
        assertEquals(setOf(7, 8, 9), today.keys)
        assertEquals(StatLine(sacks = 1, timesBeaten = 1), today[7])
        assertEquals(StatLine(defensiveFlags = 1, coverageWins = 1, stuffs = 1), today[8])
        assertEquals(StatLine(interceptions = 1), today[9])
        assertEquals(StatLine(timesBeaten = 1), defenderTally(plays, Side.AWAY)[20])
    }

    @Test
    fun `today's tally matches the box score's at the final whistle`() {
        val league = LeagueGenerator.generate(2026, 43L)
        val teams = WeekRunner.teams(league, league.tuning).values.toList()
        repeat(4) { i ->
            val game = GameSimulator(teams[i], teams[i + 16], league.tuning).simulate(SplitMixRng(i.toLong()))
            val today = defenderTally(game.playByPlay, Side.HOME) + defenderTally(game.playByPlay, Side.AWAY)
            assertTrue(today.isNotEmpty())
            today.forEach { (id, t) ->
                val box = game.boxScore.players.getValue(id)
                assertEquals("coverage, $id", box.coverageWins, t.coverageWins)
                assertEquals("stuffs, $id", box.stuffs, t.stuffs)
                assertEquals("beaten, $id", box.timesBeaten, t.timesBeaten)
                assertEquals("flags, $id", box.defensiveFlags, t.defensiveFlags)
            }
        }
    }
}
