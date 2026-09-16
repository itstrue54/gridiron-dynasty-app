package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.DepthPins
import com.nflsim.engine.model.Position
import com.nflsim.engine.ratings.SchemeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthPinsTest {

    private val league = LeagueGenerator.generate(2026, 2026L)
    private val team = league.teams.first()
    private val roster = league.roster(team.id)
    private val offense = SchemeCatalog[team.offenseScheme]
    private val defense = SchemeCatalog[team.defenseScheme]

    @Test
    fun `with no pins the chart is the automatic one`() {
        val auto = DepthChart.auto(roster, offense)
        val pinned = DepthChart.auto(roster, offense, DepthPins())
        Position.entries.forEach { assertEquals(auto.at(it).map { p -> p.id }, pinned.at(it).map { p -> p.id }) }
    }

    @Test
    fun `a pinned backup starts and the rest keep their order`() {
        val qbs = DepthChart.auto(roster, offense).at(Position.QB)
        val backup = qbs[1]
        val chart = DepthChart.auto(roster, offense, DepthPins(order = mapOf(Position.QB to listOf(backup.id.v))))
        assertEquals((listOf(backup) + (qbs - backup)).map { it.id }, chart.at(Position.QB).map { it.id })
    }

    @Test
    fun `a package pin changes who plays in that package only`() {
        val corners = DepthChart.auto(roster, defense).at(Position.CB)
        val last = corners.last()
        val pins = DepthPins(packages = mapOf(packageKey(DefensiveFront.NICKEL_FOUR_TWO) to mapOf(Position.CB to listOf(last.id.v))))
        val chart = DepthChart.auto(roster, defense, pins)
        assertEquals(last.id, DefenseUnit.from(chart, DefensiveFront.NICKEL_FOUR_TWO, defense).corners.first().id)
        assertTrue(corners.size <= 2 || DefenseUnit.from(chart, DefensiveFront.FOUR_THREE_OVER, defense).corners.none { it.id == last.id },
            "a nickel pin should not change the base defense")
    }

    @Test
    fun `a pinned returner returns kicks and pins for players who left are dropped`() {
        val receiver = DepthChart.auto(roster, offense).at(Position.WR).last()
        val chart = DepthChart.auto(roster, offense, DepthPins(kickReturner = receiver.id.v))
        assertEquals(receiver.id, SpecialTeams.returnerFor(chart, offense)?.id)
        val pins = DepthPins(order = mapOf(Position.QB to listOf(1, 2)), kickReturner = 3)
        assertEquals(DepthPins(order = mapOf(Position.QB to listOf(2))), pins.keepOnly(setOf(2)))
    }
}
