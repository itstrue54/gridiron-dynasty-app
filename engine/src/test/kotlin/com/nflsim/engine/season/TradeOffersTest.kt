package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.narrative.Banter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SPEC 8.4: clubs call the user with offers they would make, before the deadline. */
class TradeOffersTest {

    /** The weeks up to the deadline, each with its calls. */
    private val weeks: List<Pair<Dynasty, List<TradeOffers.Offer>>> by lazy {
        val league = LeagueGenerator.generate(2026, 33L)
        var d = DynastyEngine.start(league, 2026, 33L, league.teams.first().id).let { DynastyEngine.advance(it) }
        val out = mutableListOf<Pair<Dynasty, List<TradeOffers.Offer>>>()
        while (TradeDesk.open(d)) {
            out += d to TradeOffers.thisWeek(d)
            d = DynastyEngine.advance(d)
        }
        out += d to TradeOffers.thisWeek(d)
        out
    }

    private val calls get() = weeks.flatMap { (d, offers) -> offers.map { d to it } }

    @Test
    fun `clubs call in the season, a few a week, and not after the deadline`() {
        assertTrue(calls.size >= 3, "only ${calls.size} calls before the deadline")
        weeks.forEach { (d, offers) ->
            assertTrue(offers.size <= d.league.tuning.ai.tradeOffersMax, "${offers.size} calls in week ${d.week}")
        }
        assertEquals(emptyList(), weeks.last().second, "past the deadline nobody calls")
    }

    @Test
    fun `a call is a deal the club would make and the user's club would count as fair`() {
        calls.forEach { (d, offer) ->
            val book = TradeDesk.inSeason(d)
            val user = d.league.team(d.userTeamId)
            val p = offer.proposal
            assertEquals(setOf(offer.target), p.give, "they ask for one man")
            assertTrue(p.get.isNotEmpty() || p.getPicks.isNotEmpty(), "and offer something")
            assertTrue(TradeDesk.evaluate(book, d.userTeamId, p).accepted, "they'd take their own offer: $p")
            val worth = TradeDesk.value(book, d.league.player(com.nflsim.engine.model.PlayerId(offer.target)), user)
            val back = p.get.sumOf { TradeDesk.value(book, d.league.player(com.nflsim.engine.model.PlayerId(it)), user).toDouble() } +
                p.getPicks.sumOf { TradeDesk.value(book, it, user).toDouble() }
            assertTrue(back >= worth, "worth $worth to us, offered $back")
            // The pitch names him and comes from the calling club's GM.
            val caller = d.league.team(p.partner)
            assertTrue(offer.pitch.speaker.contains(caller.abbrev), offer.pitch.speaker)
            assertTrue(Banter.templates.getValue("gm.offer.${Banter.tone(caller)}").any { w ->
                w.replace("{player}", d.league.player(com.nflsim.engine.model.PlayerId(offer.target)).lastName)
                    .replace("{club}", user.nickname) == offer.pitch.line
            }, offer.pitch.line)
        }
    }

    @Test
    fun `the same week brings the same calls, and taking one is making it`() {
        val (d, offers) = weeks.first { it.second.isNotEmpty() }
        assertEquals(offers, TradeOffers.thisWeek(d))
        val (after, _) = assertNotNull(TradeDesk.makeInSeason(d, offers.first().proposal))
        assertEquals(offers.first().proposal.partner,
            after.league.player(com.nflsim.engine.model.PlayerId(offers.first().target)).teamId)
    }

    @Test
    fun `only the user's own men go on the block, and a man who leaves comes off it`() {
        val d = weeks.first().first
        val mine = d.league.roster(d.userTeamId).first()
        val theirs = d.league.players.first { it.teamId != null && it.teamId != d.userTeamId }
        val on = TradeOffers.setOnBlock(TradeOffers.setOnBlock(d, mine.id.v, true), theirs.id.v, true)
        assertEquals(setOf(mine.id.v), TradeOffers.block(on))
        assertEquals(emptySet(), TradeOffers.block(TradeOffers.setOnBlock(on, mine.id.v, false)))
        // Traded away, he is no longer the user's to offer.
        val gone = on.copy(league = on.league.copy(players = on.league.players.map {
            if (it.id == mine.id) it.copy(teamId = theirs.teamId) else it
        }))
        assertEquals(emptySet(), TradeOffers.block(gone))
    }

    @Test
    fun `men on the block draw calls, first and in their own words, and still no lowballs`() {
        var d = weeks.first().first
        val book = TradeDesk.inSeason(d)
        val user = d.league.team(d.userTeamId)
        // His three best, as his own club counts them.
        val block = d.league.roster(d.userTeamId).filter { it.status == com.nflsim.engine.model.PlayerStatus.ACTIVE }
            .sortedByDescending { TradeDesk.value(book, it, user) }.take(3)
        block.forEach { d = TradeOffers.setOnBlock(d, it.id.v, true) }
        var about = 0
        var week = d
        while (TradeDesk.open(week)) {
            val offers = TradeOffers.thisWeek(week)
            val plain = TradeOffers.thisWeek(week.copy(tradeBlock = emptySet()))
            // Calls about the block come ahead of any other.
            assertEquals(offers.sortedByDescending { it.onBlock }, offers)
            offers.filter { it.onBlock }.forEach { o ->
                about++
                assertTrue(o.target in week.tradeBlock)
                assertTrue(Banter.templates.getValue("gm.offer.block").any { w ->
                    w.replace("{player}", week.league.player(com.nflsim.engine.model.PlayerId(o.target)).lastName)
                        .replace("{club}", user.nickname) == o.pitch.line
                }, o.pitch.line)
                val b = TradeDesk.inSeason(week)
                assertTrue(TradeDesk.evaluate(b, week.userTeamId, o.proposal).accepted)
                val worth = TradeDesk.value(b, week.league.player(com.nflsim.engine.model.PlayerId(o.target)), user)
                val back = o.proposal.get.sumOf { TradeDesk.value(b, week.league.player(com.nflsim.engine.model.PlayerId(it)), user).toDouble() } +
                    o.proposal.getPicks.sumOf { TradeDesk.value(b, it, user).toDouble() }
                assertTrue(back >= worth, "a lowball for a man on the block")
            }
            assertTrue(plain.none { it.onBlock })
            week = DynastyEngine.advance(week).copy(tradeBlock = d.tradeBlock)
        }
        assertTrue(about >= 3, "only $about calls about three men on the block")
    }

    @Test
    fun `a club that could use a man on the block finds a way to pay for him`() {
        // Every club calls that can: what is left is whether it finds a package.
        val d0 = weeks.first().first
        val tuning = d0.league.tuning.let { it.copy(ai = it.ai.copy(tradeBlockCallChance = 1f, tradeOffersMax = 32)) }
        val d = d0.copy(league = d0.league.copy(tuning = tuning))
        val book = TradeDesk.inSeason(d)
        val user = d.league.team(d.userTeamId)
        // Each of his ten best, one at a time: every one draws a call from
        // somebody. (Building offers from only the six pieces the user's club
        // would want most left one of them with none: the pieces that fit a
        // club's budget were never among them.)
        val best = d.league.roster(d.userTeamId).filter { it.status == com.nflsim.engine.model.PlayerStatus.ACTIVE }
            .sortedByDescending { TradeDesk.value(book, it, user) }.take(10)
        val called = best.count { man ->
            TradeOffers.thisWeek(TradeOffers.setOnBlock(d, man.id.v, true)).any { it.onBlock && it.target == man.id.v }
        }
        assertEquals(best.size, called, "only $called of his ten best drew a call from the block")
    }

    @Test
    fun `clubs call at the draft room too, by the same rules, and taking one is making it`() {
        var d = weeks.last().first
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        val pause = com.nflsim.engine.offseason.OffseasonEngine.runToContracts(d).decide(null)
        val book = pause.tradeBook
        val user = book.league.team(d.userTeamId)
        val offers = TradeOffers.atDraft(d, book)
        assertTrue(offers.isNotEmpty(), "nobody called at the draft room")
        assertEquals(offers, TradeOffers.atDraft(d, book), "the same draft, the same calls")
        offers.forEach { o ->
            assertTrue(TradeDesk.evaluate(book, d.userTeamId, o.proposal).accepted, "${o.proposal}")
            val man = book.players.first { it.id.v == o.target }
            assertEquals(d.userTeamId, man.teamId)
            val back = o.proposal.get.sumOf { id -> TradeDesk.value(book, book.players.first { it.id.v == id }, user).toDouble() } +
                o.proposal.getPicks.sumOf { TradeDesk.value(book, it, user).toDouble() }
            assertTrue(back >= TradeDesk.value(book, man, user), "no lowballs at the draft either")
        }
        val traded = assertNotNull(pause.trade(offers.first().proposal))
        assertEquals(offers.first().proposal.partner, traded.tradeBook.players.first { it.id.v == offers.first().target }.teamId)
    }

    @Test
    fun `a club tight against the cap still gets calls, from clubs that fit under it`() {
        var d = weeks.last().first
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        val open = com.nflsim.engine.offseason.OffseasonEngine.runToContracts(d).decide(null).tradeBook
        // Dead money that leaves the user's club $5M under the cap. Each
        // club's richest packages all add more than that, so a club that
        // tried only those, before the cap, never called.
        val left = 5_000
        val room = TradeDesk.room(open, d.userTeamId, emptyList(), emptyList())
        val book = open.copy(deadMoney = open.deadMoney + (d.userTeamId.v to (open.deadMoney[d.userTeamId.v] ?: 0) + room - left))
        assertEquals(left, TradeDesk.room(book, d.userTeamId, emptyList(), emptyList()))
        val offers = TradeOffers.atDraft(d, book)
        assertTrue(offers.isNotEmpty(), "nobody called a club $5M under the cap")
        offers.forEach { o ->
            assertTrue(TradeDesk.evaluate(book, d.userTeamId, o.proposal).accepted, "${o.proposal}")
            val men = book.players.filter { it.id.v in o.proposal.get }
            assertTrue(TradeDesk.fitsCap(book, d.userTeamId, book.players.filter { it.id.v == o.target }, men))
        }
    }
}
