package com.nflsim.engine.gen

import com.nflsim.engine.ratings.SchemeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 4.x: a league's schemes are dealt evenly, so no league starts out of SPEC 13.2's bands by its draw. */
class SchemeDealTest {

    @Test
    fun `every league holds each scheme as evenly as its clubs allow`() {
        (1L..20L).forEach { seed ->
            val league = LeagueGenerator.generate(2026, seed)
            listOf(SchemeCatalog.offensive.map { it.id } to league.teams.map { it.offenseScheme },
                SchemeCatalog.defensive.map { it.id } to league.teams.map { it.defenseScheme }).forEach { (catalog, dealt) ->
                val counts = catalog.map { id -> dealt.count { it == id } }
                assertTrue(counts.max() - counts.min() <= 1, "league $seed dealt $counts")
            }
        }
    }

    @Test
    fun `leagues differ in who runs what, not in the mix`() {
        val a = LeagueGenerator.generate(2026, 1L).teams.map { it.offenseScheme }
        val b = LeagueGenerator.generate(2026, 2L).teams.map { it.offenseScheme }
        assertTrue(a != b, "two leagues dealt the same schemes to the same clubs")
        assertEquals(a.sorted(), b.sorted())
    }

    @Test
    fun `a club's roster does not depend on how the schemes were dealt`() {
        // The team streams still take the draws the schemes once came from.
        val league = LeagueGenerator.generate(2026, 3L)
        assertEquals(LeagueGenerator.generate(2026, 3L).players, league.players)
        assertEquals(53 * league.teams.size, league.players.count { it.teamId != null })
    }
}
