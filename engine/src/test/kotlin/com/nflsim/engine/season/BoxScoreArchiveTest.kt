package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.ArchivedGame
import com.nflsim.engine.model.LeagueHistory
import com.nflsim.engine.stats.BoxScore
import com.nflsim.engine.stats.StatLine
import com.nflsim.engine.stats.TeamStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 9.2: box scores full for five seasons, team totals after. */
class BoxScoreArchiveTest {

    @Test
    fun `a season archives every game, playoffs included, with its box score`() {
        val league = LeagueGenerator.generate(2026, 3L)
        var d = DynastyEngine.start(league, 2026, 3L, league.teams.first().id)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        val games = d.league.history.games
        assertEquals(272, games.count { it.week <= Schedule.WEEKS }, "every regular season game")
        assertEquals(13, games.count { it.week > Schedule.WEEKS }, "every playoff game")
        assertTrue(games.all { it.box.players.isNotEmpty() })
        assertTrue(d.playoffs.all { it.box == null }, "the bracket should not keep a second copy")
        val final = games.single { it.week == Schedule.WEEKS + PlayoffRound.FINAL.ordinal + 1 }
        assertEquals(d.champion, if (final.homeScore >= final.awayScore) final.home else final.away)
        // Every line is on exactly one side, and each side has its quarterback.
        games.forEach { g ->
            val home = g.linesFor(g.home)
            val away = g.linesFor(g.away)
            assertEquals(g.box.players.keys, home.keys + away.keys)
            assertTrue(home.values.any { it.passAttempts > 0 } && away.values.any { it.passAttempts > 0 })
        }
    }

    @Test
    fun `seasons more than five back keep team totals only`() {
        fun game(year: Int) = ArchivedGame(
            year, 1, 1, 2, 20, 17,
            BoxScore(TeamStats(points = 20), TeamStats(points = 17), mapOf(7 to StatLine(tackles = 5))),
        )
        val history = LeagueHistory(games = (2020..2030).map(::game)).compressedFor(2030)
        val full = history.games.filter { it.box.players.isNotEmpty() }.map { it.year }
        assertEquals((2026..2030).toList(), full)
        assertTrue(history.games.all { it.box.home.points == 20 }, "team totals stay forever")
    }
}
