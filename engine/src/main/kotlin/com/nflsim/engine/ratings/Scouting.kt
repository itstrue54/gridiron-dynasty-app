package com.nflsim.engine.ratings

import com.nflsim.engine.model.Position
import com.nflsim.engine.tuning.TuningTable

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
    fun prospect(playerId: Int, position: Position, scoutingDept: Int, focus: Set<Position>, t: TuningTable.Scouting): Float {
        val budget = t.budgetFloor + t.budgetRange * (scoutingDept / 100f)
        val share = when {
            focus.isEmpty() -> 1f / t.spreadWidth
            position in focus -> 1f / focus.size
            else -> t.leftover
        }
        return (ScoutingLens.prospectExposure(playerId, t) + budget * share)
            .coerceAtMost(t.ceiling)
    }

    /** The lens a club reads a prospect through. */
    fun lens(playerId: Int, viewerId: Int, position: Position, scoutingDept: Int, focus: Set<Position>, t: TuningTable.Scouting) =
        ScoutingLens.of(playerId, viewerId, prospect(playerId, position, scoutingDept, focus, t), t)
}
