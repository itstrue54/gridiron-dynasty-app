package com.nflsim.engine.econ

import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What a player costs, in thousands per year.
 *
 * One curve, used everywhere money is involved: free agency, roster
 * generation, and the release decisions the cap forces. It has to live in one
 * place, because a league where players are generated at one price and signed
 * at another does not stay balanced for more than a season or two.
 *
 * The shape is deliberately convex. Football pay is not proportional to
 * ability - the gap between an 88 and an 80 costs far more than the gap
 * between an 80 and a 72, which is exactly why a roster cannot be all
 * starters.
 */
object MarketValue {

    /** Below this a player is replacement level and earns near the minimum. */
    const val REPLACEMENT = 58

    /** No single contract goes past this, cap or no cap. */
    const val MAX_ANNUAL = 62_000

    private const val TOP_OF_MARKET = 34_000f

    fun annual(position: Position, overall: Int, age: Int): Int {
        val positional = when (position) {
            Position.QB -> 2.6f
            Position.EDGE, Position.LT, Position.CB, Position.WR -> 1.5f
            Position.DT, Position.RT, Position.S, Position.TE -> 1.1f
            Position.LB, Position.LG, Position.RG, Position.C -> 0.95f
            Position.RB -> 0.7f
            else -> 0.35f
        }
        val quality = ((overall - REPLACEMENT).coerceAtLeast(0) / 34.0).pow(2.1).toFloat()
        val ageFactor = when {
            age <= 26 -> 1.12f
            age <= 29 -> 1.0f
            age <= 32 -> 0.72f
            else -> 0.42f
        }
        return (quality * positional * ageFactor * TOP_OF_MARKET).roundToInt()
            .coerceIn(Contract.MIN_BASE_SALARY, MAX_ANNUAL)
    }

    fun annual(player: Player, scheme: Scheme?, year: Int): Int =
        annual(player.position, overall(player, scheme), player.age(year))

    /**
     * How long a deal runs. Teams commit years to young starters and buy older
     * players a season at a time, which is what makes age show up on the cap
     * sheet before it shows up on the field.
     */
    fun termFor(age: Int, depth: Int): Int = when {
        age <= 24 -> 4
        age <= 28 -> if (depth <= 1) 4 else 3
        age <= 31 -> 3
        else -> 2
    }
}
