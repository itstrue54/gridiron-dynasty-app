package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.model.PlayerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Signing a free agent and releasing a player, with the cap watching. */
class TransactionsTest {

    private val league = LeagueGenerator.generate(2026, 4L)
    private val club = league.teams.first().id

    /** A league where somebody is actually available: release a man first. */
    private fun withFreeAgent(): Pair<League, Int> {
        val spare = league.roster(club).minByOrNull { it.capHit(2026) }!!
        val after = Transactions.release(league, club, spare.id) as Transactions.Outcome.Done
        return after.league to spare.id.v
    }

    @Test
    fun `releasing a player frees his spot and charges the dead money`() {
        val (after, id) = withFreeAgent()
        val roster = after.team(club).roster
        assertEquals(Transactions.ROSTER_LIMIT - 1, roster.size)
        assertTrue(roster.none { it.v == id }, "he should be off the roster")
        val man = after.player(com.nflsim.engine.model.PlayerId(id))
        assertEquals(null, man.teamId)
        assertEquals(PlayerStatus.FREE_AGENT, man.status)
        assertEquals(null, man.contract, "a released player is not paid by the club any more")
        assertTrue(after.team(club).finances.deadMoney >= league.team(club).finances.deadMoney,
            "dead money should not fall when a contract is torn up")
    }

    @Test
    fun `signing a free agent puts him on the roster at the minimum`() {
        val (open, id) = withFreeAgent()
        val outcome = Transactions.sign(open, club, com.nflsim.engine.model.PlayerId(id))
        val done = outcome as Transactions.Outcome.Done
        val man = done.league.player(com.nflsim.engine.model.PlayerId(id))
        assertEquals(club, man.teamId)
        assertEquals(PlayerStatus.ACTIVE, man.status)
        assertEquals(1, man.contract?.years)
        assertEquals(Transactions.askingPrice, man.contract?.baseSalary?.sum())
        assertEquals(Transactions.ROSTER_LIMIT, done.league.team(club).roster.size)
        assertTrue(done.note.contains(man.lastName))
    }

    @Test
    fun `a full roster cannot sign anybody`() {
        val (open, id) = withFreeAgent()
        val full = (Transactions.sign(open, club, com.nflsim.engine.model.PlayerId(id))
            as Transactions.Outcome.Done).league
        val other = Transactions.freeAgents(full).firstOrNull()
        if (other == null) return
        val refused = Transactions.sign(full, club, other.id)
        assertTrue(refused is Transactions.Outcome.Refused)
        assertTrue((refused as Transactions.Outcome.Refused).reason.contains("full"),
            "it should say the roster is full: ${refused.reason}")
    }

    @Test
    fun `another club's player is not a free agent`() {
        val theirs = league.roster(league.teams[1].id).first()
        val refused = Transactions.sign(league, club, theirs.id)
        assertTrue(refused is Transactions.Outcome.Refused)
        assertTrue((refused as Transactions.Outcome.Refused).reason.contains("under contract"))
    }

    @Test
    fun `a club cannot release a player it does not employ`() {
        val theirs = league.roster(league.teams[1].id).first()
        val refused = Transactions.release(league, club, theirs.id)
        assertTrue(refused is Transactions.Outcome.Refused)
    }

    @Test
    fun `the free agent list is nobody's player`() {
        val (open, _) = withFreeAgent()
        val available = Transactions.freeAgents(open)
        assertTrue(available.isNotEmpty())
        assertTrue(available.all { it.teamId == null && it.status != PlayerStatus.RETIRED })
    }
}
