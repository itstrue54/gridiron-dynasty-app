package com.example.nflsimtext.ui

import com.nflsim.engine.season.PracticeSquads
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The roster moves a player's card makes: to the squad, off it, up to the 53. */
class RosterMovesTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `a full squad refuses a man, and the club is left as it was`() = runBlocking {
        val store = DynastyStore(temp.root)
        store.newDynasty(seed = 7L)
        val before = store.dynasty!!
        assertEquals("a new league's squads start full", PracticeSquads.SIZE, before.team.practiceSquad.size)
        val young = before.league.roster(before.userTeamId).first { !PracticeSquads.isVeteran(it) && it.injuryWeeks == 0 }
        store.releaseToPracticeSquad(young.id.v)
        assertTrue(store.message!!.contains("full"))
        assertEquals(before.league, store.dynasty!!.league)
    }

    @Test
    fun `a man released from the squad makes room, and the next goes down to it and up again`() = runBlocking {
        val store = DynastyStore(temp.root)
        store.newDynasty(seed = 7L)
        val d0 = store.dynasty!!
        val squadMan = d0.league.player(d0.team.practiceSquad.first())
        store.releaseFromPracticeSquad(squadMan.id.v)
        assertTrue(squadMan.id !in store.dynasty!!.team.practiceSquad)

        // A young man of the squad man's position, so the squad's count by position has room for him.
        val d1 = store.dynasty!!
        val young = d1.league.roster(d1.userTeamId)
            .firstOrNull { !PracticeSquads.isVeteran(it) && it.injuryWeeks == 0 && it.position == squadMan.position }
            ?: d1.league.roster(d1.userTeamId).first { !PracticeSquads.isVeteran(it) && it.injuryWeeks == 0 }
        store.releaseToPracticeSquad(young.id.v)
        val d2 = store.dynasty!!
        assertTrue(store.message!!, young.id in d2.team.practiceSquad)
        assertTrue(young.id !in d2.team.roster)

        // Promoted from his card, he is back on the 53.
        store.signFreeAgent(young.id.v)
        assertTrue(store.message!!, young.id in store.dynasty!!.team.roster)
    }
}
