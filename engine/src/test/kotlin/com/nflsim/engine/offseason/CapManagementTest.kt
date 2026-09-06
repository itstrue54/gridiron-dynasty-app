package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamFinances
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cap is the parity mechanism. These tests check the three things that
 * have to be true for it to do that job: it grows, it forces cuts, and a cut
 * leaves a bill behind.
 */
class CapManagementTest {

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }

    private fun schemes(): (TeamId?, Position) -> Scheme = { id, pos ->
        val t = league.team(id ?: league.teams.first().id)
        if (pos.isOffense) SchemeCatalog[t.offenseScheme] else SchemeCatalog[t.defenseScheme]
    }

    /** Every player on one team put on a deal the team cannot possibly carry. */
    private fun bloat(teamId: TeamId) = league.players.map { p ->
        if (p.teamId == teamId) p.copy(contract = Contract.of(4, 40_000, 2026)) else p
    }

    @Test
    fun `the cap grows every year`() {
        assertEquals(TeamFinances.LEAGUE_CAP, CapManagement.capFor(2026))
        val fiveYearsOn = CapManagement.capFor(2031)
        assertTrue(
            fiveYearsOn > CapManagement.capFor(2026) * 13 / 10,
            "cap should grow materially over five years, got $fiveYearsOn",
        )
        assertTrue(fiveYearsOn < CapManagement.capFor(2026) * 3 / 2, "cap grew too fast")
    }

    @Test
    fun `a team over the cap sheds salary until it is legal`() {
        val kc = league.teams.first { it.abbrev == "KC" }
        val (after, dead, releases) = CapManagement.enforce(
            league, bloat(kc.id), 2026, schemes(), SplitMixRng(1L))

        assertTrue(releases.any { it.team == kc.id.v }, "an unaffordable roster should produce cuts")

        val roster = after.filter { it.teamId == kc.id }
        val committed = CapManagement.committed(roster, 2026) + (dead[kc.id.v] ?: 0)
        assertTrue(
            committed <= CapManagement.capFor(2026) || roster.size <= 46,
            "team still $committed against a ${CapManagement.capFor(2026)} cap with ${roster.size} players",
        )
    }

    @Test
    fun `cuts stop at the roster floor`() {
        val kc = league.teams.first { it.abbrev == "KC" }
        val (after, _, _) = CapManagement.enforce(
            league, bloat(kc.id), 2026, schemes(), SplitMixRng(2L))
        val roster = after.filter { it.teamId == kc.id }
        assertTrue(roster.size >= 46, "cut below the roster floor: ${roster.size}")
        // And somebody is still there to snap the ball.
        assertTrue(roster.any { it.position == Position.QB }, "no quarterback left")
        assertTrue(roster.any { it.position == Position.C }, "no center left")
    }

    @Test
    fun `a release leaves dead money on the books`() {
        val kc = league.teams.first { it.abbrev == "KC" }
        val (_, dead, releases) = CapManagement.enforce(
            league, bloat(kc.id), 2026, schemes(), SplitMixRng(3L))
        assertTrue(releases.all { it.deadMoney > 0 }, "cutting a bonus deal is never free")
        assertTrue((dead[kc.id.v] ?: 0) > 0, "the team should be carrying dead money")
    }

    @Test
    fun `enforcing the cap does not lose or duplicate players`() {
        val kc = league.teams.first { it.abbrev == "KC" }
        val before = bloat(kc.id)
        val (after, _, _) = CapManagement.enforce(
            league, before, 2026, schemes(), SplitMixRng(4L))
        assertEquals(before.size, after.size, "player count changed")
        assertEquals(after.size, after.map { it.id }.toSet().size, "duplicate player")
    }

    @Test
    fun `a cheap roster is left alone`() {
        val (_, _, releases) = CapManagement.enforce(
            league, league.players, 2026, schemes(), SplitMixRng(5L))
        assertTrue(releases.isEmpty(), "generated rosters are cap-legal; got ${releases.size} cuts")
    }

    // ---- the books at kickoff ---------------------------------------

    @Test
    fun `generated rosters start under the cap and are not empty of salary`() {
        val cap = CapManagement.capFor(2026)
        league.teams.forEach { t ->
            val committed = CapManagement.committed(league.roster(t.id), 2026)
            assertTrue(committed < cap, "${t.abbrev} is over the cap at generation: $committed")
            assertTrue(committed > cap / 2, "${t.abbrev} has an implausibly cheap roster: $committed")
        }
    }

    @Test
    fun `contracts are staggered so free agency is a trickle not a flood`() {
        val roster = league.roster(league.teams.first { it.abbrev == "KC" }.id)
        val expiries = roster.mapNotNull { it.contract }.map { it.signedYear + it.years }.toSet()
        assertTrue(expiries.size >= 3, "the whole roster expires together: $expiries")

        val leaving = roster.count { it.contract?.isActive(2027) != true }
        assertTrue(
            leaving in 4..24,
            "expected part of a roster to reach free agency after one year, got $leaving of ${roster.size}",
        )
    }
}
