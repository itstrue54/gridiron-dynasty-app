package com.nflsim.data

import com.nflsim.engine.model.Staff
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPEC 14's M12 gate: a save written by the first playable build still plays.
 *
 * The fixture is real, not reconstructed. It was written by commit 5be1cb7,
 * the M6 build, four weeks into 2026 - save format version 1, before staffs,
 * draft picks, coach tendencies, careers or history existed. Every migration
 * since has to have run for this to load, and loading is not the test:
 * finishing the season and turning the year over is.
 */
class M6SaveTest {

    private val bytes = checkNotNull(javaClass.getResourceAsStream("/saves/m6-2026-week5.sav")) {
        "the M6 save fixture is missing"
    }.use { it.readBytes() }

    @Test
    fun `a save from the first playable build loads where it was left`() {
        val d = SaveFile.decode(bytes)
        assertEquals(2026, d.year)
        assertEquals(5, d.week, "the save was written after four weeks")
        assertEquals(DynastyPhase.REGULAR_SEASON, d.phase)
        assertEquals("KC", d.team.abbrev)
        assertTrue(d.results.isNotEmpty(), "four weeks of results should survive")
        assertEquals(32, d.league.teams.size)
    }

    @Test
    fun `every migration since M6 has run`() {
        val d = SaveFile.decode(bytes)
        assertTrue(d.league.teams.none { it.staff == Staff.UNASSIGNED }, "every club should have a staff")
        assertTrue(d.league.picks.isNotEmpty(), "every club should own its draft picks")
        val staff = d.team.staff
        assertTrue(d.league.coaches.getValue(staff.offCoordinator).tendencies.passRate != null,
            "coordinators should have tendencies")
    }

    @Test
    fun `an M6 save finishes its season and turns the year over`() {
        var d = SaveFile.decode(bytes)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        val champion = d.champion
        assertTrue(champion != null, "the season should crown somebody")
        d = DynastyEngine.advance(d)
        assertEquals(2027, d.year, "the year should turn over")
        assertEquals(DynastyPhase.REGULAR_SEASON, d.phase)
        assertTrue(d.league.history.season(2026) != null,
            "the season it finished should be the first it remembers")
        // And it saves again in today's format.
        val again = SaveFile.decode(SaveFile.encode(d))
        assertEquals(d.year, again.year)
    }
}
