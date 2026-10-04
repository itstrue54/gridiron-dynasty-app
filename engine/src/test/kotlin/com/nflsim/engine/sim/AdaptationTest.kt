package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.GamePlan
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import com.nflsim.engine.tuning.TuningTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 5.4: coordinators adapt to what they see, within a window, as far as the head coach's adjustments allow. */
class AdaptationTest {

    private val t = TuningTable.REALISTIC.adaptation

    @Test
    fun `nothing moves before enough snaps`() {
        assertEquals(Adaptation.Shift(), Adaptation.of(1, t.minSnaps - 2, 5, t.minSnaps - 1, 100, 100, t))
    }

    @Test
    fun `a defence answers the run with the box and the pass with pressure`() {
        val runHeavy = Adaptation.of(runs = 16, passes = 4, boxSum = 0, defSnaps = 0, 50, 100, t)
        assertTrue(runHeavy.box > 0f && runHeavy.blitz < 0f, "$runHeavy")
        val passHeavy = Adaptation.of(runs = 4, passes = 16, boxSum = 0, defSnaps = 0, 50, 100, t)
        assertTrue(passHeavy.blitz > 0f && passHeavy.box < 0f, "$passHeavy")
        // Never past its window - the box's own, the blitz's the rates' - and
        // nothing at all from a staff that never adjusts.
        assertTrue(kotlin.math.abs(runHeavy.box) <= t.boxWindow && kotlin.math.abs(passHeavy.blitz) <= t.window)
        assertTrue(kotlin.math.abs(runHeavy.box) > kotlin.math.abs(runHeavy.blitz), "the box moves further than the rates: $runHeavy")
        assertEquals(Adaptation.Shift(), Adaptation.of(16, 4, 0, 0, 0, 0, t))
        // Half the rating, half the reach.
        assertEquals(runHeavy.box / 2, Adaptation.of(16, 4, 0, 0, 50, 50, t).box, 1e-6f)
    }

    @Test
    fun `an offence answers a loaded box by throwing, and a light one by running`() {
        assertTrue(Adaptation.of(0, 0, boxSum = 12, defSnaps = 12, 100, 50, t).pass > 0f)
        assertTrue(Adaptation.of(0, 0, boxSum = -8, defSnaps = 12, 100, 50, t).pass < 0f)
    }

    @Test
    fun `a predictable offence gets punished by a staff that notices`() {
        val league = LeagueGenerator.generate(2026, 2026L)
        val teams = WeekRunner.teams(league, league.tuning).values.toList()
        // An offence that runs nine times in ten, against the same defence
        // coached by a staff rated 0 for adjustments and one rated 100.
        val runner = teams[0].let { GameTeam(it.team, it.roster, it.offScheme, it.defScheme, staffPlan = GamePlan(passRate = 0.05f), adjustments = 50) }
        fun defence(adj: Int) = teams[1].let { GameTeam(it.team, it.roster, it.offScheme, it.defScheme, it.aggression, it.staffPlan, adj) }
        fun yardsPerCarry(adj: Int): Double {
            var yards = 0; var carries = 0
            repeat(150) { i ->
                val g = GameSimulator(runner, defence(adj), league.tuning).simulate(SplitMixRng(i.toLong()))
                yards += g.boxScore.home.rushYards; carries += g.boxScore.home.rushAttempts
            }
            return yards.toDouble() / carries
        }
        val blind = yardsPerCarry(0)
        val sharp = yardsPerCarry(100)
        assertTrue(sharp < blind, "the box loads against him: $sharp against $blind a carry")
    }

    @Test
    fun `the opening snaps play as they would without adaptation`() {
        val league = LeagueGenerator.generate(2026, 2026L)
        val teams = WeekRunner.teams(league, league.tuning).values.toList()
        val off = league.tuning.copy(adaptation = t.copy(window = 0f))
        val with = GameSimulator(teams[2], teams[3], league.tuning).simulate(SplitMixRng(5L)).playByPlay
        val without = GameSimulator(teams[2], teams[3], off).simulate(SplitMixRng(5L)).playByPlay
        assertEquals(without.take(t.minSnaps), with.take(t.minSnaps))
    }
}
