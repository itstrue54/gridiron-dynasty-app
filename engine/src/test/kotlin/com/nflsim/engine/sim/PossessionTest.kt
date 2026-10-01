package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 5.10: the clock is all there is. Both sides' possession adds up to the game played. */
class PossessionTest {

    private val league = LeagueGenerator.generate(2026, 2026L)
    private val teams = WeekRunner.teams(league, league.tuning).values.toList()

    private fun games(overtime: Overtime?) = (0 until 300).map { i ->
        val home = teams[i % teams.size]
        val away = teams[(i * 7 + 3) % teams.size].takeIf { it != home } ?: teams[(i + 1) % teams.size]
        GameSimulator(home, away, league.tuning, overtime = overtime).simulate(SplitMixRng(i.toLong()))
    }

    private fun possession(g: GameResult) = g.boxScore.home.possessionSeconds + g.boxScore.away.possessionSeconds

    @Test
    fun `possession adds up to sixty minutes in regulation`() {
        games(overtime = null).forEach { g ->
            assertEquals(4 * GameState.QUARTER_SECONDS, possession(g),
                "${g.homeScore}-${g.awayScore}: ${g.boxScore.home.possessionText} + ${g.boxScore.away.possessionText}")
        }
    }

    @Test
    fun `with overtime, possession adds up to sixty minutes and the overtime played`() {
        var overtimes = 0
        games(Overtime.REGULAR_SEASON).forEach { g ->
            val extra = g.drives.filter { it.startQuarter > 4 }
            if (extra.isEmpty()) assertEquals(4 * GameState.QUARTER_SECONDS, possession(g))
            else {
                overtimes++
                // Overtime ends when it is decided, so only its drives' time counts.
                assertEquals(4 * GameState.QUARTER_SECONDS + extra.sumOf { it.seconds }, possession(g))
            }
        }
        assertTrue(overtimes > 0, "no overtime in 300 games")
    }
}
