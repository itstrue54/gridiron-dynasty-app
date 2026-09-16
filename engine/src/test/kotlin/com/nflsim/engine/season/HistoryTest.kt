package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** docs/SPEC.md 9.2: stat lines, standings, awards and results are kept forever. */
class HistoryTest {

    private fun played(seed: Long = 11L) = run {
        val league = LeagueGenerator.generate(2026, seed)
        var d = DynastyEngine.start(league, 2026, seed, league.teams.first().id)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d
    }

    @Test
    fun `a season the league has played is a season it remembers`() {
        val end = played()
        val rolled = DynastyEngine.advance(end)
        val record = rolled.league.history.season(2026)
        assertNotNull(record, "2026 should be in the league's history")
        assertEquals(32, record.standings.size, "every club's record should be kept")
        assertEquals(end.champion, record.champion, "the champion should be the one who won it")
        assertNotNull(record.awards?.mostValuablePlayer, "the year's MVP should be kept")
        assertTrue(record.leaders.any { it.category == "Passing yards" && it.value > 2000 },
            "the passing leader should be kept: ${record.leaders}")
    }

    @Test
    fun `what a player did joins his career`() {
        val end = played()
        val passer = end.playerStats.entries.maxByOrNull { it.value.passYards }!!
        val rolled = DynastyEngine.advance(end)
        val player = rolled.league.playersById[PlayerId(passer.key)]
        if (player == null) return  // he retired; his career is in the history instead
        assertEquals(1, player.careerStats.years, "one season played, one season on the career")
        assertEquals(passer.value.passYards, player.careerStats.total { it.passYards },
            "his career should hold the year he just had")
    }

    @Test
    fun `a man who retires leaves his career behind`() {
        val rolled = DynastyEngine.advance(played())
        val retired = rolled.league.history.retired
        assertTrue(retired.isNotEmpty(), "somebody should have retired")
        assertTrue(retired.all { it.name.isNotBlank() && it.year == 2026 })
        assertTrue(rolled.league.playersById[PlayerId(retired.first().player)] == null,
            "a retired player should be gone from the league itself")
    }

    @Test
    fun `two seasons stack up rather than replace each other`() {
        var d = played(12L)
        val firstYear = d.year
        d = DynastyEngine.advance(d)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d = DynastyEngine.advance(d)
        assertEquals(listOf(firstYear, firstYear + 1), d.league.history.seasons.map { it.year }.sorted())
        val twoSeasons = d.league.players.filter { it.careerStats.years == 2 }
        assertTrue(twoSeasons.size > 100,
            "plenty of players should have two seasons on the card, not ${twoSeasons.size}")
    }
}
