package com.nflsim.engine.sim

import com.nflsim.engine.model.League
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable

data class CalibrationMetric(
    val group: String,
    val name: String,
    val value: Double,
    val low: Double,
    val high: Double,
    val format: String = "%.2f",
) {
    val passing: Boolean get() = value in low..high

    /** How far outside the band, as a fraction of the band's width. 0 when passing. */
    val miss: Double get() = when {
        passing -> 0.0
        value < low -> (low - value) / (high - low)
        else -> (value - high) / (high - low)
    }
}

data class CalibrationReport(
    val plays: Int,
    val carries: Int,
    val attempts: Int,
    val metrics: List<CalibrationMetric>,
) {
    val passing: Int get() = metrics.count { it.passing }
    val total: Int get() = metrics.size
    val failures: List<CalibrationMetric> get() = metrics.filterNot { it.passing }

    fun table(): String = buildString {
        var group = ""
        metrics.forEach { m ->
            if (m.group != group) {
                if (group.isNotEmpty()) appendLine()
                appendLine(m.group)
                group = m.group
            }
            appendLine("  %-28s %8s   target %s-%s   %s".format(
                m.name, m.format.format(m.value),
                m.format.format(m.low), m.format.format(m.high),
                if (m.passing) "PASS" else "OUT"))
        }
        appendLine()
        appendLine("$passing of $total bands passing.")
    }
}

/**
 * Runs a large sample of snaps across the whole league and checks the result
 * against the statistical bands in docs/SPEC.md section 13.2.
 *
 * Sampling matters: this draws a random offence against a random defence every
 * snap. Calibrating against one matchup bakes that team's stylistic quirks
 * into the league's coefficients, which is exactly the mistake that produced
 * the first pass of these numbers.
 *
 * Shared by the CLI and the regression test on purpose - the thing you tune
 * against and the thing that guards the tuning must be the same code.
 */
object CalibrationHarness {

    fun run(
        league: League,
        plays: Int = 60_000,
        seed: Long = 2026L,
        tuning: TuningTable = TuningTable.REALISTIC,
    ): CalibrationReport {
        val rng = SplitMixRng(seed)

        val offences = league.teams.map { team ->
            val scheme = SchemeCatalog[team.offenseScheme]
            OffenseUnit.from(DepthChart.auto(league.roster(team.id), scheme), Personnel.P_11, scheme)
        }
        val defences = league.teams.map { team ->
            val scheme = SchemeCatalog[team.defenseScheme]
            Triple(
                DefenseUnit.from(DepthChart.auto(league.roster(team.id), scheme),
                    DefensiveFront.FOUR_THREE_OVER, scheme),
                team.stadium.crowdNoise,
                team.abbrev,
            )
        }

        var runs = 0; var runYards = 0; var runNeg = 0; var runBig = 0; var fumbles = 0
        var att = 0; var comp = 0; var passYards = 0; var sacks = 0; var picks = 0
        var flags = 0

        repeat(plays) {
            val o = rng.nextInt(offences.size)
            var d = rng.nextInt(defences.size)
            if (d == o) d = (d + 1) % defences.size
            val (defence, noise, _) = defences[d]

            val ctx = PlayContext(
                offense = offences[o],
                defense = defence,
                state = PlayState(
                    down = 1 + rng.nextInt(4),
                    distance = 1 + rng.nextInt(15),
                    yardLine = 5 + rng.nextInt(90),
                    scoreDiff = -17 + rng.nextInt(35),
                ),
                tuning = tuning,
                crowdNoise = noise,
            )
            val off = PlayCaller.offense(ctx, rng)
            val def = PlayCaller.defense(ctx, rng)
            val r = PlaySimulator.simPlay(ctx, off, def, rng)

            if (r.penalty != null) { flags++; return@repeat }
            when (r.outcome) {
                PlayOutcome.RUN -> {
                    runs++; runYards += r.yards
                    if (r.yards < 0) runNeg++
                    if (r.yards >= 20) runBig++
                }
                PlayOutcome.COMPLETION -> { att++; comp++; passYards += r.yards }
                PlayOutcome.INCOMPLETE, PlayOutcome.THROWAWAY -> att++
                PlayOutcome.INTERCEPTION -> { att++; picks++ }
                PlayOutcome.SACK -> sacks++
                PlayOutcome.FUMBLE_LOST -> { runs++; fumbles++ }
                else -> {}
            }
        }

        val metrics = listOf(
            CalibrationMetric("RUSHING", "yards per carry", runYards.toDouble() / runs, 4.1, 4.6),
            CalibrationMetric("RUSHING", "carries losing yardage", runNeg.toDouble() / runs, 0.08, 0.18),
            CalibrationMetric("RUSHING", "carries of 20+", runBig.toDouble() / runs, 0.008, 0.030, "%.3f"),
            CalibrationMetric("RUSHING", "fumbles per carry", fumbles.toDouble() / runs, 0.004, 0.014, "%.3f"),
            CalibrationMetric("PASSING", "completion percentage", comp.toDouble() / att, 0.63, 0.68),
            CalibrationMetric("PASSING", "yards per attempt", passYards.toDouble() / att, 6.8, 7.5),
            CalibrationMetric("PASSING", "interception rate", picks.toDouble() / att, 0.020, 0.028, "%.3f"),
            CalibrationMetric("PASSING", "sack rate", sacks.toDouble() / (att + sacks), 0.055, 0.085, "%.3f"),
            CalibrationMetric("DISCIPLINE", "penalties per snap", flags.toDouble() / plays, 0.06, 0.12, "%.3f"),
        )

        return CalibrationReport(plays, runs, att, metrics)
    }
}
