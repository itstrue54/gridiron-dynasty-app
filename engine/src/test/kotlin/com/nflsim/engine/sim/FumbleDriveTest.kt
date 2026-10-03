package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FumbleDriveTest {

    @Test
    fun `every lost fumble ends a drive as a fumble`() {
        val league = LeagueGenerator.generate(2026, 4L)
        val teams = WeekRunner.teams(league, league.tuning).values.toList()
        var fumbles = 0
        // A carry fumbled inside the three can carry its yards to the goal
        // line; those were scored as touchdowns for the club that lost it.
        var atTheGoalLine = 0
        repeat(400) { i ->
            val home = teams[i % teams.size]
            val away = teams[(i * 7 + 3) % teams.size].let { if (it === home) teams[(i + 1) % teams.size] else it }
            val g = GameSimulator(home, away, league.tuning).simulate(SplitMixRng(i.toLong()))
            val lost = g.boxScore.home.fumblesLost + g.boxScore.away.fumblesLost
            val ended = g.drives.count { it.ending == DriveEnding.FUMBLE }
            assertEquals(lost, ended, "game $i: $lost fumbles lost, $ended drives ended by one: ${g.drives.map { it.ending }}")
            fumbles += lost
            atTheGoalLine += g.playByPlay.count { it.yardLine >= 97 && it.text.contains("fumble", ignoreCase = true) }
        }
        assertTrue(fumbles > 0, "no fumbles in 400 games")
        assertTrue(atTheGoalLine > 0, "no fumble inside the three in 400 games: the sample misses the case")
    }
}
