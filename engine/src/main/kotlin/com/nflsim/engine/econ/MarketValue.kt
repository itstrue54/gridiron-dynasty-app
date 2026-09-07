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
 * place, because a league where players are created at one price and signed
 * at another does not stay balanced for more than a season or two.
 *
 * **Prices are relative, not absolute (ADR-006).** A player's score is his
 * standing in this league; what that standing is worth in dollars comes from
 * dividing the league's total payroll by the total of everyone's scores. This
 * is how a real market works - the cap sets the money, and players divide it
 * - and it is the only version that stays honest as ratings drift. An
 * absolute curve priced a whole league at a quarter of the cap once starter
 * quality settled around 73 instead of the 79 the league was generated with,
 * and a cap nobody can reach forces no decisions at all.
 *
 * The shape is deliberately convex. Football pay is not proportional to
 * ability - the gap between an 88 and an 80 is worth far more than the gap
 * between an 80 and a 72, which is exactly why a roster cannot be all
 * starters.
 */
object MarketValue {

    /** Below this a player is replacement level and earns near the minimum. */
    const val REPLACEMENT = 58

    /** No single contract goes past this share of one team's cap. */
    const val MAX_CAP_SHARE = 0.24f

    /**
     * A player's standing, in arbitrary units. Only the ratios between these
     * matter; `Pricer` turns them into money.
     */
    fun score(position: Position, overall: Int, age: Int): Float {
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
        return quality * positional * ageFactor
    }

    fun score(player: Player, scheme: Scheme?, year: Int): Float =
        score(player.position, overall(player, scheme), player.age(year))

    /**
     * Builds the price list for a league.
     *
     * @param rostered everyone on a roster - the population the payroll has
     *   to cover.
     * @param payroll what the league will spend in total: 32 caps, less the
     *   share that goes to minimum deals nobody negotiates.
     */
    fun pricer(
        rostered: List<Player>,
        scheme: (Player) -> Scheme,
        year: Int,
        payroll: Long,
        cap: Int,
        production: Map<Int, Float> = emptyMap(),
    ): Pricer {
        val total = rostered.sumOf {
            (score(it, scheme(it), year) * (production[it.id.v] ?: 1f)).toDouble()
        }
        // An empty or replacement-level league would divide by nothing.
        val perPoint = if (total <= 0.0) 0f else (payroll / total).toFloat()
        return Pricer(perPoint, cap, production)
    }

    /** Turns standing into money at this league's exchange rate. */
    class Pricer(
        private val perPoint: Float,
        private val cap: Int,
        private val production: Map<Int, Float> = emptyMap(),
    ) {

        val maxAnnual: Int get() = (cap * MAX_CAP_SHARE).roundToInt()

        /**
         * What a player is paid: what he is, times what he did. A team cannot
         * see a rating - it sees a stat line, and it pays for that.
         */
        fun annual(player: Player, scheme: Scheme?, year: Int): Int {
            val standing = score(player.position, overall(player, scheme), player.age(year))
            val produced = production[player.id.v] ?: 1f
            return (standing * produced * perPoint).roundToInt()
                .coerceIn(Contract.MIN_BASE_SALARY, maxAnnual)
        }
    }

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
