package com.nflsim.data

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Staff
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coaching staffs arrived after M6 shipped. A save written by M6 has no staff
 * field at all, and SPEC 9.1 is explicit that a dynasty game which eats saves
 * on update is a dead dynasty game - so the old shape has to keep loading.
 */
@OptIn(ExperimentalSerializationApi::class)
class SaveMigrationTest {

    /** The on-disk shape SaveFile writes, mirrored so a test can forge an old one. */
    @Serializable
    private data class Envelope(val version: Int, val dynasty: Dynasty)

    private val cbor = Cbor { ignoreUnknownKeys = true }

    private fun v1Bytes(dynasty: Dynasty): ByteArray {
        val stripped = dynasty.copy(
            league = dynasty.league.copy(
                teams = dynasty.league.teams.map { it.copy(staff = Staff.UNASSIGNED) },
                coaches = emptyMap(),
            )
        )
        val raw = cbor.encodeToByteArray(Envelope.serializer(), Envelope(1, stripped))
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private fun dynasty(seed: Long = 2026L): Dynasty {
        val league = LeagueGenerator.generate(2026, seed)
        return DynastyEngine.start(league, 2026, seed, league.teams.first().id)
    }

    @Test
    fun `a save from before staffs existed still loads`() {
        val loaded = SaveFile.decode(v1Bytes(dynasty()))

        assertEquals(32, loaded.league.teams.size)
        assertTrue(loaded.league.teams.none { it.staff == Staff.UNASSIGNED },
            "every team should have been hired a staff on the way in")
    }

    @Test
    fun `migrated staffs point at coaches that actually exist`() {
        val loaded = SaveFile.decode(v1Bytes(dynasty()))

        loaded.league.teams.forEach { team ->
            assertNotNull(loaded.league.coaches[team.staff.headCoach],
                "${team.abbrev} head coach does not resolve")
            team.staff.positionCoaches.forEach { (group, id) ->
                assertNotNull(loaded.league.coaches[id],
                    "${team.abbrev} $group coach does not resolve")
            }
        }
        val ids = loaded.league.coaches.keys
        assertEquals(ids.size, ids.toSet().size, "coach ids must not collide")
    }

    @Test
    fun `the same old save always migrates to the same coaches`() {
        val bytes = v1Bytes(dynasty())
        val a = SaveFile.decode(bytes)
        val b = SaveFile.decode(bytes)

        assertEquals(a.league.coaches, b.league.coaches)
        assertEquals(a.league.teams.map { it.staff }, b.league.teams.map { it.staff })
    }

    @Test
    fun `a current save round trips untouched`() {
        val original = dynasty()
        val loaded = SaveFile.decode(SaveFile.encode(original))

        assertEquals(original.league.teams.map { it.staff }, loaded.league.teams.map { it.staff })
        assertEquals(original.league.coaches, loaded.league.coaches)
    }
}
