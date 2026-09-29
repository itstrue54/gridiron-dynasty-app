package com.nflsim.engine.sim

import com.nflsim.engine.tuning.TuningTable

/**
 * In-game adaptation (SPEC 5.4): how far each coordinator has moved off his
 * tendencies, from what the other side has shown this game. A defence
 * facing an offence that passes more than most sends more; facing one that
 * runs, it loads the box. An offence facing loaded boxes throws; facing
 * light ones, it runs. Nothing moves until a side has seen enough snaps,
 * and nothing moves past the window, scaled by the head coach's adjustments
 * rating: a predictable coordinator gets punished, by a staff that notices.
 */
object Adaptation {

    /** Added to the offence's pass rate and the defence's blitz rate; the chance of a man more (or, negative, fewer) in the box. */
    data class Shift(val pass: Float = 0f, val blitz: Float = 0f, val box: Float = 0f)

    fun of(
        /** What the offence with the ball has called so far. */
        runs: Int,
        passes: Int,
        /** What the defence facing it has shown so far: men added to the box in all, over its snaps. */
        boxSum: Int,
        defSnaps: Int,
        /** Each head coach's adjustments rating, 0..100. */
        offenseAdjustments: Int,
        defenseAdjustments: Int,
        t: TuningTable.Adaptation,
    ): Shift {
        val calls = runs + passes
        var blitz = 0f; var box = 0f; var pass = 0f
        if (calls >= t.minSnaps && defenseAdjustments > 0) {
            val lean = ((passes.toFloat() / calls - t.neutralPassRate) * t.passGain).coerceIn(-1f, 1f)
            val reach = t.window * defenseAdjustments / 100f
            blitz = lean * reach
            box = -lean * reach
        }
        if (defSnaps >= t.minSnaps && offenseAdjustments > 0) {
            val loaded = (boxSum.toFloat() / defSnaps * t.boxGain).coerceIn(-1f, 1f)
            pass = loaded * t.window * offenseAdjustments / 100f
        }
        return Shift(pass, blitz, box)
    }
}
