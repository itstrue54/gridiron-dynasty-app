package com.nflsim.data.roster

import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.overall

/**
 * Writes rosters back out as CSV.
 *
 * Two reasons this matters as much as the importer:
 *
 *  1. Round trip. Export a generated league, edit it in a spreadsheet, import
 *     it back. That is how you hand-tune a roster without writing Kotlin.
 *  2. It is the format documentation. Whatever this writes, the importer reads.
 */
object RosterExporter {

    private val META = listOf(
        "team", "jersey", "first_name", "last_name", "position", "archetype",
        "overall", "age", "birth_year", "height_in", "weight_lb", "college", "dev",
    )

    private val RATING_HEADERS = RatingId.entries.map { it.name.lowercase() }

    fun header(): List<String> = META + RATING_HEADERS

    fun toCsv(players: List<Player>, year: Int, teamAbbrev: (Player) -> String = { "" }): String {
        val rows = mutableListOf(header())
        players.forEach { rows += row(it, year, teamAbbrev(it)) }
        return Csv.write(rows)
    }

    fun toCsv(league: League): String {
        val abbrevById = league.teams.associate { it.id to it.abbrev }
        return toCsv(league.players, league.year) { p -> p.teamId?.let { abbrevById[it] } ?: "" }
    }

    fun teamToCsv(league: League, abbrev: String): String? {
        val team = league.teams.firstOrNull { it.abbrev.equals(abbrev, ignoreCase = true) }
            ?: return null
        return toCsv(league.roster(team.id), league.year) { team.abbrev }
    }

    private fun row(p: Player, year: Int, team: String): List<String> = buildList {
        add(team)
        add(p.jersey?.toString() ?: "")
        add(p.firstName)
        add(p.lastName)
        add(p.position.label)
        add(p.archetype.name)
        add(overall(p).toString())
        add(p.age(year).toString())
        add(p.birthYear.toString())
        add(p.heightIn.toString())
        add(p.weightLb.toString())
        add(p.college)
        add(p.traits.developmentCurve.name)
        RatingId.entries.forEach { add(p.ratings[it].toString()) }
    }

    /** A minimal, commented starting point for someone typing a roster by hand. */
    fun template(): String = """
        # NFL Sim Text roster import template
        #
        # Only 'position' is required. Everything else is optional and is filled
        # in for you when missing. Lines starting with # are ignored.
        #
        # Three levels of detail all work:
        #   1. name,position                     -> a plausible player is generated
        #   2. name,position,ovr                 -> generated to hit that overall
        #   3. name,position,spd,acc,awr,...     -> your numbers are used as given
        #
        # Position accepts the usual spellings: QB RB FB WR TE LT LG C RG RT
        # EDGE DE OLB DT NT LB MLB ILB CB DB S FS SS K P LS. Generic 'T' and 'G'
        # are read as the left side and flagged in the report.
        #
        # Ratings columns accept full names (throw_acc_short) or short codes
        # (TAS). Any column that is not recognised is listed in the report and
        # ignored, never guessed at.
        #
        # Height accepts 74, 6-2, or 6'2".
        # Dev accepts slow, normal, quick, superstar, x_factor.
        #
        team,jersey,first_name,last_name,position,archetype,overall,age,height_in,weight_lb,college,dev
        KC,15,Example,Passer,QB,FIELD_GENERAL,92,29,74,225,Texas Tech,superstar
        KC,10,Example,Back,RB,ONE_CUT_ZONE,84,26,70,205,LSU,normal
        KC,87,Example,Receiver,TE,RECEIVING_TE,88,33,77,250,Cincinnati,quick
    """.trimIndent()
}
