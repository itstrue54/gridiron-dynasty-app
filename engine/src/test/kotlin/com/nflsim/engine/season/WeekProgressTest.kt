package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import kotlin.test.Test
import kotlin.test.assertEquals

/** SPEC 11: a week says how far along it is, and saying so changes nothing. */
class WeekProgressTest {

    @Test
    fun `each game is heard once, in order, and the week comes out the same`() {
        val league = LeagueGenerator.generate(2026, 12L)
        val start = DynastyEngine.start(league, 2026, 12L, league.teams.first().id)
        val heard = mutableListOf<Pair<Int, Int>>()
        val told = DynastyEngine.advance(start, onGame = { done, total -> heard += done to total })
        val quiet = DynastyEngine.advance(start)
        val games = start.schedule.week(start.week).size
        assertEquals((1..games).map { it to games }, heard)
        assertEquals(quiet, told, "listening must not change the week")
    }
}
