package com.nflsim.engine.season

import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.stats.StatLine
import kotlinx.serialization.Serializable

@Serializable
data class AwardWinner(val player: Int, val team: Int, val summary: String)

@Serializable
data class Awards(
    val mostValuablePlayer: AwardWinner? = null,
    val offensivePlayerOfTheYear: AwardWinner? = null,
    val defensivePlayerOfTheYear: AwardWinner? = null,
    val offensiveRookieOfTheYear: AwardWinner? = null,
    val defensiveRookieOfTheYear: AwardWinner? = null,
)

/**
 * Who gets the hardware.
 *
 * Voters are not a spreadsheet: team success matters more than it statistically
 * should, quarterbacks win more than they probably deserve, and there is real
 * noise in the ballot. All three of those are modelled on purpose - an award
 * that always goes to the highest yardage total is not worth winning.
 */
object AwardVoting {

    fun decide(
        league: League,
        records: Map<TeamId, TeamRecord>,
        stats: Map<Int, StatLine>,
        year: Int,
    ): Awards {
        val playersById = league.players.associateBy { it.id.v }
        fun wins(p: Player): Double = p.teamId?.let { records[it]?.winPct } ?: 0.5

        fun best(
            eligible: (Player) -> Boolean,
            score: (Player, StatLine) -> Double,
        ): AwardWinner? {
            val ranked = stats.entries.mapNotNull { (id, line) ->
                val player = playersById[id] ?: return@mapNotNull null
                if (!eligible(player)) return@mapNotNull null
                Triple(player, line, score(player, line))
            }.sortedByDescending { it.third }
            val winner = ranked.firstOrNull() ?: return null
            return AwardWinner(
                player = winner.first.id.v,
                team = winner.first.teamId?.v ?: 0,
                summary = summarise(winner.first, winner.second),
            )
        }

        val mvp = best({ it.position != Position.K && it.position != Position.P }) { p, s ->
            val production = when (p.position) {
                Position.QB -> s.passYards / 25.0 + s.passTouchdowns * 4.4 -
                    s.interceptionsThrown * 2.2 + s.rushYards / 10.0 + s.rushTouchdowns * 5.0
                else -> s.scrimmageYards / 9.0 + s.totalTouchdowns * 5.4 +
                    s.sacks * 6.0 + s.interceptions * 8.0
            }
            // Team success weighs heavily, and quarterbacks carry a thumb on
            // the scale, because that is how the award is actually voted.
            val positionBias = if (p.position == Position.QB) 1.12 else 1.0
            (production + wins(p) * 46.0) * positionBias
        }

        val opoy = best({ it.position.isOffense && it.position != Position.QB }) { p, s ->
            s.scrimmageYards / 8.0 + s.totalTouchdowns * 6.2 + s.receptions * 0.35 +
                wins(p) * 14.0
        }

        val dpoy = best({ !it.position.isOffense && it.position.group != PositionGroup.ST }) { p, s ->
            s.sacks * 9.5 + s.interceptions * 11.0 + s.tackles * 0.55 + wins(p) * 16.0
        }

        val oroy = best({ it.position.isOffense && it.accruedSeasons == 0 }) { _, s ->
            s.passYards / 30.0 + s.passTouchdowns * 3.4 - s.interceptionsThrown * 1.6 +
                s.scrimmageYards / 8.5 + s.totalTouchdowns * 5.6
        }

        val droy = best({ !it.position.isOffense && it.position.group != PositionGroup.ST &&
                          it.accruedSeasons == 0 }) { _, s ->
            s.sacks * 9.0 + s.interceptions * 10.0 + s.tackles * 0.6
        }

        return Awards(mvp, opoy, dpoy, oroy, droy)
    }

    private fun summarise(player: Player, s: StatLine): String = when {
        s.passAttempts > 100 ->
            "${s.passYards} yards, ${s.passTouchdowns} TD, ${s.interceptionsThrown} INT"
        s.carries > s.receptions ->
            "${s.rushYards} rushing yards, ${s.totalTouchdowns} TD"
        s.receptions > 0 ->
            "${s.receptions} catches, ${s.receivingYards} yards, ${s.receivingTouchdowns} TD"
        else ->
            "${s.tackles} tackles, ${s.sacks} sacks, ${s.interceptions} INT"
    }
}

/** Sortable leaderboards for the stats screen. */
object LeagueLeaders {

    data class Entry(val player: PlayerId, val team: TeamId?, val name: String, val value: Int, val detail: String)

    fun passingYards(league: League, stats: Map<Int, StatLine>, n: Int = 10) =
        top(league, stats, n, { it.passYards }) { s ->
            "${s.completions}/${s.passAttempts}, ${s.passTouchdowns} TD, ${s.interceptionsThrown} INT"
        }

    fun rushingYards(league: League, stats: Map<Int, StatLine>, n: Int = 10) =
        top(league, stats, n, { it.rushYards }) { s ->
            "${s.carries} car, %.1f avg, ${s.rushTouchdowns} TD".format(s.yardsPerCarry)
        }

    fun receivingYards(league: League, stats: Map<Int, StatLine>, n: Int = 10) =
        top(league, stats, n, { it.receivingYards }) { s ->
            "${s.receptions} rec, ${s.receivingTouchdowns} TD"
        }

    fun sacks(league: League, stats: Map<Int, StatLine>, n: Int = 10) =
        top(league, stats, n, { it.sacks }) { s -> "${s.tackles} tackles" }

    fun interceptions(league: League, stats: Map<Int, StatLine>, n: Int = 10) =
        top(league, stats, n, { it.interceptions }) { s -> "${s.tackles} tackles" }

    private fun top(
        league: League,
        stats: Map<Int, StatLine>,
        n: Int,
        value: (StatLine) -> Int,
        detail: (StatLine) -> String,
    ): List<Entry> {
        val byId = league.players.associateBy { it.id.v }
        return stats.entries
            .mapNotNull { (id, line) ->
                val p = byId[id] ?: return@mapNotNull null
                Entry(p.id, p.teamId, p.name, value(line), detail(line))
            }
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(n)
    }
}
