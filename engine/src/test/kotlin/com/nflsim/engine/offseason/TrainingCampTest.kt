package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import com.nflsim.engine.sim.Injury
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Training camp: development before the cut, and a slight chance of getting hurt. */
class TrainingCampTest {

    private val league = LeagueGenerator.generate(2026, 33L)

    @Test
    fun `camp hurts a few men a club, mostly briefly, and only men on a roster`() {
        val (after, hurt) = TrainingCamp.injuries(league.players, league.tuning.injuries, SplitMixRng(5L))
        val rostered = league.players.count { it.teamId != null && it.status == PlayerStatus.ACTIVE }
        val perClub = hurt.size / 32.0
        assertTrue(perClub in 0.4..3.0, "$perClub hurt a club from $rostered men")
        val byId = league.players.associateBy { it.id.v }
        hurt.forEach { h ->
            val p = byId.getValue(h.player)
            assertTrue(p.teamId != null, "a free agent hurt in camp")
            assertEquals(h.team, p.teamId!!.v)
            assertTrue(h.gamesOut in 1..Injury.SEASON_ENDING)
        }
        assertTrue(hurt.count { it.gamesOut <= 2 } * 2 > hurt.size, "most camp injuries are short")
        // Each hurt man carries it into the season; nobody else changes.
        val afterById = after.associateBy { it.id.v }
        hurt.forEach { assertTrue(afterById.getValue(it.player).injuryWeeks >= it.gamesOut) }
        assertEquals(league.players.size - hurt.size, league.players.count { afterById.getValue(it.id.v) == it })
    }

    @Test
    fun `the same camp hurts the same men`() {
        val a = TrainingCamp.injuries(league.players, league.tuning.injuries, SplitMixRng(9L)).second
        val b = TrainingCamp.injuries(league.players, league.tuning.injuries, SplitMixRng(9L)).second
        assertEquals(a, b)
    }

    private val season: Dynasty by lazy {
        var d = DynastyEngine.start(league, 2026, 33L, league.teams.first().id)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d
    }

    @Test
    fun `the cut comes after camp, with what camp did to each man`() {
        val pause = OffseasonEngine.runToDraft(season).toCutdown()
        // Everyone who reported is still there to be cut, developed.
        val before = pause.campBefore.associateBy { it.id.v }
        assertEquals(before.keys, pause.roster.map { it.id.v }.toSet())
        val moved = pause.roster.count { p -> overall(p) != overall(before.getValue(p.id.v)) }
        assertTrue(moved > pause.roster.size / 3, "camp moved only $moved of ${pause.roster.size}")
        // Camp's injuries are the league's, and the user's hurt men carry them to the cut.
        assertTrue(pause.campInjuries.isNotEmpty())
        pause.campInjuries.filter { it.team == pause.userTeam.v }.forEach { h ->
            assertTrue(pause.roster.first { it.id.v == h.player }.injuryWeeks >= h.gamesOut)
        }
    }

    @Test
    fun `a man hurt in camp is still hurt when the season starts, and nobody else is`() {
        val pause = OffseasonEngine.runToDraft(season).toCutdown()
        val next = pause.decide(null).first
        val hurt = pause.campInjuries.associate { it.player to it.gamesOut }
        assertTrue(hurt.isNotEmpty())
        next.league.players.forEach { p ->
            assertEquals(hurt[p.id.v] ?: 0, p.injuryWeeks, "${p.name} at week 1")
        }
    }
}
