package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TransactionKind
import com.nflsim.engine.offseason.OffseasonEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC 8.4: the user's trades, answered as the league's clubs answer each other. */
class TradeDeskTest {

    private val dynasty: Dynasty by lazy {
        val league = LeagueGenerator.generate(2026, 21L)
        DynastyEngine.start(league, 2026, 21L, league.teams.first().id).let { DynastyEngine.advance(it) }
    }
    private val book by lazy { TradeDesk.inSeason(dynasty) }
    private val user get() = dynasty.userTeamId
    private val partner by lazy { dynasty.league.teams[1] }

    private fun active(team: com.nflsim.engine.model.TeamId) =
        book.players.filter { it.teamId == team && it.status == PlayerStatus.ACTIVE }

    /** The user's man the partner rates highest, and the partner's it rates lowest. */
    private val mineBest by lazy { active(user).maxBy { TradeDesk.value(book, it, partner) } }
    private val theirWorst by lazy { active(partner.id).minBy { TradeDesk.value(book, it, partner) } }
    private val theirBest by lazy { active(partner.id).maxBy { TradeDesk.value(book, it, partner) } }
    private val mineWorst by lazy { active(user).minBy { TradeDesk.value(book, it, partner) } }

    @Test
    fun `a club takes a deal that suits it and turns down one that doesn't, saying by how much`() {
        val good = TradeDesk.Proposal(partner.id, give = setOf(mineBest.id.v), get = setOf(theirWorst.id.v))
        val v = TradeDesk.evaluate(book, user, good)
        assertTrue(v.accepted, "${v.reasons}")
        assertEquals(0f, v.shortBy)

        val bad = TradeDesk.Proposal(partner.id, give = setOf(mineWorst.id.v), get = setOf(theirBest.id.v))
        val no = TradeDesk.evaluate(book, user, bad)
        assertFalse(no.accepted)
        assertTrue(no.shortBy > 0f && no.reasons.any { "want more" in it }, "${no.reasons}")
    }

    @Test
    fun `only your own men and picks go, only theirs come, and not with yourself`() {
        val mine = book.picks.first { it.owner == user.v }
        val theirs = book.picks.first { it.owner == partner.id.v }
        assertFalse(TradeDesk.evaluate(book, user, TradeDesk.Proposal(partner.id, give = setOf(theirWorst.id.v))).accepted)
        assertFalse(TradeDesk.evaluate(book, user, TradeDesk.Proposal(partner.id, get = setOf(mineWorst.id.v))).accepted)
        assertFalse(TradeDesk.evaluate(book, user, TradeDesk.Proposal(partner.id, givePicks = listOf(theirs))).accepted)
        assertFalse(TradeDesk.evaluate(book, user, TradeDesk.Proposal(partner.id, getPicks = listOf(mine))).accepted)
        assertFalse(TradeDesk.evaluate(book, user, TradeDesk.Proposal(user, give = setOf(mineWorst.id.v))).accepted)
        assertFalse(TradeDesk.evaluate(book, user, TradeDesk.Proposal(partner.id)).accepted, "nothing on the table")
    }

    @Test
    fun `a full roster can't take on more men than it sends`() {
        val full = book.copy(rosterLimit = active(user).size)
        val two = active(partner.id).sortedBy { TradeDesk.value(book, it, partner) }.take(2).map { it.id.v }.toSet()
        val v = TradeDesk.evaluate(full, user, TradeDesk.Proposal(partner.id, give = setOf(mineBest.id.v), get = two))
        assertFalse(v.accepted)
        assertTrue(v.reasons.any { "Make room first" in it }, "${v.reasons}")
    }

    @Test
    fun `a club with no room can't take on a contract`() {
        // Their dead money eats every dollar of their room.
        val broke = book.copy(deadMoney = book.deadMoney + (partner.id.v to 1_000_000))
        val v = TradeDesk.evaluate(broke, user, TradeDesk.Proposal(partner.id, give = setOf(mineBest.id.v)))
        // Already over, a club may not go further over; taking a paid man on is further over.
        assertFalse(v.accepted)
        assertTrue(v.reasons.any { "over the cap" in it }, "${v.reasons}")
    }

    @Test
    fun `a trade in the season moves the men, their contracts, the dead money and the picks, and goes on the wire`() {
        val pick = book.picks.first { it.owner == user.v && it.round >= 5 }
        val proposal = TradeDesk.Proposal(partner.id, give = setOf(mineBest.id.v), get = setOf(theirWorst.id.v), givePicks = listOf(pick))
        val (after, made) = assertNotNull(TradeDesk.makeInSeason(dynasty, proposal))
        val league = after.league
        assertEquals(partner.id, league.player(mineBest.id).teamId)
        assertEquals(user, league.player(theirWorst.id).teamId)
        assertEquals(mineBest.contract, league.player(mineBest.id).contract, "contracts move as they stand")
        assertTrue(mineBest.id in league.team(partner.id).roster && mineBest.id !in league.team(user).roster)
        assertTrue(theirWorst.id in league.team(user).roster && theirWorst.id !in league.team(partner.id).roster)
        val owed = mineBest.contract?.deadCap(league.year)?.thisYear ?: 0
        assertEquals(dynasty.league.team(user).finances.deadMoney + owed, league.team(user).finances.deadMoney)
        assertEquals(partner.id.v, league.picks.first { it.year == pick.year && it.round == pick.round && it.original == pick.original }.owner)
        val wire = league.transactions.drop(dynasty.league.transactions.size)
        assertEquals(2, wire.count { it.kind == TransactionKind.TRADED })
        assertEquals(1, made.pickTrades.size)
        assertEquals(league.players.size, dynasty.league.players.size, "nobody appears or vanishes")
    }

    @Test
    fun `the deadline closes trades after week nine`() {
        var d = dynasty
        while (d.week <= TradeDesk.DEADLINE_WEEK) {
            assertTrue(TradeDesk.open(d))
            d = DynastyEngine.advance(d)
        }
        assertFalse(TradeDesk.open(d))
        val proposal = TradeDesk.Proposal(partner.id, give = setOf(mineBest.id.v), get = setOf(theirWorst.id.v))
        assertNull(TradeDesk.makeInSeason(d, proposal), "past the deadline, no deal")
    }

    @Test
    fun `a pick traded at the draft room is drafted by its new owner`() {
        var d = dynasty
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        val pause = OffseasonEngine.runToContracts(d).decide(null)
        val tb = pause.tradeBook
        val first = tb.picks.first { it.owner == d.userTeam && it.year == pause.year && it.round == 1 }
        // Our first-rounder for plenty of their later picks: a club that likes
        // picks takes it.
        val theirs = tb.picks.filter { it.owner == partner.id.v && it.year == pause.year && it.round >= 4 }.take(2)
        val proposal = TradeDesk.Proposal(partner.id, givePicks = listOf(first), getPicks = theirs)
        val verdict = TradeDesk.evaluate(tb, d.userTeamId, proposal)
        assertTrue(verdict.accepted, "the partner turned it down: $verdict")
        val traded = assertNotNull(pause.trade(proposal))
        assertEquals(partner.id.v, traded.tradeBook.picks.first { it.year == first.year && it.round == 1 && it.original == first.original }.owner)
        // The draft honours it: the partner makes that first-round pick, not us.
        val (_, report) = traded.finish()
        val slot = report.draftPicks.first { it.round == 1 && it.original == first.original }
        assertEquals(partner.id.v, slot.team)
        // And we make theirs. (draftPicks is the first round; yourPicks is all of ours.)
        assertTrue(report.yourPicks.any { it.original == partner.id.v && it.round >= 4 }, "${report.yourPicks}")
    }
}
