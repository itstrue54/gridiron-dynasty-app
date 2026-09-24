package com.example.nflsimtext.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A new dynasty starts with the club the user chose. */
class NewDynastyTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `the league comes first, and nothing is saved until a club is chosen`() = runBlocking {
        val store = DynastyStore(temp.root)
        store.previewLeague(seed = 12L, into = 2)
        val league = store.pendingLeague
        assertNotNull("a league to choose from", league)
        assertNull("no dynasty yet", store.dynasty)
        assertFalse("nothing saved before choosing", store.saves.occupied(2))

        val pick = league!!.teams[17].abbrev
        store.startWith(pick)
        assertEquals(pick, store.dynasty!!.team.abbrev)
        assertTrue("saved into the slot it was started for", store.saves.occupied(2))
        assertNull(store.pendingLeague)
    }

    @Test
    fun `surprise me takes a club, and backing out throws the league away`() = runBlocking {
        val store = DynastyStore(temp.root)
        store.previewLeague(seed = 13L)
        store.cancelPreview()
        assertNull(store.pendingLeague)
        assertNull(store.dynasty)

        store.previewLeague(seed = 13L)
        store.startWith(null)
        assertNotNull(store.dynasty)
    }
}
