package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The user's club makes its own free-agency offers. */
class FreeAgencyPauseTest {

    private val season: Dynasty by lazy {
        val league = LeagueGenerator.generate(2026, 33L)
        var d = DynastyEngine.start(league, 2026, 33L, league.teams.first().id)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d
    }

    private fun pause() = OffseasonEngine.runToContracts(season).toFreeAgency(null)

    @Test
    fun `the market is listed with what each man is worth and asks`() {
        val fa = pause()
        assertTrue(fa.candidates.size > 50, "a market of ${fa.candidates.size}")
        fa.candidates.take(20).forEach {
            assertTrue(it.opening > it.market, "a free agent opens above his market: $it")
            assertTrue(fa.advice(it).why.isNotBlank())
        }
    }

    @Test
    fun `a generous offer lands him, and a lowball never does`() {
        val fa = pause()
        val room = fa.capSpace
        // Not a man another club transition-tagged: his club may match any
        // offer (SPEC 8.3), which is a rule of its own with its own tests.
        val target = fa.candidates.first { it.market * 3 / 2 <= room / 2 && it.player.id.v !in fa.state.transitionTags }
        val lowball = fa.candidates.first { it.player.id != target.player.id && it.market * 2 <= room / 2 }
        val offers = listOf(
            FreeAgency.Offer(target.player.id.v, (target.market * 3 / 2), target.years),
            FreeAgency.Offer(lowball.player.id.v, lowball.market / 2, lowball.years),
        )
        val after = fa.decide(offers).state.players.associateBy { it.id }
        assertEquals(fa.userTeam, after.getValue(target.player.id).teamId, "half again his market should sign him")
        assertTrue(after.getValue(lowball.player.id).teamId != fa.userTeam, "half his market never clears his ask")
    }

    @Test
    fun `offers never spend more than the club has`() {
        val fa = pause()
        // An offer on every one of the top twenty at everything the club has.
        val offers = fa.candidates.take(20).map { FreeAgency.Offer(it.player.id.v, fa.capSpace, it.years) }
        val draft = fa.decide(offers)
        val mine = draft.state.players.filter { it.teamId == fa.userTeam }
        val space = CapManagement.spaceFor(mine, fa.year, draft.state.deadMoney[fa.userTeam.v] ?: 0,
            carryover = draft.ctx.league.team(fa.userTeam).finances.carryover)
        assertTrue(space >= 0, "the club went ${-space} over the cap")
        val signedHere = offers.count { o -> mine.any { it.id.v == o.player } }
        assertTrue(signedHere <= 1, "each offer was everything it had, so one at most can land: $signedHere")
    }

    @Test
    fun `leaving free agency to the front office is the old offseason`() {
        val auto = OffseasonEngine.runToDraft(season).finish().first
        val viaPause = pause().decide(null).finish().first
        assertEquals(auto.league.players.map { it.teamId }, viaPause.league.players.map { it.teamId })
    }

    @Test
    fun `making no offers still leaves a full roster for the season`() {
        val fa = pause()
        val next = fa.decide(emptyList()).finish().first
        val roster = next.league.roster(fa.userTeam)
        assertEquals(com.nflsim.engine.season.Transactions.ROSTER_LIMIT, roster.size,
            "camp bodies fill what the market did not")
        assertTrue(roster.none { it.contract == null })
    }

    @Test
    fun `an offer he will take signs him before the market opens`() {
        val fa = pause()
        val c = fa.candidates.first { it.market * 2 <= fa.capSpace }
        val floor = (c.market * fa.reservation(c)).toInt()
        val talk = fa.negotiate(c.player.id.v, floor + 1, c.years)
        assertTrue(talk.signed, talk.note)
        assertTrue(talk.pause.candidates.none { it.player.id == c.player.id }, "he is off the market")
        // And he stays signed through the ten days, whatever anyone else bids.
        val after = talk.pause.decide(emptyList()).state.players.first { it.id == c.player.id }
        assertEquals(fa.userTeam, after.teamId)
    }

    @Test
    fun `under his floor his agent names it, and twice is all he will hear`() {
        val fa = pause()
        val c = fa.candidates.first { it.market * 2 <= fa.capSpace }
        val floor = (c.market * fa.reservation(c)).toInt()
        val first = fa.negotiate(c.player.id.v, floor * 8 / 10, c.years)
        assertTrue(!first.signed && first.note.contains("will not go below"), first.note)
        assertEquals(1, first.pause.talksLeft(c.player.id.v))
        val second = first.pause.negotiate(c.player.id.v, floor * 9 / 10, c.years)
        assertTrue(!second.signed && second.note.contains("done talking"), second.note)
        // Even at his floor now: he has gone to market.
        val third = second.pause.negotiate(c.player.id.v, floor + 1, c.years)
        assertTrue(!third.signed, third.note)
        assertTrue(third.pause.candidates.any { it.player.id == c.player.id }, "still on the market")
    }

    @Test
    fun `a loyal man coming home takes less than a proud stranger`() {
        val fa = pause()
        val c = fa.candidates.first()
        val coming = c.copy(player = c.player.copy(traits = c.player.traits.copy(loyalty = 95, ego = 20)),
            from = fa.userTeam)
        val proud = c.copy(player = c.player.copy(traits = c.player.traits.copy(loyalty = 20, ego = 95)),
            from = null)
        assertTrue(fa.reservation(coming) < fa.reservation(proud),
            "coming home ${fa.reservation(coming)} vs proud ${fa.reservation(proud)}")
    }
}
