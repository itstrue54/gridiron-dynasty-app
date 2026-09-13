package com.nflsim.engine.gen

import com.nflsim.engine.model.Coach
import com.nflsim.engine.model.CoachId
import com.nflsim.engine.model.CoachRole
import com.nflsim.engine.model.GamePlan
import com.nflsim.engine.model.Staff
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.rng.SplitMixRng

/**
 * A coach's tendencies (SPEC 5.4): the game plan's levers for his role,
 * drawn near his scheme's values. Two coaches running one scheme still call
 * games differently, and because every draw is centred on the scheme's value
 * the league's averages stay where they were.
 */
object Tendencies {

    fun draw(role: CoachRole, scheme: String, rng: Rng): GamePlan {
        val s = SchemeCatalog.find(scheme)
        fun near(mean: Float, sd: Float, lo: Float, hi: Float) = (mean + rng.gaussian(0f, sd)).coerceIn(lo, hi)
        return when (role) {
            CoachRole.OFFENSIVE_COORDINATOR -> GamePlan(
                passRate = near(s?.basePassRate ?: 0.55f, PASS_SD, 0.30f, 0.80f),
                playActionRate = near(s?.playActionRate ?: 0.22f, PLAY_ACTION_SD, 0.05f, 0.50f),
                trailingPassScale = near(1f, TRAILING_SD, 0.5f, 1.5f),
                twoMinutePassBoost = near(GamePlan.TWO_MINUTE_BOOST, TWO_MINUTE_SD, 0.10f, 0.45f),
            )
            CoachRole.DEFENSIVE_COORDINATOR -> GamePlan(
                blitzRate = near(s?.blitzRate ?: 0.25f, BLITZ_SD, 0.05f, 0.60f),
                manZoneSplit = near(s?.manZoneSplit ?: 0.45f, MAN_SD, 0.05f, 0.95f),
                doubleTeamRate = near(GamePlan.DOUBLE_TEAM_RATE, DOUBLE_SD, 0.02f, 0.35f),
            )
            CoachRole.HEAD_COACH -> GamePlan(fourthDownAggression = near(AGGRESSION_MEAN, AGGRESSION_SD, 0.10f, 0.95f))
            else -> GamePlan()
        }
    }

    /** A coach from a save written before tendencies, given his from his own id: the same every time. */
    fun forExisting(coach: Coach, seed: Long): Coach =
        if (coach.tendencies != GamePlan()) coach
        else coach.copy(tendencies = draw(coach.role, coach.scheme, SplitMixRng(seed).split("tendencies|${coach.id.v}")))

    /**
     * What a staff calls from: its offensive coordinator's pass game, its
     * defensive coordinator's pressure and coverage, its head coach's fourth
     * down. A club's own game plan goes over this.
     */
    fun of(staff: Staff, coaches: Map<CoachId, Coach>): GamePlan {
        val oc = coaches[staff.offCoordinator]?.tendencies ?: GamePlan()
        val dc = coaches[staff.defCoordinator]?.tendencies ?: GamePlan()
        val hc = coaches[staff.headCoach]?.tendencies ?: GamePlan()
        return GamePlan(
            passRate = oc.passRate, playActionRate = oc.playActionRate, deepShotRate = oc.deepShotRate,
            trailingPassScale = oc.trailingPassScale, twoMinutePassBoost = oc.twoMinutePassBoost,
            blitzRate = dc.blitzRate, manZoneSplit = dc.manZoneSplit, doubleTeamRate = dc.doubleTeamRate,
            fourthDownAggression = hc.fourthDownAggression,
        )
    }

    /**
     * Spreads around the scheme's values: half the first try's, which took
     * close games just under their band over 4,000 calibration games (0.19 to
     * 0.18) as clubs that call games differently played more lopsided ones.
     */
    private const val PASS_SD = 0.02f
    private const val PLAY_ACTION_SD = 0.02f
    private const val TRAILING_SD = 0.10f
    private const val TWO_MINUTE_SD = 0.025f
    private const val BLITZ_SD = 0.025f
    private const val MAN_SD = 0.04f
    private const val DOUBLE_SD = 0.02f

    /** The average of the figure clubs used to derive from their id, so fourth downs league-wide hold. */
    private const val AGGRESSION_MEAN = 0.53f
    private const val AGGRESSION_SD = 0.06f
}
