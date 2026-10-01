package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 5.x / 13.2: the ball comes out on sacks and after catches, not only on carries. */
class PassFumbleTest {

    private val league = LeagueGenerator.generate(2026, 4L)
    private val teams = WeekRunner.teams(league, league.tuning).values.toList()

    private val games: List<GameResult> by lazy {
        (0 until 300).map { i ->
            val home = teams[i % teams.size]
            val away = teams[(i * 7 + 3) % teams.size].takeIf { it != home } ?: teams[(i + 1) % teams.size]
            GameSimulator(home, away, league.tuning).simulate(SplitMixRng(i.toLong()))
        }
    }

    @Test
    fun `sacks and catches lose fumbles, and every lost fumble is counted once`() {
        var carries = 0; var sacks = 0; var catches = 0
        games.forEach { g ->
            val lines = g.playByPlay.map { it.text.lowercase() }
            sacks += lines.count { "fumble" in it && ("sack" in it || "stripped by" in it || "strip" in it) }
            catches += lines.count { "fumble" in it && ("catch" in it || "caught" in it || "hits" in it || "finds" in it || " to " in it) && "sack" !in it }
            listOf(g.boxScore.home, g.boxScore.away).forEach { b ->
                carries += b.rushFumblesLost
                // A lost fumble is a turnover, and the box's split adds up.
                assertTrue(b.fumblesLost >= b.rushFumblesLost)
                assertEquals(b.turnovers, b.fumblesLost + b.passInterceptions, "turnovers are fumbles and interceptions")
            }
        }
        val all = games.sumOf { it.boxScore.home.fumblesLost + it.boxScore.away.fumblesLost }
        assertTrue(all - carries > 0, "no fumbles off sacks or catches in 300 games")
        assertTrue(sacks > 0, "no strip-sacks in 300 games")
        assertTrue(catches > 0, "no fumbles after a catch in 300 games")
    }

    /** Whether [text] was written from one of [key]'s lines. */
    private fun from(key: String, text: String): Boolean = PlayLines.templates.getValue(key).any { way ->
        Regex(way.split(Regex("""\{\w+\}""")).joinToString(".+") { Regex.escape(it) }).matches(text)
    }

    @Test
    fun `a fumble on a sack or after a catch gives the other side the ball`() {
        var checked = 0
        games.forEach { g ->
            g.playByPlay.forEachIndexed { i, play ->
                if (!from("pass.sack.fumble", play.text) && !from("pass.complete.fumble", play.text)) return@forEachIndexed
                val next = g.playByPlay.getOrNull(i + 1) ?: return@forEachIndexed
                // The half can end on the play; then the next snap is whoever receives.
                if (next.quarter != play.quarter && play.quarter in setOf(2, 4)) return@forEachIndexed
                assertTrue(next.offense != play.offense, "the offense kept it after: ${play.text}")
                checked++
            }
        }
        assertTrue(checked > 0, "no pass fumbles to check")
    }
}
