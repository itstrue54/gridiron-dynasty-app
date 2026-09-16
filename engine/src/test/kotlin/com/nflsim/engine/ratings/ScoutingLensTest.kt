package com.nflsim.engine.ratings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/SPEC.md 4.6: a club sees a band, not a rating, and is wrong consistently. */
class ScoutingLensTest {

    @Test
    fun `the band closes as a club sees more of a player`() {
        val widths = listOf(0f, 0.3f, 0.6f, 0.85f).map { confidence ->
            val view = ScoutingLens.of(playerId = 41, viewerId = 7, confidence = confidence).view(50)
            view.high - view.low
        }
        assertTrue(widths.zipWithNext().all { (wide, narrow) -> narrow < wide },
            "bands should narrow with confidence, got $widths")
        assertTrue(widths.first() >= 20,
            "an unscouted player should span most of a grade, got ${widths.first()}")
    }

    @Test
    fun `a club that has seen enough reads the rating itself`() {
        val view = ScoutingLens.of(41, 7, ScoutingLens.EXACT_AT).view(80)
        assertEquals(80, view.point)
        assertEquals("80", view.text)
        assertTrue(view.exact)
    }

    @Test
    fun `a scout who is wrong about a man stays wrong`() {
        val first = ScoutingLens.of(41, 7, 0.5f).view(80)
        val second = ScoutingLens.of(41, 7, 0.5f).view(80)
        assertEquals(first, second, "the same club and player should read the same every time")
    }

    @Test
    fun `two clubs are wrong about him differently`() {
        val reads = (1..12).map { club -> ScoutingLens.of(41, club, 0.5f).view(80).point }
        assertTrue(reads.distinct().size > 1, "every club read him as $reads")
    }

    @Test
    fun `confidence never reaches certainty`() {
        assertTrue(ScoutingLens.ownPlayer(yearsWithClub = 20, scoutingDept = 99) <= ScoutingLens.CEILING)
        assertTrue(ScoutingLens.of(41, 7, 1f).confidence <= ScoutingLens.CEILING)
    }

    @Test
    fun `a prospect's exposure sits below what a club knows of its own`() {
        val exposures = (1..200).map { ScoutingLens.prospectExposure(it) }
        assertTrue(exposures.all { it in 0.05f..0.5f }, "exposure ranged ${exposures.min()}..${exposures.max()}")
        assertTrue(exposures.distinct().size > 50, "exposure should vary by prospect")
    }

    @Test
    fun `an estimate stays inside the ratings scale`() {
        val views = (0..99).map { ScoutingLens.of(it, 3, 0f).view(it) }
        assertTrue(views.all { it.low >= 0 && it.high <= 99 && it.point in 0..99 })
    }
}
