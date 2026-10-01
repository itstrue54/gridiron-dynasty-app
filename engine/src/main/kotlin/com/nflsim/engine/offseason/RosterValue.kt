package com.nflsim.engine.offseason

import com.nflsim.engine.model.Player
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.tuning.TuningTable

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
 *
 * [winNow] is the club's GmProfile.winNowVsFuture. An all-in front office
 * discounts age at half the usual rate and a rebuilding one at one and a
 * half, and a rebuilder pays for youth that an all-in club counts slightly
 * against. At 0.5 this is exactly the ranking ADR-004 was tuned with. The
 * age curve and fit weight are tuning (ai.value*).
 */
fun rosterValue(player: Player, scheme: Scheme, year: Int, winNow: Float = 0.5f, t: TuningTable.Ai): Float {
    val age = player.age(year)
    val decline = (age - t.valueAgeCliff).coerceAtLeast(0) * t.valueAgePenalty * (1.5f - winNow)
    val youth = (t.valueYouthAge - age).coerceAtLeast(0) * t.valueYouthWeight * (1f - 2f * winNow)
    return overall(player, scheme) + schemeFit(player, scheme) * t.valueFitWeight - decline + youth
}
