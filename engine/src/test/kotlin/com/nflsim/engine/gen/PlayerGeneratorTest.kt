package com.nflsim.engine.gen

import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.rng.SplitMixRng
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerGeneratorTest {

    @Test
    fun `generated players land on their target overall`() {
        val rng = SplitMixRng(11L)
        var worst = 0
        for (position in Position.entries) {
            for (target in listOf(48, 60, 72, 85, 92)) {
                repeat(5) {
                    val p = PlayerGenerator.generate(PlayerId(1), position, target, 2026, rng)
                    val miss = abs(overall(p) - target)
                    if (miss > worst) worst = miss
                    assertTrue(miss <= 2, "$position target $target produced ${overall(p)}")
                }
            }
        }
        assertTrue(worst <= 2, "worst miss was $worst")
    }

    @Test
    fun `archetype shapes the rating sheet`() {
        val rng = SplitMixRng(22L)
        var powerWins = 0
        var elusiveWins = 0
        repeat(60) {
            val power = PlayerGenerator.generate(
                PlayerId(1), Position.RB, 80, 2026, rng, archetype = Archetype.POWER_BACK
            )
            val elusive = PlayerGenerator.generate(
                PlayerId(2), Position.RB, 80, 2026, rng, archetype = Archetype.ELUSIVE
            )
            if (power[RatingId.TRUCKING] > elusive[RatingId.TRUCKING]) powerWins++
            if (elusive[RatingId.ELUSIVENESS] > power[RatingId.ELUSIVENESS]) elusiveWins++
        }
        assertTrue(powerWins >= 52, "power backs should truck harder ($powerWins/60)")
        assertTrue(elusiveWins >= 52, "elusive backs should be shiftier ($elusiveWins/60)")
    }

    @Test
    fun `quarterbacks do not have useful coverage ratings`() {
        val rng = SplitMixRng(33L)
        repeat(40) {
            val qb = PlayerGenerator.generate(PlayerId(1), Position.QB, 85, 2026, rng)
            assertTrue(qb[RatingId.MAN_COVERAGE] < 60, "QB man coverage ${qb[RatingId.MAN_COVERAGE]}")
            assertTrue(qb[RatingId.KICK_POWER] < 45, "QB kick power ${qb[RatingId.KICK_POWER]}")
        }
    }

    @Test
    fun `kickers can kick and nobody else can`() {
        val rng = SplitMixRng(44L)
        val kicker = PlayerGenerator.generate(PlayerId(1), Position.K, 78, 2026, rng)
        val guard = PlayerGenerator.generate(PlayerId(2), Position.LG, 78, 2026, rng)
        assertTrue(kicker[RatingId.KICK_ACCURACY] > 65)
        assertTrue(guard[RatingId.KICK_ACCURACY] < 45)
    }

    @Test
    fun `measurements are plausible for the position`() {
        val rng = SplitMixRng(55L)
        repeat(30) {
            val ol = PlayerGenerator.generate(PlayerId(1), Position.LT, 80, 2026, rng)
            assertTrue(ol.heightIn in 74..82, "LT height ${ol.heightIn}")
            assertTrue(ol.weightLb in 288..340, "LT weight ${ol.weightLb}")

            val cb = PlayerGenerator.generate(PlayerId(2), Position.CB, 80, 2026, rng)
            assertTrue(cb.heightIn in 67..76, "CB height ${cb.heightIn}")
            assertTrue(cb.weightLb in 168..218, "CB weight ${cb.weightLb}")
        }
    }

    @Test
    fun `ages are in a working range`() {
        val rng = SplitMixRng(66L)
        repeat(200) {
            val p = PlayerGenerator.generate(PlayerId(1), Position.LB, 75, 2026, rng)
            assertTrue(p.age(2026) in 21..38, "age ${p.age(2026)}")
        }
    }

    @Test
    fun `every archetype has a profile entry`() {
        val missing = Archetype.entries.toSet() - ArchetypeProfile.covered()
        assertEquals(emptySet(), missing, "archetypes with no generation profile: $missing")
    }

    @Test
    fun `generation is deterministic`() {
        val a = PlayerGenerator.generate(PlayerId(1), Position.WR, 82, 2026, SplitMixRng(99L))
        val b = PlayerGenerator.generate(PlayerId(1), Position.WR, 82, 2026, SplitMixRng(99L))
        assertEquals(a, b)
    }
}
