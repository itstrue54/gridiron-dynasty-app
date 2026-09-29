package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SPEC 5.4: how a coordinator plays the end of a game - the victory
 * formation, the kick that ties or wins it, and prevent with a two-score lead.
 */
class LateGameTest {

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }
    private val flow = TuningTable.REALISTIC.gameFlow
    private val kicker by lazy {
        league.roster(league.teams.first().id).first { it.position == com.nflsim.engine.model.Position.K }
    }
    private val scheme by lazy { SchemeCatalog[league.teams.first().offenseScheme] }

    private fun state(quarter: Int, secondsLeft: Int, lead: Int, yardLine: Int = 75, down: Int = 1, distance: Int = 10) =
        GameState(TeamId(1), TeamId(2), homeScore = 20 + lead, awayScore = 20, quarter = quarter, secondsLeft = secondsLeft,
            possession = Side.HOME, yardLine = yardLine, down = down, distance = distance)

    @Test
    fun `ahead with the clock nearly out, the coordinator kneels`() {
        val runoff = flow.runPlayClockRunoff
        val play = flow.playSeconds
        // With the defence out of timeouts, three kneels from first down run off three snaps' clock.
        fun noTimeouts(s: GameState) = s.copy(homeTimeouts = 0, awayTimeouts = 0).toPlayState()
        assertTrue(PlayCaller.canKneelItOut(noTimeouts(state(4, 3 * runoff, lead = 3)), runoff, play), "three kneels from first down")
        assertFalse(PlayCaller.canKneelItOut(noTimeouts(state(4, 3 * runoff + 1, lead = 3)), runoff, play), "one second too many")
        assertTrue(PlayCaller.canKneelItOut(noTimeouts(state(4, runoff, lead = 1, down = 3)), runoff, play), "one kneel left on third down")
        // Each timeout the defence has left stops the clock after a kneel.
        val twoLeft = state(4, 3 * runoff - 2 * (runoff - play), lead = 3).copy(awayTimeouts = 2).toPlayState()
        assertTrue(PlayCaller.canKneelItOut(twoLeft, runoff, play), "two timeouts cost two kneels' clock")
        assertFalse(PlayCaller.canKneelItOut(twoLeft.copy(secondsLeftInQuarter = twoLeft.secondsLeftInQuarter + 1), runoff, play))
        assertFalse(PlayCaller.canKneelItOut(state(4, 30, lead = 0).toPlayState(), runoff, play), "level: play for the win")
        assertFalse(PlayCaller.canKneelItOut(state(4, 30, lead = -3).toPlayState(), runoff, play), "behind")
        assertFalse(PlayCaller.canKneelItOut(state(2, 30, lead = 7).toPlayState(), runoff, play), "the half, not the game")
    }

    @Test
    fun `the clock stops at the two-minute warning`() {
        val run = PlayResult(PlayOutcome.RUN, 4, flow.runPlayClockRunoff)
        val at = ClockManagement.after(state(4, 130, lead = 10), Side.HOME, run, flow)
        assertEquals(10, at.runoff, "a snap at 2:10 stops at 2:00")
        assertEquals(10, ClockManagement.after(state(2, 130, lead = 0), Side.HOME, run, flow).runoff, "the half too")
        assertEquals(flow.runPlayClockRunoff, ClockManagement.after(state(3, 130, lead = 0), Side.HOME, run, flow).runoff)
    }

    @Test
    fun `late and behind, a club hurries and spends its timeouts`() {
        val run = PlayResult(PlayOutcome.RUN, 4, flow.runPlayClockRunoff)
        // Behind with the ball and four minutes left: the hurry-up, no timeout yet.
        val hurry = ClockManagement.after(state(4, 240, lead = -4), Side.HOME, run, flow)
        assertEquals(flow.hurryUpRunoff, hurry.runoff)
        assertEquals(null, hurry.timeout)
        // Two minutes left: its timeout stops the clock.
        val stop = ClockManagement.after(state(4, 100, lead = -4), Side.HOME, run, flow)
        assertEquals(flow.playSeconds, stop.runoff)
        assertEquals(Side.HOME, stop.timeout)
        // Ahead, with the other club behind and holding timeouts: they stop it.
        val theirs = ClockManagement.after(state(4, 100, lead = 4), Side.HOME, run, flow)
        assertEquals(Side.AWAY, theirs.timeout)
        // Out of timeouts, or too far behind to bother: the clock runs.
        assertEquals(null, ClockManagement.after(state(4, 100, lead = 4).copy(awayTimeouts = 0), Side.HOME, run, flow).timeout)
        assertEquals(null, ClockManagement.after(state(4, 100, lead = flow.timeoutMaxDeficit + 1), Side.HOME, run, flow).timeout)
        // An incompletion stops the clock on its own: nobody spends a timeout.
        val incomplete = PlayResult(PlayOutcome.INCOMPLETE, 0, flow.incompleteClockRunoff)
        assertEquals(null, ClockManagement.after(state(4, 100, lead = -4), Side.HOME, incomplete, flow).timeout)
    }

    @Test
    fun `a user calling his game calls his own timeouts`() {
        val run = PlayResult(PlayOutcome.RUN, 4, flow.runPlayClockRunoff)
        // The first quarter: no coordinator would, but he can.
        val mine = ClockManagement.after(state(1, 600, lead = 0), Side.HOME, run, flow, choice = Side.HOME to true)
        assertEquals(Side.HOME, mine.timeout)
        assertEquals(flow.playSeconds, mine.runoff)
        // Late and behind, the coordinators would stop it; he lets it run.
        val lets = ClockManagement.after(state(4, 100, lead = -4), Side.HOME, run, flow, choice = Side.HOME to false)
        assertEquals(null, lets.timeout)
        assertEquals(flow.hurryUpRunoff, lets.runoff)
        // His call is only ever for his own side: theirs stand.
        assertEquals(Side.AWAY, ClockManagement.after(state(4, 100, lead = 4), Side.HOME, run, flow, choice = Side.HOME to false).timeout)
        // Nothing to call with none left, or on a clock that has stopped.
        assertFalse(ClockManagement.canStop(state(1, 600, lead = 0).copy(homeTimeouts = 0), Side.HOME, Side.HOME, run, flow))
        assertFalse(ClockManagement.canStop(state(1, 600, lead = 0), Side.HOME, Side.HOME,
            PlayResult(PlayOutcome.INCOMPLETE, 0, flow.incompleteClockRunoff), flow))
    }

    @Test
    fun `out of bounds stops the clock late, and saves a little before then`() {
        val run = PlayResult(PlayOutcome.RUN, 4, flow.runPlayClockRunoff)
        assertTrue(ClockManagement.outOfBoundsStops(state(4, 300, lead = 0)))
        assertFalse(ClockManagement.outOfBoundsStops(state(4, 301, lead = 0)))
        assertTrue(ClockManagement.outOfBoundsStops(state(2, 120, lead = 0)))
        assertFalse(ClockManagement.outOfBoundsStops(state(2, 121, lead = 0)))
        assertTrue(ClockManagement.outOfBoundsStops(state(5, 600, lead = 0)), "overtime")
        // Late (ahead, so no hurry-up and nobody's timeout in play): only the play's own seconds.
        val late = state(4, 250, lead = 20).copy(awayTimeouts = 0)
        assertEquals(flow.playSeconds, ClockManagement.after(late, Side.HOME, run, flow, outOfBounds = true).runoff)
        // Earlier, the clock restarts on the spot: a little saved.
        assertEquals(flow.runPlayClockRunoff - flow.outOfBoundsRestartSave,
            ClockManagement.after(state(1, 600, lead = 0), Side.HOME, run, flow, outOfBounds = true).runoff)
        // Nobody spends a timeout on a clock out of bounds already stopped.
        val behind = state(4, 100, lead = -4)
        assertEquals(null, ClockManagement.after(behind, Side.HOME, run, flow, outOfBounds = true).timeout)
        assertFalse(ClockManagement.canStop(behind, Side.HOME, Side.HOME, run, flow, outOfBounds = true))
    }

    @Test
    fun `a game says when a man gets out of bounds, and only where it stops the clock`() {
        val teams = com.nflsim.engine.season.WeekRunner.teams(league, league.tuning).values.toList()
        val lines = PlayLines.templates.getValue("out_of_bounds").toSet()
        val out = (0 until 60).flatMap { i ->
            GameSimulator(teams[i % 32], teams[(i + 5) % 32], league.tuning).simulate(SplitMixRng(i.toLong())).playByPlay
        }.filter { log -> lines.any { log.text.endsWith(it) } }
        assertTrue(out.isNotEmpty(), "some plays end out of bounds late")
        assertTrue(out.all {
            (it.quarter == 2 && it.clock <= ClockManagement.TWO_MINUTE_WARNING) ||
                (it.quarter == 4 && it.clock <= ClockManagement.LAST_FIVE_MINUTES) || it.quarter >= 5
        }, "only in the windows where it stops the clock")
    }

    @Test
    fun `ahead late, the clock is left to run`() {
        val run = PlayResult(PlayOutcome.RUN, 4, flow.runPlayClockRunoff)
        val ahead = ClockManagement.after(state(4, 240, lead = 4).copy(awayTimeouts = 0), Side.HOME, run, flow)
        assertEquals(flow.runPlayClockRunoff, ahead.runoff, "no hurry-up when ahead")
    }

    @Test
    fun `with the clock out, a kick that ties or wins goes up on any down`() {
        val snap = flow.runPlayClockRunoff
        fun kick(s: GameState) = FourthDown.lastKick(s, kicker, scheme, 0, snap)
        assertTrue(kick(state(4, 20, lead = 0, down = 1)), "level: kick to win")
        assertTrue(kick(state(4, 20, lead = -3, down = 2)), "down three: kick to tie")
        assertFalse(kick(state(4, 20, lead = -4, down = 2)), "down four: a kick does not do it")
        assertFalse(kick(state(4, 20, lead = 3, down = 2)), "ahead: no need")
        assertFalse(kick(state(4, snap + 1, lead = 0)), "time for another snap")
        assertTrue(kick(state(2, 10, lead = -10, down = 2)), "the end of the half: take the points")
        assertFalse(kick(state(4, 20, lead = 0, yardLine = 30)), "out of range")
    }

    @Test
    fun `late and within a field goal, a coach kicks rather than goes for it`() {
        // Fourth and two at the opponent's 25 with three minutes left: short
        // enough to go for, close enough to kick.
        fun kicks(lead: Int) = (0 until 400).count { i ->
            FourthDown.decide(state(4, 180, lead, yardLine = 75, down = 4, distance = 2), kicker, scheme, 0,
                0.5f, SplitMixRng(i.toLong())) == FourthDownChoice.FIELD_GOAL
        } / 400.0
        assertTrue(kicks(-3) > 0.6, "down three, the kick ties it: ${kicks(-3)}")
        assertTrue(kicks(-7) < 0.5, "down seven, a kick is not enough: ${kicks(-7)}")
    }

    @Test
    fun `two scores up in the fourth, a defence plays prevent`() {
        val offTeam = league.teams.first()
        val defTeam = league.teams[1]
        val offScheme = SchemeCatalog[offTeam.offenseScheme]
        val defScheme = SchemeCatalog[defTeam.defenseScheme]
        fun ctx(s: PlayState) = PlayContext(
            offense = OffenseUnit.from(DepthChart.auto(league.roster(offTeam.id), offScheme), Personnel.P_11, offScheme),
            defense = DefenseUnit.from(DepthChart.auto(league.roster(defTeam.id), defScheme), DefensiveFront.FOUR_THREE_OVER, defScheme),
            state = s,
        )
        // The offence is behind by the prevent lead: the defence is that far ahead.
        val behind = PlayState(down = 2, distance = 8, yardLine = 40, quarter = 4, secondsLeftInQuarter = 400,
            scoreDiff = -flow.preventLead)
        val calls = (0 until 300).map { PlayCaller.defense(ctx(behind), SplitMixRng(it.toLong())) }
        assertTrue(calls.all { !it.coverage.man && it.coverage.deepDefenders >= 2 }, "deep zones only")
        assertEquals(0, calls.sumOf { it.extraRushers }, "nobody sent")
        // One point short of the lead, or in the third quarter, it is football as usual.
        val close = (0 until 300).map { PlayCaller.defense(ctx(behind.copy(scoreDiff = -flow.preventLead + 1)), SplitMixRng(it.toLong())) }
        assertTrue(close.any { it.extraRushers > 0 } && close.any { it.coverage.man })
        val third = (0 until 300).map { PlayCaller.defense(ctx(behind.copy(quarter = 3)), SplitMixRng(it.toLong())) }
        assertTrue(third.any { it.extraRushers > 0 })
    }
}
