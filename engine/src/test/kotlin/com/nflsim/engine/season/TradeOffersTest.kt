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
}
