package com.nflsim.engine.ratings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/SPEC.md 4.6: a club sees a band, not a rating, and is wrong consistently. */
class ScoutingLensTest {

    @Test
    fun `the band closes as a club sees more of a player`() {
        val widths = listOf(0f, 0.3f, 0.6f, 0.85f).map { confidence ->
            val view = ScoutingLens.of(playerId = 41, viewerId = 7, confidence = confidence, t = SC).view(50)
            view.high - view.low
        }
        assertTrue(widths.zipWithNext().all { (wide, narrow) -> narrow < wide },
            "bands should narrow with confidence, got $widths")
        assertTrue(widths.first() >= 20,
            "an unscouted player should span most of a grade, got ${widths.first()}")
    }

    @Test
    fun `a club that has seen enough reads the rating itself`() {
        val view = ScoutingLens.of(41, 7, SC.exactAt, SC).view(80)
        assertEquals(80, view.point)
        assertEquals("80", view.text)
        assertTrue(view.exact)
    }

    @Test
    fun `a scout who is wrong about a man stays wrong`() {
        val first = ScoutingLens.of(41, 7, 0.5f, SC).view(80)
        val second = ScoutingLens.of(41, 7, 0.5f, SC).view(80)
        assertEquals(first, second, "the same club and player should read the same every time")
    }

    @Test
    fun `two clubs are wrong about him differently`() {
        val reads = (1..12).map { club -> ScoutingLens.of(41, club, 0.5f, SC).view(80).point }
        assertTrue(reads.distinct().size > 1, "every club read him as $reads")
    }

    @Test
    fun `confidence never reaches certainty`() {
        assertTrue(ScoutingLens.ownPlayer(yearsWithClub = 20, scoutingDept = 99, t = SC) <= SC.ceiling)
        assertTrue(ScoutingLens.of(41, 7, 1f, SC).confidence <= SC.ceiling)
    }

    @Test
    fun `a prospect's exposure sits below what a club knows of its own`() {
        val exposures = (1..200).map { ScoutingLens.prospectExposure(it, SC) }
        assertTrue(exposures.all { it in 0.05f..0.5f }, "exposure ranged ${exposures.min()}..${exposures.max()}")
        assertTrue(exposures.distinct().size > 50, "exposure should vary by prospect")
    }

    @Test
    fun `a new league knows its veterans and is unsure of its rookies`() {
        val league = com.nflsim.engine.gen.LeagueGenerator.generate(2026, 2026L)
        val reads = league.teams.flatMap { team ->
            league.roster(team.id).map { it.clubYears to ScoutingLens.ownPlayer(it.clubYears, team.staff.scoutingDept, SC) }
        }
        val exact = reads.count { it.second >= SC.exactAt }.toFloat() / reads.size
        assertTrue(exact in 0.15f..0.6f,
            "a club should know some of its roster and be unsure of the rest; %.2f was exact".format(exact))
        val rookies = reads.filter { it.first == 0 }
        assertTrue(rookies.isNotEmpty() && rookies.all { it.second < SC.gradeAt },
            "a man who has never played for the club should not be a known quantity")
        assertTrue(reads.filter { it.first >= 3 }.all { it.second >= SC.exactAt },
            "three years in the building should settle it")
    }

    @Test
    fun `the band is worth reading`() {
        // Away from the ends of the scale, where clamping squeezes a band.
        val inside = (1..400).count { player ->
            val truth = 40 + player % 40
            val view = ScoutingLens.of(player, player % 7, 0.3f, SC).view(truth)
            truth in view.low..view.high
        }
        assertTrue(inside >= 360,
            "a published band should hold the rating; it held $inside of 400")
    }

    @Test
    fun `an estimate stays inside the ratings scale`() {
        val views = (0..99).map { ScoutingLens.of(it, 3, 0f, SC).view(it) }
        assertTrue(views.all { it.low >= 0 && it.high <= 99 && it.point in 0..99 })
    }
}

private val SC = com.nflsim.engine.tuning.TuningTable.REALISTIC.scouting
