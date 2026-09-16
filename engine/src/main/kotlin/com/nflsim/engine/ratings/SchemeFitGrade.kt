package com.nflsim.engine.ratings

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position

/**
 * Scheme fit as a person reads it (SPEC 4.9): a letter per player, and how
 * well a side of the ball suits its scheme, judged on the players who would
 * start there.
 */
object SchemeFitGrade {

    fun letter(fit: Float): String = when {
        fit >= 0.90f -> "A"
        fit >= 0.80f -> "B"
        fit >= 0.70f -> "C"
        fit >= 0.60f -> "D"
        else -> "F"
    }

    /** Mean fit of the likely starters on one side, the best of them at each position by scheme-adjusted overall. */
    fun side(roster: List<Player>, scheme: Scheme, offense: Boolean): Float {
        val starters = STARTERS.filterKeys { it.isOffense == offense }.flatMap { (position, count) ->
            roster.filter { it.position == position }.sortedByDescending { overall(it, scheme) }.take(count)
        }
        return if (starters.isEmpty()) 0f else starters.map { schemeFit(it, scheme) }.average().toFloat()
    }

    fun describe(roster: List<Player>, scheme: Scheme, offense: Boolean): String =
        side(roster, scheme, offense).let { "${letter(it)} (${"%.2f".format(it)})" }

    /** Who starts, by position, in a base personnel grouping and front. */
    private val STARTERS = mapOf(
        Position.QB to 1, Position.RB to 1, Position.WR to 3, Position.TE to 1,
        Position.LT to 1, Position.LG to 1, Position.C to 1, Position.RG to 1, Position.RT to 1,
        Position.EDGE to 2, Position.DT to 2, Position.LB to 3, Position.CB to 2, Position.S to 2,
    )
}
