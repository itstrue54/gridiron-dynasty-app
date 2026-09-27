package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SPEC 5.4: the user may call his club's snaps, or leave them to his coordinators. */
class SnapCallerTest {

    private fun start(): Dynasty {
        val league = LeagueGenerator.generate(2026, 44L)
        return DynastyEngine.start(league, 2026, 44L, league.teams.first().id)
    }

    private fun userGame(d: Dynasty) = d.results.last { it.home == d.userTeamId || it.away == d.userTeamId }

    @Test
    fun `taking every suggestion plays the week exactly as the coordinators would`() {
        val d = start()
        val asked = mutableListOf<Boolean>()
        val echo = object : SnapCaller {
            override fun offense(snap: Snap, suggested: OffensivePlayCall) = suggested.also { asked += snap.onOffense }
            override fun defense(snap: Snap, suggested: DefensivePlayCall) = suggested.also { asked += snap.onOffense }
        }
        assertEquals(DynastyEngine.advance(d), DynastyEngine.advance(d, caller = echo))
        assertTrue(asked.contains(true) && asked.contains(false), "asked on offence and on defence")
    }

    @Test
    fun `a call of his own changes his game and nobody else's`() {
        val d = start()
        val sneaks = object : SnapCaller {
            override fun offense(snap: Snap, suggested: OffensivePlayCall): OffensivePlayCall =
                if (suggested is OffensivePlayCall.Run || suggested is OffensivePlayCall.Pass)
                    OffensivePlayCall.Run(RunConcept.QB_SNEAK, Personnel.P_22) else suggested
        }
        val quiet = DynastyEngine.advance(d)
        val called = DynastyEngine.advance(d, caller = sneaks)
        val side = if (userGame(called).home == d.userTeamId) "home" else "away"
        val box = called.lastGame!!.boxScore.let { if (side == "home") it.home else it.away }
        assertEquals(0, box.passAttempts, "every snap he called was a run")
        assertNotEquals(userGame(quiet), userGame(called), "his game went differently")
        val others = { x: Dynasty -> x.results.filterNot { it.home == d.userTeamId || it.away == d.userTeamId } }
        assertEquals(others(quiet), others(called), "every other game that week is untouched")
    }

    @Test
    fun `fourth down is his to decide`() {
        val d = start()
        val neverPunt = object : SnapCaller {
            override fun fourthDown(snap: Snap, suggested: FourthDownChoice) = FourthDownChoice.GO_FOR_IT
        }
        val called = DynastyEngine.advance(d, caller = neverPunt)
        val side = if (userGame(called).home == d.userTeamId) Side.HOME else Side.AWAY
        val punts = called.lastGame!!.playByPlay.count { it.offense == side && it.down == 4 && "punt" in it.text.lowercase() }
        assertEquals(0, punts, "a club that always goes for it never punts")
    }

    @Test
    fun `the caller hears only about his own club`() {
        val d = start()
        val sides = mutableSetOf<Side>()
        val spy = object : SnapCaller {
            override fun offense(snap: Snap, suggested: OffensivePlayCall) = suggested.also {
                sides += snap.side
                assertEquals(snap.side, snap.state.possession, "offence is asked only with the ball")
            }
            override fun defense(snap: Snap, suggested: DefensivePlayCall) = suggested.also {
                sides += snap.side
                assertNotEquals(snap.side, snap.state.possession, "defence is asked only without it")
            }
        }
        val after = DynastyEngine.advance(d, caller = spy)
        val side = if (userGame(after).home == d.userTeamId) Side.HOME else Side.AWAY
        assertEquals(setOf(side), sides)
    }

    /** The season to its playoffs, with the user in charge of the club that ends it at [seed] in the first conference. */
    private fun postseason(seed: Int): Dynasty {
        var d = start()
        while (d.phase != com.nflsim.engine.season.DynastyPhase.PLAYOFFS) d = DynastyEngine.advance(d)
        val conference = com.nflsim.engine.model.Conference.entries.first()
        return d.copy(userTeam = DynastyEngine.seeds(d, conference)[seed - 1].v)
    }

    @Test
    fun `he can call every playoff game his club plays, and the rest is untouched`() {
        val d = postseason(seed = 1)
        val kickoffs = mutableListOf<String>()
        val finals = mutableListOf<Pair<Int, Int>>()
        var snaps = 0
        val echo = object : SnapCaller {
            override fun offense(snap: Snap, suggested: OffensivePlayCall) = suggested.also { snaps++ }
            override fun defense(snap: Snap, suggested: DefensivePlayCall) = suggested.also { snaps++ }
            override fun kickoff(title: String, home: com.nflsim.engine.model.TeamId, away: com.nflsim.engine.model.TeamId) {
                assertTrue(d.userTeamId == home || d.userTeamId == away, "told only of his own games")
                kickoffs += title
            }
            override fun final(homeScore: Int, awayScore: Int) { finals += homeScore to awayScore }
        }
        val quiet = DynastyEngine.advance(d)
        val called = DynastyEngine.advance(d, caller = echo)
        assertEquals(quiet, called, "taking every suggestion plays the postseason as the coordinators would")
        val his = called.playoffs.filter { it.home == d.userTeamId || it.away == d.userTeamId }
        assertTrue(his.isNotEmpty() && snaps > 0)
        assertEquals(his.size, kickoffs.size, "one kickoff for each of his games")
        assertEquals(his.map { it.homeScore to it.awayScore }, finals)
        // The top seed rests on wild card weekend.
        assertEquals("Divisional round", kickoffs.first())
    }

    @Test
    fun `a club out of the playoffs is never asked`() {
        var d = start()
        while (d.phase != com.nflsim.engine.season.DynastyPhase.PLAYOFFS) d = DynastyEngine.advance(d)
        val seeded = com.nflsim.engine.model.Conference.entries.flatMap { DynastyEngine.seeds(d, it) }.toSet()
        val out = d.copy(userTeam = d.league.teams.first { it.id !in seeded }.id.v)
        var asked = false
        val spy = object : SnapCaller {
            override fun offense(snap: Snap, suggested: OffensivePlayCall) = suggested.also { asked = true }
            override fun kickoff(title: String, home: com.nflsim.engine.model.TeamId, away: com.nflsim.engine.model.TeamId) { asked = true }
        }
        DynastyEngine.advance(out, caller = spy)
        assertTrue(!asked)
    }
}
