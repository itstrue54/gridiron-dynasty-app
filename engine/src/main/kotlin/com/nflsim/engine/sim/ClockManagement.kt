package com.nflsim.engine.sim

import com.nflsim.engine.tuning.TuningTable

/**
 * How much clock a snap takes as a half or the game winds down (SPEC 5.10):
 * the two-minute warning, the hurry-up, and the timeouts the club chasing
 * the game spends to stop the clock.
 *
 * A club behind late wants time and the one ahead wants the clock to run.
 * Nothing here draws from a stream: the clock is decided, not rolled.
 */
object ClockManagement {

    /** What a snap took off the clock, and whose timeout stopped it after, if anyone's. */
    data class Decision(val runoff: Int, val timeout: Side? = null)

    /** The two-minute warning: a rule, not a tuning. */
    const val TWO_MINUTE_WARNING = 120

    fun after(before: GameState, offense: Side, result: PlayResult, flow: TuningTable.GameFlow): Decision {
        var runoff = result.clockRunoff
        var timeout: Side? = null
        // A kneel runs the clock like any snap; a spike and an incompletion stop it.
        val clockRuns = !result.outcome.stopsClock && result.outcome != PlayOutcome.SPIKE
        val endOfHalf = before.quarter == 2 && before.secondsLeft <= TWO_MINUTE_WARNING
        val lateInGame = before.quarter >= 4
        fun warningStops(r: Int) = (before.quarter == 2 || before.quarter == 4) &&
            before.secondsLeft > TWO_MINUTE_WARNING && before.secondsLeft - r < TWO_MINUTE_WARNING

        if (clockRuns && endOfHalf && result.outcome != PlayOutcome.KNEEL) {
            // The two-minute drill before the half: whoever has the ball hurries.
            runoff = minOf(runoff, flow.hurryUpRunoff)
        }
        if (clockRuns && lateInGame) {
            val lead = before.scoreFor(offense) - before.scoreFor(offense.other())
            // Who is chasing: the club behind, or with the game level and the
            // clock nearly out, the one with the ball.
            val chasing = when {
                lead < 0 -> offense
                lead > 0 -> offense.other()
                before.secondsLeft <= TWO_MINUTE_WARNING -> offense
                else -> null
            }
            if (chasing == offense && before.secondsLeft <= flow.hurryUpSeconds) {
                runoff = minOf(runoff, flow.hurryUpRunoff)
            }
            if (chasing != null && kotlin.math.abs(lead) <= flow.timeoutMaxDeficit &&
                before.secondsLeft <= flow.timeoutSeconds && before.timeoutsFor(chasing) > 0 &&
                runoff > flow.playSeconds &&
                // Nor on one that runs the clock out whatever anybody does.
                before.secondsLeft > flow.playSeconds &&
                // Nobody spends one on a snap the two-minute warning stops anyway.
                !warningStops(runoff)
            ) {
                runoff = flow.playSeconds
                timeout = chasing
            }
        }
        // The clock stops at the two-minute warning, whatever the play was.
        if (warningStops(runoff)) {
            runoff = maxOf(before.secondsLeft - TWO_MINUTE_WARNING, minOf(runoff, flow.playSeconds))
        }
        return Decision(runoff, timeout)
    }
}
