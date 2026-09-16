package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** docs/SPEC.md 8.5: the club sits in the room and makes its own picks. */
class DraftRoomTest {

    private fun seasonEnd(seed: Long = 7L) = run {
        val league = LeagueGenerator.generate(2026, seed)
        var d = DynastyEngine.start(league, 2026, seed, league.teams.first().id)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d
    }

    @Test
    fun `the offseason stops on the club's pick with a board to read`() {
        val dynasty = seasonEnd()
        val pause = OffseasonEngine.runToDraft(dynasty)
        val board = pause.boardFor(dynasty.userTeamId)
        assertNotNull(board.stoppedAt, "the draft should stop for the club's own pick")
        assertTrue(board.available.size > 200, "the board held ${board.available.size} prospects")
        assertTrue(board.picks.size == board.stoppedAt!! - 1,
            "picks before the club's slot should be made: ${board.picks.size} before ${board.stoppedAt}")
        assertTrue(board.picks.none { it.team == dynasty.userTeamId.v },
            "the club should not have picked before its own slot")
    }

    @Test
    fun `a club gets the man it chose`() {
        val dynasty = seasonEnd()
        val pause = OffseasonEngine.runToDraft(dynasty)
        val board = pause.boardFor(dynasty.userTeamId)
        val slot = board.stoppedAt!!
        // Somebody the board would not have taken first, to prove the choice counts.
        val wanted = board.available.sortedByDescending { overall(it) }[5]
        val (rolled, _) = pause.finish(mapOf(slot to wanted.id.v))
        assertTrue(rolled.league.roster(dynasty.userTeamId).any { it.id.v == wanted.id.v },
            "${wanted.name} should be on the roster after the club drafted him")
    }

    @Test
    fun `reading the board twice reads the same board`() {
        val dynasty = seasonEnd()
        val pause = OffseasonEngine.runToDraft(dynasty)
        val first = pause.boardFor(dynasty.userTeamId)
        val second = pause.boardFor(dynasty.userTeamId)
        assertEquals(first.stoppedAt, second.stoppedAt)
        assertEquals(first.available.map { it.id.v }, second.available.map { it.id.v },
            "the same pause should show the same board every time")
    }

    @Test
    fun `a pick moves the club on to its next slot`() {
        val dynasty = seasonEnd()
        val pause = OffseasonEngine.runToDraft(dynasty)
        val first = pause.boardFor(dynasty.userTeamId)
        val slot = first.stoppedAt!!
        val taken = first.available.first()
        val next = pause.boardFor(dynasty.userTeamId, mapOf(slot to taken.id.v))
        assertNotNull(next.stoppedAt, "a club with seven rounds should have another pick")
        assertTrue(next.stoppedAt!! > slot, "the next slot should come later than $slot")
        assertTrue(next.available.none { it.id.v == taken.id.v }, "the man taken should be off the board")
    }

    @Test
    fun `leaving it to the scouts runs the whole draft`() {
        val dynasty = seasonEnd()
        val (rolled, report) = OffseasonEngine.runToDraft(dynasty).finish()
        assertTrue(report.draftedCount > 200, "only ${report.draftedCount} were drafted")
        assertTrue(rolled.league.roster(dynasty.userTeamId).isNotEmpty())
    }
}
