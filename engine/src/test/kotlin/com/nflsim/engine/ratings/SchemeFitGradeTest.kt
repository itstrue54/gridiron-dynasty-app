package com.nflsim.engine.ratings

import com.nflsim.engine.gen.LeagueGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemeFitGradeTest {

    @Test
    fun `fit reads as a letter`() {
        assertEquals(listOf("A", "B", "C", "D", "F"), listOf(0.95f, 0.85f, 0.75f, 0.65f, 0.4f).map { SchemeFitGrade.letter(it) })
    }

    @Test
    fun `a side's fit is its starters' mean`() {
        val league = LeagueGenerator.generate(2026, 2026L)
        val team = league.teams.first()
        val roster = league.roster(team.id)
        val offense = SchemeFitGrade.side(roster, SchemeCatalog[team.offenseScheme], true)
        val defense = SchemeFitGrade.side(roster, SchemeCatalog[team.defenseScheme], false)
        assertTrue(offense in 0.3f..1f && defense in 0.3f..1f, "fits $offense and $defense")
    }
}
