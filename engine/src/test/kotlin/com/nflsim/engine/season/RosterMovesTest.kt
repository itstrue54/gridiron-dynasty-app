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

    /** The league with nobody on the street, so a club's own squad is all it has to promote from. */
    private val noStreet = league.copy(players = league.players.filterNot(PracticeSquads::unattached))

    private fun hurt(l: League, team: TeamId, weeks: Int): Pair<League, PlayerId> {
        val man = l.roster(team).first { it.position == com.nflsim.engine.model.Position.WR }
        return l.copy(players = l.players.map { if (it.id == man.id) it.copy(injuryWeeks = weeks) else it }) to man.id
    }

    @Test
    fun `a long injury goes on reserve and the club fills his place from its squad`() {
        val (l, id) = hurt(noStreet, other, RosterMoves.IR_WEEKS + 2)
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
    fun `a man on the street better than the club's squad is signed instead`() {
        // A street receiver with the league's best receiving ratings.
        val star = league.players.filter { it.position == com.nflsim.engine.model.Position.WR }
            .maxByOrNull { com.nflsim.engine.ratings.overall(it) }!!
        val walkOn = Transactions.freeAgents(league).first { it.position == com.nflsim.engine.model.Position.WR }
        val l0 = league.copy(players = league.players.map { if (it.id == walkOn.id) it.copy(ratings = star.ratings) else it })
        val (l, _) = hurt(l0, other, RosterMoves.IR_WEEKS + 2)
        val after = RosterMoves.afterWeek(l, l.tuning, user)
        val arrived = after.team(other).roster.toSet() - l.team(other).roster.toSet()
        assertEquals(setOf(walkOn.id), arrived, "the street's man is better than anyone on the squad")
        assertTrue(walkOn.id !in Transactions.freeAgents(after).map { it.id })
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

    @Test
    fun `the wire records the reserve move and the signing, dated by the next game`() {
        val (l, id) = hurt(noStreet, other, RosterMoves.IR_WEEKS + 2)
        // After week 5 is played there are 13 games left, so the moves come before week 6.
        val after = RosterMoves.afterWeek(l, l.tuning, user, weeksLeft = Schedule.WEEKS - 5)
        val wire = after.transactions
        val ir = wire.single { it.kind == com.nflsim.engine.model.TransactionKind.INJURED_RESERVE }
        assertEquals(id.v, ir.player)
        assertEquals(other.v, ir.team)
        assertEquals(6, ir.week)
        val promoted = wire.single { it.kind == com.nflsim.engine.model.TransactionKind.PROMOTED }
        assertEquals(Transactions.price(Schedule.WEEKS - 5), promoted.amount, "he is paid for the weeks left")
    }

    @Test
    fun `a club reaches into another's squad for a man clearly better`() {
        // A star receiver is parked on a third club's practice squad.
        val star = league.players.filter { it.position == com.nflsim.engine.model.Position.WR }
            .maxByOrNull { com.nflsim.engine.ratings.overall(it) }!!
        val keeper = league.teams[2].id
        val parked = league.copy(
            players = league.players.map {
                if (it.id == star.id) it.copy(
                    teamId = null, contract = null, status = PlayerStatus.PRACTICE_SQUAD) else it
            },
            teams = league.teams.map {
                when (it.id) {
                    star.teamId -> it.copy(roster = it.roster - star.id)
                    keeper -> it.copy(practiceSquad = it.practiceSquad.drop(1) + star.id)
                    else -> it
                }
            },
        )
        // And the club next door loses every receiver it has for the year.
        val short = parked.copy(players = parked.players.map {
            if (it.teamId == other && it.position == com.nflsim.engine.model.Position.WR)
                it.copy(injuryWeeks = RosterMoves.IR_WEEKS + 4) else it
        })

        val after = RosterMoves.afterWeek(short, short.tuning, user)
        assertEquals(other, after.player(star.id).teamId, "the best man available should be signed")
        assertTrue(star.id !in after.team(keeper).practiceSquad, "and leave the squad he was parked on")
        val line = after.transactions.single {
            it.kind == com.nflsim.engine.model.TransactionKind.SIGNED_OFF_SQUAD && it.player == star.id.v
        }
        assertEquals(keeper.v, line.other, "the wire should say whose squad he came off")
    }

    @Test
    fun `the user's places wait for him, unless he hands them to the front office`() {
        fun afterAWeek(delegate: Boolean): Int {
            var d = DynastyEngine.start(league, 2026, 9L, user).copy(frontOfficeRoster = delegate)
            // His receivers hurt for the year, before a week is played.
            d = d.copy(league = d.league.copy(players = d.league.players.map {
                if (it.teamId == user && it.position == com.nflsim.engine.model.Position.WR)
                    it.copy(injuryWeeks = RosterMoves.IR_WEEKS + 6) else it
            }))
            d = DynastyEngine.advance(d)
            return RosterMoves.active(d.league, user).size
        }
        assertTrue(afterAWeek(delegate = false) < Transactions.ROSTER_LIMIT, "left to the user, the places wait")
        assertEquals(Transactions.ROSTER_LIMIT, afterAWeek(delegate = true), "handed over, they are filled")
    }
}
