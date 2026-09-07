package com.nflsim.engine.offseason

import com.nflsim.engine.model.Player
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit

/**
 * How a front office ranks a player for a roster spot: what he is now, how
 * well he fits, and how much of him is left.
 *
 * Two players of equal ability are not equal to a team - the younger one is
 * cheaper, has upside, and is not about to fall off. Without this term the
 * league ages a year every eight seasons: a declining thirty-two year old
 * still outrates a rookie, so he keeps the roster spot, keeps losing three
 * points a year, and never reaches the free agency that would end his career.
 * Rosters skew young because of decisions like this one, not because players
 * spontaneously retire (docs/DECISIONS.md ADR-004).
 */
fun rosterValue(player: Player, scheme: Scheme, year: Int): Float =
    overall(player, scheme) + schemeFit(player, scheme) * 8f -
        (player.age(year) - AGE_CLIFF).coerceAtLeast(0) * AGE_PENALTY

/** Age past which a team starts discounting a player. */
const val AGE_CLIFF = 29

/** Rating points of discount per year past the cliff. */
const val AGE_PENALTY = 2.2f
