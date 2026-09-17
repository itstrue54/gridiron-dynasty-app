package com.nflsim.engine.season

import com.nflsim.engine.model.League
import com.nflsim.engine.model.NewsEvent
import com.nflsim.engine.model.NewsKind
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.sim.GameResult
import com.nflsim.engine.stats.StatLine

/**
 * The week, as news (SPEC 10.1's weekly news).
 *
 * Only what a beat writer would actually file: the men who got hurt, the
 * afternoons somebody had, the career numbers that turned over, and the
 * coaches whose seat is warm. Everything is drawn from the week that was just
 * played, so nothing here invents anything the sim did not do.
 */
object NewsDesk {

    /** A game worth writing about. */
    private const val BIG_PASSING = 350
    private const val BIG_RUSHING = 150
    private const val BIG_RECEIVING = 150
    private const val BIG_SACKS = 3

    /** Career marks worth a paragraph, in the order they are checked. */
    private val PASSING_MARKS = listOf(10_000, 20_000, 30_000, 40_000, 50_000, 60_000)
    private val RUSHING_MARKS = listOf(5_000, 10_000, 12_000, 15_000, 18_000)
    private val RECEIVING_MARKS = listOf(5_000, 8_000, 10_000, 12_000, 15_000)
    private val SACK_MARKS = listOf(50, 100, 150)

    /** From this week on, a club well under .500 has a coach to talk about. */
    private const val HOT_SEAT_WEEK = 8
    private const val HOT_SEAT_MARGIN = 3

    /**
     * What happened this week. [before] is each player's total through last
     * week - career plus season - so a mark is only news the week it turns
     * over, and [standings] decides whose seat is warm.
     */
    fun forWeek(
        league: League,
        week: Int,
        results: List<GameResult>,
        before: Map<Int, StatLine>,
        after: Map<Int, StatLine>,
        standings: Standings,
        alreadySaid: List<NewsEvent> = emptyList(),
    ): List<NewsEvent> {
        val news = mutableListOf<NewsEvent>()
        fun name(id: Int) = league.playersById[PlayerId(id)]
        fun club(id: Int?) = league.teams.firstOrNull { it.id.v == id }

        // Who got hurt, and for how long.
        results.flatMap { it.injuries }.sortedByDescending { it.gamesOut }.forEach { hurt ->
            val p = name(hurt.player) ?: return@forEach
            if (hurt.gamesOut < 2) return@forEach
            val side = club(hurt.team)?.abbrev ?: ""
            news += NewsEvent(
                week = week,
                kind = NewsKind.INJURY,
                headline = if (hurt.gamesOut >= com.nflsim.engine.sim.Injury.SEASON_ENDING)
                    "${p.name} (${p.position.label}, $side) is out for the season."
                else "${p.name} (${p.position.label}, $side) is out ${hurt.gamesOut} weeks.",
                player = p.id.v,
                team = hurt.team,
            )
        }

        // The afternoons somebody had.
        results.forEach { game ->
            game.boxScore.players.forEach { (id, line) ->
                val p = name(id) ?: return@forEach
                val side = club(p.teamId?.v)?.abbrev ?: ""
                val note = when {
                    line.passYards >= BIG_PASSING ->
                        "${p.name} threw for ${line.passYards} and ${line.passTouchdowns} for $side."
                    line.rushYards >= BIG_RUSHING ->
                        "${p.name} ran for ${line.rushYards} on ${line.carries} carries for $side."
                    line.receivingYards >= BIG_RECEIVING ->
                        "${p.name} caught ${line.receptions} for ${line.receivingYards} for $side."
                    line.sacks >= BIG_SACKS ->
                        "${p.name} had ${line.sacks} sacks for $side."
                    else -> null
                }
                if (note != null) {
                    news += NewsEvent(week, NewsKind.PERFORMANCE, note, p.id.v, p.teamId?.v)
                }
            }
        }

        // Career numbers that turned over this week.
        after.forEach { (id, now) ->
            val p = name(id) ?: return@forEach
            val was = before[id] ?: StatLine()
            fun mark(marks: List<Int>, of: (StatLine) -> Int, what: String) {
                val crossed = marks.lastOrNull { of(now) >= it && of(was) < it } ?: return
                news += NewsEvent(
                    week, NewsKind.MILESTONE,
                    "${p.name} passed $crossed career $what.",
                    p.id.v, p.teamId?.v,
                )
            }
            mark(PASSING_MARKS, { it.passYards }, "passing yards")
            mark(RUSHING_MARKS, { it.rushYards }, "rushing yards")
            mark(RECEIVING_MARKS, { it.receivingYards }, "receiving yards")
            mark(SACK_MARKS, { it.sacks }, "sacks")
        }

        // Whose seat is warm. Said once a season about any one club.
        if (week >= HOT_SEAT_WEEK) {
            val said = alreadySaid.filter { it.kind == NewsKind.HOT_SEAT }.mapNotNull { it.team }.toSet()
            league.teams.forEach { team ->
                if (team.id.v in said) return@forEach
                val record = standings.record(team.id)
                if (record.losses - record.wins < HOT_SEAT_MARGIN) return@forEach
                val coach = league.coaches[team.staff.headCoach] ?: return@forEach
                news += NewsEvent(
                    week, NewsKind.HOT_SEAT,
                    "${team.name} are ${record.wins}-${record.losses} and ${coach.name} is under pressure.",
                    null, team.id.v,
                )
            }
        }

        return news
    }
}
