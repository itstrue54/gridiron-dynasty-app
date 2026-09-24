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
}
