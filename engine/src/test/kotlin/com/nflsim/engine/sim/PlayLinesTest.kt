package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.narrative.TemplateBook
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/SPEC.md 10.4: the play-by-play is written from narrative/plays.json. */
class PlayLinesTest {

    /** What each line may be written with. A slot outside this is a typo in the file. */
    private val allowed = mapOf(
        "kneel" to setOf("qb"),
        "spike" to setOf("qb"),
        "timeout" to setOf("team", "left"),
        "penalty.false_start" to setOf("who"),
        "penalty.offside" to setOf("who"),
        "penalty.pass_holding" to setOf("who"),
        "penalty.pass_interference" to setOf("spot"),
        "penalty.run_holding" to setOf("who"),
        "pass.sack" to setOf("sacker", "qb", "loss"),
        "pass.sack.team" to setOf("qb", "loss"),
        "pass.scramble" to setOf("qb", "gain"),
        "pass.scramble.stopped" to setOf("qb"),
        "pass.throwaway" to setOf("qb"),
        "pass.nobody_open" to emptySet(),
        "pass.interception" to setOf("defender", "concept", "qb"),
        "pass.incomplete" to setOf("qb", "concept", "receiver"),
        "pass.complete" to setOf("qb", "receiver", "concept", "yards", "yardage"),
        "pass.complete.nothing" to setOf("qb", "receiver", "concept"),
        "run.fumble" to setOf("carrier", "concept"),
        "run.loss" to setOf("carrier", "concept", "loss", "stop"),
        "run.stuffed" to setOf("carrier", "concept", "stop"),
        "run.breakaway" to setOf("carrier", "concept", "yards", "yardage"),
        "run.big" to setOf("carrier", "concept", "yards", "yardage", "stop"),
        "run.gain" to setOf("carrier", "concept", "yards", "yardage", "stop"),
        "run.tackle" to setOf("tackler"),
        "fg.good" to setOf("kicker", "distance"),
        "fg.miss" to setOf("kicker", "distance"),
        "fg.no_kicker" to emptySet(),
        "punt" to setOf("punter", "gross", "return"),
        "punt.touchback" to setOf("punter"),
        "punt.no_punter" to setOf("gross"),
        "punt.return" to setOf("ret"),
        "punt.return.named" to setOf("ret", "returner"),
    )

    /** Pieces spliced into another line rather than read on their own. */
    private val fragments = setOf("run.tackle", "punt.return", "punt.return.named")

    @Test
    fun `every line has between eight and fifteen ways to say it`() {
        assertEquals(allowed.keys, PlayLines.templates.keys)
        PlayLines.templates.forEach { (key, ways) ->
            assertTrue(ways.size in 8..15, "$key has ${ways.size} variants")
            assertEquals(ways.size, ways.distinct().size, "$key repeats itself")
        }
    }

    @Test
    fun `templates only use the slots their line fills and read as sentences`() {
        PlayLines.templates.forEach { (key, ways) ->
            ways.forEach { way ->
                assertTrue(TemplateBook.slots(way).all { it in allowed.getValue(key) }, "$key: $way")
                if (key in fragments) {
                    assertTrue(way.startsWith(", ") && (way.last().isLetterOrDigit() || way.endsWith("}")),
                        "a fragment splices into a sentence: $key: $way")
                } else {
                    assertTrue(way.endsWith(".") || way.endsWith("!"), "$key: $way")
                }
                // {who} can be "the offense" when nobody is named, so it never opens a sentence.
                assertTrue(!way.startsWith("{who}"), "$key: $way")
            }
        }
    }

    @Test
    fun `every turnover says so in words the game log highlights`() {
        // The game log marks a turnover by reading the line (Screens.kt eventOf).
        PlayLines.templates.getValue("pass.interception").forEach {
            assertTrue("intercept" in it.lowercase(), it)
        }
        PlayLines.templates.getValue("run.fumble").forEach {
            assertTrue("fumble" in it.lowercase(), it)
        }
    }

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }

    private fun context(): PlayContext {
        val off = league.teams.first { it.abbrev == "KC" }
        val def = league.teams.first { it.abbrev == "SEA" }
        val offScheme = SchemeCatalog[off.offenseScheme]
        val defScheme = SchemeCatalog[def.defenseScheme]
        return PlayContext(
            offense = OffenseUnit.from(DepthChart.auto(league.roster(off.id), offScheme), Personnel.P_11, offScheme),
            defense = DefenseUnit.from(
                DepthChart.auto(league.roster(def.id), defScheme), DefensiveFront.FOUR_THREE_OVER, defScheme),
            state = PlayState(),
        )
    }

    @Test
    fun `the words never move the play`() {
        val ctx = context()
        val calls = SplitMixRng(4L)
        repeat(500) { i ->
            val off = PlayCaller.offense(ctx, calls)
            val def = PlayCaller.defense(ctx, calls)
            val a = PlaySimulator.simPlay(ctx.copy(narration = SplitMixRng(1L)), off, def, SplitMixRng(i.toLong()))
            val b = PlaySimulator.simPlay(ctx.copy(narration = SplitMixRng(99L)), off, def, SplitMixRng(i.toLong()))
            assertEquals(a.copy(log = a.log.copy(narrative = "")), b.copy(log = b.log.copy(narrative = "")),
                "snap $i played differently when worded differently")
        }
    }

    @Test
    fun `a game tells the same kind of play more than one way`() {
        val ctx = context().copy(narration = SplitMixRng(7L))
        val rng = SplitMixRng(8L)
        val completions = (1..600).mapNotNull {
            val r = PlaySimulator.simPlay(ctx, PlayCaller.offense(ctx, rng), PlayCaller.defense(ctx, rng), rng)
            r.log.narrative.takeIf { r.outcome == PlayOutcome.COMPLETION && r.penalty == null }
        }
        val names = league.players.map { it.lastName } + league.players.map { it.name } +
            PassConcept.entries.map { it.label }
        val shapes = completions.map { line ->
            names.sortedByDescending { it.length }.fold(line.replace(Regex("-?[0-9]+( yards?)?"), "#")) { s, n ->
                s.replace(n, "@")
            }
        }.toSet()
        assertTrue(shapes.size >= 6, "only ${shapes.size} ways of telling a completion: $shapes")
    }

    @Test
    fun `yardage reads in the singular for one yard`() {
        assertEquals("1 yard", PlayLines.yardage(1))
        assertEquals("7 yards", PlayLines.yardage(7))
    }
}
