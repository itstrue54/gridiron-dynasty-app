package com.nflsim.engine.offseason

import com.nflsim.engine.tuning.TuningTable
import com.nflsim.engine.model.DevCurve
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.model.Ratings
import com.nflsim.engine.rng.Rng
import kotlin.math.pow

/**
 * How players get better and worse.
 *
 * The shape that matters: mental ratings keep climbing into the early thirties
 * while physical ones fall away from the mid twenties. That is why an old
 * quarterback or safety stays useful and an old running back or corner does
 * not, and it is what makes the age of a roster a real decision rather than a
 * number on a screen (docs/SPEC.md 7.1).
 */
object Progression {

    /** Age a position peaks at, before the player's own peakAgeOffset. */
    private val PEAK_AGE: Map<PositionGroup, Int> = mapOf(
        PositionGroup.QB to 30,
        PositionGroup.RB to 25,
        PositionGroup.WR to 27,
        PositionGroup.TE to 28,
        PositionGroup.OL to 29,
        PositionGroup.EDGE to 27,
        PositionGroup.DT to 28,
        PositionGroup.LB to 27,
        PositionGroup.CB to 26,
        PositionGroup.S to 28,
        PositionGroup.ST to 32,
    )

    data class Context(
        val year: Int,
        /** 0..100, the position coach's development rating. */
        val coaching: Int = 60,
        /** Snaps taken last season. Playing time is how young players grow. */
        val snaps: Int = 0,
        /** The league's progression tuning. */
        val tuning: TuningTable.Progression = TuningTable.REALISTIC.progression,
    )

    data class Change(val player: Player, val delta: Int, val note: String?)

    fun progress(player: Player, ctx: Context, rng: Rng): Change {
        val tn = ctx.tuning
        val age = player.age(ctx.year)
        val peak = (PEAK_AGE[player.position.group] ?: 27) + player.traits.peakAgeOffset

        val ageFactor = ageFactor(age, peak, tn)
        val devMultiplier = player.traits.developmentCurve.multiplier
        val work = tn.workBase + tn.workRange * (player.traits.workEthic / 100f)
        // The base keeps league-mean coaching (65) at mean coachability (50) at
        // 0.9475, so the slope widens the gap between staffs without changing
        // how much the league develops overall.
        val coach = tn.coachBase + tn.coachSlope * (ctx.coaching / 100f) * (player.traits.coachability / 100f)
        val snaps = snapFactor(ctx.snaps, tn)

        // Growth is helped by everything; decline is not. A hard worker with a
        // great position coach does not stop being thirty five.
        // Growth is helped by everything; decline is only softened, never
        // reversed. The amplification here used to run 1.5 to 1.75, which took
        // more off the top of the league every year than development put back
        // at the bottom - the whole league drifted down about a third of a
        // point a season, which is invisible until you look at a decade.
        val raw = if (ageFactor > 0) {
            ageFactor * devMultiplier * work * coach * snaps
        } else {
            ageFactor * (tn.ageWorkBase - work * tn.ageWorkScale)
        }

        val noise = rng.gaussian(0f, tn.noise)
        var delta = raw + noise
        // Near the top a year's rise comes harder, the noise's included: most
        // players who reached 90 had drifted up from 88 a point at a time.
        if (delta > 0f) delta *= taper(player, tn)

        // Breakouts and collapses. These are the stories a dynasty is made of,
        // so they are discrete events rather than a wider spread on the noise.
        var note: String? = null
        if (age <= 26 && rng.nextFloat() < breakoutChance(player, tn)) {
            delta += tn.breakoutBase + rng.nextFloat() * tn.breakoutRange
            note = "took a leap"
        } else if (age >= 29 && rng.nextFloat() < collapseChance(player, age, peak, tn)) {
            delta -= tn.collapseBase + rng.nextFloat() * tn.collapseRange
            note = "fell off"
        }

        val physical = delta * tn.physicalShare
        val mental = if (delta > 0) delta * tn.mentalGrowth else delta * tn.mentalDecline
        // Mental ratings keep rising well past physical peak, slowly.
        val mentalFloor = if (age in peak..(peak + 6)) tn.mentalFloor else 0f

        val updated = player.copy(
            ratings = player.ratings.applyDelta(
                physical = physical,
                mental = mental + mentalFloor,
                other = delta * tn.otherShare,
            ),
            yearsInSystem = player.yearsInSystem + 1,
            yearsWithClub = player.clubYears + 1,
            accruedSeasons = player.accruedSeasons + 1,
        )
        return Change(updated, delta.toInt(), note)
    }

    /**
     * Positive while a player is climbing toward his peak, negative after, and
     * the decline accelerates. Raising the exponent above one is what makes a
     * player fall off a cliff rather than fading in a straight line.
     */
    private fun ageFactor(age: Int, peak: Int, tn: TuningTable.Progression): Float = when {
        age < peak -> ((peak - age) / (peak - 20f)).coerceIn(0f, 1f) * tn.youthGrowth
        age == peak -> tn.peakGrowth
        else -> -((age - peak).toFloat().pow(tn.declineExponent)) * tn.declineScale
    }

    /**
     * Near the top, a year's rise comes harder: a good player gets better,
     * but nobody drifts from 88 to 95 on the age curve and a few lucky
     * years. Without it the count of players rated 90+ climbed fourfold
     * over a dynasty's first decade (CALIBRATION.md pass 8). Breakouts are
     * left whole: they are the stories.
     */
    private fun taper(player: Player, tn: TuningTable.Progression): Float {
        if (tn.growthAtCeiling >= 1f) return 1f
        val ovr = com.nflsim.engine.ratings.overall(player)
        if (ovr <= tn.growthTaperFrom) return 1f
        val share = ((ovr - tn.growthTaperFrom).toFloat() / (99 - tn.growthTaperFrom)).coerceIn(0f, 1f)
        return 1f - (1f - tn.growthAtCeiling) * share
    }

    /** Playing time drives growth. A rookie who sits does not develop. */
    private fun snapFactor(snaps: Int, tn: TuningTable.Progression): Float = when {
        snaps >= tn.snapsHeavy -> tn.snapHeavy
        snaps >= tn.snapsRegular -> tn.snapRegular
        snaps >= tn.snapsSpot -> tn.snapSpot
        else -> tn.snapBench
    }

    private fun breakoutChance(player: Player, tn: TuningTable.Progression): Float {
        val base = when (player.traits.developmentCurve) {
            DevCurve.SLOW -> tn.breakoutSlow
            DevCurve.NORMAL -> tn.breakoutNormal
            DevCurve.QUICK -> tn.breakoutQuick
            DevCurve.SUPERSTAR -> tn.breakoutSuperstar
            DevCurve.X_FACTOR -> tn.breakoutXFactor
        }
        return base * (tn.breakoutWorkBase + player.traits.workEthic / 100f)
    }

    private fun collapseChance(player: Player, age: Int, peak: Int, tn: TuningTable.Progression): Float {
        val past = (age - peak).coerceAtLeast(0)
        return (tn.collapseRate * past * (tn.collapseDurability - player.traits.durabilityUnderLoad / 100f))
            .coerceIn(0f, 0.35f)
    }

    /** Whether a player hangs them up. */
    fun retires(player: Player, year: Int, overall: Int, rng: Rng, tn: TuningTable.Progression = TuningTable.REALISTIC.progression): Boolean {
        val age = player.age(year)
        if (age < 27) return false

        val byAge = when {
            age <= 28 -> tn.retireBy28
            age <= 30 -> tn.retireBy30
            age <= 32 -> tn.retireBy32
            age <= 34 -> tn.retireBy34
            age <= 36 -> tn.retireBy36
            age <= 38 -> 0.700f
            else -> 0.920f
        }
        // A player who can still play keeps playing; a replacement level
        // thirty-two year old is the one who quietly does not come back.
        val quality = ((72 - overall) / 55f).coerceIn(-0.35f, 0.55f)
        val loyalty = (player.traits.loyalty - 50) / 500f
        return rng.nextFloat() < (byAge * (1f + quality) - loyalty).coerceIn(0f, 0.97f)
    }
}
