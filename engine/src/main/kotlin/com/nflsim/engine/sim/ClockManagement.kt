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

    /**
     * What a snap takes off the clock, and whose timeout stops it. [choice],
     * a side and whether it calls one, overrides the coordinators' call for
     * that side: the user calling his own game (SnapCaller.timeout).
     */
    fun after(
        before: GameState,
        offense: Side,
        result: PlayResult,
        flow: TuningTable.GameFlow,
        choice: Pair<Side, Boolean>? = null,
        outOfBounds: Boolean = false,
    ): Decision {
        var runoff = running(before, offense, result, flow, outOfBounds)
        var timeout: Side? = null
        // A kneel runs the clock like any snap; a spike and an incompletion stop it.
        val clockRuns = !result.outcome.stopsClock && result.outcome != PlayOutcome.SPIKE
        val lateInGame = before.quarter >= 4
        fun warningStops(r: Int) = warningStops(before, r)

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
            if (chasing != null && kotlin.math.abs(lead) <= flow.timeoutMaxDeficit &&
                before.secondsLeft <= flow.timeoutSeconds && canStop(before, chasing, offense, result, flow, outOfBounds)
            ) {
                timeout = chasing
            }
        }
        // His own call, for his own side.
        if (choice != null) {
            val (side, call) = choice
            if (call && canStop(before, side, offense, result, flow, outOfBounds)) timeout = side
            else if (!call && timeout == side) timeout = null
        }
        if (timeout != null) runoff = flow.playSeconds
        // The clock stops at the two-minute warning, whatever the play was.
        if (warningStops(runoff)) {
            runoff = maxOf(before.secondsLeft - TWO_MINUTE_WARNING, minOf(runoff, flow.playSeconds))
        }
        return Decision(runoff, timeout)
    }

    /**
     * Whether [side] can stop the clock after this snap to any purpose: the
     * clock would run, it has a timeout, and stopping saves time - not on a
     * snap that ends the half anyway, nor one the two-minute warning stops.
     */
    fun canStop(
        before: GameState,
        side: Side,
        offense: Side,
        result: PlayResult,
        flow: TuningTable.GameFlow,
        outOfBounds: Boolean = false,
    ): Boolean {
        val clockRuns = !result.outcome.stopsClock && result.outcome != PlayOutcome.SPIKE
        val r = running(before, offense, result, flow, outOfBounds)
        return clockRuns && before.timeoutsFor(side) > 0 && r > flow.playSeconds &&
            before.secondsLeft > flow.playSeconds && !warningStops(before, r)
    }

    /**
     * The last two minutes of the half and the last five of the game, and
     * overtime: where going out of bounds stops the clock until the snap.
     */
    fun outOfBoundsStops(before: GameState): Boolean =
        (before.quarter == 2 && before.secondsLeft <= TWO_MINUTE_WARNING) ||
            (before.quarter == 4 && before.secondsLeft <= LAST_FIVE_MINUTES) || before.quarter >= 5

    /** The rules: the window in the fourth quarter where out of bounds stops the clock. */
    const val LAST_FIVE_MINUTES = 300

    /** What the snap takes with nobody calling time: the hurry-up and out of bounds applied, before any timeout. */
    private fun running(
        before: GameState,
        offense: Side,
        result: PlayResult,
        flow: TuningTable.GameFlow,
        outOfBounds: Boolean = false,
    ): Int {
        var runoff = result.clockRunoff
        val clockRuns = !result.outcome.stopsClock && result.outcome != PlayOutcome.SPIKE
        if (!clockRuns) return runoff
        if (before.quarter == 2 && before.secondsLeft <= TWO_MINUTE_WARNING && result.outcome != PlayOutcome.KNEEL) {
            // The two-minute drill before the half: whoever has the ball hurries.
            runoff = minOf(runoff, flow.hurryUpRunoff)
        }
        if (before.quarter >= 4) {
            val lead = before.scoreFor(offense) - before.scoreFor(offense.other())
            val chasingWithBall = lead < 0 || (lead == 0 && before.secondsLeft <= TWO_MINUTE_WARNING)
            if (chasingWithBall && before.secondsLeft <= flow.hurryUpSeconds) runoff = minOf(runoff, flow.hurryUpRunoff)
        }
        if (outOfBounds) {
            runoff = if (outOfBoundsStops(before)) minOf(runoff, flow.playSeconds)
                else maxOf(flow.playSeconds, runoff - flow.outOfBoundsRestartSave)
        }
        return runoff
    }

    private fun warningStops(before: GameState, r: Int) = (before.quarter == 2 || before.quarter == 4) &&
        before.secondsLeft > TWO_MINUTE_WARNING && before.secondsLeft - r < TWO_MINUTE_WARNING
}
