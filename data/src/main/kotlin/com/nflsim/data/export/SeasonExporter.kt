package com.nflsim.data.export

import com.nflsim.engine.model.ArchivedGame
import com.nflsim.engine.model.Conference
import com.nflsim.engine.model.Division
import com.nflsim.engine.model.League
import com.nflsim.engine.model.LeaderEntry
import com.nflsim.engine.model.SeasonRecord
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.season.AwardWinner
import com.nflsim.engine.season.Awards
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyPhase
import com.nflsim.engine.season.Schedule
import com.nflsim.engine.season.SeasonLeaders
import com.nflsim.engine.season.TeamRecord
import java.util.Locale

/**
 * A season, written out to post or keep (SPEC 11): Markdown to share, and a
 * CSV of the standings. Results, standings, awards and leaders only - what a
 * league remembers about a year - so nothing here reads a rating.
 */
object SeasonExporter {

    /** A season as it is written: finished, or the current one so far. */
    data class Season(
        val year: Int,
        /** Where the current season has got to; null once it is complete. */
        val soFar: String?,
        val champion: Int?,
        /** Each division's clubs, in standings order. */
        val divisions: List<Pair<String, List<TeamRecord>>>,
        val awards: Awards?,
        val leaders: List<LeaderEntry>,
        val games: List<ArchivedGame>,
    )

    /** A season the league has filed. */
    fun of(record: SeasonRecord, league: League): Season {
        val byTeam = record.standings.associateBy { it.team }
        return Season(
            year = record.year,
            soFar = null,
            champion = record.champion,
            divisions = divisions(league) { ids ->
                ids.mapNotNull { byTeam[it] }.sortedWith(
                    compareByDescending<TeamRecord> { pct(it) }.thenByDescending { it.pointsFor - it.pointsAgainst })
            },
            awards = record.awards,
            leaders = record.leaders,
            games = league.history.games.filter { it.year == record.year },
        )
    }

    /** The season being played, as it stands. */
    fun current(dynasty: Dynasty): Season {
        val standings = dynasty.standings()
        val played = dynasty.results.maxOfOrNull { it.week } ?: 0
        return Season(
            year = dynasty.year,
            soFar = when {
                dynasty.phase == DynastyPhase.REGULAR_SEASON && played == 0 -> "before week 1"
                dynasty.phase == DynastyPhase.REGULAR_SEASON -> "through week $played"
                dynasty.phase == DynastyPhase.PLAYOFFS -> "regular season complete"
                else -> null
            },
            champion = dynasty.champion,
            divisions = divisions(dynasty.league) { ids ->
                val (conference, division) = dynasty.league.team(ids.first()).let { it.conference to it.division }
                standings.division(conference, division).map { standings.record(it) }
            },
            awards = null,
            leaders = SeasonLeaders.of(dynasty.playerStats, dynasty.league),
            games = dynasty.league.history.games.filter { it.year == dynasty.year },
        )
    }

    private fun divisions(league: League, order: (List<TeamId>) -> List<TeamRecord>): List<Pair<String, List<TeamRecord>>> =
        Conference.entries.flatMap { c -> Division.entries.map { c to it } }.mapNotNull { (c, d) ->
            val ids = league.teams.filter { it.conference == c && it.division == d }.map { it.id }
            if (ids.isEmpty()) null else league.divisionName(league.team(ids.first())) to order(ids)
        }

    private fun pct(r: TeamRecord): Double =
        if (r.games == 0) 0.0 else (r.wins + r.ties / 2.0) / r.games

    fun markdown(season: Season, league: League, user: TeamId?): String = buildString {
        fun name(id: Int) = league.teams.firstOrNull { it.id.v == id }?.name ?: "#$id"
        val mine = user?.let { u -> league.teams.firstOrNull { it.id == u } }

        appendLine("# ${season.year} " + league.names.leagueShort.let { if (it.isBlank()) "" else "$it " } + "season" +
            (season.soFar?.let { ", $it" } ?: ""))
        appendLine()
        val title = league.names.championship.let { if (it.isBlank()) "Champion" else "$it champion" }
        season.champion?.let { appendLine("**$title:** ${name(it)}") }
        if (mine != null) {
            val division = season.divisions.firstOrNull { (_, rows) -> rows.any { it.team == mine.id } }
            val row = division?.second?.firstOrNull { it.team == mine.id }
            if (row != null) {
                val place = division.second.indexOf(row) + 1
                appendLine("**${mine.name}:** ${recordText(row)}, ${ordinal(place)} in the ${division.first} " +
                    "(${row.pointsFor} for, ${row.pointsAgainst} against)")
            }
        }
        appendLine()

        if (mine != null) {
            val games = season.games.filter { it.involves(mine.id.v) }.sortedBy { it.week }
            if (games.isNotEmpty()) {
                appendLine("## ${mine.name}, game by game")
                appendLine()
                appendLine("| Week | Opponent | Result |")
                appendLine("|---|---|---|")
                // A regular-season week without a game was the bye.
                val weeks = games.map { it.week }.toSet()
                val lastRegular = games.filter { it.week <= Schedule.WEEKS }.maxOfOrNull { it.week } ?: 0
                val byes = (1..lastRegular).filter { it !in weeks }
                (games.map { it.week to it } + byes.map { it to null }).sortedBy { it.first }.forEach { (week, g) ->
                    if (g == null) {
                        appendLine("| $week | Bye | |")
                        return@forEach
                    }
                    val home = g.home == mine.id.v
                    val us = if (home) g.homeScore else g.awayScore
                    val them = if (home) g.awayScore else g.homeScore
                    val result = when {
                        us > them -> "W"
                        us < them -> "L"
                        else -> "T"
                    }
                    appendLine("| ${weekLabel(g.week)} | ${if (home) "" else "at "}${name(if (home) g.away else g.home)} " +
                        "| $result $us-$them |")
                }
                appendLine()
            }
        }

        appendLine("## Standings")
        season.divisions.forEach { (division, rows) ->
            appendLine()
            appendLine("### $division")
            appendLine()
            appendLine("| Team | W | L | T | PF | PA |")
            appendLine("|---|---|---|---|---|---|")
            rows.forEach { r ->
                val team = name(r.team.v).let { if (r.team == user) "**$it**" else it }
                appendLine("| $team | ${r.wins} | ${r.losses} | ${r.ties} | ${r.pointsFor} | ${r.pointsAgainst} |")
            }
        }

        val awards = season.awards?.let(::awardLines).orEmpty()
        if (awards.isNotEmpty()) {
            appendLine()
            appendLine("## Awards")
            appendLine()
            awards.forEach { (label, w) ->
                val abbrev = league.teams.firstOrNull { it.id.v == w.team }?.abbrev
                val who = listOfNotNull(w.position.ifBlank { null }, abbrev).joinToString(", ")
                appendLine("- **$label:** ${w.name}" + if (who.isEmpty()) "" else " ($who)")
            }
        }

        if (season.leaders.isNotEmpty()) {
            appendLine()
            appendLine("## League leaders")
            appendLine()
            season.leaders.forEach { appendLine("- **${it.category}:** ${it.name}, ${thousands(it.value)}") }
        }
        appendLine()
        append("_From a Gridiron Dynasty league._")
        appendLine()
    }

    /** One row a club: the standings as a spreadsheet reads them. */
    fun standingsCsv(season: Season, league: League): String = buildString {
        appendLine("season,division,team,abbrev,wins,losses,ties,points_for,points_against,champion")
        season.divisions.forEach { (division, rows) ->
            rows.forEach { r ->
                val team = league.team(r.team)
                appendLine(listOf(
                    season.year, csv(division), csv(team.name), team.abbrev,
                    r.wins, r.losses, r.ties, r.pointsFor, r.pointsAgainst,
                    if (season.champion == r.team.v) "yes" else "",
                ).joinToString(","))
            }
        }
    }

    /** "nflsimtext-2026-season.md", ".csv". */
    fun fileName(season: Season, extension: String): String = "nflsimtext-${season.year}-season.$extension"

    private fun awardLines(a: Awards): List<Pair<String, AwardWinner>> = listOfNotNull(
        a.mostValuablePlayer?.let { "Most valuable player" to it },
        a.offensivePlayerOfTheYear?.let { "Offensive player of the year" to it },
        a.defensivePlayerOfTheYear?.let { "Defensive player of the year" to it },
        a.offensiveRookieOfTheYear?.let { "Offensive rookie of the year" to it },
        a.defensiveRookieOfTheYear?.let { "Defensive rookie of the year" to it },
        a.comebackPlayerOfTheYear?.let { "Comeback player of the year" to it },
        a.coachOfTheYear?.let { "Coach of the year" to it },
    )

    private fun recordText(r: TeamRecord): String =
        if (r.ties > 0) "${r.wins}-${r.losses}-${r.ties}" else "${r.wins}-${r.losses}"

    private fun weekLabel(week: Int): String = when (week - Schedule.WEEKS) {
        in Int.MIN_VALUE..0 -> "$week"
        1 -> "Wild card"
        2 -> "Divisional"
        3 -> "Conference"
        else -> "Final"
    }

    private fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    private fun thousands(n: Int): String = String.format(Locale.US, "%,d", n)

    private fun csv(s: String): String = if (s.any { it == ',' || it == '"' }) "\"${s.replace("\"", "\"\"")}\"" else s
}
