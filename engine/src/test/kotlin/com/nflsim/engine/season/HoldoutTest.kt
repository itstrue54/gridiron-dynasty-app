package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.DemandState
import com.nflsim.engine.model.League
import com.nflsim.engine.model.NewsKind
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.rng.SplitMixRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SPEC 10.4: the holdout - a veteran who wanted paying in the spring stays away from camp. */
class HoldoutTest {

    private val league = LeagueGenerator.generate(2026, 14L)
    private val user = league.teams.first().id

    /** A good veteran on [club], with an ego, on a deal far under his market. */
    private fun underpaid(club: TeamId, ego: Int = 90): Pair<League, Player> {
        val man = league.roster(club).filter { it.accruedSeasons >= league.tuning.ai.disputeAccruedSeasons }
            .maxBy { overall(it, SchemeCatalog.tuned(if (it.position.isOffense) league.team(club).offenseScheme else league.team(club).defenseScheme, league.tuning)) }
        val l = league.copy(players = league.players.map {
            if (it.id == man.id) it.copy(
                traits = it.traits.copy(ego = ego),
                contract = Contract.of(2, Contract.MIN_BASE_SALARY * 2, league.year),
                demand = DemandState.NONE, form = 0, morale = 70,
            ) else it
        })
        return l to l.player(man.id)
    }

    private fun camp(l: League, wants: Set<Int>) = ContractDisputes.atCamp(l, user, wants, emptyMap(), SplitMixRng(3L))

    @Test
    fun `the user's man holds out, rusty, and the demand is on his desk`() {
        val (l, man) = underpaid(user)
        val r = camp(l, setOf(man.id.v))
        val after = r.league.player(man.id)
        assertEquals(DemandState.PENDING, after.demand)
        assertEquals(-l.tuning.ai.holdoutForm, after.form, "he missed camp")
        assertEquals(man.morale - l.tuning.ai.holdoutMorale, after.morale)
        val story = r.news.single()
        assertEquals(NewsKind.DISPUTE, story.kind)
        assertEquals(1, story.week)
        assertTrue(man.name in story.headline, story.headline)
        assertTrue(ContractDisputes.pending(r.league, user).any { it.player.id == man.id }, "on the Demands screen")
    }

    @Test
    fun `a league club answers its holdout at once`() {
        val other = league.teams[5].id
        val (l, man) = underpaid(other)
        val r = camp(l, setOf(man.id.v))
        val after = r.league.player(man.id)
        assertNotEquals(DemandState.PENDING, after.demand, "the club answered")
        assertEquals(2, r.news.size, "the holdout, then the answer")
        assertTrue(after.form < 0, "paid or not, he missed camp")
    }

    @Test
    fun `only a man who wanted paying, with the ego for it and the case, holds out`() {
        val (l, man) = underpaid(user)
        assertEquals(emptyList(), camp(l, emptySet()).news, "he never said he wanted paying")
        val (meek, quiet) = underpaid(user, ego = 20)
        assertEquals(emptyList(), camp(meek, setOf(quiet.id.v)).news, "no ego, no holdout")
        // Paid his market, he has no case.
        val paid = l.copy(players = l.players.map {
            if (it.id == man.id) it.copy(contract = Contract.of(3, 60_000 * 3, l.year)) else it
        })
        assertEquals(emptyList(), camp(paid, setOf(man.id.v)).news, "paid men report")
    }

    @Test
    fun `a new season opens with camp's holdouts, and only them`() {
        var d = DynastyEngine.start(league, 2026, 14L, user)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        val next = DynastyEngine.advance(d)
        assertEquals(DynastyPhase.REGULAR_SEASON, next.phase)
        next.news.forEach { n ->
            assertEquals(1, n.week)
            val p = next.league.playersById.getValue(com.nflsim.engine.model.PlayerId(n.player!!))
            assertTrue(p.form < 0 || p.demand != DemandState.NONE, "a holdout story is about a holdout: $n")
        }
    }
}
