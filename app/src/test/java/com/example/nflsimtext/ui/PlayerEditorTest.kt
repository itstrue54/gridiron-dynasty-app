package com.example.nflsimtext.ui

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.season.DynastyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC 10.5: the editor shows every rating once, opens what a position leans on, and finds anyone in the league. */
class PlayerEditorTest {

    private val league = LeagueGenerator.generate(2026, 8L)
    private val dynasty = DynastyEngine.start(league, 2026, 8L, league.teams.first().id).copy(editPlayers = true)

    @Test
    fun `every rating appears in exactly one group`() {
        val listed = RATING_GROUPS.flatMap { it.second }
        assertEquals(RatingId.entries.toSet(), listed.toSet())
        assertEquals(listed.size, listed.toSet().size)
    }

    @Test
    fun `a position opens the groups it leans on`() {
        Position.entries.forEach { assertTrue("$it opens nothing", groupsFor(it).isNotEmpty()) }
        assertTrue("Quarterback" in groupsFor(Position.QB))
        assertTrue("Kicking" in groupsFor(Position.K))
        assertTrue("Defense" in groupsFor(Position.CB))
    }

    @Test
    fun `the finder reaches every club, the free agents, and a name`() {
        val everyone = finder(dynasty, "ALL", "ALL", "")
        assertTrue(everyone.map { it.teamId }.toSet().size >= league.teams.size)
        val club = league.teams[7]
        assertTrue(finder(dynasty, club.abbrev, "ALL", "").all { it.teamId == club.id })
        assertTrue(finder(dynasty, "Free agents", "ALL", "").all { it.teamId == null })
        val someone = league.roster(club.id).first()
        assertTrue(finder(dynasty, "ALL", "ALL", someone.lastName.lowercase()).any { it.id == someone.id })
        assertTrue(finder(dynasty, "ALL", "QB", "").all { it.position.group.name == "QB" })
    }
}
