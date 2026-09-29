package com.nflsim.engine.sim

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.tuning.TuningTable

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
        t: TuningTable.FourthDown = TuningTable.REALISTIC.fourthDown,
    ): FourthDownChoice {
        val yardsToGoal = 100 - state.yardLine
        val kickDistance = yardsToGoal + 17
        val range = kickerRange(kicker, kickScheme, altitudeFt, t)
        val kickable = kickDistance <= range

        // Late and behind by more than a kick can make up, a coach goes for it.
        // Within a field goal and in range, he kicks it: down three it ties,
        // down one or two it wins (SPEC 5.4).
        val trailing = state.scoreDiff < 0 && (state.scoreDiff < -FIELD_GOAL_POINTS || !kickable)
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
            yardsToGoal > 65 -> t.goOwnEnd
            state.distance <= 1 -> t.goInches
            state.distance <= 2 -> t.goTwo
            state.distance <= 4 -> t.goShort
            else -> t.goLong
        }

        // Four down territory: too far for a kick, too close to punt.
        if (yardsToGoal in 33..48) goChance += t.goFourDownTerritory
        if (yardsToGoal <= 5 && state.distance <= 3) goChance += t.goGoalLine
        if (lateAndTrailing) goChance += t.goLateTrailing
        if (state.quarter >= 4 && state.scoreDiff > 7) goChance -= t.goProtectingLead

        goChance = (goChance * (t.aggressionBase + aggression * t.aggressionScale)).coerceIn(t.goFloor, t.goCeiling)

        if (rng.nextFloat() < goChance) return FourthDownChoice.GO_FOR_IT
        return if (kickable) FourthDownChoice.FIELD_GOAL else FourthDownChoice.PUNT
    }

    /**
     * The clock is all but out - another snap with it running would end the
     * half or the game - and a field goal is in range. At the half he takes
     * the points; at the end, if a kick ties the game or wins it. On any
     * down: nobody waits for fourth down with the clock at 0:20.
     */
    fun lastKick(
        state: GameState,
        kicker: Player?,
        kickScheme: Scheme,
        altitudeFt: Int,
        snapRunoff: Int,
        t: TuningTable.FourthDown = TuningTable.REALISTIC.fourthDown,
    ): Boolean {
        if (state.secondsLeft > snapRunoff) return false
        val endOfHalf = state.quarter == 2
        val endOfGame = state.quarter >= 4 && state.scoreDiff in -FIELD_GOAL_POINTS..0
        if (!endOfHalf && !endOfGame) return false
        return (100 - state.yardLine) + 17 <= kickerRange(kicker, kickScheme, altitudeFt, t)
    }

    /** What a field goal is worth: the rules. */
    const val FIELD_GOAL_POINTS = 3

    /** The longest attempt a coach will send this kicker out for. */
    fun kickerRange(
        kicker: Player?,
        scheme: Scheme,
        altitudeFt: Int,
        t: TuningTable.FourthDown = TuningTable.REALISTIC.fourthDown,
    ): Int {
        if (kicker == null) return t.rangeNoKicker
        val power = rate(kicker, RatingId.KICK_POWER, scheme)
        return (t.rangeBase + (power / 99f) * t.rangePerPower + (altitudeFt / 5280f) * t.rangePerMile).toInt()
    }
}
