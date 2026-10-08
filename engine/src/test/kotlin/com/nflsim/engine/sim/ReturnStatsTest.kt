package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Position
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import com.nflsim.engine.stats.StatLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Returns and coverage tackles in the box score (SPEC 5.10). */
class ReturnStatsTest {

    private val league = LeagueGenerator.generate(2026, 23L)
    private val teams = WeekRunner.teams(league, league.tuning).values.toList()

    private val games = (0 until 40).map { i ->
        val home = teams[i % teams.size]
        val away = teams[(i * 5 + 1) % teams.size].takeIf { it != home } ?: teams[(i + 1) % teams.size]
        Triple(home, away, GameSimulator(home, away, league.tuning).simulate(SplitMixRng(i.toLong())))
    }

    @Test
    fun `every return has one tackle, made by the other club's coverage`() {
        var returns = 0
        games.forEach { (home, away, game) ->
            fun total(team: GameTeam, f: (StatLine) -> Int) =
                team.roster.sumOf { p -> game.boxScore.players[p.id.v]?.let(f) ?: 0 }
            val homeReturns = total(home) { it.kickReturns + it.puntReturns }
            val awayReturns = total(away) { it.kickReturns + it.puntReturns }
            assertEquals(homeReturns, total(away) { it.specialTeamsTackles }, "every home return is an away coverage tackle")
            assertEquals(awayReturns, total(home) { it.specialTeamsTackles })
            returns += homeReturns + awayReturns
        }
        assertTrue(returns > 100, "40 games have returns to count: $returns")
    }

    @Test
    fun `returners and cover men are who the units say`() {
        val specialists = setOf(Position.QB, Position.K, Position.P, Position.LS)
        games.forEach { (home, away, game) ->
            (home.roster + away.roster).forEach { p ->
                val line = game.boxScore.players[p.id.v] ?: return@forEach
                if (line.specialTeamsTackles > 0) assertTrue(p.position !in specialists, "${p.position} made a coverage tackle")
                if (line.kickReturns + line.puntReturns > 0)
                    assertTrue(p.position in setOf(Position.WR, Position.RB, Position.CB), "${p.position} returned a kick")
            }
        }
    }

    @Test
    fun `a season's lines add up the special teams too`() {
        val a = StatLine(kickReturns = 2, kickReturnYards = 50, puntReturns = 1, puntReturnYards = 9, specialTeamsTackles = 3)
        val b = StatLine(kickReturns = 1, kickReturnYards = 20, specialTeamsTackles = 1)
        assertEquals(StatLine(kickReturns = 3, kickReturnYards = 70, puntReturns = 1, puntReturnYards = 9, specialTeamsTackles = 4), a + b)
    }
}
