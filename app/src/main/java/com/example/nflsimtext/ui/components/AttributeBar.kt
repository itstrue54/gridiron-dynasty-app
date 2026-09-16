package com.example.nflsimtext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.ratingColor

/**
 * One rating as a label, a 4dp bar and its number. Square ends: this is a
 * chalk mark on a sheet, not a progress meter.
 */
@Composable
fun AttributeBar(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    /** What the club is guessing within, when it does not know the rating. */
    band: IntRange? = null,
    /** The figure to print, which may be a band rather than a number. */
    text: String = value.toString(),
) {
    val c = NdTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(NdTheme.spacing.rowHeight)
            .clearAndSetSemantics {
                contentDescription = if (band == null) "$label $value"
                else "$label, somewhere between ${band.first} and ${band.last}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = NdTheme.type.data, color = c.chalk, modifier = Modifier.weight(1.4f))
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = NdTheme.spacing.m)
                .height(4.dp)
                .background(c.turfLine),
        ) {
            val ink = ratingColor(value, c)
            if (band == null) {
                Box(
                    Modifier
                        .fillMaxWidth(value.coerceIn(0, 99) / 99f)
                        .height(4.dp)
                        .background(ink),
                )
            } else {
                // Solid to the least he can be, then faint through what the
                // club is unsure of: the bar still says how good he is, and
                // where it stops being sure.
                val low = band.first.coerceIn(0, 99)
                val high = band.last.coerceIn(low, 99)
                Row(Modifier.fillMaxWidth()) {
                    if (low > 0) {
                        Box(Modifier.weight(low.toFloat()).height(4.dp).background(ink))
                    }
                    Box(
                        Modifier
                            .weight((high - low).coerceAtLeast(1).toFloat())
                            .height(4.dp)
                            .background(ink.copy(alpha = 0.4f)),
                    )
                    if (high < 99) Spacer(Modifier.weight((99 - high).toFloat()))
                }
            }
        }
        Text(
            text,
            style = NdTheme.type.data,
            color = c.chalk,
            maxLines = 1,
            modifier = Modifier.width(if (band == null) 28.dp else 56.dp),
        )
    }
}

@Preview(name = "Night")
@Composable
private fun BarsNight() = PreviewFrame(dark = true) { Bars() }

@Preview(name = "Day")
@Composable
private fun BarsDay() = PreviewFrame(dark = false) { Bars() }

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun BarsLarge() = PreviewFrame(dark = true) { Bars() }

@Composable
private fun Bars() = Column {
    AttributeBar("Accuracy short", 84)
    AttributeBar("Arm strength", 91)
    AttributeBar("Pocket presence", 68, band = 62..74, text = "62-74")
}
