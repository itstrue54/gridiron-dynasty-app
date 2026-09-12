package com.nflsim.engine.tuning

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.sim.GameCalibration
import com.nflsim.engine.sim.GameState
import com.nflsim.engine.sim.SpecialTeams
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TuningTableTest {

    private val league = LeagueGenerator.generate(2026, 2026L)

    @Test
    fun `a league plays by its own tuning table`() {
        val realistic = GameCalibration.run(league, games = 12, seed = 11L)
        val arcade = GameCalibration.run(league.copy(tuning = TuningTable.ARCADE), games = 12, seed = 11L)
        assertNotEquals(realistic.table(), arcade.table(), "the league's table should reach its games")
    }

    @Test
    fun `the table is saved with the league`() {
        val json = Json { ignoreUnknownKeys = true; allowStructuredMapKeys = true }
        val saved = json.encodeToString(League.serializer(), league.copy(tuning = TuningTable.GRINDER))
        assertEquals(TuningTable.GRINDER, json.decodeFromString(League.serializer(), saved).tuning)
    }

    @Test
    fun `progression reads its group`() {
        val young = league.players.first { it.birthYear >= 2003 }
        val flat = TuningTable.Progression(coachSlope = 0f)
        fun delta(coaching: Int) = com.nflsim.engine.offseason.Progression.progress(
            young, com.nflsim.engine.offseason.Progression.Context(2026, coaching, 700, flat), SplitMixRng(3L)).delta
        assertEquals(delta(20), delta(90), "with no coach slope, coaching should not matter")
    }

    @Test
    fun `the draft reads the league's AI tuning`() {
        val prospects = com.nflsim.engine.offseason.SyntheticDraftClass.generate(2027, 100_000, SplitMixRng(5L))
        val scheme = SchemeCatalog.offensive.first()
        val pureTalent = TuningTable.Ai(draftNeedWeight = 0f, draftFitWeight = 0f, draftScoutingError = 0f)
        val result = com.nflsim.engine.offseason.DraftRunner.run(
            listOf(1 to league.teams.first().id), prospects, { scheme }, { emptyMap() }, 2027, SplitMixRng(1L),
            ai = pureTalent)
        val (_, _, _, player) = result.picks.first()
        assertEquals(prospects.maxBy { com.nflsim.engine.ratings.overall(it) }.id.v, player,
            "with no need, fit or scouting error, the board is pure talent")
    }

    @Test
    fun `special teams read the table`() {
        val scheme = SchemeCatalog.offensive.first()
        val always = TuningTable.SpecialTeams(kickoffTouchbackRate = 1f)
        val never = TuningTable.SpecialTeams(kickoffTouchbackRate = 0f, kickoffReturnBase = 30)
        assertEquals(GameState.TOUCHBACK_YARD_LINE, SpecialTeams.kickoff(null, scheme, SplitMixRng(1L), always).first)
        assertEquals(30, SpecialTeams.kickoff(null, scheme, SplitMixRng(1L), never).first)
    }
}
