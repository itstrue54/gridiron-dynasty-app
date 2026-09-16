package com.example.nflsimtext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme

/** What happened on the play, where it earns a coloured edge. */
enum class PlayEvent { SCORE, TURNOVER, FIRST_DOWN }

/** One line of the play log: down and distance in a fixed column, then the call. */
@Composable
fun PlayLogEntry(
    downDistance: String,
    text: String,
    modifier: Modifier = Modifier,
    event: PlayEvent? = null,
) {
    val c = NdTheme.colors
    val edge: Color = when (event) {
        PlayEvent.SCORE -> c.eventScore
        PlayEvent.TURNOVER -> c.eventTurnover
        PlayEvent.FIRST_DOWN -> c.eventFirstDown
        null -> Color.Transparent
    }
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(edge))
        Row(Modifier.padding(start = NdTheme.spacing.s, top = NdTheme.spacing.xs, bottom = NdTheme.spacing.xs)) {
            Text(
                downDistance,
                style = NdTheme.type.label,
                color = c.chalkDim,
                modifier = Modifier.width(88.dp),
            )
            Column { Text(text, style = NdTheme.type.body, color = c.chalk) }
        }
    }
}

@Preview(name = "Night")
@Composable
private fun LogNight() = PreviewFrame(dark = true) { Log() }

@Preview(name = "Day")
@Composable
private fun LogDay() = PreviewFrame(dark = false) { Log() }

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun LogLarge() = PreviewFrame(dark = true) { Log() }

@Composable
private fun Log() = Column {
    PlayLogEntry("3rd & 7", "Harlan pass short right to Okafor, +9. First down.", event = PlayEvent.FIRST_DOWN)
    PlayLogEntry("2nd & 7", "Dunn run middle, +0.")
    PlayLogEntry("1st & 10", "Harlan sacked, −3.")
}
