package com.nflsim.engine.season

import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.stats.StatLine
import kotlinx.serialization.Serializable

@Serializable
data class AwardWinner(
    val player: Int,
    val team: Int,
    val summary: String,
    /** Kept with the award: a winner who retires is gone from the league. */
    val name: String = "",
    val position: String = "",
)

/** An All-Pro first (tier 1) or second (2) team place, or a Pro Bowl (3). */
@Serializable
data class Honour(val player: Int, val name: String, val position: String, val club: Int, val tier: Int)

@Serializable
data class Awards(
    val mostValuablePlayer: AwardWinner? = null,
    val offensivePlayerOfTheYear: AwardWinner? = null,
    val defensivePlayerOfTheYear: AwardWinner? = null,
    val offensiveRookieOfTheYear: AwardWinner? = null,
    val defensiveRookieOfTheYear: AwardWinner? = null,
    val comebackPlayerOfTheYear: AwardWinner? = null,
    /** Here the player field is the coach's id. */
    val coachOfTheYear: AwardWinner? = null,
    val honours: List<Honour> = emptyList(),
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
                name = winner.first.name,
                position = winner.first.position.label,
            )
        }

        val mvp = best({ it.position != Position.K && it.position != Position.P }) { p, s ->
            val production = mvpProduction(p, s)
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

    /**
     * The rest of SPEC 6's hardware: comeback player and coach of the year,
     * the All-Pro teams and the Pro Bowl. All-Pro voters watch the tape, not
     * only the box score - a lineman has no stat line at all - so ratings
     * carry these, production adds a few points, and the ballot is noisy.
     */
    fun honours(
        awards: Awards,
        league: League,
        records: Map<TeamId, TeamRecord>,
        stats: Map<Int, StatLine>,
        previousStats: Map<Int, StatLine>,
        previousWinPct: Map<Int, Float>,
        year: Int,
    ): Awards {
        val rng = SplitMixRng(year * 7919L)
        val rostered = league.players.filter { it.teamId != null }
        val score = rostered.associate { p ->
            p.id.v to overall(p) + (stats[p.id.v]?.let { tape(p, it) } ?: 0f) + rng.gaussian(0f, 1.5f)
        }
        fun honour(p: Player, tier: Int) = Honour(p.id.v, p.name, p.position.label, p.teamId?.v ?: 0, tier)
        fun ranked(group: List<Player>, pos: Position) =
            group.filter { it.position == pos }.sortedByDescending { score.getValue(it.id.v) }

        val allPro = ALL_PRO.flatMap { (pos, n) ->
            val best = ranked(rostered, pos)
            best.take(n).map { honour(it, 1) } + best.drop(n).take(n).map { honour(it, 2) }
        }
        val proBowl = rostered.groupBy { league.team(it.teamId!!).conference }.values.flatMap { conference ->
            PRO_BOWL.flatMap { (pos, n) -> ranked(conference, pos).take(n).map { honour(it, 3) } }
        }

        // Comeback: the biggest rise in production from a veteran who had
        // little of it the season before.
        val comeback = if (previousStats.isEmpty()) null else rostered
            .filter { it.accruedSeasons >= 2 }
            .mapNotNull { p ->
                val line = stats[p.id.v] ?: return@mapNotNull null
                val now = mvpProduction(p, line)
                val before = previousStats[p.id.v]?.let { mvpProduction(p, it) } ?: 0.0
                if (now < COMEBACK_FLOOR || before > now * COMEBACK_SHARE) null else Triple(p, line, now - before)
            }
            .maxByOrNull { it.third }
            ?.let { (p, line, _) ->
                AwardWinner(p.id.v, p.teamId?.v ?: 0, summarise(p, line), p.name, p.position.label)
            }

        // Coach of the year: the head coach whose club won, and rose most.
        val coach = league.teams.maxByOrNull { t ->
            val now = records[t.id]?.winPct?.toFloat() ?: 0.5f
            now * 0.6f + (now - (previousWinPct[t.id.v] ?: 0.5f)) + rng.gaussian(0f, 0.05f)
        }?.let { t ->
            val hc = league.coaches[t.staff.headCoach]
            val now = records[t.id]?.winPct ?: 0.5
            val before = previousWinPct[t.id.v] ?: 0.5f
            AwardWinner(hc?.id?.v ?: 0, t.id.v, "%s %.3f, from %.3f".format(t.abbrev, now, before),
                hc?.name ?: "${t.abbrev} head coach", "HC")
        }

        return awards.copy(comebackPlayerOfTheYear = comeback, coachOfTheYear = coach, honours = allPro + proBowl)
    }

    private fun mvpProduction(p: Player, s: StatLine): Double = when (p.position) {
        Position.QB -> s.passYards / 25.0 + s.passTouchdowns * 4.4 -
            s.interceptionsThrown * 2.2 + s.rushYards / 10.0 + s.rushTouchdowns * 5.0
        else -> s.scrimmageYards / 9.0 + s.totalTouchdowns * 5.4 +
            s.sacks * 6.0 + s.interceptions * 8.0
    }

    /** What production adds to a rating on the All-Pro ballot, up to eight points. */
    private fun tape(p: Player, s: StatLine): Float = when {
        p.position == Position.QB ->
            (s.passYards / 25f + s.passTouchdowns * 4f - s.interceptionsThrown * 2f) / 40f
        p.position.isOffense -> (s.scrimmageYards / 10f + s.totalTouchdowns * 6f) / 25f
        else -> (s.sacks * 6f + s.interceptions * 8f + s.tackles * 0.5f) / 10f
    }.coerceIn(0f, 8f)

    /** All-Pro places per team, the AP's shape on this engine's positions. */
    private val ALL_PRO = mapOf(
        Position.QB to 1, Position.RB to 1, Position.FB to 1, Position.WR to 2, Position.TE to 1,
        Position.LT to 1, Position.LG to 1, Position.C to 1, Position.RG to 1, Position.RT to 1,
        Position.EDGE to 2, Position.DT to 2, Position.LB to 2, Position.CB to 2, Position.S to 2,
        Position.K to 1, Position.P to 1, Position.LS to 1,
    )

    /** Pro Bowl places per conference. */
    private val PRO_BOWL = mapOf(
        Position.QB to 3, Position.RB to 3, Position.FB to 1, Position.WR to 4, Position.TE to 2,
        Position.LT to 2, Position.LG to 2, Position.C to 2, Position.RG to 2, Position.RT to 2,
        Position.EDGE to 3, Position.DT to 3, Position.LB to 3, Position.CB to 4, Position.S to 3,
        Position.K to 1, Position.P to 1, Position.LS to 1,
    )

    /** A comeback is a real season, from someone who managed under 40% of it the year before. */
    private const val COMEBACK_FLOOR = 120.0
    private const val COMEBACK_SHARE = 0.4

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
