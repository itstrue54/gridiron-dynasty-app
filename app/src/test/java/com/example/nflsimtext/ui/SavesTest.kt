package com.example.nflsimtext.ui

import com.nflsim.data.SaveFile
import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.season.DynastyEngine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SPEC 9.1: five slots, three rotating autosaves, and nothing lost on the way. */
class SavesTest {

    @get:Rule val temp = TemporaryFolder()

    private val dynasty by lazy {
        val league = LeagueGenerator.generate(2026, 4L)
        DynastyEngine.start(league, 2026, 4L, league.teams.first().id)
    }

    @Test
    fun `a slot keeps what it was given, and says what is in it`() {
        val saves = Saves(temp.root)
        assertFalse(saves.any())
        saves.write(2, dynasty)
        assertTrue(saves.occupied(2))

        val card = saves.cards().single()
        assertEquals(2, card.slot)
        assertFalse(card.auto)
        assertEquals(dynasty.team.name, card.club)
        assertEquals(2026, card.year)
        assertTrue(card.summary.contains(dynasty.team.name))
        assertEquals(dynasty.userTeam, saves.load(saves.slotFile(2)).userTeam)
    }

    @Test
    fun `autosaves rotate, and the oldest is the one to go`() {
        val saves = Saves(temp.root)
        repeat(Saves.AUTOSAVES + 2) { n ->
            // Each save is a week further on, so the newest is recognisable,
            // and each is aged so the rotation has an oldest to pick.
            saves.autosave(dynasty.copy(week = n + 1)).setLastModified(1_000L * (n + 1))
        }
        val autos = saves.cards().filter { it.auto }
        assertEquals(Saves.AUTOSAVES, autos.size)
        val weeks = autos.map { it.week }.sorted()
        assertEquals("the three most recent phases should be the ones kept",
            listOf(3, 4, 5), weeks)
    }

    @Test
    fun `the single save older builds wrote becomes slot one, once`() {
        val saves = Saves(temp.root)
        temp.root.mkdirs()
        File(temp.root, "dynasty.sav").writeBytes(SaveFile.encode(dynasty))
        assertTrue(saves.any())

        assertTrue(saves.adoptLegacySave())
        assertTrue(saves.occupied(1))
        assertEquals(dynasty.team.name, saves.cards().first().club)
        // And not again over a slot that now has something in it.
        File(temp.root, "dynasty.sav").writeBytes(SaveFile.encode(dynasty))
        assertFalse(saves.adoptLegacySave())
    }

    @Test
    fun `a save that cannot be read is listed as unreadable rather than hidden`() {
        val saves = Saves(temp.root)
        temp.root.mkdirs()
        saves.slotFile(3).writeBytes(byteArrayOf(1, 2, 3))
        val card = saves.cards().single()
        assertNull(card.club)
        assertEquals("Unreadable", card.summary)
    }
}
