package com.nflsim.engine.econ

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.stats.StatLine

/**
 * Last season, in one number per player, scaled so an average starter at his
 * position is 1.0.
 *
 * Teams pay for production, not for ratings (docs/SPEC.md 8.3). Nobody in a
 * front office can see a rating; they see what a player did. Wiring salaries
 * to the stat line rather than the attribute sheet gives the market its two
 * most realistic properties for free:
 *
 *  - A player coming off a big year gets paid for it, even if the year was
 *    partly his offence, his quarterback, or luck. That is where genuinely
 *    bad contracts come from, and bad contracts are what make the cap a game
 *    rather than an accounting exercise.
 *  - A good player who sat behind a starter is cheap, because he has not
 *    proved anything. Finding those is the reward for scouting properly.
 *
 * Linemen and specialists have no counting statistics, so they are priced on
 * ability alone - which is also roughly how they are paid in reality.
 */
object Production {

    /** Never fully worthless, never worth more than about twice the average. */
    private const val FLOOR = 0.55f
    private const val CEILING = 1.90f

    /** A veteran who did not play has not proved anything lately. */
    private const val UNPROVEN_VETERAN = 0.80f

    /**
     * @param players everyone whose production matters - a whole league.
     * @param stats last season only. Career totals would pay a thirty-four
     *   year old for what he did at twenty-six.
     */
    fun index(players: List<Player>, stats: Map<Int, StatLine>): Map<Int, Float> {
        val raw = players.associate { it.id.v to raw(it, stats[it.id.v]) }

        // Normalise inside a position group. Comparing a corner's tackles to a
        // receiver's yards would just pay everyone at the loudest position.
        val averages = players
            .groupBy { it.position.group }
            .mapValues { (_, group) ->
                val produced = group.mapNotNull { raw[it.id.v] }.filter { it > 0f }
                if (produced.isEmpty()) 0f else produced.sorted()[produced.size / 2]
            }

        return players.associate { p ->
            val mine = raw[p.id.v] ?: 0f
            val par = averages[p.position.group] ?: 0f
            val factor = when {
                par <= 0f -> 1f                                   // nothing to compare against
                mine <= 0f && p.accruedSeasons == 0 -> 1f         // a rookie has an excuse
                mine <= 0f -> UNPROVEN_VETERAN
                else -> (0.55f + 0.45f * (mine / par)).coerceIn(FLOOR, CEILING)
            }
            p.id.v to factor
        }
    }

    /**
     * A season in one number. The weights are not a rating - they only have to
     * rank players at the same position against each other sensibly.
     */
    private fun raw(player: Player, line: StatLine?): Float {
        if (line == null) return 0f
        return when (player.position.group) {
            PositionGroup.QB ->
                line.passYards * 0.0010f + line.passTouchdowns * 0.85f -
                    line.interceptionsThrown * 0.65f + line.rushYards * 0.0015f +
                    line.rushTouchdowns * 0.6f
            PositionGroup.RB ->
                line.scrimmageYards * 0.0016f + line.totalTouchdowns * 0.85f -
                    line.fumblesLost * 0.6f
            PositionGroup.WR, PositionGroup.TE ->
                line.receivingYards * 0.0022f + line.receivingTouchdowns * 0.95f +
                    line.receptions * 0.012f
            PositionGroup.EDGE, PositionGroup.DT ->
                line.sacks * 1.5f + line.tackles * 0.045f
            PositionGroup.LB ->
                line.tackles * 0.05f + line.sacks * 1.0f + line.interceptions * 1.2f
            PositionGroup.CB, PositionGroup.S ->
                line.interceptions * 1.8f + line.tackles * 0.05f
            // No counting stats. Paid on ability, same as in reality.
            PositionGroup.OL, PositionGroup.ST -> 0f
        }
    }
}
