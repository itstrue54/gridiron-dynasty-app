package com.nflsim.engine.model

import com.nflsim.engine.gen.LeagueGenerator
import kotlin.test.Test
import kotlin.test.assertEquals

/** SPEC 9.4: what the league and its conferences are called. */
class LeagueNamesTest {

    @Test
    fun `an unnamed league reads as the game always has`() {
        val league = LeagueGenerator.generate(2026, 1L)
        val team = league.teams.first { it.conference == Conference.AMERICAN && it.division == Division.WEST }
        assertEquals("American West", league.divisionName(team))
        assertEquals("American Conference", league.names.conferenceFull(Conference.AMERICAN))
        assertEquals("", league.names.leagueShort)
        assertEquals("Champions", league.names.championsTitle)
    }

    @Test
    fun `a named league names its divisions, conferences and title game`() {
        val names = LeagueNames(
            league = "Example Football League", short = "EFL", championship = "Example Bowl",
            conferences = mapOf(Conference.AMERICAN to ConferenceName("Eastern Football Conference", "EFC")),
        )
        val league = LeagueGenerator.generate(2026, 1L).copy(names = names)
        val team = league.teams.first { it.conference == Conference.AMERICAN && it.division == Division.NORTH }
        assertEquals("EFC North", league.divisionName(team))
        assertEquals("Eastern Football Conference", names.conferenceFull(Conference.AMERICAN))
        // A conference the names leave out keeps the game's.
        assertEquals("Continental South", names.division(Conference.CONTINENTAL, Division.SOUTH))
        assertEquals("EFL", names.leagueShort)
        assertEquals("Example Football League", names.copy(short = "").leagueShort)
        assertEquals("Example Bowl champions", names.championsTitle)
    }
}
