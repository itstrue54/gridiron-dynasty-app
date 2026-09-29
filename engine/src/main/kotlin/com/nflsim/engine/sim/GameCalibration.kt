package com.nflsim.engine.sim

import com.nflsim.engine.model.League
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable
import kotlin.math.abs

/**
 * The single calibration authority: every band in docs/SPEC.md 13.2, measured
 * from real simulated games.
 *
 * There used to be a second harness that fed synthetic situations straight into
 * simPlay. It sampled field position uniformly from the 5 to the 94, which put
 * 17% of its snaps inside the twenty against roughly 8% in a real game. When
 * red zone difficulty was tuned, that harness reported the league had broken
 * while game-level statistics said it was perfect. Both could not be right, and
 * the one measuring situations that do not occur at that frequency was wrong.
 *
 * One harness. Measure what actually happens.
 */

data class CalibrationMetric(
    val group: String,
    val name: String,
    val value: Double,
    val low: Double,
    val high: Double,
    val format: String = "%.2f",
    /** Reported for context, not scored. */
    val diagnostic: Boolean = false,
) {
    val passing: Boolean get() = diagnostic || value in low..high

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
    private val scored: List<CalibrationMetric> get() = metrics.filterNot { it.diagnostic }
    val passing: Int get() = scored.count { it.passing }
    val total: Int get() = scored.size
    val failures: List<CalibrationMetric> get() = scored.filterNot { it.passing }

    fun table(): String = buildString {
        var group = ""
        metrics.forEach { m ->
            if (m.group != group) {
                if (group.isNotEmpty()) appendLine()
                appendLine(m.group)
                group = m.group
            }
            val verdict = if (m.diagnostic) "" else if (m.passing) "PASS" else "OUT"
            appendLine("  %-28s %8s   %s".format(
                m.name, m.format.format(m.value),
                if (m.diagnostic) "" else "target %s-%s   %s".format(
                    m.format.format(m.low), m.format.format(m.high), verdict)))
        }
        appendLine()
        appendLine("$passing of $total bands passing.")
    }
}

object GameCalibration {

    fun run(
        league: League,
        games: Int = 200,
        seed: Long = 2026L,
        tuning: TuningTable = league.tuning,
    ): CalibrationReport {
        val rng = SplitMixRng(seed)
        val teams = league.teams.map { team ->
            GameTeam(
                team = team,
                roster = league.roster(team.id),
                offScheme = SchemeCatalog.tuned(team.offenseScheme, tuning),
                defScheme = SchemeCatalog.tuned(team.defenseScheme, tuning),
                aggression = 0.35f + rng.nextFloat() * 0.4f,
                staffPlan = com.nflsim.engine.gen.Tendencies.of(team.staff, league.coaches),
            )
        }

        var points = 0; var plays = 0; var yards = 0
        var carries = 0; var rushYards = 0; var rushLoss = 0; var rushBig = 0; var fumbles = 0
        var attempts = 0; var completions = 0; var passYards = 0; var sacks = 0; var picks = 0
        var thirdAtt = 0; var thirdConv = 0
        var rzTrips = 0; var rzTd = 0
        var homeWins = 0; var decided = 0; var closeGames = 0
        var homePoints = 0; var awayPoints = 0
        var driveCount = 0; var rzTripCount = 0
        var fgMade = 0; var fgAttempts = 0; var punts = 0; var downsLost = 0; var tds = 0
        var homeYards = 0; var awayYards = 0
        var homePenalties = 0; var awayPenalties = 0
        var turnovers = 0; var penalties = 0
        var played = 0

        repeat(games) {
            val h = rng.nextInt(teams.size)
            var a = rng.nextInt(teams.size)
            if (a == h) a = (a + 1) % teams.size

            // Measured as the regular season plays: a tie goes to its overtime.
            val result = GameSimulator(teams[h], teams[a], tuning, overtime = Overtime.REGULAR_SEASON)
                .simulate(rng.split("game=$it"))

            played++
            result.drives.forEach { d ->
                driveCount++
                when (d.ending) {
                    DriveEnding.FIELD_GOAL -> { fgMade++; fgAttempts++ }
                    DriveEnding.MISSED_FIELD_GOAL -> fgAttempts++
                    DriveEnding.PUNT -> punts++
                    DriveEnding.DOWNS -> downsLost++
                    DriveEnding.TOUCHDOWN -> tds++
                    else -> {}
                }
            }
            rzTripCount += result.boxScore.home.redZoneTrips + result.boxScore.away.redZoneTrips
            listOf(result.boxScore.home, result.boxScore.away).forEach { b ->
                carries += b.rushAttempts; rushYards += b.rushYards
                rushLoss += b.rushesForLoss; rushBig += b.rushesOfTwentyPlus
                fumbles += b.fumblesLost
                attempts += b.passAttempts; completions += b.completions
                passYards += b.passYards; sacks += b.sacksAllowed
                picks += b.passInterceptions
            }
            points += result.homeScore + result.awayScore
            plays += result.boxScore.home.plays + result.boxScore.away.plays
            yards += result.boxScore.home.totalYards + result.boxScore.away.totalYards
            thirdAtt += result.boxScore.home.thirdDownAttempts + result.boxScore.away.thirdDownAttempts
            thirdConv += result.boxScore.home.thirdDownConversions + result.boxScore.away.thirdDownConversions
            rzTrips += result.boxScore.home.redZoneTrips + result.boxScore.away.redZoneTrips
            rzTd += result.boxScore.home.redZoneTouchdowns + result.boxScore.away.redZoneTouchdowns
            turnovers += result.boxScore.home.turnovers + result.boxScore.away.turnovers
            penalties += result.boxScore.home.penalties + result.boxScore.away.penalties
            homePoints += result.homeScore; awayPoints += result.awayScore
            homeYards += result.boxScore.home.totalYards
            awayYards += result.boxScore.away.totalYards
            homePenalties += result.boxScore.home.penalties
            awayPenalties += result.boxScore.away.penalties
            if (!result.isTie) { decided++; if (result.winner == result.home) homeWins++ }
            if (abs(result.homeScore - result.awayScore) <= 3) closeGames++
        }

        val teamGames = played * 2.0
        val metrics = listOf(
            CalibrationMetric("RUSHING", "yards per carry", rushYards.toDouble() / carries, 4.1, 4.6),
            CalibrationMetric("RUSHING", "carries losing yardage", rushLoss.toDouble() / carries, 0.08, 0.18),
            CalibrationMetric("RUSHING", "carries of 20+", rushBig.toDouble() / carries, 0.008, 0.030, "%.3f"),
            CalibrationMetric("RUSHING", "fumbles per carry", fumbles.toDouble() / carries, 0.004, 0.014, "%.3f"),
            CalibrationMetric("PASSING", "completion percentage", completions.toDouble() / attempts, 0.63, 0.68),
            CalibrationMetric("PASSING", "yards per attempt", passYards.toDouble() / attempts, 6.8, 7.5),
            CalibrationMetric("PASSING", "interception rate", picks.toDouble() / attempts, 0.020, 0.028, "%.3f"),
            CalibrationMetric("PASSING", "sack rate", sacks.toDouble() / (attempts + sacks), 0.055, 0.085, "%.3f"),
            CalibrationMetric("DISCIPLINE", "penalties per snap", penalties.toDouble() / plays, 0.06, 0.12, "%.3f"),
            CalibrationMetric("SCORING", "points per team per game", points / teamGames, 21.0, 24.5, "%.1f"),
            CalibrationMetric("SCORING", "yards per team per game", yards / teamGames, 320.0, 360.0, "%.0f"),
            CalibrationMetric("SCORING", "plays per team per game", plays / teamGames, 62.0, 67.0, "%.1f"),
            CalibrationMetric("EFFICIENCY", "third down conversion", thirdConv.toDouble() / thirdAtt, 0.37, 0.42),
            CalibrationMetric("EFFICIENCY", "red zone touchdown rate", rzTd.toDouble() / rzTrips, 0.53, 0.60),
            CalibrationMetric("EFFICIENCY", "turnovers per team per game", turnovers / teamGames, 1.0, 1.8, "%.2f"),
            CalibrationMetric("EFFICIENCY", "penalties per team per game", penalties / teamGames, 5.5, 7.0, "%.1f"),
            CalibrationMetric("OUTCOMES", "home win rate", homeWins.toDouble() / decided, 0.52, 0.60),
            CalibrationMetric("OUTCOMES", "games decided by 3 or less", closeGames.toDouble() / played, 0.18, 0.26),
        )
        // Diagnostics rather than bands: when the home win rate is wrong, these
        // say whether home field is failing to apply or applying backwards.
        val diagnostics = listOf(
            CalibrationMetric("HOME FIELD", "home points per game", homePoints / played.toDouble(), 0.0, 99.0, "%.1f", diagnostic = true),
            CalibrationMetric("HOME FIELD", "away points per game", awayPoints / played.toDouble(), 0.0, 99.0, "%.1f", diagnostic = true),
            CalibrationMetric("HOME FIELD", "home yards per game", homeYards / played.toDouble(), 0.0, 999.0, "%.0f", diagnostic = true),
            CalibrationMetric("HOME FIELD", "away yards per game", awayYards / played.toDouble(), 0.0, 999.0, "%.0f", diagnostic = true),
            CalibrationMetric("HOME FIELD", "home penalties per game", homePenalties / played.toDouble(), 0.0, 99.0, "%.2f", diagnostic = true),
            CalibrationMetric("HOME FIELD", "away penalties per game", awayPenalties / played.toDouble(), 0.0, 99.0, "%.2f", diagnostic = true),
            CalibrationMetric("DRIVES", "drives per team per game", driveCount / teamGames, 0.0, 99.0, "%.1f", diagnostic = true),
            CalibrationMetric("DRIVES", "red zone trips per team per game", rzTripCount / teamGames, 0.0, 99.0, "%.2f", diagnostic = true),
            CalibrationMetric("DRIVES", "touchdown drives per team", tds / teamGames, 0.0, 99.0, "%.2f", diagnostic = true),
            CalibrationMetric("DRIVES", "field goals made-attempted", fgMade / teamGames, 0.0, 99.0, "%.2f", diagnostic = true),
            CalibrationMetric("DRIVES", "field goal attempts per team", fgAttempts / teamGames, 0.0, 99.0, "%.2f", diagnostic = true),
            CalibrationMetric("DRIVES", "punts per team per game", punts / teamGames, 0.0, 99.0, "%.2f", diagnostic = true),
            CalibrationMetric("DRIVES", "turnovers on downs per team", downsLost / teamGames, 0.0, 99.0, "%.2f", diagnostic = true),
        )
        return CalibrationReport(plays, carries, attempts, metrics + diagnostics)
    }
}
