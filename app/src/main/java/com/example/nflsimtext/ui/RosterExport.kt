package com.example.nflsimtext.ui

import android.content.Context
import com.nflsim.data.roster.RosterExporter
import com.nflsim.engine.model.League

/**
 * Writing a club's roster out as a spreadsheet (SPEC 9.4). The same shape the
 * game reads back in, so a roster can leave, be edited, and come home.
 * It goes where [Downloads] puts things a person should be able to find.
 */
object RosterExport {

    /** Writes [league]'s roster, or one club's, and says where it went. */
    fun write(league: League, context: Context, teamAbbrev: String? = null): String {
        val csv = if (teamAbbrev == null) RosterExporter.toCsv(league)
        else RosterExporter.teamToCsv(league, teamAbbrev)
            ?: error("no club called $teamAbbrev")
        val name = buildString {
            append("nflsimtext-")
            append(teamAbbrev?.lowercase() ?: "league")
            append("-${league.year}.csv")
        }
        return Downloads.write(context, name, "text/csv", csv)
    }
}
