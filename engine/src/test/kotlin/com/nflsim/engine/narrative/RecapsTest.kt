package com.nflsim.engine.narrative

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.sim.PlayLog
import com.nflsim.engine.sim.Side
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/SPEC.md 10.4: a recap is the result and the plays that swung it. */
class RecapsTest {

    private val allowed = mapOf(
        "lead.default" to setOf("winner", "loser", "ws", "ls"),
        "lead.close" to setOf("winner", "loser", "ws", "ls"),
        "lead.blowout" to setOf("winner", "loser", "ws", "ls"),
        "lead.comeback" to setOf("winner", "loser", "ws", "ls", "deficit"),
        "lead.tie" to setOf("home", "away", "score"),
        "moment" to setOf("clock", "period", "team", "situation", "fourth", "play"),
        "situation.trailing" to setOf("us", "them", "gap", "points"),
        "situation.leading" to setOf("us", "them", "gap", "points"),
        "situation.level" to setOf("us"),
        "fourth" to setOf("distance"),
    )

    @Test
    fun `every part of a recap has between eight and fifteen ways to say it`() {
        assertEquals(allowed.keys, Recaps.templates.keys)
        Recaps.templates.forEach { (key, ways) ->
            assertTrue(ways.size in 8..15, "$key has ${ways.size} variants")
            assertEquals(ways.size, ways.distinct().size, "$key repeats itself")
            ways.forEach { way ->
                assertTrue(TemplateBook.slots(way).all { it in allowed.getValue(key) }, "$key: $way")
                if (key.startsWith("lead.")) assertTrue(way.endsWith("."), "$key: $way")
                // Every moment carries the play itself, and the pieces spliced into it.
                if (key == "moment") assertTrue("{play}" in way && "{situation}{fourth}" in way, way)
                // Any moment can be the first one told, so none may open as if
                // following another ("Then, ...").
                if (key == "moment") assertTrue(CONNECTORS.none { way.startsWith(it) }, "a moment opens mid-story: $way")
            }
        }
    }

    private fun play(q: Int, clock: Int, offense: Side, home: Int, away: Int, yardLine: Int = 50, down: Int = 1) =
        PlayLog(q, clock, offense, down, 10, yardLine, home, away, "A play.")

    @Test
    fun `win probability reads the scoreboard, the clock and the ball`() {
        val kickoff = WinProbability.home(play(1, 900, Side.HOME, 0, 0, yardLine = 25))
        assertTrue(kickoff in 0.45f..0.55f, "an even game starts even: $kickoff")
        assertTrue(WinProbability.home(play(4, 120, Side.AWAY, 21, 0)) > 0.99f, "21 up with two to play is won")
        // Mirror the game and the chance mirrors with it.
        val a = WinProbability.home(play(3, 400, Side.HOME, 17, 10, yardLine = 70))
        val b = WinProbability.home(play(3, 400, Side.AWAY, 10, 17, yardLine = 70))
        assertEquals(a, 1f - b, 1e-4f)
        // A lead is worth more the less time there is to overcome it.
        val early = WinProbability.home(play(1, 900, Side.AWAY, 7, 0))
        val late = WinProbability.home(play(4, 300, Side.AWAY, 7, 0))
        assertTrue(late > early, "$late should beat $early")
        // And the ball near the goal line is worth more than at your own.
        assertTrue(WinProbability.home(play(2, 500, Side.HOME, 0, 0, yardLine = 90)) >
            WinProbability.home(play(2, 500, Side.HOME, 0, 0, yardLine = 10)))
    }

    private val league by lazy { LeagueGenerator.generate(2026, 91L) }

    private fun games(n: Int) = run {
        var d = DynastyEngine.start(league, 2026, 91L, league.teams.first().id)
        (1..n).map { d = DynastyEngine.advance(d); d to d.lastGame!! }
    }

    @Test
    fun `a recap tells three to five plays, in order, starting with the biggest swing`() {
        games(4).forEach { (d, g) ->
            val keys = Recaps.keyPlays(g.playByPlay, g.homeScore, g.awayScore)
            assertTrue(keys.size in Recaps.MIN_MOMENTS..Recaps.MAX_MOMENTS, "${keys.size} moments")
            assertEquals(keys.sortedBy { it.first }, keys, "told in the order they happened")
            val curve = WinProbability.curve(g.playByPlay, g.homeScore, g.awayScore)
            val biggest = g.playByPlay.indices.maxBy { abs(curve[it + 1] - curve[it]) }
            assertTrue(keys.any { it.first == biggest }, "the play that moved it most is told")

            val recap = Recaps.write(g.playByPlay, d.league.team(g.home), d.league.team(g.away),
                g.homeScore, g.awayScore, Recaps.wordsFor(d.seed, g.playByPlay, g.home.v, g.away.v, g.homeScore, g.awayScore))!!
            assertTrue(recap.moments.all { m -> g.playByPlay[m.index].text in m.text && "{" !in m.text })
            assertTrue("{" !in recap.lead && recap.lead.endsWith("."), recap.lead)
        }
    }

    @Test
    fun `the same game reads the same way every time`() {
        val (d, g) = games(1).single()
        fun write() = Recaps.write(g.playByPlay, d.league.team(g.home), d.league.team(g.away),
            g.homeScore, g.awayScore, Recaps.wordsFor(d.seed, g.playByPlay, g.home.v, g.away.v, g.homeScore, g.awayScore))
        assertEquals(write(), write())
    }

    @Test
    fun `the lead says what kind of game it was`() {
        val home = league.teams[0]
        val away = league.teams[1]
        fun lead(plays: List<PlayLog>, h: Int, a: Int) =
            Recaps.write(plays, home, away, h, a, com.nflsim.engine.rng.SplitMixRng(1L))!!.lead
        val tie = lead(listOf(play(4, 30, Side.HOME, 17, 17)), 17, 17)
        assertTrue(home.name in tie && away.name in tie && "17" in tie, tie)
        // Home down 17 in the fourth, and won.
        val comeback = lead(listOf(play(1, 900, Side.HOME, 0, 0), play(4, 600, Side.HOME, 0, 17)), 21, 17)
        assertTrue("17" in comeback && home.name in comeback, comeback)
        assertTrue(Recaps.templates.getValue("lead.comeback").any { t ->
            t.replace("{winner}", home.name).replace("{loser}", away.name).replace("{ws}", "21")
                .replace("{ls}", "17").replace("{deficit}", "17") == comeback
        }, "a win from 17 down is a comeback: $comeback")
        val rout = lead(listOf(play(1, 900, Side.HOME, 0, 0), play(4, 600, Side.HOME, 28, 0)), 35, 3)
        assertTrue(Recaps.templates.getValue("lead.blowout").any { t ->
            t.replace("{winner}", home.name).replace("{loser}", away.name).replace("{ws}", "35")
                .replace("{ls}", "3") == rout
        }, "a 32-point win is a rout: $rout")
    }

    private companion object {
        val CONNECTORS = listOf("Then", "And ", "But ", "Later", "Next", "After that", "Also")
    }
}
