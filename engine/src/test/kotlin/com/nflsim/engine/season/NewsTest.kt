package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.NewsKind
import kotlin.test.Test
import kotlin.test.assertTrue

/** docs/SPEC.md 10.1: the week, as news a beat writer would file. */
class NewsTest {

    private fun season(seed: Long = 3L): Dynasty {
        val league = LeagueGenerator.generate(2026, seed)
        var d = DynastyEngine.start(league, 2026, seed, league.teams.first().id)
        while (d.phase == DynastyPhase.REGULAR_SEASON) d = DynastyEngine.advance(d)
        return d
    }

    @Test
    fun `a season files news of every kind`() {
        val d = season()
        val kinds = d.news.map { it.kind }.toSet()
        assertTrue(NewsKind.INJURY in kinds, "somebody gets hurt in a season")
        assertTrue(NewsKind.PERFORMANCE in kinds, "somebody has an afternoon")
        assertTrue(NewsKind.HOT_SEAT in kinds, "some club is well under .500 by week eight")
        assertTrue(d.news.all { it.headline.endsWith(".") }, "a headline is a sentence")
    }

    @Test
    fun `a club's coach is only talked about once`() {
        val d = season()
        val seats = d.news.filter { it.kind == NewsKind.HOT_SEAT }.mapNotNull { it.team }
        assertTrue(seats.size == seats.distinct().size, "each club's seat is news once: $seats")
    }

    @Test
    fun `news belongs to the week it was filed`() {
        val d = season()
        assertTrue(d.news.all { it.week in 1..Schedule.WEEKS })
        assertTrue(d.news.map { it.week } == d.news.map { it.week }.sorted(),
            "the file should read in order")
    }

    @Test
    fun `an injury reads as weeks out or as the season`() {
        val d = season()
        val injuries = d.news.filter { it.kind == NewsKind.INJURY }
        assertTrue(injuries.isNotEmpty())
        assertTrue(injuries.all { it.headline.contains("out") && it.player != null })
    }

    @Test
    fun `the year turns over without last year's headlines`() {
        var d = season()
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        val rolled = DynastyEngine.advance(d)
        // Nothing from last year: only what camp brought, the holdouts and
        // their clubs' answers (SPEC 10.4), all filed for week one.
        assertTrue(rolled.news.none { it in d.news }, "last year's headlines are gone")
        assertTrue(rolled.news.all { it.kind == NewsKind.DISPUTE && it.week == 1 }, "${rolled.news}")
    }
}
