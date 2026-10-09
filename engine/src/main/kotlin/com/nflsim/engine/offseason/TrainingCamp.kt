package com.nflsim.engine.offseason

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.sim.Injury
import com.nflsim.engine.tuning.TuningTable

/**
 * Training camp (SPEC 7 phase 11): the men a club has signed practise, and
 * a few of them get hurt. The development the offseason gives a player
 * happens here too, before the cut to 53, so a club cuts the man camp has
 * shown it rather than the one it signed.
 */
object TrainingCamp {

    /** A man hurt in camp: who, his club, and the games he will miss. */
    data class CampInjury(val player: Int, val team: Int, val gamesOut: Int)

    /**
     * Who is hurt in camp. Every man on a club's roster - not a free agent,
     * not a squad man yet - risks [TuningTable.Injuries.campRate], raised by
     * his proneness and lowered by his resistance as on a snap, and misses
     * games on the season's spread. Drawn from a stream of camp's own, so
     * no other part of the offseason moves.
     */
    fun injuries(players: List<Player>, i: TuningTable.Injuries, rng: Rng): Pair<List<Player>, List<CampInjury>> {
        val hurt = mutableListOf<CampInjury>()
        val after = players.map { p ->
            val team = p.teamId ?: return@map p
            if (p.status != PlayerStatus.ACTIVE) return@map p
            val proneness = i.pronenessBase + i.pronenessRange * p.traits.injuryProneness / 100f
            val resistance = i.resistBase - i.resistRange * p.ratings[RatingId.INJURY_RESIST] / 100f
            val own = rng.split("camp|${p.id.v}")
            if (own.nextFloat() >= i.campRate * proneness * resistance) return@map p
            val games = Injury.gamesOut(i, own)
            hurt += CampInjury(p.id.v, team.v, games)
            p.copy(injuryWeeks = maxOf(p.injuryWeeks, games))
        }
        return after to hurt
    }
}
