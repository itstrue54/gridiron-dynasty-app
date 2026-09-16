package com.nflsim.engine.ratings

import com.nflsim.engine.model.Position

/**
 * Where a club's scouts spend the spring (SPEC 7 phase 8, 4.6).
 *
 * A club cannot watch everyone closely. Its department sets how much there is
 * to spend, and its focus sets how that is spread: name a position or two and
 * the club goes into the draft sure about those men and guessing at the rest,
 * or name none and it knows a little about everybody. What it buys is
 * confidence, which is to say a narrower band on draft day.
 */
object Scouting {

    /**
     * A club's confidence in a prospect: what the man's exposure gave it for
     * free, plus what the club spent looking at his position.
     */
    fun prospect(playerId: Int, position: Position, scoutingDept: Int, focus: Set<Position>): Float {
        val budget = BUDGET_FLOOR + BUDGET_RANGE * (scoutingDept / 100f)
        val share = when {
            focus.isEmpty() -> 1f / SPREAD_WIDTH
            position in focus -> 1f / focus.size
            else -> LEFTOVER
        }
        return (ScoutingLens.prospectExposure(playerId) + budget * share)
            .coerceAtMost(ScoutingLens.CEILING)
    }

    /** The lens a club reads a prospect through. */
    fun lens(playerId: Int, viewerId: Int, position: Position, scoutingDept: Int, focus: Set<Position>) =
        ScoutingLens.of(playerId, viewerId, prospect(playerId, position, scoutingDept, focus))

    /** How many positions a club spread over when it named none: the whole board, thinly. */
    const val SPREAD_WIDTH = 6f

    /** What a club still picks up about a position it is not watching. */
    private const val LEFTOVER = 0.08f
    private const val BUDGET_FLOOR = 0.25f
    private const val BUDGET_RANGE = 0.45f
}
