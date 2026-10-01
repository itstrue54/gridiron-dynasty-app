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
        /** Yards the weather takes off the kicker's range (Weather.kickRangeLoss). */
        rangeLoss: Float = 0f,
    ): FourthDownChoice {
        val yardsToGoal = 100 - state.yardLine
        val kickDistance = yardsToGoal + 17
        val range = kickerRange(kicker, kickScheme, altitudeFt, t, rangeLoss)
        val kickable = kickDistance <= range

        // Late and behind by more than a kick can make up, a coach goes for it.
        // Within a field goal and in range, he kicks it: down three it ties,
        // down one or two it wins (SPEC 5.4).
        val trailing = state.scoreDiff < 0 && (state.scoreDiff < -FIELD_GOAL_POINTS || !kickable)
        val lateAndTrailing = state.quarter >= 4 && state.secondsLeft < t.lateSeconds && trailing
        val desperate = state.quarter >= 4 && state.secondsLeft < t.desperateSeconds && state.scoreDiff <= -t.desperateDeficit

        // Down four or more with the clock going, a field goal is pointless.
        if (desperate) {
            return if (yardsToGoal > t.desperatePuntYards && state.distance > t.desperatePuntDistance) FourthDownChoice.PUNT
            else FourthDownChoice.GO_FOR_IT
        }

        // Chip shot with anything but a short distance to gain.
        val easyKick = kickable && yardsToGoal <= t.easyKickYards
        if (easyKick && state.distance >= t.kickAlwaysDistance) return FourthDownChoice.FIELD_GOAL

        var goChance = when {
            yardsToGoal > t.ownEndYards -> t.goOwnEnd
            state.distance <= 1 -> t.goInches
            state.distance <= 2 -> t.goTwo
            state.distance <= 4 -> t.goShort
            else -> t.goLong
        }

        // Four down territory: too far for a kick, too close to punt.
        if (yardsToGoal in t.fourDownTerritoryNear..t.fourDownTerritoryFar) goChance += t.goFourDownTerritory
        if (yardsToGoal <= t.goalLineYards && state.distance <= t.goalLineDistance) goChance += t.goGoalLine
        // Short of a first down in easy range, the points are not automatic:
        // a touchdown is worth more than the three. Not late and within a
        // kick, when the three tie or win it.
        val kickDecides = state.quarter >= 4 && state.secondsLeft < t.lateSeconds && state.scoreDiff in -FIELD_GOAL_POINTS..0
        if (easyKick && !kickDecides) goChance += t.goKickRange
        if (lateAndTrailing) goChance += t.goLateTrailing
        if (state.quarter >= 4 && state.scoreDiff > t.protectingLeadPoints) goChance -= t.goProtectingLead

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
        rangeLoss: Float = 0f,
    ): Boolean {
        if (state.secondsLeft > snapRunoff) return false
        val endOfHalf = state.quarter == 2
        val endOfGame = state.quarter >= 4 && state.scoreDiff in -FIELD_GOAL_POINTS..0
        if (!endOfHalf && !endOfGame) return false
        return (100 - state.yardLine) + 17 <= kickerRange(kicker, kickScheme, altitudeFt, t, rangeLoss)
    }

    /** What a field goal is worth: the rules. */
    const val FIELD_GOAL_POINTS = 3

    /** The longest attempt a coach will send this kicker out for. */
    fun kickerRange(
        kicker: Player?,
        scheme: Scheme,
        altitudeFt: Int,
        t: TuningTable.FourthDown = TuningTable.REALISTIC.fourthDown,
        rangeLoss: Float = 0f,
    ): Int {
        if (kicker == null) return t.rangeNoKicker
        val power = rate(kicker, RatingId.KICK_POWER, scheme)
        return (t.rangeBase + (power / 99f) * t.rangePerPower + (altitudeFt / 5280f) * t.rangePerMile - rangeLoss).toInt()
    }
}
