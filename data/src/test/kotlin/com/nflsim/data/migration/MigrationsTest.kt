package com.nflsim.data.migration

import com.nflsim.data.CURRENT_SAVE_VERSION
import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.season.DynastyEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The save migration chain (SPEC 9.1): one step a version, none missing. */
class MigrationsTest {

    private val dynasty by lazy {
        val league = LeagueGenerator.generate(2026, 3L)
        DynastyEngine.start(league, 2026, 3L, league.teams.first().id)
    }

    @Test
    fun `every version before this build's has exactly one step to the next`() {
        val from = Migrations.steps.map { it.from }
        assertEquals((1 until CURRENT_SAVE_VERSION).toList(), from.sorted(),
            "a version bump needs its step in data/migration in the same commit")
        assertEquals(from.size, from.toSet().size, "two steps from the same version")
        Migrations.steps.forEach { assertTrue(it.why.isNotBlank(), "step ${it.from} does not say why") }
    }

    @Test
    fun `a save from before GMs had names gets the names its league would have had`() {
        val blank = dynasty.copy(league = dynasty.league.copy(
            teams = dynasty.league.teams.map { it.copy(gm = it.gm.copy(name = "")) }))
        val named = Migrations.migrate(blank, 17, 18)
        assertTrue(named.league.teams.all { it.gm.name.isNotBlank() })
        assertEquals(dynasty.league.teams.map { it.gm.name }, named.league.teams.map { it.gm.name },
            "same seed, same names as a league generated today")
        // A club whose GM was already named keeps him.
        val kept = blank.copy(league = blank.league.copy(teams = blank.league.teams.mapIndexed { i, t ->
            if (i == 0) t.copy(gm = t.gm.copy(name = "Real Person")) else t }))
        assertEquals("Real Person", Migrations.migrate(kept, 17, 18).league.teams.first().gm.name)
    }

    @Test
    fun `a save from this build passes through untouched`() {
        assertSame(dynasty, Migrations.migrate(dynasty, CURRENT_SAVE_VERSION, CURRENT_SAVE_VERSION))
    }

    @Test
    fun `a save from a newer build is refused rather than misread`() {
        assertFailsWith<IllegalArgumentException> {
            Migrations.migrate(dynasty, CURRENT_SAVE_VERSION + 1, CURRENT_SAVE_VERSION)
        }
    }

    @Test
    fun `a version with no step is refused rather than skipped`() {
        assertFailsWith<IllegalStateException> {
            Migrations.migrate(dynasty, CURRENT_SAVE_VERSION, CURRENT_SAVE_VERSION + 1)
        }
    }

    @Test
    fun `a save from before weather gets its stadiums' climates back`() {
        val league = com.nflsim.engine.gen.LeagueGenerator.generate(2026, 11L)
        val fresh = com.nflsim.engine.season.DynastyEngine.start(league, 2026, 11L, league.teams.first().id)
        val old = fresh.copy(league = fresh.league.copy(teams = fresh.league.teams.map {
            it.copy(stadium = it.stadium.copy(climate = "temperate"))
        }))
        val migrated = Migrations.migrate(old, 23, 24)
        assertEquals(fresh.league.teams.map { it.stadium.climate }, migrated.league.teams.map { it.stadium.climate })
        assertTrue(migrated.league.teams.any { it.stadium.climate == "great_lakes" })
    }
}
