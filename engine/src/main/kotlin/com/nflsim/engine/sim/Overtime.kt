package com.nflsim.engine.sim

/**
 * The overtime a game is played under when it is level after four quarters
 * (SPEC 5.10). Both are the NFL's rules from 2025 on: a toss, a kickoff and
 * two timeouts a side each period; both clubs get the ball once, and after
 * that the next score wins.
 *
 * These are rules rather than tuning, so they live here and not in the
 * TuningTable.
 */
enum class Overtime(
    /** How long each overtime period runs. */
    val periodSeconds: Int,
    /** How many periods before the game ends level anyway. */
    val maxPeriods: Int,
) {
    /** One ten-minute period; still level when it runs out, the game is a tie. */
    REGULAR_SEASON(periodSeconds = 600, maxPeriods = 1),

    /**
     * Fifteen-minute periods until somebody wins. The NFL plays on for as
     * long as it takes; ten is a safety valve so a stalemate cannot run
     * forever, and past it the playoff bracket settles the game.
     */
    PLAYOFFS(periodSeconds = GameState.QUARTER_SECONDS, maxPeriods = 10),
}
