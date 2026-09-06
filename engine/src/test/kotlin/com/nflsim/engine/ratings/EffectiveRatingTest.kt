package com.nflsim.engine.ratings

import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.HiddenTraits
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.Ratings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EffectiveRatingTest {

    private val wideZone = SchemeCatalog["OFF_WIDE_ZONE"]
    private val gapPower = SchemeCatalog["OFF_GAP_POWER"]

    private fun back(
        archetype: Archetype,
        base: Int = 80,
        versatility: Int = 50,
        yearsInSystem: Int = 0,
        fatigue: Int = 0,
        morale: Int = 75,
    ) = Player(
        id = PlayerId(1),
        firstName = "Test", lastName = "Back",
        position = Position.RB,
        archetype = archetype,
        birthYear = 2002, heightIn = 71, weightLb = 214, college = "Test",
        ratings = Ratings.uniform(base),
        traits = HiddenTraits.AVERAGE.copy(schemeVersatility = versatility),
        yearsInSystem = yearsInSystem,
        fatigue = fatigue,
        morale = morale,
    )

    private fun rate(p: Player, scheme: Scheme, id: RatingId = RatingId.CARRYING) =
        effectiveRating(p, id, RatingContext.forPlayer(p, scheme))

    @Test
    fun `the right back in the right scheme outplays the wrong one`() {
        val zoneBack = back(Archetype.ONE_CUT_ZONE)
        val powerBack = back(Archetype.POWER_BACK)
        val zoneInWideZone = rate(zoneBack, wideZone)
        val powerInWideZone = rate(powerBack, wideZone)
        assertTrue(
            zoneInWideZone > powerInWideZone,
            "one-cut back ($zoneInWideZone) should beat power back ($powerInWideZone) in wide zone"
        )
    }

    @Test
    fun `the same player is worth more in the scheme that suits him`() {
        val powerBack = back(Archetype.POWER_BACK)
        val inGap = rate(powerBack, gapPower)
        val inZone = rate(powerBack, wideZone)
        assertTrue(inGap > inZone, "power back: gap $inGap should beat wide zone $inZone")
        assertTrue(inGap - inZone >= 7, "scheme should meaningfully change a player's value")
    }

    @Test
    fun `time in the system helps`() {
        val rookie = back(Archetype.ONE_CUT_ZONE, yearsInSystem = 0)
        val veteran = back(Archetype.ONE_CUT_ZONE, yearsInSystem = 3)
        assertTrue(rate(veteran, wideZone) > rate(rookie, wideZone))
    }

    @Test
    fun `familiarity stops improving after three years`() {
        val third = back(Archetype.ONE_CUT_ZONE, yearsInSystem = 3)
        val tenth = back(Archetype.ONE_CUT_ZONE, yearsInSystem = 10)
        assertEquals(rate(third, wideZone), rate(tenth, wideZone))
    }

    @Test
    fun `versatility softens a bad fit but does not fix it`() {
        val rigid = back(Archetype.POWER_BACK, versatility = 5)
        val flexible = back(Archetype.POWER_BACK, versatility = 95)
        val ideal = back(Archetype.ONE_CUT_ZONE, versatility = 5)
        assertTrue(rate(flexible, wideZone) > rate(rigid, wideZone))
        assertTrue(rate(flexible, wideZone) < rate(ideal, wideZone))
    }

    @Test
    fun `versatility never boosts a player who already fits`() {
        val rigid = back(Archetype.ONE_CUT_ZONE, versatility = 0)
        val flexible = back(Archetype.ONE_CUT_ZONE, versatility = 99)
        assertEquals(rate(rigid, wideZone), rate(flexible, wideZone))
    }

    @Test
    fun `fatigue costs the player something`() {
        val fresh = back(Archetype.ONE_CUT_ZONE, fatigue = 0)
        val gassed = back(Archetype.ONE_CUT_ZONE, fatigue = 100)
        val drop = rate(fresh, wideZone) - rate(gassed, wideZone)
        assertTrue(drop >= 8, "a fully gassed player should lose real ground, lost $drop")
    }

    @Test
    fun `morale moves the needle a little`() {
        val unhappy = back(Archetype.ONE_CUT_ZONE, morale = 0)
        val happy = back(Archetype.ONE_CUT_ZONE, morale = 100)
        assertTrue(rate(happy, wideZone) >= rate(unhappy, wideZone))
    }

    @Test
    fun `emphasised ratings get a bump`() {
        // OFF_WIDE_ZONE emphasises VISION and ACCELERATION for backs
        val p = back(Archetype.ONE_CUT_ZONE)
        val emphasised = rate(p, wideZone, RatingId.VISION)
        val plain = rate(p, wideZone, RatingId.CARRYING)
        assertTrue(emphasised > plain, "emphasised $emphasised should exceed plain $plain")
    }

    @Test
    fun `a perfect fit is worth roughly ten points over a genuinely bad one`() {
        // The design target from SPEC 4.9: scheme has to be able to beat talent.
        // Measured at the real extreme - a 1.00 fit against a 0.40 fit. A power
        // back in an Air Raid is the worst pairing the shipped schemes contain.
        val airRaid = SchemeCatalog["OFF_AIR_RAID"]
        val ideal = back(Archetype.RECEIVING_BACK, base = 85, yearsInSystem = 3)
        val miscast = back(Archetype.POWER_BACK, base = 85, yearsInSystem = 3)
        assertEquals(1.00f, airRaid.fitFor(ideal.position, ideal.archetype))
        assertEquals(0.40f, airRaid.fitFor(miscast.position, miscast.archetype))
        val swing = rate(ideal, airRaid) - rate(miscast, airRaid)
        assertTrue(swing in 9..16, "expected a 9-16 point swing on an 85 base, got $swing")
    }

    @Test
    fun `even a merely suboptimal fit costs a few points`() {
        // 1.00 against 0.55 - not miscast, just not ideal. Should still show up.
        val ideal = back(Archetype.ONE_CUT_ZONE, base = 85, yearsInSystem = 3)
        val okay = back(Archetype.POWER_BACK, base = 85, yearsInSystem = 3)
        val swing = rate(ideal, wideZone) - rate(okay, wideZone)
        assertTrue(swing in 5..12, "expected a 5-12 point swing, got $swing")
    }

    @Test
    fun `results always stay on the rating scale`() {
        for (archetype in Archetype.forPosition(Position.RB)) {
            for (base in listOf(1, 50, 99)) {
                for (fatigue in listOf(0, 100)) {
                    val p = back(archetype, base = base, fatigue = fatigue)
                    for (scheme in SchemeCatalog.offensive) {
                        val v = rate(p, scheme)
                        assertTrue(v in 1..99, "$archetype base=$base got $v in ${scheme.id}")
                    }
                }
            }
        }
    }
}
