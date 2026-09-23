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

        // Who lost his place. A club's best man at his position sat while
        // somebody behind him played: the coach is going on form, not talent
        // (SPEC 10.1). Said once about any one man.
        val benched = alreadySaid.filter { it.kind == NewsKind.BENCHING }.mapNotNull { it.player }.toSet()
        results.forEach { game ->
            listOf(game.home, game.away).forEach { id ->
                val club = league.team(id)
                val offence = com.nflsim.engine.ratings.SchemeCatalog.tuned(club.offenseScheme, league.tuning)
                val defence = com.nflsim.engine.ratings.SchemeCatalog.tuned(club.defenseScheme, league.tuning)
                BENCHABLE.forEach { position ->
                    val scheme = if (position.isOffense) offence else defence
                    val group = league.roster(id).filter {
                        it.position == position && it.injuryWeeks == 0 &&
                            it.status == com.nflsim.engine.model.PlayerStatus.ACTIVE
                    }
                    if (group.size < 2) return@forEach
                    val best = group.maxByOrNull { com.nflsim.engine.ratings.overall(it, scheme) }
                        ?: return@forEach
                    if (best.id.v in benched) return@forEach
                    fun snaps(p: com.nflsim.engine.model.Player) = game.snaps[p.id.v] ?: 0
                    // Whoever played the position. A benched man still gets a
                    // few snaps, so it is the share that tells the story.
                    val playing = group.maxByOrNull(::snaps) ?: return@forEach
                    if (playing.id == best.id || snaps(playing) < BENCHING_SNAPS) return@forEach
                    if (snaps(best) > snaps(playing) * BENCHING_SHARE) return@forEach
                    news += NewsEvent(
                        week, NewsKind.BENCHING,
                        "${best.name} (${position.label}, ${club.abbrev}) has lost his place to " +
                            "${playing.name}.",
                        best.id.v, id.v,
                    )
                }
            }
        }

        return news
    }

    /** Positions a coach benches a man at. Nobody reads that a guard sat. */
    private val BENCHABLE = listOf(
        com.nflsim.engine.model.Position.QB, com.nflsim.engine.model.Position.RB,
        com.nflsim.engine.model.Position.WR, com.nflsim.engine.model.Position.TE,
        com.nflsim.engine.model.Position.LB, com.nflsim.engine.model.Position.CB,
        com.nflsim.engine.model.Position.S,
    )

    /** Snaps the man in front of him has to take before it reads as a benching. */
    private const val BENCHING_SNAPS = 10

    /** Of those snaps, what the man behind can still take and not read as benched. */
    private const val BENCHING_SHARE = 0.4f
}
