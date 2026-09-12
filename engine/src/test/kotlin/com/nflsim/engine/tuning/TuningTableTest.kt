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
    fun `special teams read the table`() {
        val scheme = SchemeCatalog.offensive.first()
        val always = TuningTable.SpecialTeams(kickoffTouchbackRate = 1f)
        val never = TuningTable.SpecialTeams(kickoffTouchbackRate = 0f, kickoffReturnBase = 30)
        assertEquals(GameState.TOUCHBACK_YARD_LINE, SpecialTeams.kickoff(null, scheme, SplitMixRng(1L), always).first)
        assertEquals(30, SpecialTeams.kickoff(null, scheme, SplitMixRng(1L), never).first)
    }
}
