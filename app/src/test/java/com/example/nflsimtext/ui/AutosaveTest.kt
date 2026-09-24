package com.example.nflsimtext.ui

import com.nflsim.engine.season.DynastyPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SPEC 9.1: an autosave every time the phase turns over, and not otherwise. */
class AutosaveTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `the playoffs turning over writes an autosave, a week does not`() = runBlocking {
        val store = DynastyStore(temp.root)
        store.newDynasty(seed = 7L)
        val autos = { store.saves.cards().count { it.auto } }
        assertEquals("a new dynasty has nothing to fall back on yet", 0, autos())

        // A week inside the regular season is not a phase turning over.
        store.advance()
        assertEquals(DynastyPhase.REGULAR_SEASON, store.dynasty!!.phase)
        assertEquals(0, autos())

        // Play the season out: week 18 hands over to the playoffs.
        while (store.dynasty!!.phase == DynastyPhase.REGULAR_SEASON) store.advance()
        assertEquals(DynastyPhase.PLAYOFFS, store.dynasty!!.phase)
        assertEquals("the playoffs starting should leave a save behind", 1, autos())

        // And the playoffs handing over to the offseason leaves another.
        while (store.dynasty!!.phase == DynastyPhase.PLAYOFFS) store.advance()
        assertEquals(DynastyPhase.OFFSEASON, store.dynasty!!.phase)
        assertEquals(2, autos())

        // The autosave reads back as the dynasty it was taken from.
        val card = store.saves.cards().last { it.auto }
        assertEquals(store.dynasty!!.team.name, card.club)
        assertTrue(store.saves.cards().any { !it.auto && it.slot == store.slot })
    }

    @Test
    fun `the year turning over through the draft room writes an autosave too`() = runBlocking {
        val store = DynastyStore(temp.root)
        store.newDynasty(seed = 8L)
        while (store.dynasty!!.phase != DynastyPhase.OFFSEASON) store.advance()
        val before = store.saves.cards().count { it.auto }
        store.openDraftRoom()
        store.goToCamp()
        store.finishCamp(null)
        assertEquals(DynastyPhase.REGULAR_SEASON, store.dynasty!!.phase)
        assertEquals("the new year should leave a save behind", before + 1, store.saves.cards().count { it.auto })
    }
}
