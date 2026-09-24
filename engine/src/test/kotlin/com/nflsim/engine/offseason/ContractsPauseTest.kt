package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The user's club decides its own expiring players. */
class ContractsPauseTest {

    private val season: Dynasty by lazy {
        val league = LeagueGenerator.generate(2026, 33L)
        var d = DynastyEngine.start(league, 2026, 33L, league.teams.first().id)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d
    }

    private fun roster(pause: OffseasonEngine.DraftPause, team: com.nflsim.engine.model.TeamId) =
        pause.finish().first.league.let { l -> l.players.filter { it.teamId == team }.map { it.id.v }.toSet() }

    @Test
    fun `the user's expiring men are listed with what they cost`() {
        val pause = OffseasonEngine.runToContracts(season)
        assertTrue(pause.expiring.isNotEmpty(), "somebody's deal should have run out")
        pause.expiring.forEach {
            assertTrue(it.market > 0 && it.asking > 0 && it.years >= 1, "$it")
            assertTrue(it.franchise >= it.transition, "a franchise tag costs more than a transition tag: $it")
        }
    }

    @Test
    fun `whoever the user keeps stays, and whoever he lets go is gone`() {
        val pause = OffseasonEngine.runToContracts(season)
        val user = pause.userTeam
        val best = pause.expiring.first().player.id.v
        val rest = pause.expiring.drop(1).map { it.player.id.v }
        val choices = mapOf(best to ContractChoice.RESIGN) + rest.associateWith { ContractChoice.WALK }
        assertTrue(pause.cost(choices) > 0)

        val kept = roster(pause.decide(choices), user)
        assertTrue(best in kept, "the man the user re-signed should be on the roster")
        // The league's logic did not quietly re-sign the ones the user let go.
        val stayed = rest.filter { it in kept }
        assertTrue(stayed.isEmpty(), "let go but still here: $stayed")
    }

    @Test
    fun `one tag at most, and the franchise tag keeps him`() {
        val pause = OffseasonEngine.runToContracts(season)
        val user = pause.userTeam
        val two = pause.expiring.take(2).map { it.player.id.v }
        val choices = two.associateWith { ContractChoice.FRANCHISE }
        val draft = pause.decide(choices)
        val tags = draft.state.tags.filter { it.team == user.v }
        assertEquals(1, tags.size, "the CBA allows one tag: $tags")
        assertTrue(tags.single().player in roster(draft, user), "a franchise-tagged man stays")
    }

    @Test
    fun `leaving it to the league changes nothing`() {
        val auto = OffseasonEngine.runToDraft(season).finish().first
        val viaPause = OffseasonEngine.runToContracts(season).decide(null).finish().first
        assertEquals(auto.league.players.map { it.teamId }, viaPause.league.players.map { it.teamId })
    }

    @Test
    fun `taking the front office's advice is the offseason the AI would have run`() {
        val pause = OffseasonEngine.runToContracts(season)
        val user = pause.userTeam
        val advised = roster(pause.decide(pause.suggested), user)
        val auto = roster(OffseasonEngine.runToContracts(season).decide(null), user)
        assertEquals(auto, advised, "the suggested choices should keep the same men")
    }
}
