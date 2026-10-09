package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.DepthPins
import com.nflsim.engine.model.Position
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A coach changing his depth chart mid-game, and who gets hurt on which snap. */
class InGameDepthTest {

    private val league = LeagueGenerator.generate(2026, 43L)
    private val teams = WeekRunner.teams(league, league.tuning).values.toList()
    private val home = teams[2]
    private val away = teams[9]
    private val starter = home.offDepth.at(Position.QB)[0]
    private val backup = home.offDepth.at(Position.QB)[1]

    /** A caller who takes every coordinator's call and benches his starter after [snaps] of his snaps. */
    private inner class Bencher(val snaps: Int) : SnapCaller {
        var seen = 0
        override fun offense(snap: Snap, suggested: OffensivePlayCall): OffensivePlayCall { seen++; return suggested }
        override fun defense(snap: Snap, suggested: DefensivePlayCall): DefensivePlayCall { seen++; return suggested }
        override fun depthPins(): DepthPins? =
            if (seen < snaps) null else home.team.depthPins.copy(order = home.team.depthPins.order + (Position.QB to listOf(backup.id.v, starter.id.v)))
    }

    private fun play(caller: SnapCaller?) =
        GameSimulator(home, away, league.tuning, caller = caller, callerSide = if (caller == null) null else Side.HOME)
            .simulate(SplitMixRng(4L))

    @Test
    fun `the backup the coach moves up mid-game takes the snaps from then on`() {
        val untouched = play(null)
        assertEquals(0, untouched.boxScore.players[backup.id.v]?.passAttempts ?: 0, "the starter plays a game nobody changes")
        val changed = play(Bencher(snaps = 30))
        assertTrue((changed.boxScore.players[backup.id.v]?.passAttempts ?: 0) > 0, "the backup throws once he is moved up")
        assertTrue((changed.boxScore.players[starter.id.v]?.passAttempts ?: 0) > 0, "the starter threw before the change")
    }

    @Test
    fun `a caller who changes nothing plays the game a simmed one would`() {
        val quiet = object : SnapCaller {}
        assertEquals(play(null).copy(playByPlay = emptyList()), play(quiet).copy(playByPlay = emptyList()))
    }

    @Test
    fun `every injury in a game is on the line of the snap it happened on`() {
        repeat(12) { i ->
            val g = GameSimulator(teams[i], teams[i + 13], league.tuning).simulate(SplitMixRng(i.toLong()))
            assertEquals(g.injuries, g.playByPlay.flatMap { it.injured })
        }
    }
}
