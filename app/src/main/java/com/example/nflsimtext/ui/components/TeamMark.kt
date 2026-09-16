package com.example.nflsimtext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nflsimtext.ui.theme.DisplayFamily
import com.example.nflsimtext.ui.theme.NdTheme

/**
 * A club's mark: its abbreviation on its own colour. Never logo artwork of a
 * real team (docs/DESIGN.md 1).
 */
@Composable
fun TeamMark(
    abbr: String,
    primary: Color,
    secondary: Color,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    val ink = if (contrast(secondary, primary) >= 3.0) secondary else blackOrWhiteOn(primary)
    Box(
        modifier.size(size).background(primary, NdTheme.shapes.block),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            abbr.uppercase(),
            fontFamily = DisplayFamily,
            fontWeight = FontWeight.W700,
            fontSize = (size.value * 0.42f).sp,
            color = ink,
        )
    }
}

private fun channel(c: Float) =
    if (c <= 0.03928f) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

private fun luminance(c: Color) =
    0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

private fun contrast(a: Color, b: Color): Double {
    val x = luminance(a); val y = luminance(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}

/** When a club's own second colour is unreadable on its first, fall back. */
private fun blackOrWhiteOn(ground: Color): Color =
    if (contrast(Color.White, ground) >= contrast(Color.Black, ground)) Color.White else Color.Black

@Preview(name = "Night")
@Composable
private fun MarksNight() = PreviewFrame(dark = true) { Marks() }

@Preview(name = "Day")
@Composable
private fun MarksDay() = PreviewFrame(dark = false) { Marks() }

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun MarksLarge() = PreviewFrame(dark = true) { Marks() }

@Composable
private fun Marks() = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    // Clubs bring their own colours; the preview borrows tokens so no hex
    // value lives outside ui/theme.
    val c = NdTheme.colors
    TeamMark("AUS", c.sitThirdDown, c.stripe)
    TeamMark("MEM", c.sitRedZone, c.sitRedZone)
    TeamMark("SEA", c.chalk, c.chalkDim)
}
