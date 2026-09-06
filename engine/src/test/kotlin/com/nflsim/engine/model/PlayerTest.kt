package com.nflsim.engine.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerTest {

    private fun back(archetype: Archetype = Archetype.ONE_CUT_ZONE) = Player(
        id = PlayerId(1),
        firstName = "Dallas",
        lastName = "Reeve",
        position = Position.RB,
        archetype = archetype,
        birthYear = 2002,
        heightIn = 71,
        weightLb = 214,
        college = "Fresno State",
        ratings = Ratings.of(70, RatingId.SPEED to 91, RatingId.VISION to 88),
        traits = HiddenTraits.AVERAGE,
    )

    @Test
    fun `name joins first and last`() {
        assertEquals("Dallas Reeve", back().name)
    }

    @Test
    fun `age is relative to the league year`() {
        assertEquals(24, back().age(2026))
    }

    @Test
    fun `ratings are readable through the player`() {
        assertEquals(91, back()[RatingId.SPEED])
    }

    @Test
    fun `an archetype from the wrong position group is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            back().copy(archetype = Archetype.MAN_PRESS)
        }
        assertEquals(true, error.message?.contains("MAN_PRESS"))
    }

    @Test
    fun `survives a serialization round trip`() {
        val original = back()
        val restored = Json.decodeFromString<Player>(Json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `archetypes are offered only for the matching position`() {
        val rbOptions = Archetype.forPosition(Position.RB)
        assertEquals(true, Archetype.ONE_CUT_ZONE in rbOptions)
        assertEquals(false, Archetype.MAN_PRESS in rbOptions)
    }
}
