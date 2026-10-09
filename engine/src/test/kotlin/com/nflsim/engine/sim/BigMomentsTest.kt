package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A touchdown or a turnover is called on the snap it happened on. */
class BigMomentsTest {

    private val league = LeagueGenerator.generate(2026, 43L)
    private val teams = WeekRunner.teams(league, league.tuning).values.toList()
    private val games = (0 until 20).map { i -> GameSimulator(teams[i], teams[i + 11], league.tuning).simulate(SplitMixRng(200L + i)) }

    private fun PlayLog.points(side: Side) = if (side == Side.HOME) homeScore else awayScore

    @Test
    fun `every touchdown is called on the snap that scored it, with its try`() {
        games.forEach { g ->
            val plays = g.playByPlay
            plays.zipWithNext().filter { (a, b) -> a.kind == PlayKind.SNAP && b.points(a.offense) - a.points(a.offense) >= 6 }
                .forEach { (snap, after) ->
                    val line = snap.text.lowercase()
                    assertTrue(listOf("touchdown", "six", "scores").any { it in line }, "not called: ${snap.text}")
                    // The try is said, unless the touchdown won it in overtime.
                    if (after.quarter <= 4) assertTrue(listOf("extra point", "point after", "the kick").any { it in line }, "no try: ${snap.text}")
                }
        }
    }

    @Test
    fun `every turnover is called, and only turnovers`() {
        games.forEach { g ->
            val takeaways = g.drives.count { it.ending in setOf(DriveEnding.INTERCEPTION, DriveEnding.FUMBLE, DriveEnding.DOWNS) }
            assertEquals(takeaways, g.playByPlay.count { it.kind == PlayKind.SNAP && "turnover" in it.text.lowercase() })
        }
    }

    @Test
    fun `the opening score of a game is a touchdown, not a lead change`() {
        games.forEach { g ->
            val first = g.playByPlay.zipWithNext().firstOrNull { (a, b) -> b.homeScore + b.awayScore > 0 && a.homeScore + a.awayScore == 0 }
            first?.let { (snap, _) -> assertTrue("the lead" !in snap.text && "in front" !in snap.text && "ahead" !in snap.text, snap.text) }
        }
    }
}
