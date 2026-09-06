package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The regression guard on the tuning.
 *
 * A change to blocking, coverage or play calling can move a statistic three
 * layers away from where the change was made. This runs the same harness the
 * CLI uses and fails the build if the league stops looking like football.
 *
 * Measured from real simulated games, not synthetic situations - see the note
 * on GameCalibration for why that distinction cost a whole tuning pass.
 *
 * A tolerance is allowed: this runs fewer games than a real calibration pass so
 * CI stays fast, and a band edge should not fail on sampling noise. A genuine
 * regression moves a metric much further than this.
 */
class CalibrationTest {

    private val tolerance = 0.30 // fraction of a band's width

    @Test
    fun `the league still looks like football`() {
        val league = LeagueGenerator.generate(2026, 2026L)
        val report = GameCalibration.run(league, games = 90, seed = 2026L)

        val badlyOff = report.failures.filter { it.miss > tolerance }
        assertTrue(
            badlyOff.isEmpty(),
            buildString {
                appendLine("Calibration regressed. Run:")
                appendLine("  ./gradlew :engine-cli:run --args=\"gamecal\"")
                appendLine()
                append(report.table())
            }
        )
    }

    @Test
    fun `the sample is big enough to mean something`() {
        val league = LeagueGenerator.generate(2026, 2026L)
        val report = GameCalibration.run(league, games = 90, seed = 2026L)
        assertTrue(report.carries > 2_000, "only ${report.carries} carries in the sample")
        assertTrue(report.attempts > 3_000, "only ${report.attempts} pass attempts in the sample")
    }

    @Test
    fun `calibration is reproducible from a seed`() {
        val league = LeagueGenerator.generate(2026, 2026L)
        val a = GameCalibration.run(league, games = 12, seed = 11L)
        val b = GameCalibration.run(league, games = 12, seed = 11L)
        assertTrue(a.metrics.map { it.value } == b.metrics.map { it.value },
            "two runs of the same seed disagreed")
    }
}
