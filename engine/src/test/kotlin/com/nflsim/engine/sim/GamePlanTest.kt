package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.GamePlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GamePlanTest {

    private val league = LeagueGenerator.generate(2026, 2026L)
    private fun everyone(plan: GamePlan) = league.copy(teams = league.teams.map { it.copy(gamePlan = plan) })

    @Test
    fun `an untouched plan changes nothing and a pass-first plan throws more`() {
        val base = GameCalibration.run(league, games = 24, seed = 5L)
        assertEquals(base.table(), GameCalibration.run(everyone(GamePlan()), games = 24, seed = 5L).table())
        val airy = GameCalibration.run(everyone(GamePlan(passRate = 0.85f)), games = 24, seed = 5L)
        assertTrue(airy.attempts.toFloat() / airy.carries > base.attempts.toFloat() / base.carries,
            "a pass-first plan should throw more")
    }

    @Test
    fun `the default aggression is the figure clubs always had`() {
        assertEquals(0.35f + 3 * 0.06f, GamePlan.defaultAggression(10))
    }
}
