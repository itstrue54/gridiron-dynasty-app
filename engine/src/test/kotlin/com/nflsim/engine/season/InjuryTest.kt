package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.sim.GameResult
import com.nflsim.engine.sim.Injury
import com.nflsim.engine.stats.BoxScore
import com.nflsim.engine.stats.TeamStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InjuryTest {

    private val league = LeagueGenerator.generate(2026, 2026L)

    @Test
    fun `an injured player misses the games it costs, then dresses again`() {
        val player = league.players.first { it.teamId != null }
        val team = player.teamId!!
        val hurt = GameResult(
            home = team, away = team, homeScore = 0, awayScore = 0,
            boxScore = BoxScore(TeamStats(), TeamStats(), emptyMap()),
            drives = emptyList(), playByPlay = emptyList(),
            injuries = listOf(Injury(player.id.v, team.v, gamesOut = 2, quarter = 4)),
        )
        fun dressed(l: com.nflsim.engine.model.League) =
            WeekRunner.teams(l, l.tuning).getValue(team).roster.any { it.id == player.id }

        var l = WeekRunner.afterWeek(league, listOf(hurt), league.tuning)
        assertEquals(2, l.player(player.id).injuryWeeks)
        assertTrue(!dressed(l), "an injured player should not dress")
        l = WeekRunner.afterWeek(l, emptyList(), league.tuning)
        assertTrue(!dressed(l), "two games means two games")
        l = WeekRunner.afterWeek(l, emptyList(), league.tuning)
        assertEquals(0, l.player(player.id).injuryWeeks)
        assertTrue(dressed(l), "healed, he should dress again")
    }

    @Test
    fun `a season has injuries, and they cost games`() {
        val season = SeasonSimulator(league, 2026, 5L).simulate()
        val perTeam = season.injuries.size / 32.0
        assertTrue(perTeam in 10.0..60.0, "$perTeam injuries a club in a season")
    }
}
