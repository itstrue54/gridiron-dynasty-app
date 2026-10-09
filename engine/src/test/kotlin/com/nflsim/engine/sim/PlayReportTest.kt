package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import com.nflsim.engine.tuning.TuningTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the play-by-play says about a snap beyond its line: the calls, and the defender it turned on. */
class PlayReportTest {

    private val t = TuningTable.REALISTIC.passing
    private val cover = PlayerId(7); private val tackler = PlayerId(9)
    private fun pass(outcome: PlayOutcome, yards: Int, routeWin: Float) = PlayResult(
        outcome, yards, 5, coverage = cover, tackler = tackler, log = SimLog(mapOf("routeWin" to routeWin)))

    @Test
    fun `the defender a snap turned on, good or bad`() {
        assertEquals(cover.v to DefenderPlay.BEATEN, PlayReport.standout(pass(PlayOutcome.COMPLETION, 32, 4f), false, t))
        assertNull(PlayReport.standout(pass(PlayOutcome.COMPLETION, 8, 4f), false, t), "a short catch is nobody's fault")
        assertEquals(cover.v to DefenderPlay.COVERED, PlayReport.standout(pass(PlayOutcome.INCOMPLETE, 0, -3f), false, t))
        assertNull(PlayReport.standout(pass(PlayOutcome.INCOMPLETE, 0, 3f), false, t), "an open man missed is the offense's")
        assertEquals(tackler.v to DefenderPlay.SACK, PlayReport.standout(PlayResult(PlayOutcome.SACK, -7, 5, tackler = tackler), false, t))
        assertEquals(tackler.v to DefenderPlay.INTERCEPTION, PlayReport.standout(PlayResult(PlayOutcome.INTERCEPTION, 0, 5, tackler = tackler), false, t))
        assertEquals(tackler.v to DefenderPlay.STUFF, PlayReport.standout(PlayResult(PlayOutcome.RUN, -1, 5, tackler = tackler), false, t))
        assertNull(PlayReport.standout(PlayResult(PlayOutcome.RUN, 4, 5, tackler = tackler), false, t))
        val flag = PlayResult(PlayOutcome.INCOMPLETE, 0, 5, penalty = Penalty(PenaltyType.PASS_INTERFERENCE, 12, cover))
        assertEquals(cover.v to DefenderPlay.FLAG, PlayReport.standout(flag, true, t))
    }

    @Test
    fun `every snap in a game names both calls, and its standout is a defender in it`() {
        val league = LeagueGenerator.generate(2026, 37L)
        val teams = WeekRunner.teams(league, league.tuning).values.toList()
        var named = 0
        repeat(10) { i ->
            val home = teams[i]; val away = teams[i + 10]
            val game = GameSimulator(home, away, league.tuning).simulate(SplitMixRng(i.toLong()))
            game.playByPlay.filter { it.kind == PlayKind.SNAP }.forEach { p ->
                assertTrue(p.offenseCall != null && p.defenseCall != null, p.text)
                p.defender?.let { id ->
                    val defense = if (p.offense == Side.HOME) away else home
                    assertTrue(defense.roster.any { it.id.v == id }, "${p.defenderPlay} by a man not on the defense: ${p.text}")
                    named++
                }
            }
        }
        assertTrue(named > 100, "10 games name $named standout defenders")
    }

    @Test
    fun `a game's box score tallies each defender's reps as the play-by-play names them`() {
        val league = LeagueGenerator.generate(2026, 38L)
        val teams = WeekRunner.teams(league, league.tuning).values.toList()
        repeat(6) { i ->
            val game = GameSimulator(teams[i], teams[i + 12], league.tuning).simulate(SplitMixRng(i.toLong()))
            val named = game.playByPlay.filter { it.defender != null }.groupBy { it.defender!! }
            named.forEach { (id, plays) ->
                val line = game.boxScore.players.getValue(id)
                fun n(what: DefenderPlay) = plays.count { it.defenderPlay == what }
                assertEquals(n(DefenderPlay.COVERED), line.coverageWins)
                assertEquals(n(DefenderPlay.STUFF), line.stuffs)
                assertEquals(n(DefenderPlay.BEATEN), line.timesBeaten)
                assertEquals(n(DefenderPlay.FLAG), line.defensiveFlags)
            }
            // Nobody else has a rep counted.
            game.boxScore.players.filterKeys { it !in named }.values.forEach { line ->
                assertEquals(0, line.coverageWins + line.stuffs + line.timesBeaten + line.defensiveFlags)
            }
        }
    }
}
