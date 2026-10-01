package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Position
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FatigueTest {

    @Test
    fun `defensive linemen and backs rotate, quarterbacks never do`() {
        val league = LeagueGenerator.generate(2026, 2026L)
        // Nobody hurt: a starter carted off in the third is not a rotation.
        val t = league.tuning.let { it.copy(injuries = it.injuries.copy(scale = 0f)) }
        val teams = league.teams.take(8).map { team ->
            GameTeam(team, league.roster(team.id), SchemeCatalog.tuned(team.offenseScheme, t),
                SchemeCatalog.tuned(team.defenseScheme, t))
        }
        val qb = mutableListOf<Double>(); val rb = mutableListOf<Double>(); val edge = mutableListOf<Double>()
        val rng = SplitMixRng(3L)
        for (g in teams.indices) {
            val home = teams[g]; val away = teams[(g + 1) % teams.size]
            val sim = GameSimulator(home, away, t)
            sim.simulate(rng.split("g$g"))
            for (gt in listOf(home, away)) {
                val off = sim.offenseSnaps.getValue(gt.id).toDouble()
                val def = sim.defenseSnaps.getValue(gt.id).toDouble()
                gt.offDepth.starter(Position.QB)?.let { qb += (sim.snaps[it.id.v] ?: 0) / off }
                gt.offDepth.starter(Position.RB)?.let { rb += (sim.snaps[it.id.v] ?: 0) / off }
                gt.defDepth.starter(Position.EDGE)?.let { edge += (sim.snaps[it.id.v] ?: 0) / def }
            }
        }
        assertEquals(1.0, qb.average(), 0.001, "quarterbacks should play every snap")
        assertTrue(edge.average() in 0.55..0.85, "starting edge rushers played ${edge.average()} of snaps")
        // A lead back stays in unless his backup, fresh, is as good as he is tired.
        assertTrue(rb.average() in 0.5..0.95, "lead backs played ${rb.average()} of snaps")
    }
}
