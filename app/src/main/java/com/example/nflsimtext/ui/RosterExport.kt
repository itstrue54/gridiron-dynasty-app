package com.example.nflsimtext.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.nflsim.data.roster.RosterExporter
import com.nflsim.engine.model.League
import java.io.File

/**
 * Writing a club's roster out as a spreadsheet (SPEC 9.4). The same shape the
 * game reads back in, so a roster can leave, be edited, and come home.
 *
 * On Android 10 and up it goes to the phone's own Downloads, where a person
 * can actually find it; below that, to the app's folder, which is the only
 * place it may write without asking for permission it does not need.
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            toDownloads(context, name, csv)
        } else {
            val file = File(context.getExternalFilesDir(null), name)
            file.writeText(csv)
            file.path
        }
    }

    private fun toDownloads(context: Context, name: String, csv: String): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver
            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("the phone would not open a file to write")
        context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
            ?: error("the phone would not write the file")
        return "Downloads/$name"
    }
}
