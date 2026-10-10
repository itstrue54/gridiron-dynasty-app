package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What clubs do with what camp showed: cuts and trades, and the user's as suggestions he takes or not. */
class CampDecisionsTest {

    private val season: Dynasty by lazy {
        val league = LeagueGenerator.generate(2026, 33L)
        var d = DynastyEngine.start(league, 2026, 33L, league.teams.first().id)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d
    }
    private val pause: CutdownPause by lazy { OffseasonEngine.runToDraft(season).toCutdown() }

    /**
     * The same league with the user at a club that trades a man at camp, so
     * his suggestions and an offer are there to test.
     */
    private val mover: CutdownPause by lazy {
        // The clubs that traded at camp; as the user, the first that is offered a trade.
        pause.campTrades.map { it.from }.distinct().take(5).firstNotNullOfOrNull { club ->
            val d = season.copy(userTeam = club)
            OffseasonEngine.runToDraft(d).toCutdown().takeIf { it.campOffers.isNotEmpty() }
        } ?: error("no club that traded at camp is offered a trade as the user")
    }

    @Test
    fun `the league's clubs cut and trade at camp, within their limits`() {
        val cuts = pause.campReleases
        val trades = pause.campTrades
        assertTrue(cuts.size + trades.size >= 10, "only ${cuts.size} cuts and ${trades.size} trades at camp")
        val user = pause.userTeam.v
        assertTrue(cuts.none { it.team == user } && trades.none { it.from == user || it.to == user }, "the user's club was moved for him")
        val byClub = (cuts.map { it.team } + trades.map { it.from }).groupingBy { it }.eachCount()
        assertTrue(byClub.values.all { it <= season.league.tuning.ai.campMaxMoves }, "$byClub")
        val now = pause.state.players.associateBy { it.id.v }
        cuts.forEach { c ->
            val p = now.getValue(c.player)
            assertEquals(null, p.teamId, "${c.name} cut but still signed")
            assertTrue(c.savings > 0, "${c.name} cut for nothing")
        }
        trades.forEach { t -> assertEquals(t.to, now.getValue(t.player).teamId?.v, "${t.name} not where he was traded") }
        // Each trade paid its seller a late pick next year.
        val paid = pause.state.pickTrades.filter { it.reason == CampDecisions.CAMP_REASON }
        assertEquals(trades.size, paid.size)
        paid.forEach { pk ->
            assertTrue(pk.round >= 6 && pk.year == pause.year + 1)
            assertTrue(pause.state.picks.any { it.year == pk.year && it.round == pk.round && it.original == pk.original && it.owner == pk.to })
        }
    }

    @Test
    fun `nobody hurt, signed this year, or needed to field a position is let go`() {
        val reported = pause.reported
        pause.campReleases.forEach { c ->
            val p = reported.getValue(c.player)
            assertTrue((p.contract?.signedYear ?: 0) < pause.year, "${c.name} was signed this year")
        }
        pause.campInjuries.forEach { h ->
            assertTrue(pause.campReleases.none { it.player == h.player } && pause.campTrades.none { it.player == h.player })
        }
        pause.state.players.filter { it.teamId != null }.groupBy { it.teamId!! }.forEach { (team, men) ->
            men.groupBy { it.position }.forEach { (pos, atPos) ->
                val had = pause.reported.values.count { it.teamId == team && it.position == pos }
                if (had >= TeamNeeds.requiredStarters(pos)) assertTrue(atPos.size >= TeamNeeds.requiredStarters(pos), "$team short at $pos")
            }
        }
    }

    @Test
    fun `the user's camp moves are suggestions, each with its reason, and made only when he takes them`() {
        val pause = mover
        val plan = pause.campPlan
        assertTrue(plan.isNotEmpty())
        // His club is untouched until he decides.
        plan.forEach { m -> assertTrue(pause.roster.any { it.id == m.player.id }) }
        plan.forEach { m ->
            assertTrue(m.player.id.v in pause.suggested.release, "${m.player.name} planned but not suggested")
            assertTrue(pause.whyCut(m.player).startsWith(pause.campReason(m).substringBefore(".")))
        }
        // Taking an offer makes that trade and nothing else.
        pause.campOffers.first().let { offer ->
            val after = pause.takeCampTrade(offer)
            assertTrue(after.roster.none { it.id == offer.player.id })
            assertEquals(offer.to, after.state.players.first { it.id == offer.player.id }.teamId)
            assertTrue(after.state.picks.any { it == offer.pick!!.copy(owner = pause.userTeam.v) })
            assertEquals(pause.roster.size - 1, after.roster.size)
        }
    }

    @Test
    fun `left to the front office, the user's club makes its camp moves as every club does`() {
        val pause = mover
        assertTrue(pause.campPlan.isNotEmpty())
        val next = pause.decide(null).first
        pause.campPlan.forEach { m ->
            // A man cut whom nobody signs may leave the league altogether.
            val p = next.league.players.firstOrNull { it.id == m.player.id }
            if (m.to != null) assertEquals(m.to, p?.teamId) else assertTrue(p?.teamId != pause.userTeam, "${m.player.name} kept")
        }
    }

    @Test
    fun `a patient general manager makes fewer camp moves than a hasty one`() {
        // Patience is 1.28 + riskTolerance x 0.5: every club's GM made hasty, then patient.
        // Patience only gates the passed-by moves, so one league can tie (seed 33: 14 and 14);
        // two leagues together are counted. Four measured: 14-14, 16-9, 21-14, 21-16.
        val other: Dynasty = LeagueGenerator.generate(2026, 41L).let { league ->
            var d = DynastyEngine.start(league, 2026, 41L, league.teams.first().id)
            while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
            d
        }
        fun moves(d: Dynasty, risk: Float): Int {
            val league = d.league.copy(teams = d.league.teams.map { it.copy(gm = it.gm.copy(riskTolerance = risk)) })
            val camp = OffseasonEngine.runToDraft(d.copy(league = league)).toCutdown()
            return camp.campReleases.size + camp.campTrades.size
        }
        val hasty = moves(season, 0f) + moves(other, 0f)
        val patient = moves(season, 1f) + moves(other, 1f)
        assertTrue(hasty > patient, "hasty $hasty vs patient $patient")
    }
}
