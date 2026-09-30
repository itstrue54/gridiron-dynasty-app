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
}
