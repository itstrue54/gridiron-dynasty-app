package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import com.nflsim.engine.tuning.TuningTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 5.7: a catch can be broken open for a long gain, and the table can turn it off. */
class ExplosivePlayTest {

    private val league = LeagueGenerator.generate(2026, 4L)

    private fun games(tuning: TuningTable, count: Int = 150): List<GameResult> {
        val teams = WeekRunner.teams(league, tuning).values.toList()
        return (0 until count).map { i ->
            val home = teams[i % teams.size]
            val away = teams[(i * 7 + 3) % teams.size].takeIf { it != home } ?: teams[(i + 1) % teams.size]
            GameSimulator(home, away, tuning).simulate(SplitMixRng(i.toLong()))
        }
    }

    /** Whether [text] was written from one of [key]'s lines. */
    private fun from(key: String, text: String): Boolean = PlayLines.templates.getValue(key).any { way ->
        Regex(way.split(Regex("""\{\w+\}""")).joinToString(".+") { Regex.escape(it) }).matches(text)
    }

    @Test
    fun `catches are broken open for long gains, and each one is called only when long`() {
        val t = TuningTable.REALISTIC
        var long = 0
        games(t).forEach { g ->
            g.playByPlay.forEach { play ->
                if (!from("pass.catch_and_run", play.text)) return@forEach
                long++
                val gain = Regex("""\d+""").findAll(play.text).lastOrNull()?.value?.toInt()
                assertTrue(gain != null && gain >= t.passing.catchAndRunYards, "a catch and run went $gain: ${play.text}")
            }
        }
        assertTrue(long > 0, "no catch broken open in 150 games")
    }

    @Test
    fun `with the breakaway base at zero, no catch breaks open`() {
        val base = TuningTable.REALISTIC
        val off = base.copy(passing = base.passing.copy(yacBreakawayBase = 0f))
        val lines = games(off, 60).sumOf { g -> g.playByPlay.count { from("pass.catch_and_run", it.text) } }
        assertEquals(0, lines)
    }
}
