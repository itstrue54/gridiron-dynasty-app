package com.nflsim.engine.ratings

import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.HiddenTraits
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.Ratings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverallTest {

    @Test
    fun `every position has weights that sum to one`() {
        for (position in Position.entries) {
            val total = OverallWeights.forPosition(position).values.sum()
            assertTrue(
                abs(total - 1f) < 0.001f,
                "$position weights sum to $total, expected 1.0"
            )
        }
    }

    @Test
    fun `weights only reference ratings that exist`() {
        for (position in Position.entries) {
            val weights = OverallWeights.forPosition(position)
            assertTrue(weights.isNotEmpty(), "$position has no weights")
            weights.keys.forEach { assertTrue(it in RatingId.entries) }
        }
    }

    @Test
    fun `a flat player overalls at his flat rating`() {
        val p = player(Position.QB, Archetype.POCKET_PASSER, Ratings.uniform(72))
        assertEquals(72, overall(p))
    }

    @Test
    fun `overall responds to the ratings that matter at the position`() {
        val accurate = player(
            Position.QB, Archetype.POCKET_PASSER,
            Ratings.of(60, RatingId.THROW_ACC_SHORT to 95, RatingId.THROW_ACC_MID to 95)
        )
        val fast = player(
            Position.QB, Archetype.POCKET_PASSER,
            Ratings.of(60, RatingId.SPEED to 95, RatingId.SCRAMBLING to 95)
        )
        assertTrue(
            overall(accurate) > overall(fast),
            "accuracy should matter more than speed for a QB"
        )
    }

    @Test
    fun `overall changes when the scheme changes`() {
        val powerBack = player(Position.RB, Archetype.POWER_BACK, Ratings.uniform(85))
        val inGap = overall(powerBack, SchemeCatalog["OFF_GAP_POWER"])
        val inZone = overall(powerBack, SchemeCatalog["OFF_WIDE_ZONE"])
        assertTrue(inGap > inZone, "gap $inGap should beat wide zone $inZone for a power back")
    }

    @Test
    fun `scheme fit is reported on the zero to one scale`() {
        val zoneBack = player(Position.RB, Archetype.ONE_CUT_ZONE, Ratings.uniform(80))
        assertEquals(1.0f, schemeFit(zoneBack, SchemeCatalog["OFF_WIDE_ZONE"]))
        assertTrue(schemeFit(zoneBack, SchemeCatalog["OFF_GAP_POWER"]) < 0.8f)
    }

    @Test
    fun `overall stays on the rating scale for every position`() {
        for (position in Position.entries) {
            for (base in listOf(1, 45, 99)) {
                val archetype = Archetype.forPosition(position).first()
                val p = player(position, archetype, Ratings.uniform(base))
                val ovr = overall(p)
                assertTrue(ovr in 1..99, "$position base=$base gave $ovr")
            }
        }
    }

    private fun player(position: Position, archetype: Archetype, ratings: Ratings) = Player(
        id = PlayerId(1),
        firstName = "Test", lastName = "Player",
        position = position, archetype = archetype,
        birthYear = 2000, heightIn = 73, weightLb = 210, college = "Test",
        ratings = ratings,
        traits = HiddenTraits.AVERAGE,
    )
}
