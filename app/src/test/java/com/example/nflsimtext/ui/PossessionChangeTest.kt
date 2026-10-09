package com.example.nflsimtext.ui

import com.nflsim.engine.sim.PlayKind
import com.nflsim.engine.sim.PlayLines
import com.nflsim.engine.sim.PlayLog
import com.nflsim.engine.sim.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the field says when the ball changes hands, read from every line the game can write. */
class PossessionChangeTest {

    /** Every line of [key] in the game's play-by-play, its blanks filled. */
    private fun lines(key: String): List<String> =
        PlayLines.templates.getValue(key).map { it.replace(Regex("\\{[a-z]+\\}"), "Smith") }

    private fun play(text: String, down: Int = 4, kind: PlayKind = PlayKind.SNAP, quarter: Int = 2,
                     offense: Side = Side.HOME, home: Int = 7, away: Int = 3) =
        PlayLog(quarter, 300, offense, down, 10, 40, home, away, text, kind)

    /** The change after [text], the other club having the ball next, the score unchanged. */
    private fun after(text: String, down: Int = 4, kind: PlayKind = PlayKind.SNAP) =
        possessionChange(listOf(play(text, down, kind)), Side.AWAY, 2, 7, 3)

    @Test
    fun `a punt or a field goal says what it was, by its kind`() {
        (lines("punt") + lines("punt.touchback") + lines("punt.no_punter"))
            .forEach { assertEquals(it, PossessionChange.PUNT, after(it, kind = PlayKind.PUNT)) }
        lines("punt.blocked").forEach { assertEquals(it, PossessionChange.BLOCKED_PUNT, after(it, kind = PlayKind.PUNT)) }
        (lines("fg.miss") + lines("fg.no_kicker"))
            .forEach { assertEquals(it, PossessionChange.MISSED_FIELD_GOAL, after(it, kind = PlayKind.FIELD_GOAL)) }
        lines("fg.blocked").forEach { assertEquals(it, PossessionChange.BLOCKED_FIELD_GOAL, after(it, kind = PlayKind.FIELD_GOAL)) }
    }

    @Test
    fun `a saved game's log, without kinds, still knows its missed and blocked kicks by their words`() {
        (lines("fg.miss") + lines("fg.no_kicker")).forEach { assertEquals(it, PossessionChange.MISSED_FIELD_GOAL, after(it)) }
        lines("fg.blocked").forEach { assertEquals(it, PossessionChange.BLOCKED_FIELD_GOAL, after(it)) }
    }

    @Test
    fun `every interception and fumble line says what it was`() {
        lines("pass.interception").forEach { assertEquals(it, PossessionChange.INTERCEPTION, after(it, down = 2)) }
        (lines("run.fumble") + lines("pass.sack.fumble") + lines("pass.complete.fumble"))
            .forEach { assertEquals(it, PossessionChange.FUMBLE, after(it, down = 1)) }
    }

    @Test
    fun `a fourth down that comes up short is a turnover on downs, not a missed kick`() {
        // "misses" is an incomplete pass's word too.
        (lines("pass.incomplete") + lines("run.stuffed")).forEach { assertEquals(it, PossessionChange.DOWNS, after(it)) }
    }

    @Test
    fun `a score hands it over by kickoff, and the half by the second-half kickoff`() {
        assertEquals(PossessionChange.KICKOFF, possessionChange(listOf(play("A touchdown run.")), Side.AWAY, 2, 14, 3))
        assertEquals(PossessionChange.SECOND_HALF, possessionChange(listOf(play("A run for 3.", down = 1)), Side.AWAY, 3, 7, 3))
    }

    @Test
    fun `before the first snap it is the kickoff, and a note between plays is passed over`() {
        assertEquals(PossessionChange.OPENING_KICKOFF, possessionChange(emptyList(), Side.AWAY, 1, 0, 0))
        val weather = play("Snow is falling.", down = 1, kind = PlayKind.NOTE, quarter = 1, home = 0, away = 0)
        assertEquals(PossessionChange.OPENING_KICKOFF, possessionChange(listOf(weather), Side.AWAY, 1, 0, 0))
        // A punt, then a timeout logged before the next snap: still the punt.
        val timeout = play("Timeout, Denver.", down = 1, kind = PlayKind.NOTE, offense = Side.AWAY)
        assertEquals(PossessionChange.PUNT, possessionChange(listOf(play(lines("punt").first(), kind = PlayKind.PUNT), timeout), Side.AWAY, 2, 7, 3))
    }

    @Test
    fun `no change when the same club still has the ball`() {
        assertNull(possessionChange(listOf(play("A run for 4.", down = 1)), Side.HOME, 2, 7, 3))
    }

    @Test
    fun `the field names the change and whose ball it is`() {
        assertEquals("Interception · DEN ball", possessionBanner(PossessionChange.INTERCEPTION, "DEN"))
    }

    @Test
    fun `sim drive shows every snap of the club with the ball, and stops at the new possession`() {
        val plays = listOf(Side.AWAY, Side.AWAY, Side.HOME, Side.HOME, Side.HOME, Side.AWAY).map { play("x", down = 1, offense = it) }
        assertEquals(2, endOfDrive(plays, 0))
        assertEquals(5, endOfDrive(plays, 2))
        assertEquals(6, endOfDrive(plays, 5))
        assertEquals(6, endOfDrive(plays, 6))
    }
}
