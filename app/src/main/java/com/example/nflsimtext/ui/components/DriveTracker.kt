package com.example.nflsimtext.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme

/** Which way this offence is moving. */
enum class Direction { RIGHT, LEFT }

/**
 * The field as a 32dp strip (docs/DESIGN.md 6): yard ticks every ten, the ball
 * a chalk dot, the line to gain a stripe bar, end zones tinted.
 */
@Composable
fun DriveTracker(
    ballOn: Int,
    lineToGain: Int?,
    direction: Direction,
    modifier: Modifier = Modifier,
    ballLabel: String = "the $ballOn",
    gainLabel: String? = lineToGain?.let { "the $it" },
) {
    val c = NdTheme.colors
    val spoken = buildString {
        append("Ball on $ballLabel.")
        if (gainLabel != null) append(" Line to gain, $gainLabel.")
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        val endZone = size.width * 0.06f
        val field = size.width - endZone * 2
        fun x(yard: Int): Float {
            val fraction = yard.coerceIn(0, 100) / 100f
            val along = if (direction == Direction.RIGHT) fraction else 1f - fraction
            return endZone + field * along
        }

        drawRect(c.turfLine, Offset.Zero, Size(endZone, size.height))
        drawRect(c.turfLine, Offset(size.width - endZone, 0f), Size(endZone, size.height))

        for (yard in 0..100 step 10) {
            drawLine(
                c.turfLine,
                Offset(x(yard), size.height * 0.18f),
                Offset(x(yard), size.height * 0.82f),
                strokeWidth = 1.dp.toPx(),
            )
        }

        if (lineToGain != null) {
            drawLine(
                c.stripe,
                Offset(x(lineToGain), 0f),
                Offset(x(lineToGain), size.height),
                strokeWidth = 3.dp.toPx(),
            )
        }
        drawCircle(c.chalk, radius = 5.dp.toPx(), center = Offset(x(ballOn), size.height / 2))
    }
}

@Preview(name = "Night")
@Composable
private fun TrackerNight() = PreviewFrame(dark = true) {
    DriveTracker(66, 73, Direction.RIGHT, ballLabel = "the Memphis 34", gainLabel = "the Memphis 27")
}

@Preview(name = "Day")
@Composable
private fun TrackerDay() = PreviewFrame(dark = false) {
    DriveTracker(66, 73, Direction.RIGHT, ballLabel = "the Memphis 34", gainLabel = "the Memphis 27")
}

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun TrackerLarge() = PreviewFrame(dark = true) {
    DriveTracker(40, 50, Direction.LEFT)
}
