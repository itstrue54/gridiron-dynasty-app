package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SPEC 8.3: the user decides how far his club matches for a transition-tagged man. */
class TransitionMatchTest {

    private val season: Dynasty by lazy {
        val league = LeagueGenerator.generate(2026, 33L)
        var d = DynastyEngine.start(league, 2026, 33L, league.teams.first().id)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d
    }

    /** Free agency with one of the user's expiring men transition-tagged and everyone else let go. */
    private fun tagged(pick: (List<ContractsPause.Expiring>) -> ContractsPause.Expiring = { it.first() }): Pair<FreeAgencyPause, Int> {
        val pause = OffseasonEngine.runToContracts(season)
        val star = pick(pause.expiring).player.id.v
        val choices = pause.expiring.associate {
            it.player.id.v to ContractDecision(if (it.player.id.v == star) ContractChoice.TRANSITION else ContractChoice.WALK)
        }
        return pause.toFreeAgency(choices) to star
    }

    /**
     * A tagged man another club actually bids for: the first of the user's
     * mid-priced expiring men who draws an offer sheet when his club will
     * not match. Fixed for this seed; a failure here means none did.
     */
    private val contested: Pair<FreeAgencyPause, Int> by lazy {
        val pause = OffseasonEngine.runToContracts(season)
        pause.expiring.filter { it.market in 2_000..15_000 }.sortedBy { it.market }.take(8).firstNotNullOfOrNull { e ->
            val (fa, id) = tagged { list -> list.first { it.player.id == e.player.id } }
            val bid = fa.decide(emptyList(), mapOf(id to 0)).state.auction?.signings?.any { it.player == id } == true
            if (bid) fa to id else null
        } ?: error("no expiring man drew an offer sheet on this seed")
    }

    /**
     * A tagged man nobody bids for: the first of the user's expiring men who
     * draws no offer sheet. Found, like [contested], rather than assumed: who
     * the market wants moves with every season the engine plays.
     */
    private val uncontested: Pair<FreeAgencyPause, Int> by lazy {
        val pause = OffseasonEngine.runToContracts(season)
        pause.expiring.firstNotNullOfOrNull { e ->
            val (fa, id) = tagged { list -> list.first { it.player.id == e.player.id } }
            val bid = fa.decide(emptyList(), mapOf(id to 0)).state.auction?.signings?.any { it.player == id } == true
            if (bid) null else fa to id
        } ?: error("every expiring man drew an offer sheet on this seed")
    }

    @Test
    fun `a tagged man is listed with every ceiling and a recommended one`() {
        val (fa, star) = tagged()
        val t = fa.tagged.single()
        assertEquals(star, t.candidate.player.id.v)
        assertEquals(listOf(0, t.candidate.market), t.options.take(2).map { it.upTo })
        assertTrue(t.options.map { it.upTo } == t.options.map { it.upTo }.sorted(), "ceilings rise: ${t.options}")
        assertTrue(t.advice in t.options && t.why.isNotBlank(), "$t")
        assertEquals(mapOf(star to t.advice.upTo), fa.suggestedMatches)
    }

    private fun clubAfter(fa: FreeAgencyPause, id: Int, offers: List<FreeAgency.Offer>?, upTo: Int) =
        fa.decide(offers, mapOf(id to upTo)).state.players.first { it.id.v == id }.teamId

    @Test
    fun `an offer sheet is matched up to the ceiling and not past it`() {
        val (fa, id) = contested
        val offer = fa.decide(emptyList(), mapOf(id to 0)).state.auction!!.signings.first { it.player == id }.value
        assertNotEquals(fa.userTeam, clubAfter(fa, id, emptyList(), 0), "never matching, the bidder has him")
        assertNotEquals(fa.userTeam, clubAfter(fa, id, emptyList(), offer - 1), "a dollar short, he goes")
        assertEquals(fa.userTeam, clubAfter(fa, id, emptyList(), offer), "at the offer, the club matches")
        assertEquals(fa.userTeam, clubAfter(fa, id, emptyList(), Int.MAX_VALUE), "matching anything, he stays")
    }

    @Test
    fun `unsigned, he plays on the tender whatever the ceiling`() {
        val (fa, id) = uncontested
        assertEquals(fa.userTeam, clubAfter(fa, id, emptyList(), 0))
        assertEquals(fa.userTeam, clubAfter(fa, id, emptyList(), Int.MAX_VALUE))
    }

    @Test
    fun `the front office still matches whatever fits`() {
        val (fa, id) = contested
        assertEquals(fa.userTeam, fa.decide(null).state.players.first { it.id.v == id }.teamId,
            "handed to the front office, the club matches as every club does")
    }
}
