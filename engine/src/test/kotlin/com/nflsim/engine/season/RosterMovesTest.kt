package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TeamId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Tuesday wire: reserve, promotions, and who decides them. */
class RosterMovesTest {

    private val league = LeagueGenerator.generate(2026, 9L)
    private val user = league.teams[0].id
    private val other = league.teams[1].id

    private fun hurt(l: League, team: TeamId, weeks: Int): Pair<League, PlayerId> {
        val man = l.roster(team).first { it.position == com.nflsim.engine.model.Position.WR }
        return l.copy(players = l.players.map { if (it.id == man.id) it.copy(injuryWeeks = weeks) else it }) to man.id
    }

    @Test
    fun `a long injury goes on reserve and the club fills his place from its squad`() {
        val (l, id) = hurt(league, other, RosterMoves.IR_WEEKS + 2)
        val squadBefore = l.team(other).practiceSquad.toSet()
        val after = RosterMoves.afterWeek(l, l.tuning, user)
        assertEquals(PlayerStatus.IR, after.player(id).status)
        assertEquals(Transactions.ROSTER_LIMIT, RosterMoves.active(after, other).size)
        val arrived = after.team(other).roster.toSet() - l.team(other).roster.toSet()
        assertEquals(1, arrived.size)
        assertTrue(arrived.single() in squadBefore, "the replacement should come off his own squad")
        assertEquals(PracticeSquads.SIZE, after.team(other).practiceSquad.size, "and the squad is topped up")
    }

    @Test
    fun `a short injury stays on the 53`() {
        val (l, id) = hurt(league, other, RosterMoves.IR_WEEKS - 1)
        val after = RosterMoves.afterWeek(l, l.tuning, user)
        assertEquals(PlayerStatus.ACTIVE, after.player(id).status)
        assertEquals(l.team(other).roster, after.team(other).roster)
    }

    @Test
    fun `the user's club goes on reserve but signs nobody on its own`() {
        val (l, id) = hurt(league, user, RosterMoves.IR_WEEKS)
        val after = RosterMoves.afterWeek(l, l.tuning, user)
        assertEquals(PlayerStatus.IR, after.player(id).status)
        assertEquals(Transactions.ROSTER_LIMIT - 1, RosterMoves.active(after, user).size)
        assertEquals(l.team(user).practiceSquad, after.team(user).practiceSquad)
    }

    @Test
    fun `a healed man comes back, and the club cuts to make room`() {
        val (l, id) = hurt(league, other, RosterMoves.IR_WEEKS)
        val onIr = RosterMoves.afterWeek(l, l.tuning, user)
        val healed = onIr.copy(players = onIr.players.map { if (it.id == id) it.copy(injuryWeeks = 0) else it })
        val after = RosterMoves.afterWeek(healed, healed.tuning, user)
        assertEquals(PlayerStatus.ACTIVE, after.player(id).status)
        assertEquals(Transactions.ROSTER_LIMIT, RosterMoves.active(after, other).size)
    }

    @Test
    fun `the user's healed man waits while his place is filled`() {
        val (l, id) = hurt(league, user, RosterMoves.IR_WEEKS)
        val onIr = RosterMoves.afterWeek(l, l.tuning, user)
        val filled = (Transactions.sign(onIr, user, onIr.team(user).practiceSquad.first()) as Transactions.Outcome.Done).league
        val healed = filled.copy(players = filled.players.map { if (it.id == id) it.copy(injuryWeeks = 0) else it })
        val after = RosterMoves.afterWeek(healed, healed.tuning, user)
        assertEquals(PlayerStatus.IR, after.player(id).status, "no room, so the user decides")
        assertTrue(Transactions.activate(after, user, id) is Transactions.Outcome.Refused)
    }
}
