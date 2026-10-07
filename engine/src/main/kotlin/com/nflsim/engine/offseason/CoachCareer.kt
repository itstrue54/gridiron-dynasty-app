package com.nflsim.engine.offseason

import com.nflsim.engine.model.Coach
import com.nflsim.engine.model.CoachRatings
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.tuning.TuningTable
import kotlin.math.roundToInt

/**
 * A coach's career (SPEC 4.7): his ratings move with his age. A young coach
 * improves a little every year until his prime, holds through it, and past
 * it slows down - very slowly: a man's experience doesn't leave him the way
 * a player's legs do.
 *
 * The curve is an offset from a man's prime, by age. A new coach is drawn
 * where his age puts him on it - a 35-year-old assistant is still learning -
 * so the years he grows are years he has ahead of him, not growth on top of
 * a finished coach. That is what keeps a league's coaching where it was
 * calibrated over a long dynasty: without it every young hire would climb
 * past the man he replaced. [TuningTable.Staff.careerPeakLift] sets how far
 * above the old flat level a man's prime sits, so the league's coaching as a
 * whole - young, prime and old together - averages where it always did.
 */
object CoachCareer {

    /** Where a man of [age] stands against his prime, in rating points: below it young, at it through it, a little below after. */
    fun offset(age: Int, t: TuningTable.Staff): Float = when {
        age < t.careerPeakFrom -> -t.careerGrowth * (t.careerPeakFrom - age)
        age > t.careerPeakTo -> -t.careerDecline * (age - t.careerPeakTo)
        else -> 0f
    }

    /** A new coach's rating: [mean] at his prime, moved to where his age puts him. */
    fun hireMean(mean: Float, age: Int, t: TuningTable.Staff): Float = mean + t.careerPeakLift + offset(age, t)

    /**
     * A year on: a year older, and every rating moved by what that year of
     * his career brings - plus a little of his own, drawn for him by name.
     */
    fun older(c: Coach, t: TuningTable.Staff, rng: Rng): Coach {
        val change = offset(c.age + 1, t) - offset(c.age, t)
        val own = rng.split("career|${c.id.v}|${c.age}")
        fun move(r: Int) = (r + change + own.gaussian(0f, t.careerNoise)).roundToInt().coerceIn(0, 100)
        val r = c.ratings
        return c.copy(
            age = c.age + 1,
            ratings = CoachRatings(move(r.development), move(r.gameplan), move(r.adjustments),
                move(r.discipline), move(r.motivation), move(r.evaluation)),
        )
    }

    /** Where he is in his career, in words, for the Staff screen. */
    fun stage(age: Int, t: TuningTable.Staff): String = when {
        age < t.careerPeakFrom -> "Still improving."
        age <= t.careerPeakTo -> "In his prime."
        else -> "Slowing down, slowly."
    }
}
