package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 5.10: a game level after four quarters goes to overtime - the regular season's, or the playoffs'. */
class OvertimeTest {

    @Test
    fun `a game decided in four quarters plays exactly as it would without overtime`() {
        games.filterNot { it.none.isTie }.forEach { g ->
            assertEquals(g.none, g.regular)
            assertEquals(g.none, g.playoffs)
        }
    }

    @Test
    fun `overtime comes after regulation, and regulation is the same game`() {
        assertTrue(level.size >= 3, "only ${level.size} ties in $GAMES games: play more")
        level.forEach { g ->
            listOf(g.regular, g.playoffs).forEach { ot ->
                assertEquals(g.none.playByPlay, ot.playByPlay.takeWhile { it.quarter <= 4 })
                assertEquals(g.none.drives, ot.drives.takeWhile { it.startQuarter <= 4 })
                assertTrue(ot.playByPlay.any { it.quarter >= 5 }, "it went to overtime")
            }
        }
    }

    @Test
    fun `a playoff game is decided in overtime`() {
        level.forEach { g -> assertTrue(!g.playoffs.isTie, "still level: ${g.playoffs.homeScore}-${g.playoffs.awayScore}") }
    }

    @Test
    fun `the regular season plays one ten-minute period, and a game still level after it is a tie`() {
        level.forEach { g ->
            val ot = g.regular.playByPlay.filter { it.quarter >= 5 }
            assertTrue(ot.all { it.quarter == 5 }, "one period only")
            assertTrue(ot.all { it.clock <= Overtime.REGULAR_SEASON.periodSeconds }, "ten minutes, not fifteen")
            // A tie only once the period has run out: the last snap of
            // overtime came with no more than a snap's clock left, and it, or
            // the punt or kick after it, took the rest. (A drive's own
            // seconds don't count the punt, so they can't show it.)
            if (g.regular.isTie) {
                val last = g.regular.playByPlay.last { it.quarter == 5 }
                assertTrue(last.clock <= league.tuning.gameFlow.runPlayClockRunoff + PUNT_SECONDS,
                    "the period had time left: $last")
            }
        }
    }

    @Test
    fun `both clubs get the ball, then the next score wins`() {
        level.flatMap { listOf(it.regular, it.playoffs) }.forEach { game ->
            val ot = game.drives.filter { it.startQuarter >= 5 }
            // A first-possession touchdown does not end it: the other club answers.
            if (ot.first().ending == DriveEnding.TOUCHDOWN) {
                assertTrue(ot.size >= 2 && ot[1].offense != ot[0].offense, "$ot")
            }
            // Once both have had it, the game ends on the first score - and a
            // touchdown then is the end, with no try after it.
            val firstBoth = ot.indexOfFirst { d -> d.offense != ot.first().offense }
            if (firstBoth < 0) return@forEach
            val after = ot.drop(firstBoth + 1)
            after.dropLast(1).forEach { assertTrue(!it.isScore, "the game went on after a sudden-death score: $ot") }
            val last = ot.last()
            if (last in after && last.ending == DriveEnding.TOUCHDOWN) assertEquals(6, last.points, "$ot")
        }
    }

    @Test
    fun `a touchdown that wins it after the other club has had the ball has no try`() {
        var checked = 0
        level.flatMap { listOf(it.regular, it.playoffs) }.forEach { game ->
            val ot = game.drives.filter { it.startQuarter >= 5 }
            // Every overtime touchdown after the other club's possession that
            // ends the game by putting its club ahead is worth six: the second
            // club answering nothing with a touchdown included. One that only
            // draws level - its club six behind - has its try, and the try
            // is what wins it.
            ot.forEachIndexed { i, d ->
                val otherHad = ot.take(i).any { it.offense != d.offense }
                if (d.ending == DriveEnding.TOUCHDOWN && otherHad && d == ot.last() && !game.isTie) {
                    val own = ot.take(i).filter { it.offense == d.offense }.sumOf { it.points }
                    val theirs = ot.take(i).filter { it.offense != d.offense }.sumOf { it.points }
                    if (own + 6 > theirs) assertEquals(6, d.points, "a walk-off touchdown kicked a try: $ot")
                    else assertTrue(d.points in 7..8, "a touchdown that only drew level won without its try: $ot")
                    checked++
                }
            }
        }
        assertTrue(checked > 0, "the sample has a walk-off touchdown to look at")
    }

    /** One game played three ways, from the same stream. */
    private data class Three(val none: GameResult, val regular: GameResult, val playoffs: GameResult)

    private companion object {
        const val GAMES = 800
        /** What a punt takes off the clock (GameSimulator). */
        const val PUNT_SECONDS = 12

        // Played once for the class, not once a test.
        private val league = LeagueGenerator.generate(2026, 12L)
        private val teams = WeekRunner.teams(league, league.tuning).values.toList()

        private val games: List<Three> by lazy {
            (0 until GAMES).map { i ->
                val home = teams[i % teams.size]
                val away = teams[(i * 7 + 3) % teams.size].takeIf { it != home } ?: teams[(i + 1) % teams.size]
                fun play(overtime: Overtime?) =
                    GameSimulator(home, away, league.tuning, overtime = overtime).simulate(SplitMixRng(i.toLong()).split("ot-test"))
                Three(play(null), play(Overtime.REGULAR_SEASON), play(Overtime.PLAYOFFS))
            }
        }

        /** The games level after four quarters. */
        private val level by lazy { games.filter { it.none.isTie } }
    }
}
