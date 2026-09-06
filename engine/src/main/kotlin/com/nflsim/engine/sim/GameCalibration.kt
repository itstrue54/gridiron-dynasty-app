package com.nflsim.engine.sim

import com.nflsim.engine.model.League
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable
import kotlin.math.abs

/**
 * The half of docs/SPEC.md 13.2 that needs whole games to measure.
 *
 * Points, plays, third down and red zone rates are all emergent - they come out
 * of how drives are strung together, not out of any single coefficient. That is
 * why they could not be checked until M3 existed.
 */
object GameCalibration {

    fun run(
        league: League,
        games: Int = 200,
        seed: Long = 2026L,
        tuning: TuningTable = TuningTable.REALISTIC,
    ): CalibrationReport {
        val rng = SplitMixRng(seed)
        val teams = league.teams.map { team ->
            GameTeam(
                team = team,
                roster = league.roster(team.id),
                offScheme = SchemeCatalog[team.offenseScheme],
                defScheme = SchemeCatalog[team.defenseScheme],
                aggression = 0.35f + rng.nextFloat() * 0.4f,
            )
        }

        var points = 0; var plays = 0; var yards = 0
        var thirdAtt = 0; var thirdConv = 0
        var rzTrips = 0; var rzTd = 0
        var homeWins = 0; var decided = 0; var closeGames = 0
        var turnovers = 0; var penalties = 0
        var played = 0

        repeat(games) {
            val h = rng.nextInt(teams.size)
            var a = rng.nextInt(teams.size)
            if (a == h) a = (a + 1) % teams.size

            val result = GameSimulator(teams[h], teams[a], tuning)
                .simulate(rng.split("game=$it"))

            played++
            points += result.homeScore + result.awayScore
            plays += result.boxScore.home.plays + result.boxScore.away.plays
            yards += result.boxScore.home.totalYards + result.boxScore.away.totalYards
            thirdAtt += result.boxScore.home.thirdDownAttempts + result.boxScore.away.thirdDownAttempts
            thirdConv += result.boxScore.home.thirdDownConversions + result.boxScore.away.thirdDownConversions
            rzTrips += result.boxScore.home.redZoneTrips + result.boxScore.away.redZoneTrips
            rzTd += result.boxScore.home.redZoneTouchdowns + result.boxScore.away.redZoneTouchdowns
            turnovers += result.boxScore.home.turnovers + result.boxScore.away.turnovers
            penalties += result.boxScore.home.penalties + result.boxScore.away.penalties
            if (!result.isTie) { decided++; if (result.winner == result.home) homeWins++ }
            if (abs(result.homeScore - result.awayScore) <= 3) closeGames++
        }

        val teamGames = played * 2.0
        val metrics = listOf(
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
        return CalibrationReport(plays, 0, 0, metrics)
    }
}
