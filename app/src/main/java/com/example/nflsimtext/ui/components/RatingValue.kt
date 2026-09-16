package com.example.nflsimtext.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.ratingColor

/** A rating in its tier colour, the number always doing the talking. */
@Composable
fun RatingValue(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = NdTheme.type.data,
) = Text(
    value.toString(),
    style = style,
    color = ratingColor(value, NdTheme.colors),
    textAlign = TextAlign.End,
    modifier = modifier,
)

@Preview(name = "Night")
@Composable
private fun RatingsNight() = PreviewFrame(dark = true) { RatingRow() }

@Preview(name = "Day")
@Composable
private fun RatingsDay() = PreviewFrame(dark = false) { RatingRow() }

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun RatingsLarge() = PreviewFrame(dark = true) { RatingRow() }

@Composable
private fun RatingRow() = Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    listOf(94, 86, 74, 61).forEach { RatingValue(it) }
}
