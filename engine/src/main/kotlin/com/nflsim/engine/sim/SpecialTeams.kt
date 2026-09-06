package com.nflsim.engine.sim

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.rng.Rng
import kotlin.math.roundToInt

data class KickResult(val good: Boolean, val distance: Int, val narrative: String)

data class PuntResult(
    val netYards: Int,
    val touchback: Boolean,
    val returnYards: Int,
    val narrative: String,
)

/**
 * Kicking, punting and returns.
 *
 * Field goals are the place altitude and weather show up most obviously, and
 * the distance curve matters: a 38 yarder should be routine and a 55 yarder
 * should be a real decision. Getting that curve wrong changes fourth-down
 * strategy across the whole league.
 */
object SpecialTeams {

    fun fieldGoal(
        kicker: Player?,
        yardsToGoal: Int,
        scheme: Scheme,
        altitudeFt: Int,
        rng: Rng,
        clutch: Boolean = false,
    ): KickResult {
        // Snap, hold, and seven yards of backfield, plus the ten yard end zone.
        val distance = yardsToGoal + 17
        if (kicker == null) return KickResult(false, distance, "No kicker available.")

        val power = rate(kicker, RatingId.KICK_POWER, scheme)
        val accuracy = rate(kicker, RatingId.KICK_ACCURACY, scheme)

        // Beyond his range it falls apart quickly rather than gradually.
        val range = 42 + (power / 99f) * 22f + (altitudeFt / 5280f) * 4f
        val over = distance - range

        var chance = when {
            distance <= 25 -> 0.985f
            else -> logistic((range - distance) / 6.2f) * (0.72f + accuracy / 260f)
        }
        if (over > 0) chance *= (1f - (over / 26f)).coerceAtLeast(0.05f)
        if (clutch) chance *= 0.94f + (kicker.traits.clutch / 99f) * 0.09f
        chance = chance.coerceIn(0.005f, 0.995f)

        val good = rng.nextFloat() < chance
        val text = if (good) "${kicker.lastName}'s $distance yard attempt is good."
        else "${kicker.lastName} misses from $distance."
        return KickResult(good, distance, text)
    }

    fun punt(
        punter: Player?,
        returner: Player?,
        yardLine: Int,
        puntScheme: Scheme,
        returnScheme: Scheme,
        rng: Rng,
    ): PuntResult {
        val yardsToGoal = 100 - yardLine
        if (punter == null) {
            return PuntResult(35, false, 0, "The punt travels 35 yards.")
        }

        val power = rate(punter, RatingId.PUNT_POWER, puntScheme)
        val placement = rate(punter, RatingId.PUNT_ACCURACY, puntScheme)

        var gross = (38f + (power - 70) * 0.30f + rng.gaussian(0f, 5.5f)).roundToInt()

        // Inside the fifty a punter aims for the coffin corner rather than distance.
        if (yardsToGoal < 45) {
            val aim = (yardsToGoal - 6 - (99 - placement) * 0.08f).roundToInt()
            gross = minOf(gross, aim.coerceAtLeast(12))
        }

        if (yardLine + gross >= 100) {
            val touchbackChance = 0.62f - (placement - 70) * 0.006f
            if (rng.nextFloat() < touchbackChance.coerceIn(0.15f, 0.9f)) {
                return PuntResult(
                    netYards = (80 - yardLine).coerceAtLeast(5),
                    touchback = true, returnYards = 0,
                    narrative = "${punter.lastName}'s punt sails into the end zone. Touchback.",
                )
            }
            gross = (99 - yardLine) - 1
        }

        // Returns are rare and mostly short; the occasional one is not.
        var ret = 0
        if (returner != null && rng.nextFloat() < 0.42f) {
            val speed = rate(returner, RatingId.SPEED, returnScheme)
            val elusive = rate(returner, RatingId.ELUSIVENESS, returnScheme)
            ret = (rng.exponential(6.5f) + (speed + elusive - 150) * 0.05f)
                .roundToInt().coerceIn(0, 60)
        }

        val net = (gross - ret).coerceAtLeast(1)
        val text = buildString {
            append("${punter.lastName} punts $gross yards")
            if (ret > 12) append(", returned $ret by ${returner?.lastName}")
            else if (ret > 0) append(", returned $ret")
            append(".")
        }
        return PuntResult(net, false, ret, text)
    }

    fun extraPoint(kicker: Player?, scheme: Scheme, rng: Rng): Boolean {
        if (kicker == null) return rng.nextFloat() < 0.90f
        val accuracy = rate(kicker, RatingId.KICK_ACCURACY, scheme)
        return rng.nextFloat() < (0.905f + accuracy / 1400f).coerceAtMost(0.985f)
    }

    /** Where the receiving team starts after a kickoff. */
    fun kickoff(returner: Player?, returnScheme: Scheme, rng: Rng): Pair<Int, String> {
        if (rng.nextFloat() < 0.63f) {
            return GameState.TOUCHBACK_YARD_LINE to "Touchback."
        }
        val base = 22
        val bonus = if (returner == null) 0 else {
            val speed = rate(returner, RatingId.SPEED, returnScheme)
            ((speed - 70) * 0.12f + rng.gaussian(0f, 5f)).roundToInt()
        }
        val spot = (base + bonus).coerceIn(4, 60)
        val text = if (spot >= 45) "A big return out to the $spot." else "Returned to the $spot."
        return spot to text
    }

    fun kickerFor(depth: DepthChart): Player? = depth.starter(Position.K)
    fun punterFor(depth: DepthChart): Player? = depth.starter(Position.P)

    /** Fastest skill player available takes returns. */
    fun returnerFor(depth: DepthChart, scheme: Scheme): Player? =
        (depth.at(Position.WR) + depth.at(Position.RB) + depth.at(Position.CB))
            .maxByOrNull { rate(it, RatingId.SPEED, scheme) }
}
