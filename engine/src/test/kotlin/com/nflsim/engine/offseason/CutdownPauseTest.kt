package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Position
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The user's club fills its own camp and makes its own cut to 53. */
class CutdownPauseTest {

    private val season: Dynasty by lazy {
        val league = LeagueGenerator.generate(2026, 33L)
        var d = DynastyEngine.start(league, 2026, 33L, league.teams.first().id)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d
    }

    private fun cutdown() = OffseasonEngine.runToDraft(season).toCutdown()

    private fun rosterAfter(next: Dynasty, pause: CutdownPause) = next.league.roster(pause.userTeam)

    @Test
    fun `leaving the cut to the front office is the old offseason`() {
        val auto = OffseasonEngine.runToDraft(season).finish().first
        val viaPause = cutdown().decide(null).first
        assertEquals(auto.league.players.map { it.teamId }, viaPause.league.players.map { it.teamId })
    }

    @Test
    fun `the suggestions make a legal 53`() {
        val pause = cutdown()
        val next = pause.decide(pause.suggested).first
        val roster = rosterAfter(next, pause)
        assertTrue(roster.size in CutdownPause.GAME_DAY..CutdownPause.ROSTER_LIMIT, "${roster.size} men")
        pause.mustField.forEach { pos -> assertTrue(roster.any { it.position == pos }, "no $pos") }
    }

    @Test
    fun `whoever he cuts is gone at his dead money, and whoever he signs stays`() {
        val pause = cutdown()
        val gone = pause.roster.filter { pause.deadIfCut(it) > 0 }.maxByOrNull { pause.deadIfCut(it) }!!
        val signed = pause.pool.first()
        val cut = pause.suggested.copy(
            release = pause.suggested.release + gone.id.v,
            sign = pause.suggested.sign + signed.id.v,
        )
        val state = pause.apply(cut)
        assertTrue((state.deadMoney[pause.userTeam.v] ?: 0) >= pause.deadIfCut(gone), "his dead money is charged")
        val next = pause.decide(cut).first
        val roster = rosterAfter(next, pause)
        assertTrue(roster.none { it.id == gone.id }, "the man cut is gone")
        assertTrue(roster.any { it.id == signed.id }, "the man signed stays")
    }

    @Test
    fun `a position every club must field is filled if he leaves it empty`() {
        val pause = cutdown()
        val kickers = pause.roster.filter { it.position == Position.K }.map { it.id.v }.toSet()
        val next = pause.decide(CutdownPause.Cut(release = kickers)).first
        assertTrue(rosterAfter(next, pause).any { it.position == Position.K }, "somebody has to kick")
    }

    @Test
    fun `a camp he leaves over 53 is cut to it by the league's own logic`() {
        val pause = cutdown()
        val many = pause.pool.take(30).map { it.id.v }.toSet()
        val next = pause.decide(CutdownPause.Cut(sign = many)).first
        assertEquals(CutdownPause.ROSTER_LIMIT, rosterAfter(next, pause).size)
    }
}
