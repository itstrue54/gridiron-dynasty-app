package com.example.nflsimtext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme

/** What a tag is saying, which decides its colour. */
enum class TagTone { NEUTRAL, INFO, CAUTION, URGENT }

/**
 * A small label that carries its own text, so colour is never the only signal
 * (docs/DESIGN.md 9). Injury tags read Q, D, O and IR.
 */
@Composable
fun StatusTag(text: String, tone: TagTone = TagTone.NEUTRAL, modifier: Modifier = Modifier) {
    val c = NdTheme.colors
    val ground: Color = when (tone) {
        TagTone.NEUTRAL -> c.turfLine
        TagTone.INFO -> c.sitThirdDown
        TagTone.CAUTION -> c.stripe
        TagTone.URGENT -> c.sitRedZone
    }
    val ink: Color = when (tone) {
        TagTone.CAUTION -> c.onStripe
        else -> c.chalk
    }
    Text(
        text,
        style = NdTheme.type.caption.copy(fontWeight = FontWeight.W500),
        color = ink,
        modifier = modifier
            .background(ground, NdTheme.shapes.tag)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Preview(name = "Night")
@Composable
private fun TagsNight() = PreviewFrame(dark = true) { TagRow() }

@Preview(name = "Day")
@Composable
private fun TagsDay() = PreviewFrame(dark = false) { TagRow() }

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun TagsLarge() = PreviewFrame(dark = true) { TagRow() }

@Composable
private fun TagRow() = Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    StatusTag("Q", TagTone.CAUTION)
    StatusTag("D", TagTone.INFO)
    StatusTag("O", TagTone.URGENT)
    StatusTag("IR", TagTone.NEUTRAL)
}
