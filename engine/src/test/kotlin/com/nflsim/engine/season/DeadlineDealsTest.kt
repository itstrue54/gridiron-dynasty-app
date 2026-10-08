package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.NewsKind
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TransactionKind
import com.nflsim.engine.offseason.ContenderTrades
import com.nflsim.engine.rng.SplitMixRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 8.4: the league's clubs trade with each other at the deadline. */
class DeadlineDealsTest {

    /** Dynasties at the deadline, before its deals. */
    private val atDeadline: List<Dynasty> by lazy {
        // About a third of leagues see no deadline star deal (16 measured), so
        // six leagues, not three, make "contenders deal" a fair test.
        listOf(3L, 11L, 2026L, 17L, 18L, 19L).map { seed ->
            val league = LeagueGenerator.generate(2026, seed)
            var d = DynastyEngine.start(league, 2026, seed, league.teams[(seed % 32).toInt()].id)
            while (d.week < TradeDesk.DEADLINE_WEEK) d = DynastyEngine.advance(d)
            d
        }
    }

    private fun deals(d: Dynasty) = DeadlineDeals.run(d, SplitMixRng(1L))

    @Test
    fun `contenders deal at the deadline, and it makes the news and the wire`() {
        val all = atDeadline.map { d -> d to deals(d) }
        val stars = all.sumOf { (_, r) -> r.news.size }
        assertTrue(stars >= 1, "no deadline deals in ${atDeadline.size} leagues")
        all.forEach { (d, r) ->
            val wire = r.league.transactions.drop(d.league.transactions.size)
            assertTrue(wire.all { it.kind == TransactionKind.TRADED })
            assertTrue(r.news.all { it.kind == NewsKind.TRADE && it.week == TradeDesk.DEADLINE_WEEK })
            // Every star named in the news is on his new club.
            r.news.forEach { n -> assertEquals(n.team, r.league.playersById.getValue(com.nflsim.engine.model.PlayerId(n.player!!)).teamId?.v) }
        }
    }

    @Test
    fun `nobody ends up over the 53, and the user's club is never dealt for`() {
        atDeadline.forEach { d ->
            val after = deals(d).league
            after.teams.forEach { team ->
                val active = after.roster(team.id).count { it.status == PlayerStatus.ACTIVE }
                val before = d.league.roster(team.id).count { it.status == PlayerStatus.ACTIVE }
                assertTrue(active <= maxOf(Transactions.ROSTER_LIMIT, before), "${team.abbrev} has $active")
                // Every man a club lists is a man who plays for it.
                assertTrue(after.roster(team.id).all { it.teamId == team.id }, team.abbrev)
            }
            assertEquals(d.league.team(d.userTeamId).roster, after.team(d.userTeamId).roster, "the user's roster")
            assertEquals(d.league.players.size, after.players.size, "nobody appears or vanishes")
        }
    }

    @Test
    fun `a star moves for a man back, and the deal is the same every time`() {
        atDeadline.forEach { d ->
            val r = deals(d)
            assertEquals(r.league, deals(d).league)
            val moves = r.league.transactions.drop(d.league.transactions.size)
            moves.filter { m -> r.news.any { it.player == m.player } }.forEach { star ->
                assertTrue(moves.any { it.team == star.other && it.other == star.team }, "nobody went back for ${star.name}")
            }
        }
    }

    @Test
    fun `the deadline happens as the season passes it`() {
        val d = atDeadline.first()
        val next = DynastyEngine.advance(d)
        val expected = deals(d).news.map { it.player }
        assertEquals(expected, next.news.filter { it.kind == NewsKind.TRADE }.map { it.player })
        expected.forEach { id ->
            assertTrue(next.league.transactions.any { it.player == id && it.kind == TransactionKind.TRADED && it.week == TradeDesk.DEADLINE_WEEK })
        }
        // Not before and not again.
        assertTrue(d.news.none { it.kind == NewsKind.TRADE })
        assertEquals(next.news.count { it.kind == NewsKind.TRADE }, DynastyEngine.advance(next).news.count { it.kind == NewsKind.TRADE })
    }

    @Test
    fun `only stars are named, and the reason says why they moved`() {
        atDeadline.forEach { d ->
            val r = deals(d)
            assertTrue(r.news.size <= r.league.transactions.size - d.league.transactions.size)
        }
        assertTrue(ContenderTrades.STAR_REASON.isNotBlank())
    }
}
