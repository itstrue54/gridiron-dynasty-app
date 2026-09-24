package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.NewsKind
import com.nflsim.engine.rng.SplitMixRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** docs/SPEC.md 10.4: news is written from narrative/news.json, several ways each. */
class HeadlinesTest {

    /** What each story may be told with. A slot outside this is a typo in the file. */
    private val allowed = mapOf(
        "injury.season" to setOf("player", "pos", "club"),
        "injury.weeks" to setOf("player", "pos", "club", "weeks"),
        "big.passing" to setOf("player", "club", "yards", "tds"),
        "big.rushing" to setOf("player", "club", "yards", "carries"),
        "big.receiving" to setOf("player", "club", "yards", "catches"),
        "big.sacks" to setOf("player", "club", "sacks"),
        "milestone" to setOf("player", "mark", "what"),
        "hot_seat" to setOf("team", "wins", "losses", "coach"),
        "benching" to setOf("player", "pos", "club", "replacement"),
        "dispute.raised" to setOf("player", "pos", "club", "paid", "market"),
        "dispute.settled" to setOf("player", "pos", "club", "years", "annual"),
        "dispute.refused" to setOf("player", "pos", "club"),
        "poached" to setOf("by", "pos", "player"),
    )

    @Test
    fun `every story has between eight and fifteen ways to tell it`() {
        assertEquals(allowed.keys, Headlines.templates.keys)
        Headlines.templates.forEach { (key, ways) ->
            assertTrue(ways.size in 8..15, "$key has ${ways.size} variants")
            assertEquals(ways.size, ways.distinct().size, "$key repeats itself")
        }
    }

    @Test
    fun `templates only use the slots their story fills`() {
        Headlines.templates.forEach { (key, ways) ->
            ways.forEach { way ->
                val used = Headlines.slots(way)
                assertTrue(used.all { it in allowed.getValue(key) }, "$key: $way")
                assertTrue("player" in used || "team" in used,
                    "$key should say who it is about: $way")
                assertTrue(way.endsWith("."), "a headline is a sentence: $way")
            }
        }
    }

    @Test
    fun `the same seed tells the same story the same way`() {
        val a = Headlines.write("big.sacks", SplitMixRng(9), "player" to "A. Man", "club" to "IND", "sacks" to 4)
        val b = Headlines.write("big.sacks", SplitMixRng(9), "player" to "A. Man", "club" to "IND", "sacks" to 4)
        assertEquals(a, b)
        assertTrue("A. Man" in a && "4" in a && "{" !in a, a)
    }

    @Test
    fun `a slot the caller forgot is an error, not braces on screen`() {
        assertFailsWith<IllegalStateException> {
            Headlines.write("big.sacks", SplitMixRng(9), "player" to "A. Man")
        }
    }

    @Test
    fun `a season's news is not all written the same way`() {
        val league = LeagueGenerator.generate(2026, 3L)
        var d = DynastyEngine.start(league, 2026, 3L, league.teams.first().id)
        while (d.phase == DynastyPhase.REGULAR_SEASON) d = DynastyEngine.advance(d)
        val injuries = d.news.filter { it.kind == NewsKind.INJURY }
        // Strip the names and numbers and see how many shapes are left.
        val shapes = injuries.map { it.headline.replace(Regex("[0-9]+"), "#") }
            .map { h -> d.league.players.fold(h) { s, p -> s.replace(p.name, "@") } }
            .map { h -> d.league.teams.fold(h) { s, t -> s.replace(Regex("\\b${t.abbrev}\\b"), "%") } }
            .toSet()
        assertTrue(shapes.size >= 5, "only ${shapes.size} ways of saying a man is hurt: $shapes")
    }

    @Test
    fun `the same dynasty writes the same week the same way`() {
        val league = LeagueGenerator.generate(2026, 5L)
        fun week(): List<String> {
            var d = DynastyEngine.start(league, 2026, 5L, league.teams.first().id)
            repeat(3) { d = DynastyEngine.advance(d) }
            return d.news.map { it.headline }
        }
        assertEquals(week(), week())
    }
}
