package com.nflsim.engine.offseason

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
    )

    data class Change(val player: Player, val delta: Int, val note: String?)

    fun progress(player: Player, ctx: Context, rng: Rng): Change {
        val age = player.age(ctx.year)
        val peak = (PEAK_AGE[player.position.group] ?: 27) + player.traits.peakAgeOffset

        val ageFactor = ageFactor(age, peak)
        val devMultiplier = player.traits.developmentCurve.multiplier
        val work = 0.75f + 0.5f * (player.traits.workEthic / 100f)
        val coach = 0.85f + 0.30f * (ctx.coaching / 100f) * (player.traits.coachability / 100f)
        val snaps = snapFactor(ctx.snaps)

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
            ageFactor * (1.20f - work * 0.20f)
        }

        val noise = rng.gaussian(0f, 1.5f)
        var delta = raw + noise

        // Breakouts and collapses. These are the stories a dynasty is made of,
        // so they are discrete events rather than a wider spread on the noise.
        var note: String? = null
        if (age <= 26 && rng.nextFloat() < breakoutChance(player)) {
            delta += 4f + rng.nextFloat() * 5f
            note = "took a leap"
        } else if (age >= 29 && rng.nextFloat() < collapseChance(player, age, peak)) {
            delta -= 5f + rng.nextFloat() * 6f
            note = "fell off"
        }

        val physical = delta * 1.15f
        val mental = if (delta > 0) delta * 0.7f else delta * 0.35f
        // Mental ratings keep rising well past physical peak, slowly.
        val mentalFloor = if (age in peak..(peak + 6)) 0.55f else 0f

        val updated = player.copy(
            ratings = player.ratings.applyDelta(
                physical = physical,
                mental = mental + mentalFloor,
                other = delta * 0.9f,
            ),
            yearsInSystem = player.yearsInSystem + 1,
            accruedSeasons = player.accruedSeasons + 1,
        )
        return Change(updated, delta.toInt(), note)
    }

    /**
     * Positive while a player is climbing toward his peak, negative after, and
     * the decline accelerates. Raising the exponent above one is what makes a
     * player fall off a cliff rather than fading in a straight line.
     */
    private fun ageFactor(age: Int, peak: Int): Float = when {
        age < peak -> ((peak - age) / (peak - 20f)).coerceIn(0f, 1f) * 2.6f
        age == peak -> 0.3f
        else -> -((age - peak).toFloat().pow(1.28f)) * 0.62f
    }

    /** Playing time drives growth. A rookie who sits does not develop. */
    private fun snapFactor(snaps: Int): Float = when {
        snaps >= 800 -> 1.25f
        snaps >= 450 -> 1.05f
        snaps >= 150 -> 0.85f
        else -> 0.62f
    }

    private fun breakoutChance(player: Player): Float {
        val base = when (player.traits.developmentCurve) {
            DevCurve.SLOW -> 0.010f
            DevCurve.NORMAL -> 0.025f
            DevCurve.QUICK -> 0.055f
            DevCurve.SUPERSTAR -> 0.090f
            DevCurve.X_FACTOR -> 0.140f
        }
        return base * (0.6f + player.traits.workEthic / 100f)
    }

    private fun collapseChance(player: Player, age: Int, peak: Int): Float {
        val past = (age - peak).coerceAtLeast(0)
        return (0.012f * past * (1.4f - player.traits.durabilityUnderLoad / 100f))
            .coerceIn(0f, 0.35f)
    }

    /** Whether a player hangs them up. */
    fun retires(player: Player, year: Int, overall: Int, rng: Rng): Boolean {
        val age = player.age(year)
        if (age < 27) return false

        val byAge = when {
            age <= 28 -> 0.006f
            age <= 30 -> 0.030f
            age <= 32 -> 0.095f
            age <= 34 -> 0.230f
            age <= 36 -> 0.450f
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
