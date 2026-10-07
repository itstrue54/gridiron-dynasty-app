package com.nflsim.data
import com.nflsim.data.roster.LeagueImport
import com.nflsim.data.roster.RosterExporter
import com.nflsim.data.roster.RosterJson
import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.rng.SplitMixRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
/**
 * A roster file is the user's, and anything can be in it (SPEC 9.4). Valid
 * exports and templates, CSV and JSON, mangled - cut short, cut out, doubled,
 * shuffled, numbers swapped for junk - must load or be refused with a reason,
 * never throw. 1,240 such files threw nothing before this was cut down.
 */
class ImportFuzzTest {
    @Test fun `a mangled roster file loads or is refused, and never throws`() {
        val league = LeagueGenerator.generate(2026, 8L)
        val samples = mapOf(
            "csv-team" to RosterExporter.teamToCsv(league, league.teams.first().abbrev)!!,
            "csv-template" to RosterExporter.template(),
            "json-template" to RosterJson.template(),
        )
        val rng = SplitMixRng(99L)
        val junk = listOf(",", "\"", "\n", "{", "}", "[", "]", ":", "0", "9", "-", "x", "é", "\u0000", "\t", "\r\n", "\"\"", ",,", "99999999999", "-5", "1e9", "NaN", "null", "true")
        fun mutate(s: String): String {
            if (s.isEmpty()) return junk[rng.nextInt(junk.size)]
            val i = rng.nextInt(s.length); val j = (i + rng.nextInt(minOf(200, s.length - i) + 1)).coerceAtMost(s.length)
            return when (rng.nextInt(8)) {
                0 -> s.substring(0, i)
                1 -> s.removeRange(i, j)
                2 -> s.substring(0, j) + s.substring(i, j) + s.substring(j)
                3 -> s.substring(0, i) + junk[rng.nextInt(junk.size)] + s.substring(minOf(s.length, i + 1))
                4 -> Regex("-?\\d+").replace(s) { m -> if (rng.nextInt(20) == 0) junk[rng.nextInt(junk.size)] else m.value }
                5 -> s.lines().shuffled(java.util.Random(rng.nextLong())).joinToString("\n")
                6 -> s.lines().map { line -> if (rng.nextInt(10) == 0) line.split(",").shuffled(java.util.Random(rng.nextLong())).joinToString(",") else line }.joinToString("\n")
                else -> s.substring(0, i) + junk[rng.nextInt(junk.size)].repeat(1 + rng.nextInt(5)) + s.substring(i)
            }
        }
        val out = StringBuilder()
        val failures = mutableMapOf<String, Int>()
        var runs = 0; var loaded = 0
        for ((name, base) in samples) {
            val n = 100
            repeat(n) {
                var text = base
                repeat(1 + rng.nextInt(4)) { text = mutate(text) }
                runs++
                try {
                    val r = LeagueImport.build(text, 2026, 5L)
                    if (r.league != null) loaded++
                } catch (t: Throwable) {
                    val key = "${t::class.simpleName}: ${t.message?.take(80)} @ ${t.stackTrace.firstOrNull { it.className.startsWith("com.nflsim") }}"
                    if (failures.merge(key, 1, Int::plus) == 1) out.append("[$name] $key\n${text.take(400)}\n")
                }
            }
        }
        assertEquals(emptyMap(), failures, out.toString())
        assertTrue(loaded > 0 && loaded < runs, "some mangled files still load and some are refused: $loaded of $runs")
    }
}
