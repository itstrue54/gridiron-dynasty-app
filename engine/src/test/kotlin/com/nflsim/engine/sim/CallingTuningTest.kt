package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 12: the play caller's and fourth down's numbers are the tuning table's, not the code's. */
class CallingTuningTest {

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }

    private fun ctx(tuning: TuningTable, state: PlayState = PlayState(down = 2, distance = 6, yardLine = 40)): PlayContext {
        val off = league.teams[0]; val def = league.teams[1]
        val os = SchemeCatalog[off.offenseScheme]; val ds = SchemeCatalog[def.defenseScheme]
        return PlayContext(
            offense = OffenseUnit.from(DepthChart.auto(league.roster(off.id), os), Personnel.P_11, os),
            defense = DefenseUnit.from(DepthChart.auto(league.roster(def.id), ds), DefensiveFront.FOUR_THREE_OVER, ds),
            state = state, tuning = tuning,
        )
    }

    @Test
    fun `the pass rate's bounds are the table's`() {
        val t = TuningTable.REALISTIC
        val allPass = t.copy(calling = t.calling.copy(passRateFloor = 1f, passRateCeiling = 1f))
        val allRun = t.copy(calling = t.calling.copy(passRateFloor = 0f, passRateCeiling = 0f))
        val calls = { tuning: TuningTable -> (0 until 200).map { PlayCaller.offense(ctx(tuning), SplitMixRng(it.toLong())) } }
        assertTrue(calls(allPass).all { it is OffensivePlayCall.Pass })
        assertTrue(calls(allRun).all { it is OffensivePlayCall.Run })
    }

    @Test
    fun `the sneak rate and the blitz adds are the table's`() {
        val t = TuningTable.REALISTIC
        val atTheOne = PlayState(down = 1, distance = 1, yardLine = 99)
        val sneaks = t.copy(calling = t.calling.copy(sneakRate = 1f, passRateFloor = 0f, passRateCeiling = 0f))
        assertTrue((0 until 100).all {
            (PlayCaller.offense(ctx(sneaks, atTheOne), SplitMixRng(it.toLong())) as OffensivePlayCall.Run).concept == RunConcept.QB_SNEAK
        })
        val thirdLong = PlayState(down = 3, distance = 9, yardLine = 40)
        val alwaysSend = t.copy(calling = t.calling.copy(blitzThirdLong = 1f))
        assertTrue((0 until 100).all { PlayCaller.defense(ctx(alwaysSend, thirdLong), SplitMixRng(it.toLong())).extraRushers > 0 })
    }

    @Test
    fun `fourth down's rates and the kicker's range are the table's`() {
        val t = TuningTable.REALISTIC.fourthDown
        val kicker = league.roster(league.teams.first().id).first { it.position == com.nflsim.engine.model.Position.K }
        val scheme = SchemeCatalog[league.teams.first().offenseScheme]
        // Fourth and two at midfield: never a chip shot, never desperate.
        val s = GameState(TeamId(1), TeamId(2), quarter = 2, secondsLeft = 600, yardLine = 50, down = 4, distance = 2)
        fun choices(tuning: TuningTable.FourthDown) = (0 until 100).map { FourthDown.decide(s, kicker, scheme, 0, 0.5f, SplitMixRng(it.toLong()), tuning) }
        assertTrue(choices(t.copy(goFloor = 1f, goCeiling = 1f)).all { it == FourthDownChoice.GO_FOR_IT })
        assertTrue(choices(t.copy(goFloor = 0f, goCeiling = 0f)).none { it == FourthDownChoice.GO_FOR_IT })
        assertEquals(t.rangeNoKicker, FourthDown.kickerRange(null, scheme, 0, t))
        assertEquals(FourthDown.kickerRange(kicker, scheme, 0, t) + 10, FourthDown.kickerRange(kicker, scheme, 0, t.copy(rangeBase = t.rangeBase + 10)))
    }

    @Test
    fun `in easy kicking range, short of a first down he sometimes goes for it`() {
        val t = TuningTable.REALISTIC.fourthDown
        val kicker = league.roster(league.teams.first().id).first { it.position == com.nflsim.engine.model.Position.K }
        val scheme = SchemeCatalog[league.teams.first().offenseScheme]
        fun goes(distance: Int, tuning: TuningTable.FourthDown, quarter: Int = 2, lead: Int = 0) = (0 until 400).count {
            val s = GameState(TeamId(1), TeamId(2), quarter = quarter, secondsLeft = 200, yardLine = 75, down = 4, distance = distance,
                homeScore = maxOf(lead, 0), awayScore = maxOf(-lead, 0), possession = Side.HOME)
            FourthDown.decide(s, kicker, scheme, 0, 0.5f, SplitMixRng(it.toLong()), tuning) == FourthDownChoice.GO_FOR_IT
        }
        // Fourth and four at the 25: kicked every time under the old rule, gone for some of the time now.
        assertEquals(0, goes(4, t.copy(kickAlwaysDistance = 3, goKickRange = 0f)))
        assertTrue(goes(4, t) > 0, "never goes for it on fourth and four in range")
        assertEquals(0, goes(t.kickAlwaysDistance, t), "goes for it at the kick-always distance")
        // The range bonus is the table's.
        assertTrue(goes(2, t) > goes(2, t.copy(goKickRange = 0f)))
        // Late and down three, the bonus is off: the kick ties it.
        assertEquals(goes(2, t.copy(goKickRange = 0f), quarter = 4, lead = -3), goes(2, t, quarter = 4, lead = -3))
    }

    @Test
    fun `screens are called at the table's rate, on early downs and third and long only`() {
        val t = TuningTable.REALISTIC
        fun screens(tuning: TuningTable, state: PlayState) = (0 until 300).count {
            // All passes, and no shot plays: a shot is never a screen.
            val c = ctx(tuning.copy(calling = tuning.calling.copy(passRateFloor = 1f, passRateCeiling = 1f)), state)
                .copy(offPlan = com.nflsim.engine.model.GamePlan(deepShotRate = 0f))
            val call = PlayCaller.offense(c, SplitMixRng(it.toLong()))
            (call as OffensivePlayCall.Pass).concept == PassConcept.SCREEN
        }
        val always = t.copy(calling = t.calling.copy(screenRate = 1f))
        val never = t.copy(calling = t.calling.copy(screenRate = 0f))
        // A short first-down aim can still draw a screen from the depth pool;
        // the rate is on top of that.
        val firstDown = PlayState(down = 1, distance = 10, yardLine = 40)
        assertTrue(screens(never, firstDown) < 60)
        assertEquals(300, screens(always, firstDown))
        assertEquals(300, screens(always, PlayState(down = 3, distance = t.calling.screenThirdDistance, yardLine = 40)))
        // Third and short, and inside the five, the rate adds none.
        for (s in listOf(PlayState(down = 3, distance = 4, yardLine = 40), PlayState(down = 1, distance = 4, yardLine = 96)))
            assertEquals(screens(never, s), screens(always, s), "$s")
    }
}

