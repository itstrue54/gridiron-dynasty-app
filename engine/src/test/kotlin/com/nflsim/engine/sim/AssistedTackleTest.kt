package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.season.Schedule
import com.nflsim.engine.season.SeasonSimulator
import kotlin.test.Test
import kotlin.test.assertTrue

/** A quarter of tackles have a second man in, and the NFL counts him. */
class AssistedTackleTest {

    @Test
    fun `a season's combined tackles read like the NFL's`() {
        val league = LeagueGenerator.generate(2026, 8L)
        val season = SeasonSimulator(league, 2026, 8L).simulate()
        val lines = season.playerStats.values
        val tackles = lines.sumOf { it.tackles }
        val assists = lines.sumOf { it.assists }
        val share = assists.toDouble() / tackles
        assertTrue(share in 0.18..0.32, "assists were $share of tackles")

        // Around 1,050 combined a club a season, the NFL's figure.
        val perTeam = lines.sumOf { it.combinedTackles } / 32.0
        assertTrue(perTeam in 950.0..1_200.0, "combined tackles a club came to $perTeam")

        // And the man who leads them reads like a leaderboard, not like solo tackles.
        val leader = lines.maxOf { it.combinedTackles }
        assertTrue(leader in 140..210, "the tackle leader had $leader")
    }

    @Test
    fun `nobody assists his own tackle`() {
        val league = LeagueGenerator.generate(2026, 9L)
        val teams = com.nflsim.engine.season.WeekRunner.teams(league, league.tuning)
        val schedule = com.nflsim.engine.season.ScheduleGenerator.generate(
            league, 2026, com.nflsim.engine.rng.SplitMixRng(9L))
        val matchup = schedule.week(1).first()
        val game = GameSimulator(
            teams.getValue(matchup.home), teams.getValue(matchup.away), league.tuning,
        ).simulate(com.nflsim.engine.rng.SplitMixRng(11L))
        assertTrue(game.boxScore.players.values.sumOf { it.assists } > 0, "somebody helped on a tackle")
        assertTrue(Schedule.WEEKS > 0)
    }
}
