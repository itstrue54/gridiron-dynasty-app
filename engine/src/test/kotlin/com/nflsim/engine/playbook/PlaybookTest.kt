package com.nflsim.engine.playbook

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.SchemeSide
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.sim.DefenseUnit
import com.nflsim.engine.sim.DefensiveFront
import com.nflsim.engine.sim.DepthChart
import com.nflsim.engine.sim.OffenseUnit
import com.nflsim.engine.sim.Personnel
import com.nflsim.engine.sim.PlayCaller
import com.nflsim.engine.sim.PlayContext
import com.nflsim.engine.sim.PlayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SPEC 5.4: every scheme has a deep book, and the coordinator's calls are in it. */
class PlaybookTest {

    @Test
    fun `every scheme has a deep book of formations and plays`() {
        SchemeCatalog.all.forEach { s ->
            val book = Playbooks.forScheme(s.id)
            assertEquals(s.id, book.scheme)
            assertTrue(book.formations.size >= 4, "${s.id}: ${book.formations.size} formations")
            assertTrue(book.plays.size >= 50, "${s.id}: ${book.plays.size} plays")
            book.formations.forEach { f ->
                assertEquals(f.plays.size, f.plays.map { it.name }.distinct().size, "${s.id} ${f.name} repeats a name")
                if (s.side == SchemeSide.OFFENSE) {
                    assertNotNull(f.personnel, "${s.id} ${f.name}")
                    f.plays.forEach { p ->
                        assertTrue((p.run == null) != (p.pass == null), "${p.name}: a run or a pass")
                        Playbooks.offense(f, p)
                    }
                } else {
                    assertNotNull(f.front, "${s.id} ${f.name}")
                    f.plays.forEach { p -> Playbooks.defense(f, p) }
                }
            }
        }
    }

    private val league by lazy { LeagueGenerator.generate(2026, 9L) }

    /** Thousands of snaps across the field and the clock. */
    private fun states(): List<PlayState> {
        val rng = SplitMixRng(3L)
        return (1..2500).map {
            PlayState(
                down = 1 + rng.nextInt(4), distance = 1 + rng.nextInt(20), yardLine = 1 + rng.nextInt(98),
                quarter = 1 + rng.nextInt(4), secondsLeftInQuarter = rng.nextInt(900), scoreDiff = -21 + rng.nextInt(43),
            )
        }
    }

    @Test
    fun `every call an offensive coordinator makes is a play in his book`() {
        val roster = league.roster(league.teams.first().id)
        val defScheme = SchemeCatalog.all.first { it.side == SchemeSide.DEFENSE }
        SchemeCatalog.all.filter { it.side == SchemeSide.OFFENSE }.forEach { s ->
            val book = Playbooks.forScheme(s.id)
            val base = PlayContext(
                offense = OffenseUnit.from(DepthChart.auto(roster, s), Personnel.P_11, s),
                defense = DefenseUnit.from(DepthChart.auto(roster, defScheme), DefensiveFront.FOUR_THREE_OVER, defScheme),
                state = PlayState(),
            )
            val rng = SplitMixRng(s.id.hashCode().toLong())
            states().forEach { st ->
                val call = PlayCaller.offense(base.copy(state = st), rng)
                assertNotNull(Playbooks.nameOf(book, call), "${s.id} has nothing for $call at $st")
            }
        }
    }

    @Test
    fun `every call a defensive coordinator makes is a play in his book`() {
        val roster = league.roster(league.teams.first().id)
        val offScheme = SchemeCatalog.all.first { it.side == SchemeSide.OFFENSE }
        SchemeCatalog.all.filter { it.side == SchemeSide.DEFENSE }.forEach { s ->
            val book = Playbooks.forScheme(s.id)
            val base = PlayContext(
                offense = OffenseUnit.from(DepthChart.auto(roster, offScheme), Personnel.P_11, offScheme),
                defense = DefenseUnit.from(DepthChart.auto(roster, s), DefensiveFront.FOUR_THREE_OVER, s),
                state = PlayState(),
            )
            val rng = SplitMixRng(s.id.hashCode().toLong())
            states().forEach { st ->
                val call = PlayCaller.defense(base.copy(state = st), rng)
                assertNotNull(Playbooks.nameOf(book, call), "${s.id} has nothing for $call at $st")
            }
        }
    }

    @Test
    fun `a play on the sheet is the call it names`() {
        val book = Playbooks.forScheme("OFF_WEST_COAST")
        val (f, p) = book.plays.first { it.second.pass != null && it.second.playAction }
        val call = Playbooks.offense(f, p)
        assertEquals(f to p, Playbooks.nameOf(book, call), "a book play reads back as itself")
    }
}
