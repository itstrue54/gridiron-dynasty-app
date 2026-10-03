package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Player
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 7.1: a year's rise comes harder near the top; decline and breakouts do not change. */
class GrowthTaperTest {

    private val league = LeagueGenerator.generate(2026, 5L)
    private val on = TuningTable.REALISTIC.progression
    private val off = on.copy(growthAtCeiling = 1f)

    /** Each player's change over 300 seeds, with and without the taper. */
    private fun changes(p: Player, snaps: Int = 900) = (0 until 300L.toInt()).map { i ->
        val ctx = { t: TuningTable.Progression -> Progression.Context(2026, coaching = 70, snaps = snaps, tuning = t) }
        Progression.progress(p, ctx(on), SplitMixRng(i.toLong())) to Progression.progress(p, ctx(off), SplitMixRng(i.toLong()))
    }

    private val young = league.players.filter { it.age(2026) <= 24 }
    private val star = young.maxBy { overall(it) }
    private val prospect = young.filter { overall(it) <= on.growthTaperFrom }.maxBy { overall(it) }
    private val veteran = league.players.filter { it.age(2026) >= 33 }.maxBy { overall(it) }

    @Test
    fun `a young star rises less with the taper, and never falls further for it`() {
        assertTrue(overall(star) > on.growthTaperFrom, "the fixture is above the taper: ${overall(star)}")
        val pairs = changes(star)
        val rose = pairs.filter { (_, untapered) -> untapered.delta > 0 && untapered.note == null }
        assertTrue(rose.isNotEmpty())
        assertTrue(rose.sumOf { it.first.delta } < rose.sumOf { it.second.delta }, "the taper took nothing off")
        pairs.forEach { (tapered, untapered) -> if (untapered.delta <= 0) assertEquals(untapered.delta, tapered.delta) }
    }

    @Test
    fun `below the taper's start, and in decline, nothing changes`() {
        changes(prospect).forEach { (tapered, untapered) -> assertEquals(untapered.player, tapered.player) }
        changes(veteran).forEach { (tapered, untapered) ->
            if (untapered.delta <= 0) assertEquals(untapered.player, tapered.player)
        }
    }

    @Test
    fun `a breakout is never tapered`() {
        val leaps = changes(star).filter { it.second.note == "took a leap" }
        assertTrue(leaps.isNotEmpty(), "no breakout in 300 years")
        // The leap itself is whole: the tapered year still gains at least the breakout's base.
        leaps.forEach { (tapered, _) -> assertTrue(tapered.delta >= on.breakoutBase.toInt() - 4, "a breakout year of ${tapered.delta}") }
    }
}
