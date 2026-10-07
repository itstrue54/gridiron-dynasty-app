package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.Ratings
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaySimulatorTest {

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }

    private fun context(offAbbrev: String = "KC", defAbbrev: String = "SEA"): PlayContext {
        val offTeam = league.teams.first { it.abbrev == offAbbrev }
        val defTeam = league.teams.first { it.abbrev == defAbbrev }
        val offScheme = SchemeCatalog[offTeam.offenseScheme]
        val defScheme = SchemeCatalog[defTeam.defenseScheme]
        return PlayContext(
            offense = OffenseUnit.from(
                DepthChart.auto(league.roster(offTeam.id), offScheme), Personnel.P_11, offScheme),
            defense = DefenseUnit.from(
                DepthChart.auto(league.roster(defTeam.id), defScheme),
                DefensiveFront.FOUR_THREE_OVER, defScheme),
            state = PlayState(),
            crowdNoise = defTeam.stadium.crowdNoise,
        )
    }

    @Test
    fun `the gap scheme table scores every concept against every front`() {
        val expected = RunConcept.entries.size * DefensiveFront.entries.size
        assertEquals(expected, GapSchemeTable.coverage(),
            "every run concept needs a value against every defensive front")
    }

    @Test
    fun `ten thousand plays run without throwing`() {
        val ctx = context()
        val rng = SplitMixRng(1L)
        repeat(10_000) {
            val state = PlayState(
                down = 1 + rng.nextInt(4),
                distance = 1 + rng.nextInt(20),
                yardLine = 1 + rng.nextInt(98),
                scoreDiff = -21 + rng.nextInt(43),
            )
            val situational = ctx.copy(state = state)
            val off = PlayCaller.offense(situational, rng)
            val def = PlayCaller.defense(situational, rng)
            val result = PlaySimulator.simPlay(situational, off, def, rng)
            assertTrue(result.yards >= -18 && result.yards <= 99,
                "absurd yardage ${result.yards} on ${result.log.narrative}")
            assertTrue(result.clockRunoff in 0..45, "clock runoff ${result.clockRunoff}")
        }
    }

    @Test
    fun `rushing yardage lands in a football-shaped distribution`() {
        val ctx = context()
        val rng = SplitMixRng(7L)
        val gains = mutableListOf<Int>()
        repeat(20_000) {
            val call = OffensivePlayCall.Run(RunConcept.INSIDE_ZONE)
            val def = DefensivePlayCall(DefensiveFront.FOUR_THREE_OVER, Coverage.COVER_3)
            val r = PlaySimulator.simPlay(ctx, call, def, rng)
            if (r.outcome == PlayOutcome.RUN && r.penalty == null) gains += r.yards
        }
        val mean = gains.average()
        val sorted = gains.sorted()
        val median = sorted[sorted.size / 2]
        val negatives = gains.count { it < 0 }.toDouble() / gains.size
        val explosive = gains.count { it >= 20 }.toDouble() / gains.size

        assertTrue(mean in 3.4..5.6, "yards per carry was $mean")
        assertTrue(median in 2..5, "median carry was $median")
        assertTrue(negatives in 0.05..0.22, "$negatives of carries lost yardage")
        assertTrue(explosive in 0.004..0.06, "$explosive of carries went 20+")
    }

    @Test
    fun `a lead back past his carry cap hands off to the next back`() {
        val ctx = context()
        val lead = ctx.offense.backfield.first()
        val capped = ctx.copy(carries = { id -> if (id == lead.id.v) 30 else 0 },
            tuning = ctx.tuning.copy(rushing = ctx.tuning.rushing.copy(leadBackCarryCap = 25)))
        fun leadShare(c: PlayContext): Double {
            val rng = SplitMixRng(11L)
            var byLead = 0; var runs = 0
            repeat(2_000) {
                val r = PlaySimulator.simPlay(c, OffensivePlayCall.Run(RunConcept.INSIDE_ZONE),
                    DefensivePlayCall(DefensiveFront.FOUR_THREE_OVER, Coverage.COVER_3), rng)
                if (r.outcome == PlayOutcome.RUN) { runs++; if (r.ballCarrier == lead.id) byLead++ }
            }
            return byLead.toDouble() / runs
        }
        assertTrue(leadShare(ctx) > 0.6, "a fresh lead back should take most handoffs")
        assertEquals(0.0, leadShare(capped), "a lead back past his cap still carried")
    }

    @Test
    fun `passing produces plausible completion and sack rates`() {
        val ctx = context()
        val rng = SplitMixRng(9L)
        var attempts = 0; var completions = 0; var sacks = 0; var picks = 0
        var yards = 0
        repeat(20_000) {
            val call = OffensivePlayCall.Pass(PassConcept.CURL)
            val def = DefensivePlayCall(DefensiveFront.NICKEL_FOUR_TWO, Coverage.COVER_3)
            val r = PlaySimulator.simPlay(ctx, call, def, rng)
            if (r.penalty != null) return@repeat
            when (r.outcome) {
                PlayOutcome.COMPLETION -> { attempts++; completions++; yards += r.yards }
                PlayOutcome.INCOMPLETE -> attempts++
                PlayOutcome.INTERCEPTION -> { attempts++; picks++ }
                PlayOutcome.SACK -> sacks++
                else -> {}
            }
        }
        val completionPct = completions.toDouble() / attempts
        val sackRate = sacks.toDouble() / (attempts + sacks)
        val intRate = picks.toDouble() / attempts
        val yardsPerAttempt = yards.toDouble() / attempts

        assertTrue(completionPct in 0.52..0.78, "completion rate was $completionPct")
        assertTrue(sackRate in 0.02..0.14, "sack rate was $sackRate")
        assertTrue(intRate in 0.005..0.06, "interception rate was $intRate")
        assertTrue(yardsPerAttempt in 4.5..11.0, "yards per attempt was $yardsPerAttempt")
    }

    @Test
    fun `a better offensive line runs the ball better`() {
        val ctx = context()
        fun meanYards(lineOverride: Int): Double {
            val line = ctx.offense.line.map { it.copy(ratings = Ratings.uniform(lineOverride)) }
            val tuned = ctx.copy(offense = ctx.offense.copy(line = line))
            val rng = SplitMixRng(3L)
            val gains = mutableListOf<Int>()
            repeat(6000) {
                val r = PlaySimulator.simPlay(
                    tuned,
                    OffensivePlayCall.Run(RunConcept.POWER),
                    DefensivePlayCall(DefensiveFront.FOUR_THREE_OVER, Coverage.COVER_3),
                    rng,
                )
                if (r.outcome == PlayOutcome.RUN && r.penalty == null) gains += r.yards
            }
            return gains.average()
        }
        val elite = meanYards(95)
        val awful = meanYards(50)
        assertTrue(elite - awful > 0.8,
            "an elite line gained $elite, a bad one $awful - blocking barely mattered")
    }

    @Test
    fun `pressure rises when the defense sends more than the offense can block`() {
        val ctx = context()
        fun pressureRate(extraRushers: Int, extraProtectors: Int): Double {
            val rng = SplitMixRng(4L)
            var pressure = 0.0
            var n = 0
            repeat(3000) {
                val r = PlaySimulator.simPlay(
                    ctx,
                    OffensivePlayCall.Pass(PassConcept.DIG, extraProtectors = extraProtectors),
                    DefensivePlayCall(DefensiveFront.FOUR_THREE_OVER, Coverage.COVER_1,
                        extraRushers = extraRushers),
                    rng,
                )
                r.log["pressureChance"]?.let { pressure += it; n++ }
            }
            return pressure / n
        }
        assertTrue(pressureRate(2, 0) > pressureRate(0, 0),
            "blitzing did not raise pressure")
        assertTrue(pressureRate(0, 2) < pressureRate(0, 0),
            "keeping extra blockers in did not lower pressure")
    }

    @Test
    fun `scheme matchup changes what a run concept is worth`() {
        val ctx = context()
        fun meanYards(concept: RunConcept, front: DefensiveFront): Double {
            val rng = SplitMixRng(6L)
            val gains = mutableListOf<Int>()
            repeat(6000) {
                val r = PlaySimulator.simPlay(
                    ctx, OffensivePlayCall.Run(concept),
                    DefensivePlayCall(front, Coverage.COVER_3), rng,
                )
                if (r.outcome == PlayOutcome.RUN && r.penalty == null) gains += r.yards
            }
            return gains.average()
        }
        // Outside zone against a two-gap front that holds its ground is a bad idea.
        val vsTwoGap = meanYards(RunConcept.OUTSIDE_ZONE, DefensiveFront.THREE_FOUR_TWO_GAP)
        val vsDime = meanYards(RunConcept.OUTSIDE_ZONE, DefensiveFront.DIME_FOUR_ONE)
        assertTrue(vsDime - vsTwoGap > 1.0,
            "outside zone gained $vsTwoGap vs two-gap and $vsDime vs dime - front barely mattered")
    }

    @Test
    fun `every play carries a narrative line and its working`() {
        val ctx = context()
        val rng = SplitMixRng(12L)
        repeat(400) {
            val off = PlayCaller.offense(ctx, rng)
            val def = PlayCaller.defense(ctx, rng)
            val r = PlaySimulator.simPlay(ctx, off, def, rng)
            assertTrue(r.log.narrative.isNotBlank(), "a play produced no narrative")
        }
    }

    @Test
    fun `the same seed replays the same play exactly`() {
        val ctx = context()
        val call = OffensivePlayCall.Pass(PassConcept.POST)
        val def = DefensivePlayCall(DefensiveFront.FOUR_THREE_OVER, Coverage.COVER_2_ZONE)
        val a = PlaySimulator.simPlay(ctx, call, def, SplitMixRng(42L))
        val b = PlaySimulator.simPlay(ctx, call, def, SplitMixRng(42L))
        assertEquals(a.outcome, b.outcome)
        assertEquals(a.yards, b.yards)
        assertEquals(a.log.narrative, b.log.narrative)
    }

    @Test
    fun `penalties happen but do not take over the game`() {
        val ctx = context()
        val rng = SplitMixRng(15L)
        var flagged = 0
        val n = 20_000
        repeat(n) {
            val off = PlayCaller.offense(ctx, rng)
            val def = PlayCaller.defense(ctx, rng)
            if (PlaySimulator.simPlay(ctx, off, def, rng).penalty != null) flagged++
        }
        val rate = flagged.toDouble() / n
        // Roughly 6 accepted flags per team per game over ~65 snaps.
        assertTrue(rate in 0.02..0.20, "penalty rate per play was $rate")
    }

    @Test
    fun `a disciplined head coach's side is flagged less, and an average one as before`() {
        val t = com.nflsim.engine.tuning.TuningTable.REALISTIC.penalties
        assertEquals(1f, coachFlags(null, t))
        assertEquals(1f, coachFlags(t.coachDisciplineMean.toInt(), t))
        assertTrue(coachFlags(30, t) > 1f && coachFlags(100, t) < 1f)
        // Centred: two coaches the same distance either side average out.
        assertEquals(1f, (coachFlags(45, t) + coachFlags(85, t)) / 2f, 1e-6f)

        fun flags(offFlags: Float, defFlags: Float, onOffence: Boolean): Int {
            val ctx = context().copy(offFlags = offFlags, defFlags = defFlags)
            val rng = SplitMixRng(16L)
            var n = 0
            repeat(20_000) {
                val p = PlaySimulator.simPlay(ctx, PlayCaller.offense(ctx, rng), PlayCaller.defense(ctx, rng), rng).penalty
                if (p != null && p.type.onOffense == onOffence) n++
            }
            return n
        }
        val loose = coachFlags(30, t)
        val tight = coachFlags(100, t)
        assertTrue(flags(loose, 1f, true) > flags(tight, 1f, true), "a loose offence jumps and holds more")
        assertTrue(flags(1f, loose, false) > flags(1f, tight, false), "a loose defence jumps and interferes more")
    }

    @Test
    fun `a stacked box makes running harder`() {
        val ctx = context()
        fun meanYards(boxAdd: Int): Double {
            val rng = SplitMixRng(8L)
            val gains = mutableListOf<Int>()
            repeat(5000) {
                val r = PlaySimulator.simPlay(
                    ctx, OffensivePlayCall.Run(RunConcept.INSIDE_ZONE),
                    DefensivePlayCall(DefensiveFront.FOUR_THREE_OVER, Coverage.COVER_1, boxAdd = boxAdd),
                    rng,
                )
                if (r.outcome == PlayOutcome.RUN && r.penalty == null) gains += r.yards
            }
            return gains.average()
        }
        assertTrue(meanYards(0) - meanYards(2) > 0.5, "stacking the box did nothing")
    }

    @Test
    fun `a play never gains more than the field has left`() {
        val ctx = context().copy(state = PlayState(down = 1, distance = 3, yardLine = 97))
        val rng = SplitMixRng(21L)
        repeat(2000) {
            val off = PlayCaller.offense(ctx, rng)
            val def = PlayCaller.defense(ctx, rng)
            val r = PlaySimulator.simPlay(ctx, off, def, rng)
            assertTrue(r.yards <= 3, "gained ${r.yards} from the opponent 3 yard line")
        }
    }

    @Test
    fun `a great quarterback completes more than a poor one`() {
        val ctx = context()
        fun completionRate(rating: Int): Double {
            val qb = ctx.offense.quarterback.copy(ratings = Ratings.uniform(rating)
                .with(RatingId.THROW_ACC_MID to rating, RatingId.THROW_ACC_SHORT to rating))
            val tuned = ctx.copy(offense = ctx.offense.copy(quarterback = qb))
            val rng = SplitMixRng(5L)
            var att = 0; var comp = 0
            repeat(5000) {
                val r = PlaySimulator.simPlay(
                    tuned, OffensivePlayCall.Pass(PassConcept.CURL),
                    DefensivePlayCall(DefensiveFront.NICKEL_FOUR_TWO, Coverage.COVER_3), rng)
                if (r.penalty != null) return@repeat
                if (r.outcome == PlayOutcome.COMPLETION) { att++; comp++ }
                else if (r.outcome == PlayOutcome.INCOMPLETE || r.outcome == PlayOutcome.INTERCEPTION) att++
            }
            return comp.toDouble() / att
        }
        val elite = completionRate(95)
        val poor = completionRate(55)
        assertTrue(elite - poor > 0.05, "elite QB completed $elite, poor QB $poor")
    }
}
