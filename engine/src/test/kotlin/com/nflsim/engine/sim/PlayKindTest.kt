package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import kotlin.test.Test
import kotlin.test.assertTrue

/** The play-by-play says what each line is, so a screen needn't guess from its words. */
class PlayKindTest {

    private val league = LeagueGenerator.generate(2026, 29L)
    private val teams = WeekRunner.teams(league, league.tuning).values.toList()
    private val logs = (0 until 30).flatMap { i ->
        GameSimulator(teams[i % 32], teams[(i * 3 + 1) % 32].takeIf { it != teams[i % 32] } ?: teams[(i + 1) % 32], league.tuning)
            .simulate(SplitMixRng(i.toLong())).playByPlay
    }

    @Test
    fun `punts and field goals are logged as kicks, on fourth down or a last kick, and the rest as snaps`() {
        val punts = logs.filter { it.kind == PlayKind.PUNT }
        val kicks = logs.filter { it.kind == PlayKind.FIELD_GOAL }
        assertTrue(punts.size > 50 && kicks.size > 30, "${punts.size} punts, ${kicks.size} field goals in 30 games")
        assertTrue(punts.all { it.down == 4 }, "a punt is a fourth-down play")
        // The words of every kick are a kick's; no snap's words are a punt's.
        assertTrue(kicks.all { "punt" !in it.text.lowercase() })
        assertTrue(logs.filter { it.kind == PlayKind.SNAP }.none { it.text.lowercase().startsWith("touchback") })
    }

    /** Whether [text] is one of [key]'s lines, its blanks filled with anything. */
    private fun isLine(key: String, text: String) = PlayLines.templates.getValue(key).any { template ->
        Regex(template.split(Regex("\\{[a-z]+\\}")).joinToString(".*") { Regex.escape(it) }).matches(text)
    }

    @Test
    fun `timeouts and the weather are notes, not plays`() {
        val notes = logs.filter { it.kind == PlayKind.NOTE }
        assertTrue(notes.isNotEmpty())
        notes.forEach { assertTrue(isLine("timeout", it.text) || isLine("weather", it.text), it.text) }
        assertTrue(logs.filter { it.kind != PlayKind.NOTE }.none { isLine("timeout", it.text) })
    }
}
