package com.nflsim.engine.sim

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.rng.Rng

enum class FourthDownChoice { GO_FOR_IT, PUNT, FIELD_GOAL }

/**
 * What a coordinator does on fourth down.
 *
 * This one function shapes scoring more than almost anything else in the sim.
 * Too aggressive and points per game runs away; too conservative and drives
 * die at midfield all afternoon. The aggression figure comes from the head
 * coach and is what a user will feel most directly when they hire one.
 */
object FourthDown {

    fun decide(
        state: GameState,
        kicker: Player?,
        kickScheme: Scheme,
        altitudeFt: Int,
        aggression: Float,
        rng: Rng,
    ): FourthDownChoice {
        val yardsToGoal = 100 - state.yardLine
        val kickDistance = yardsToGoal + 17
        val range = kickerRange(kicker, kickScheme, altitudeFt)
        val kickable = kickDistance <= range

        val trailing = state.scoreDiff < 0
        val lateAndTrailing = state.quarter >= 4 && state.secondsLeft < 300 && trailing
        val desperate = state.quarter >= 4 && state.secondsLeft < 150 && state.scoreDiff <= -4

        // Down four or more with the clock going, a field goal is pointless.
        if (desperate) {
            return if (yardsToGoal > 45 && state.distance > 8) FourthDownChoice.PUNT
            else FourthDownChoice.GO_FOR_IT
        }

        // Chip shot with anything but a very long distance to gain.
        if (kickable && yardsToGoal <= 38 && state.distance >= 3) return FourthDownChoice.FIELD_GOAL

        var goChance = when {
            yardsToGoal > 65 -> 0.01f
            state.distance <= 1 -> 0.42f
            state.distance <= 2 -> 0.26f
            state.distance <= 4 -> 0.12f
            else -> 0.04f
        }

        // Four down territory: too far for a kick, too close to punt.
        if (yardsToGoal in 33..48) goChance += 0.22f
        if (yardsToGoal <= 5 && state.distance <= 3) goChance += 0.25f
        if (lateAndTrailing) goChance += 0.35f
        if (state.quarter >= 4 && state.scoreDiff > 7) goChance -= 0.10f

        goChance = (goChance * (0.6f + aggression * 0.8f)).coerceIn(0.005f, 0.95f)

        if (rng.nextFloat() < goChance) return FourthDownChoice.GO_FOR_IT
        return if (kickable) FourthDownChoice.FIELD_GOAL else FourthDownChoice.PUNT
    }

    /** The longest attempt a coach will send this kicker out for. */
    fun kickerRange(kicker: Player?, scheme: Scheme, altitudeFt: Int): Int {
        if (kicker == null) return 35
        val power = rate(kicker, RatingId.KICK_POWER, scheme)
        return (44 + (power / 99f) * 20f + (altitudeFt / 5280f) * 4f).toInt()
    }
}
