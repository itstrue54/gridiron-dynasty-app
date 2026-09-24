package com.example.nflsimtext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme

/** What the game is asking for right now. Always paired with text. */
enum class Situation { NORMAL, THIRD_DOWN, RED_ZONE, TWO_MINUTE }

/**
 * The core pattern (docs/DESIGN.md 5): a raised block with a 4dp situation
 * edge, a title and optional meta, a rule, then content.
 */
@Composable
fun SituationBlock(
    title: String,
    modifier: Modifier = Modifier,
    situation: Situation = Situation.NORMAL,
    meta: String? = null,
    /** Set when the whole block is a control, as an expander is. */
    onClick: (() -> Unit)? = null,
    /** A block with nothing under its title needs no rule under it either. */
    divider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = NdTheme.colors
    val edge: Color = when (situation) {
        Situation.NORMAL -> c.sitNormal
        Situation.THIRD_DOWN -> c.sitThirdDown
        Situation.RED_ZONE -> c.sitRedZone
        Situation.TWO_MINUTE -> c.sitTwoMinute
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(NdTheme.shapes.block)
            .background(c.turfRaised)
            // The edge is drawn, not laid out. It used to be a Box in a Row
            // sized to its own minimum intrinsic height, and a FlowRow reports
            // that as a single line: a block ending in buttons that wrapped
            // was cut off after the first row, taking "Let the front office
            // decide" off the contracts screen with it.
            .drawBehind { drawRect(edge, size = Size(EDGE.toPx(), size.height)) }
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(start = EDGE),
    ) {
        Column(Modifier.padding(NdTheme.spacing.l)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = NdTheme.type.title, color = c.chalk)
                if (meta != null) Text(meta, style = NdTheme.type.label, color = c.chalkDim)
            }
            if (divider) {
                Box(
                    Modifier
                        .padding(vertical = NdTheme.spacing.s)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(c.turfLine),
                )
            }
            content()
        }
    }
}

/** The situation edge's width. */
private val EDGE = 4.dp

@Preview(name = "Night")
@Composable
private fun BlockNight() = PreviewFrame(dark = true) { Blocks() }

@Preview(name = "Day")
@Composable
private fun BlockDay() = PreviewFrame(dark = false) { Blocks() }

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun BlockLarge() = PreviewFrame(dark = true) { Blocks() }

@Composable
private fun Blocks() = Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
    SituationBlock("South division", meta = "Week 7") {
        Text("Austin 4-2", style = NdTheme.type.data, color = NdTheme.colors.chalk)
    }
    SituationBlock("Needs attention", situation = Situation.RED_ZONE) {
        Text("2 contracts expire after season", style = NdTheme.type.body, color = NdTheme.colors.chalk)
    }
}
