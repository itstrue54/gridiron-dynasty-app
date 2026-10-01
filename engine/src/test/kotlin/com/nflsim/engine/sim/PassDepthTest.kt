package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 5.7: a throw can go further than its route, never shorter, and not in the red zone. */
class PassDepthTest {

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }

    private fun ctx(tuning: TuningTable, yardLine: Int): PlayContext {
        val off = league.teams[0]; val def = league.teams[1]
        val os = SchemeCatalog[off.offenseScheme]; val ds = SchemeCatalog[def.defenseScheme]
        return PlayContext(
            offense = OffenseUnit.from(DepthChart.auto(league.roster(off.id), os), Personnel.P_11, os),
            defense = DefenseUnit.from(DepthChart.auto(league.roster(def.id), ds), DefensiveFront.FOUR_THREE_OVER, ds),
            state = PlayState(down = 1, distance = 10, yardLine = yardLine), tuning = tuning,
        )
    }

    /** Air yards on each caught dig, by seed: null where it was not caught. */
    private fun airYards(tuning: TuningTable, yardLine: Int): List<Float?> {
        val c = ctx(tuning, yardLine)
        val call = OffensivePlayCall.Pass(PassConcept.DIG, Personnel.P_11, playAction = false, extraProtectors = 0, primaryTarget = 0)
        return (0 until 400).map { i ->
            val rng = SplitMixRng(i.toLong())
            PassResolution.resolve(c, call, PlayCaller.defense(c, rng.split("def")), rng).log["airYards"]
        }
    }

    @Test
    fun `the tail throws further between the twenties and changes nothing in the red zone`() {
        val t = TuningTable.REALISTIC
        val off = t.copy(passing = t.passing.copy(airYardsTail = 0f))
        val on = t.copy(passing = t.passing.copy(airYardsTail = 0.3f))
        fun mean(xs: List<Float?>) = xs.filterNotNull().average()
        assertTrue(mean(airYards(on, 40)) > mean(airYards(off, 40)) + 2, "the tail adds depth at midfield")
        // Inside the twenty the tail draws nothing, so every throw comes out the same.
        assertEquals(airYards(off, 85), airYards(on, 85))
    }
}
