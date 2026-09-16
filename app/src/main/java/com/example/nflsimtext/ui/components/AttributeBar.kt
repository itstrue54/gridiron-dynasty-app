package com.example.nflsimtext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
fun AttributeBar(label: String, value: Int, modifier: Modifier = Modifier) {
    val c = NdTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(NdTheme.spacing.rowHeight)
            .clearAndSetSemantics { contentDescription = "$label $value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = NdTheme.type.data, color = c.chalk, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = NdTheme.spacing.m)
                .height(4.dp)
                .background(c.turfLine),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0, 99) / 99f)
                    .height(4.dp)
                    .background(ratingColor(value, c)),
            )
        }
        Text(
            value.toString(),
            style = NdTheme.type.data,
            color = c.chalk,
            modifier = Modifier.width(28.dp),
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
    AttributeBar("Pocket presence", 68)
}
