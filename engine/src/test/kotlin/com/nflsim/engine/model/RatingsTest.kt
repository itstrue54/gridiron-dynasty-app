package com.nflsim.engine.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RatingsTest {

    @Test
    fun `uniform sets every rating`() {
        val r = Ratings.uniform(70)
        RatingId.entries.forEach { assertEquals(70, r[it]) }
    }

    @Test
    fun `of applies named overrides on top of a base`() {
        val r = Ratings.of(60, RatingId.SPEED to 94, RatingId.AWARENESS to 71)
        assertEquals(94, r[RatingId.SPEED])
        assertEquals(71, r[RatingId.AWARENESS])
        assertEquals(60, r[RatingId.STRENGTH])
    }

    @Test
    fun `with does not mutate the original`() {
        val original = Ratings.uniform(50)
        val changed = original.with(RatingId.SPEED to 99)
        assertEquals(50, original[RatingId.SPEED])
        assertEquals(99, changed[RatingId.SPEED])
    }

    @Test
    fun `values are clamped to the legal range`() {
        val r = Ratings.uniform(50).with(RatingId.SPEED to 250, RatingId.STRENGTH to -40)
        assertEquals(99, r[RatingId.SPEED])
        assertEquals(1, r[RatingId.STRENGTH])
    }

    @Test
    fun `applyDelta moves physical and mental at different rates`() {
        val before = Ratings.uniform(70)
        val after = before.applyDelta(physical = -4f, mental = 3f)
        assertEquals(66, after[RatingId.SPEED])
        assertEquals(73, after[RatingId.AWARENESS])
        assertEquals(70, after[RatingId.CATCHING])
    }

    @Test
    fun `equality compares contents not identity`() {
        assertEquals(Ratings.uniform(50), Ratings.uniform(50))
        assertEquals(Ratings.uniform(50).hashCode(), Ratings.uniform(50).hashCode())
        assertNotEquals(Ratings.uniform(50), Ratings.uniform(51))
    }

    @Test
    fun `wrong array size is rejected`() {
        assertFailsWith<IllegalArgumentException> { Ratings(IntArray(5)) }
    }

    @Test
    fun `survives a serialization round trip`() {
        val original = Ratings.of(64, RatingId.THROW_POWER to 92, RatingId.SCRAMBLING to 41)
        val restored = Json.decodeFromString<Ratings>(Json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `every rating id is covered by the physical or mental split or neither`() {
        // Not every rating is one or the other - most are skills. This just
        // asserts the two sets do not overlap, which would double-count in aging.
        val overlap = RatingId.PHYSICAL intersect RatingId.MENTAL
        assertTrue(overlap.isEmpty(), "overlapping rating categories: $overlap")
    }
}
