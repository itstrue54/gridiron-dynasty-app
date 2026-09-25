package com.example.nflsimtext.ui

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.playbook.Playbooks
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** SPEC 5.4: the called game, driven the way the screen drives it. */
class LiveGameTest {

    private fun start(): Dynasty {
        val league = LeagueGenerator.generate(2026, 44L)
        return DynastyEngine.start(league, 2026, 44L, league.teams.first().id)
    }

    private fun live(d: Dynasty) = LiveGame(
        Playbooks.forScheme(d.team.offenseScheme), Playbooks.forScheme(d.team.defenseScheme))

    /** Plays the week on a worker, answering from here with [answer] until it ends. */
    private fun play(d: Dynasty, game: LiveGame, answer: (LiveGame, Int) -> Unit): Pair<Dynasty, Int> {
        val pool = Executors.newSingleThreadExecutor()
        val week = pool.submit<Dynasty> { DynastyEngine.advance(d, caller = game) }
        // Count each question once: the loop may see it again before the game moves on.
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        while (!week.isDone) {
            val a = game.ask
            if (a != null) {
                if (seen.add(a)) answer(game, seen.size)
            } else Thread.sleep(1)
        }
        val asked = seen.size
        pool.shutdown()
        return week.get(30, TimeUnit.SECONDS) to asked
    }

    @Test
    fun `taking every call is the coordinators' week`() {
        val d = start()
        val (after, asked) = play(d, live(d)) { g, _ -> g.takeSuggestion(); Thread.sleep(0) }
        assertTrue("he was asked at every snap: $asked", asked > 100)
        assertEquals(DynastyEngine.advance(d), after)
    }

    @Test
    fun `a second tap on an old snap is not the answer to the next`() {
        val d = start()
        // Every call answered twice: the stray copy must not play the next snap.
        val (after, _) = play(d, live(d)) { g, _ -> g.takeSuggestion(); g.takeSuggestion() }
        assertEquals(DynastyEngine.advance(d), after)
    }

    @Test
    fun `letting the coordinators finish ends the game`() {
        val d = start()
        val (after, asked) = play(d, live(d)) { g, n -> if (n >= 5) g.finish() else g.takeSuggestion() }
        assertTrue("five calls, then the coordinators: $asked", asked in 5..6)
        assertEquals(DynastyEngine.advance(d), after)
    }
}
