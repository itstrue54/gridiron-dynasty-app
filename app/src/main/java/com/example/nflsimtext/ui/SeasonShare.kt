package com.example.nflsimtext.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.data.export.SeasonExporter
import com.nflsim.engine.model.League
import com.nflsim.engine.model.TeamId

/**
 * A season, sent somewhere (SPEC 11): Markdown to wherever the player posts,
 * through the phone's share sheet, and the standings as a CSV in Downloads.
 */
@Composable
fun SeasonShareBlock(
    season: SeasonExporter.Season,
    league: League,
    user: TeamId?,
    extra: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val c = NdTheme.colors
    var saved by remember(season.year) { mutableStateOf<String?>(null) }
    SituationBlock("Share the ${season.year} season", meta = season.soFar ?: "Final") {
        Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
            extra()
            Text(
                "Your results, the standings, the awards and the leaders, written up to post.",
                style = NdTheme.type.body, color = c.chalkDim,
            )
            SecondaryButton("Share this season", {
                val text = SeasonExporter.markdown(season, league, user)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "The ${season.year} season")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, "Share the ${season.year} season"))
            }, Modifier.fillMaxWidth())
            SecondaryButton("Save the standings as CSV", {
                saved = try {
                    "Saved to " + Downloads.write(context, SeasonExporter.fileName(season, "csv"), "text/csv",
                        SeasonExporter.standingsCsv(season, league))
                } catch (e: Exception) {
                    e.message ?: "The file would not write."
                }
            }, Modifier.fillMaxWidth())
            saved?.let { Text(it, style = NdTheme.type.caption, color = c.chalkDim) }
        }
    }
}
