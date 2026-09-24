package com.nflsim.engine.season

import com.nflsim.engine.model.League
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.offseason.TeamNeeds
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.overall

/**
 * What the pundits expect of a club before a ball is snapped: a coarse tier,
 * for choosing which club to take over. It is a ranking of the league's
 * starting lineups, each judged in its own schemes, and it is never a number
 * - the screen that shows it reads no ratings (SPEC 4.6).
 */
enum class Outlook(val label: String, val blurb: String) {
    CONTENDER("Contender", "built to win now"),
    HOPEFUL("Playoff hopeful", "a piece or two away"),
    MIDDLE("Middle of the pack", "could go either way"),
    REBUILD("Rebuilding", "a long way back, and a high pick to show for it"),
    ;

    companion object {
        /** How many clubs each tier holds, strongest first: 6, 8, 10 and the rest. */
        private val TIERS = listOf(CONTENDER to 6, HOPEFUL to 8, MIDDLE to 10)

        /** Every club's outlook in [league]. */
        fun of(league: League): Map<TeamId, Outlook> {
            val ranked = league.teams.sortedByDescending { strength(league, it.id) }.map { it.id }
            var from = 0
            val out = mutableMapOf<TeamId, Outlook>()
            TIERS.forEach { (tier, size) ->
                ranked.drop(from).take(size).forEach { out[it] = tier }
                from += size
            }
            ranked.drop(from).forEach { out[it] = REBUILD }
            return out
        }

        /** The mean of a club's starters, each at his position, in the club's own schemes. */
        internal fun strength(league: League, team: TeamId): Double {
            val club = league.team(team)
            val offence = SchemeCatalog.tuned(club.offenseScheme, league.tuning)
            val defence = SchemeCatalog.tuned(club.defenseScheme, league.tuning)
            val roster = league.roster(team)
            return TeamNeeds.STARTERS.flatMap { (position, starters) ->
                val scheme = if (position.isOffense) offence else defence
                roster.filter { it.position == position }
                    .map { overall(it, scheme) }
                    .sortedDescending()
                    .take(starters)
            }.average()
        }
    }
}
