package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import com.nflsim.engine.season.Transactions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/SPEC.md 8.1: unused cap room carries into the next league year. */
class CapCarryoverTest {

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }

    private fun withCarryover(team: TeamId, amount: Int) = league.copy(teams = league.teams.map {
        if (it.id == team) it.copy(finances = it.finances.copy(carryover = amount)) else it
    })

    @Test
    fun `a club's room is its unused room, times the share`() {
        val full = CapManagement.carryForward(league, league)
        league.teams.forEach { t ->
            val room = Transactions.spaceFor(league, t.id).coerceAtLeast(0)
            assertEquals(room, full.team(t.id).finances.carryover, t.abbrev)
        }
        val half = league.copy(tuning = league.tuning.copy(ai = league.tuning.ai.copy(capCarryoverShare = 0.5f)))
        val t = league.teams.first()
        assertEquals((Transactions.spaceFor(league, t.id) * 0.5f).toInt(),
            CapManagement.carryForward(half, half).team(t.id).finances.carryover)
        val none = league.copy(tuning = league.tuning.copy(ai = league.tuning.ai.copy(capCarryoverShare = 0f)))
        assertTrue(CapManagement.carryForward(none, none).teams.all { it.finances.carryover == 0 })
    }

    @Test
    fun `a club over the cap carries nothing, and owes nothing more for it`() {
        val kc = league.teams.first { it.abbrev == "KC" }
        val over = league.copy(players = league.players.map {
            if (it.teamId == kc.id) it.copy(contract = Contract.of(4, 40_000, 2026)) else it
        })
        assertTrue(Transactions.spaceFor(over, kc.id) < 0)
        assertEquals(0, CapManagement.carryForward(over, over).team(kc.id).finances.carryover)
    }

    @Test
    fun `carryover is room this year`() {
        val t = league.teams.first()
        val before = Transactions.spaceFor(league, t.id)
        assertEquals(before + 12_000, Transactions.spaceFor(withCarryover(t.id, 12_000), t.id))
    }

    private fun schemes(l: League): (TeamId?, Position) -> Scheme = { id, pos ->
        val t = l.team(id ?: l.teams.first().id)
        if (pos.isOffense) SchemeCatalog[t.offenseScheme] else SchemeCatalog[t.defenseScheme]
    }

    @Test
    fun `a club inside its own cap is not made to cut`() {
        val kc = league.teams.first { it.abbrev == "KC" }
        // Dead money that puts KC $20M over the league's cap.
        val owed = Transactions.spaceFor(league, kc.id) + 20_000
        val tight = league.copy(teams = league.teams.map {
            if (it.id == kc.id) it.copy(finances = it.finances.copy(deadMoney = owed)) else it
        })
        assertEquals(-20_000, Transactions.spaceFor(tight, kc.id), "set up over the league cap")
        val (_, _, cutsWithout) = CapManagement.enforce(tight, league.players, 2026, schemes(tight), SplitMixRng(1L))
        assertTrue(cutsWithout.any { it.team == kc.id.v }, "over the league cap, it cuts")

        val carried = tight.copy(teams = tight.teams.map {
            if (it.id == kc.id) it.copy(finances = it.finances.copy(carryover = 25_000)) else it
        })
        val (_, _, cuts) = CapManagement.enforce(carried, league.players, 2026, schemes(carried), SplitMixRng(1L))
        assertTrue(cuts.none { it.team == kc.id.v }, "its own cap has room: ${cuts.filter { it.team == kc.id.v }}")
    }

    @Test
    fun `the offseason carries each club's unused room into the new year`() {
        val start = LeagueGenerator.generate(2026, 91L)
        var d = DynastyEngine.start(start, 2026, 91L, start.teams.first().id)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        val room = d.league.teams.associate { it.id to Transactions.spaceFor(d.league, it.id).coerceAtLeast(0) }
        assertTrue(room.values.any { it > 0 }, "somebody finished the year with room")
        val next = DynastyEngine.advance(d)
        assertEquals(2027, next.year)
        next.league.teams.forEach { t ->
            assertEquals(room.getValue(t.id), t.finances.carryover, "${t.abbrev} carried the wrong amount")
        }
    }
}
