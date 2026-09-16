package com.example.nflsimtext.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme

/** Position filters and the like: selected is a chalk fill, the rest outlines. */
@Composable
fun FilterChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = NdTheme.colors
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
    ) {
        options.forEach { option ->
            Chip(option, option == selected) { onSelect(option) }
        }
    }
}

/**
 * One chip: chalk-filled when chosen, an outline when not. A row of filters
 * picks one; a club's scouting focus picks several.
 */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    role: Role = Role.Tab,
    onClick: () -> Unit,
) {
    val c = NdTheme.colors
    Box(
        modifier
            .defaultMinSize(minHeight = NdTheme.spacing.minTouch)
            .then(
                if (selected) Modifier.background(c.chalk, NdTheme.shapes.tag)
                else Modifier.border(BorderStroke(1.dp, c.turfLine), NdTheme.shapes.tag)
            )
            .clickable(role = role) { onClick() }
            .padding(horizontal = NdTheme.spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = NdTheme.type.label, color = if (selected) c.turf else c.chalkDim)
    }
}

@Preview(name = "Night")
@Composable
private fun ChipsNight() = PreviewFrame(dark = true) { Chips() }

@Preview(name = "Day")
@Composable
private fun ChipsDay() = PreviewFrame(dark = false) { Chips() }

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun ChipsLarge() = PreviewFrame(dark = true) { Chips() }

@Composable
private fun Chips() = FilterChipRow(
    options = listOf("All", "QB", "RB", "WR", "TE", "OL", "DL", "LB", "DB", "ST"),
    selected = "All",
    onSelect = {},
)
