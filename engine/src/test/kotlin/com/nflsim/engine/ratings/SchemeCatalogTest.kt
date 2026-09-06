package com.nflsim.engine.ratings

import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.model.RatingId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchemeCatalogTest {

    @Test
    fun `all shipped schemes load`() {
        assertEquals(16, SchemeCatalog.all.size)
        assertEquals(7, SchemeCatalog.offensive.size)
        assertEquals(9, SchemeCatalog.defensive.size)
    }

    @Test
    fun `scheme ids are unique`() {
        val ids = SchemeCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate scheme ids: $ids")
    }

    @Test
    fun `every archetype key names a real archetype in the right group`() {
        val groupNames = PositionGroup.entries.map { it.name }.toSet()
        val positionNames = Position.entries.map { it.name }.toSet()
        val archetypesByName = Archetype.entries.associateBy { it.name }

        for (scheme in SchemeCatalog.all) {
            for ((key, fits) in scheme.archetypeFit) {
                assertTrue(
                    key in groupNames || key in positionNames,
                    "${scheme.id}: '$key' is neither a position nor a position group"
                )
                val expectedGroup = if (key in groupNames) PositionGroup.valueOf(key) else null
                for (archetypeName in fits.keys) {
                    val archetype = assertNotNull(
                        archetypesByName[archetypeName],
                        "${scheme.id}: unknown archetype '$archetypeName'"
                    )
                    if (expectedGroup != null) {
                        assertEquals(
                            expectedGroup, archetype.group,
                            "${scheme.id}: $archetypeName listed under $key"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `every emphasis entry names a real rating`() {
        val ratingNames = RatingId.entries.map { it.name }.toSet()
        for (scheme in SchemeCatalog.all) {
            for ((key, ratings) in scheme.ratingEmphasis) {
                for (r in ratings) {
                    assertTrue(r in ratingNames, "${scheme.id} / $key: unknown rating '$r'")
                }
            }
        }
    }

    @Test
    fun `fit values are in range`() {
        for (scheme in SchemeCatalog.all) {
            for ((key, fits) in scheme.archetypeFit) {
                for ((archetype, fit) in fits) {
                    assertTrue(fit in 0f..1f, "${scheme.id} / $key / $archetype: fit $fit out of range")
                }
            }
        }
    }

    @Test
    fun `each scheme has at least one ideal fit somewhere`() {
        for (scheme in SchemeCatalog.all) {
            val best = scheme.archetypeFit.values.flatMap { it.values }.maxOrNull() ?: 0f
            assertTrue(best >= 0.95f, "${scheme.id} has no archetype it truly wants (best $best)")
        }
    }

    @Test
    fun `lookup falls back from position to group`() {
        val wideZone = SchemeCatalog["OFF_WIDE_ZONE"]
        // schemes.json declares OL as a group, not LT specifically
        assertEquals(1.0f, wideZone.fitFor(Position.LT, Archetype.ZONE_BLOCKER))
        assertEquals(0.50f, wideZone.fitFor(Position.RG, Archetype.POWER_MAULER))
    }

    @Test
    fun `an unlisted archetype gets the neutral fit`() {
        val airRaid = SchemeCatalog["OFF_AIR_RAID"]
        assertEquals(Scheme.NEUTRAL_FIT, airRaid.fitFor(Position.K, Archetype.SPECIALIST))
    }

    @Test
    fun `unknown scheme id fails loudly`() {
        val error = runCatching { SchemeCatalog["OFF_NOT_A_SCHEME"] }.exceptionOrNull()
        assertTrue(error != null && error.message!!.contains("OFF_NOT_A_SCHEME"))
    }
}
