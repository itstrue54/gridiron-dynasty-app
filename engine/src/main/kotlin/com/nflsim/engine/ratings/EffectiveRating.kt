package com.nflsim.engine.ratings

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.Ratings
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything the sim needs to know about a player's situation when reading a
 * rating. Deliberately small - if it grows, that is a signal the sim is
 * reaching for state it should have been handed.
 */
data class RatingContext(
    val scheme: Scheme,
    /** 0..100. Accumulates over a game, resets weekly. */
    val fatigue: Int = 0,
    /** 0..100. */
    val morale: Int = 75,
    /** Seasons in this scheme. Caps out at 3. */
    val yearsInSystem: Int = 0,
) {
    companion object {
        fun forPlayer(player: Player, scheme: Scheme) = RatingContext(
            scheme = scheme,
            fatigue = player.fatigue,
            morale = player.morale,
            yearsInSystem = player.yearsInSystem,
        )
    }
}

/**
 * The single most important function in the ratings layer.
 *
 * NOTHING in the sim reads a raw rating. Everything comes through here, which
 * is what makes scheme fit real rather than cosmetic: a perfect-fit veteran
 * plays a few points above his sheet, and a badly-miscast rookie plays a
 * dozen points below it. See docs/SPEC.md 4.9.
 */
fun effectiveRating(player: Player, ratingId: RatingId, ctx: RatingContext): Int {
    val base = player.ratings[ratingId]

    val fit = ctx.scheme.fitFor(player.position, player.archetype)
    // Versatility softens a bad fit. It never improves a good one, and it
    // closes at most 20% of the gap - a versatile player is still miscast.
    val adjFit = fit + (1f - fit) * (player.traits.schemeVersatility / 500f)

    val schemeMod = SCHEME_FLOOR + SCHEME_RANGE * adjFit
    val familiarity = FAMILIARITY_FLOOR +
        FAMILIARITY_RANGE * (min(ctx.yearsInSystem, YEARS_TO_LEARN) / YEARS_TO_LEARN.toFloat())
    val emphasis = if (ctx.scheme.emphasizes(player.position, ratingId)) EMPHASIS_BONUS else 1f
    val fatigueMod = 1f - (ctx.fatigue.coerceIn(0, 100) / 100f) * MAX_FATIGUE_PENALTY
    val moraleMod = MORALE_FLOOR + MORALE_RANGE * (ctx.morale.coerceIn(0, 100) / 100f)

    val result = base * schemeMod * familiarity * emphasis * fatigueMod * moraleMod
    return result.roundToInt().coerceIn(1, 99)
}

/**
 * Position-weighted overall from a bare rating sheet, with no player context.
 * Used by the generators, which need to hit a target before a Player exists.
 */
fun rawOverall(position: Position, ratings: Ratings): Int {
    val weights = OverallWeights.forPosition(position)
    var sum = 0f
    var weightTotal = 0f
    for ((ratingId, weight) in weights) {
        sum += ratings[ratingId] * weight
        weightTotal += weight
    }
    return (sum / weightTotal).roundToInt().coerceIn(1, 99)
}

/**
 * Position-weighted overall. Pass a scheme to get the number that actually
 * matters; pass null for the raw "on paper" number a box score would print.
 */
fun overall(player: Player, scheme: Scheme? = null): Int {
    val weights = OverallWeights.forPosition(player.position)
    val ctx = scheme?.let { RatingContext.forPlayer(player, it) }
    var sum = 0f
    var weightTotal = 0f
    for ((ratingId, weight) in weights) {
        val value = if (ctx != null) effectiveRating(player, ratingId, ctx)
                    else player.ratings[ratingId]
        sum += value * weight
        weightTotal += weight
    }
    return (sum / weightTotal).roundToInt().coerceIn(1, 99)
}

/** How well this player suits a scheme, 0.0 to 1.0. Used by AI GMs and the UI. */
fun schemeFit(player: Player, scheme: Scheme): Float =
    scheme.fitFor(player.position, player.archetype)

// All of these move to TuningTable at M4. Named constants until then so the
// calibration pass has something to grab.
private const val SCHEME_FLOOR = 0.76f
private const val SCHEME_RANGE = 0.24f
private const val FAMILIARITY_FLOOR = 0.96f
private const val FAMILIARITY_RANGE = 0.04f
private const val YEARS_TO_LEARN = 3
private const val EMPHASIS_BONUS = 1.03f
private const val MAX_FATIGUE_PENALTY = 0.15f
private const val MORALE_FLOOR = 0.98f
private const val MORALE_RANGE = 0.04f
