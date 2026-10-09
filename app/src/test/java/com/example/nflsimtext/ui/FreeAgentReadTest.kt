package com.example.nflsimtext.ui

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Transaction
import com.nflsim.engine.model.TransactionKind
import com.nflsim.engine.ratings.Scouting
import com.nflsim.engine.ratings.ScoutingLens
import com.nflsim.engine.season.DynastyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A free agent or squad man reads with the user's staff's years only if that staff coached him (SPEC 4.6). */
class FreeAgentReadTest {

    private val league = LeagueGenerator.generate(2026, 43L)
    private val dynasty = DynastyEngine.start(league, 2026, 43L, league.teams[3].id)
    private val user = dynasty.userTeamId
    private val other = league.teams[9].id
    private val newcomer = ScoutingLens.ownPlayer(0, Scouting.department(dynasty.team, league), league.tuning.scouting)

    /** A veteran of [other]'s, five years there, now on the street. */
    private val released = league.roster(other).first().copy(teamId = null, contract = null,
        status = PlayerStatus.FREE_AGENT, yearsWithClub = 5)

    private fun releasedBy(club: Int) = dynasty.copy(league = league.copy(transactions = listOf(
        Transaction(2026, 4, TransactionKind.RELEASED, club, released.id.v, released.name, released.position.label),
    )))

    @Test
    fun `a man another club released reads as a newcomer, however long he was there`() {
        val d = releasedBy(other.v)
        assertFalse(knownBy(d, released))
        assertEquals(newcomer, lensOf(d, released).confidence, 0f)
    }

    @Test
    fun `a man the user released reads as his staff knew him`() {
        val d = releasedBy(user.v)
        assertTrue(knownBy(d, released))
        assertTrue(lensOf(d, released).confidence > newcomer)
    }

    @Test
    fun `on the market, his last club decides it`() {
        assertTrue(knownBy(dynasty, released, lastClub = user))
        assertFalse(knownBy(dynasty, released, lastClub = other))
        assertFalse("nobody's man at all", knownBy(dynasty, released))
    }

    @Test
    fun `the user's own squad man is his, another club's is not`() {
        // A squad man has no club of his own: only the squad list says whose he is.
        val squad = released.copy(status = PlayerStatus.PRACTICE_SQUAD)
        val ours = dynasty.copy(league = league.copy(teams = league.teams.map {
            if (it.id == user) it.copy(practiceSquad = it.practiceSquad + squad.id) else it
        }))
        assertTrue(knownBy(ours, squad))
        val theirs = dynasty.copy(league = league.copy(transactions = listOf(
            Transaction(2026, 4, TransactionKind.TO_SQUAD, user.v, squad.id.v, squad.name, squad.position.label),
        )))
        assertFalse("on another club's squad, whatever the wire says he was", knownBy(theirs, squad))
    }
}
