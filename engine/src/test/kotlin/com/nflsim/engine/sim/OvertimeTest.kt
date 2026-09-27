package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SPEC 5.10: a playoff game tied after four quarters goes to overtime. */
class OvertimeTest {

    @Test
    fun `a game decided in four quarters plays exactly as it would without overtime`() {
        games.filterNot { it.first.isTie }.forEach { (without, with) -> assertEquals(without, with) }
    }

    @Test
    fun `a game level after four quarters goes to overtime and is decided there`() {
        assertTrue(wentToOvertime.size >= 3, "only ${wentToOvertime.size} ties in $GAMES games: play more")
        wentToOvertime.forEach { (without, with) ->
            assertTrue(!with.isTie, "still level: ${with.homeScore}-${with.awayScore}")
            // Regulation is the same game; overtime comes after it.
            assertEquals(without.playByPlay, with.playByPlay.takeWhile { it.quarter <= 4 })
            assertTrue(with.playByPlay.any { it.quarter >= 5 })
            assertEquals(without.drives, with.drives.takeWhile { it.startQuarter <= 4 })
        }
    }

    @Test
    fun `both clubs get the ball, then the next score wins`() {
        wentToOvertime.forEach { (_, with) ->
            val ot = with.drives.filter { it.startQuarter >= 5 }
            // A first-possession touchdown does not end it: the other club answers.
            if (ot.first().ending == DriveEnding.TOUCHDOWN) {
                assertTrue(ot.size >= 2 && ot[1].offense != ot[0].offense, "$ot")
            }
            // Once both have had it, the game ends on the first score - and a
            // touchdown then is the end, with no try after it.
            val firstBoth = ot.indexOfFirst { d -> d.offense != ot.first().offense }
            val after = ot.drop(firstBoth + 1)
            after.dropLast(1).forEach { assertTrue(!it.isScore, "the game went on after a sudden-death score: $ot") }
            val last = ot.last()
            if (last in after && last.ending == DriveEnding.TOUCHDOWN) assertEquals(6, last.points, "$ot")
        }
    }

    @Test
    fun `a regular-season tie stands`() {
        assertNotEquals(0, games.count { it.first.isTie }, "the sample has ties to look at")
        wentToOvertime.forEach { (without, _) -> assertTrue(without.playByPlay.all { it.quarter <= 4 }) }
    }

    private companion object {
        const val GAMES = 800

        // Played once for the class, not once a test.
        private val league = LeagueGenerator.generate(2026, 12L)
        private val teams = WeekRunner.teams(league, league.tuning).values.toList()

        /** Games with and without overtime, from the same streams: (without, with). */
        private val games: List<Pair<GameResult, GameResult>> by lazy {
            (0 until GAMES).map { i ->
                val home = teams[i % teams.size]
                val away = teams[(i * 7 + 3) % teams.size].takeIf { it != home } ?: teams[(i + 1) % teams.size]
                fun play(overtime: Boolean) =
                    GameSimulator(home, away, league.tuning, overtime = overtime).simulate(SplitMixRng(i.toLong()).split("ot-test"))
                play(false) to play(true)
            }
        }

        private val wentToOvertime by lazy { games.filter { (without, _) -> without.isTie } }
    }
}
