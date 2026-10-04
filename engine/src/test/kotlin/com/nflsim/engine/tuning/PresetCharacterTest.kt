package com.nflsim.engine.tuning

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.sim.GameCalibration
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SPEC 12: Arcade is higher scoring with more explosives; Grinder is lower
 * scoring, run-heavy, with more pressure. As offsets from Realistic they
 * keep that character whatever Realistic becomes - this holds them to it.
 */
class PresetCharacterTest {

    private val league = LeagueGenerator.generate(2026, 2026L)
    private fun run(t: TuningTable) =
        GameCalibration.run(league.copy(tuning = t), games = 400, seed = 2026L).metrics.associate { it.name to it.value }

    private val realistic by lazy { run(TuningTable.REALISTIC) }
    private val arcade by lazy { run(TuningTable.ARCADE) }
    private val grinder by lazy { run(TuningTable.GRINDER) }

    @Test
    fun `arcade scores more, with more long plays`() {
        assertTrue(arcade.getValue("points per team per game") > realistic.getValue("points per team per game") + 2, "points")
        assertTrue(arcade.getValue("plays of 20+ per team per game") > realistic.getValue("plays of 20+ per team per game"), "20+")
    }

    @Test
    fun `grinder scores less, runs more, and sacks more`() {
        assertTrue(grinder.getValue("points per team per game") < realistic.getValue("points per team per game") - 2, "points")
        assertTrue(grinder.getValue("carries per team per game") > realistic.getValue("carries per team per game") + 1, "carries")
        assertTrue(grinder.getValue("attempts per team per game") < realistic.getValue("attempts per team per game"), "attempts")
        assertTrue(grinder.getValue("sacks per team per game") > realistic.getValue("sacks per team per game"), "sacks")
    }
}
