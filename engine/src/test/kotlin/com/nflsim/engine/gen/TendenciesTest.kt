package com.nflsim.engine.gen

import com.nflsim.engine.model.CoachRole
import com.nflsim.engine.model.GamePlan
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TendenciesTest {

    @Test
    fun `coordinators differ but centre on their scheme`() {
        val scheme = SchemeCatalog.offensive.first()
        val draws = (1..400).map { Tendencies.draw(CoachRole.OFFENSIVE_COORDINATOR, scheme.id, SplitMixRng(it.toLong())) }
        assertTrue(abs(draws.map { it.passRate!! }.average() - scheme.basePassRate) < 0.01,
            "pass rates should centre on the scheme's")
        assertTrue(draws.map { it.passRate }.toSet().size > 50, "coaches running one scheme should differ")
        val aggression = (1..400).map {
            Tendencies.draw(CoachRole.HEAD_COACH, scheme.id, SplitMixRng(it.toLong())).fourthDownAggression!!
        }
        assertTrue(abs(aggression.average() - 0.53) < 0.02, "fourth-down aggression should keep the old average")
    }

    @Test
    fun `your plan goes over your staff's tendencies`() {
        val staff = GamePlan(passRate = 0.6f, blitzRate = 0.3f)
        assertEquals(GamePlan(passRate = 0.7f, blitzRate = 0.3f), GamePlan(passRate = 0.7f).over(staff))
    }

    @Test
    fun `a new league's coaches have tendencies`() {
        val league = LeagueGenerator.generate(2026, 2026L)
        val staff = league.teams.first().staff
        assertNotNull(league.coaches.getValue(staff.offCoordinator).tendencies.passRate)
        assertNotNull(league.coaches.getValue(staff.defCoordinator).tendencies.blitzRate)
        assertNotNull(league.coaches.getValue(staff.headCoach).tendencies.fourthDownAggression)
    }
}
