package com.nflsim.engine.rng

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitMixRngTest {

    @Test
    fun `same seed produces the same sequence`() {
        val a = SplitMixRng(42L)
        val b = SplitMixRng(42L)
        repeat(1000) {
            assertEquals(a.nextLong(), b.nextLong())
        }
    }

    @Test
    fun `different seeds produce different sequences`() {
        val a = SplitMixRng(42L)
        val b = SplitMixRng(43L)
        val matches = (0 until 1000).count { a.nextLong() == b.nextLong() }
        assertTrue(matches < 5, "streams should not overlap, matched $matches times")
    }

    @Test
    fun `split is deterministic for the same label`() {
        val parentA = SplitMixRng(7L)
        val parentB = SplitMixRng(7L)
        val childA = parentA.split("year=2026|week=7|game=3")
        val childB = parentB.split("year=2026|week=7|game=3")
        repeat(500) {
            assertEquals(childA.nextLong(), childB.nextLong())
        }
    }

    @Test
    fun `split gives unrelated streams for different labels`() {
        val parent = SplitMixRng(7L)
        val a = parent.split("week=7|game=3")
        val b = parent.split("week=7|game=4")
        val matches = (0 until 500).count { a.nextLong() == b.nextLong() }
        assertTrue(matches < 3, "sibling streams should not overlap, matched $matches times")
    }

    @Test
    fun `split does not depend on how much the parent has been used`() {
        val fresh = SplitMixRng(99L).split("draft")
        val used = SplitMixRng(99L).also { repeat(10_000) { _ -> it.nextLong() } }.split("draft")
        repeat(100) {
            assertEquals(fresh.nextLong(), used.nextLong())
        }
    }

    @Test
    fun `nextInt stays inside the bound`() {
        val rng = SplitMixRng(123L)
        repeat(100_000) {
            val v = rng.nextInt(53)
            assertTrue(v in 0..52, "nextInt(53) returned $v")
        }
    }

    @Test
    fun `nextInt is roughly uniform`() {
        val rng = SplitMixRng(555L)
        val buckets = IntArray(10)
        val n = 200_000
        repeat(n) { buckets[rng.nextInt(10)]++ }
        val expected = n / 10.0
        buckets.forEachIndexed { i, count ->
            val drift = abs(count - expected) / expected
            assertTrue(drift < 0.05, "bucket $i off by ${(drift * 100).toInt()}%")
        }
    }

    @Test
    fun `nextFloat stays in zero to one`() {
        val rng = SplitMixRng(2024L)
        repeat(100_000) {
            val v = rng.nextFloat()
            assertTrue(v >= 0f && v < 1f, "nextFloat returned $v")
        }
    }

    @Test
    fun `gaussian has roughly the requested mean and spread`() {
        val rng = SplitMixRng(31337L)
        val n = 100_000
        var sum = 0.0
        var sumSq = 0.0
        repeat(n) {
            val v = rng.gaussian(0f, 1f).toDouble()
            sum += v
            sumSq += v * v
        }
        val mean = sum / n
        val sd = kotlin.math.sqrt(sumSq / n - mean * mean)
        assertTrue(abs(mean) < 0.02, "mean was $mean, expected near 0")
        assertTrue(abs(sd - 1.0) < 0.02, "sd was $sd, expected near 1")
    }

    @Test
    fun `gaussian honours mean and sd arguments`() {
        val rng = SplitMixRng(8L)
        val n = 100_000
        var sum = 0.0
        repeat(n) { sum += rng.gaussian(75f, 8f).toDouble() }
        val mean = sum / n
        assertTrue(abs(mean - 75.0) < 0.2, "mean was $mean, expected near 75")
    }
}
