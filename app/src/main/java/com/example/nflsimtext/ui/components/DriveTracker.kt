package com.example.nflsimtext.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.reducedMotion

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
    /** The play just scored: the end zone takes the pylon and gives it back. */
    scored: Boolean = false,
    /** Off when the state was jumped to rather than played out. */
    animate: Boolean = true,
    /**
     * The ball has just changed hands: why, and whose it is now - "Interception
     * · DEN ball". Shown on the field over the ball's new spot.
     */
    banner: String? = null,
) {
    val c = NdTheme.colors
    val motion = NdTheme.motion
    val still = !animate || reducedMotion()
    // The ball slides to its new spot; the line to gain follows once it lands
    // (docs/DESIGN.md 7).
    val ball by animateFloatAsState(
        targetValue = ballOn.toFloat(),
        animationSpec = if (still) snap() else motion.signatureSpec(),
        label = "ball",
    )
    val gain by animateFloatAsState(
        targetValue = (lineToGain ?: ballOn).toFloat(),
        animationSpec = if (still) snap()
        else tween(motion.standard, delayMillis = motion.signature, easing = FastOutSlowInEasing),
        label = "line to gain",
    )
    val endZone by animateColorAsState(
        targetValue = if (scored) c.pylon else c.turfLine,
        animationSpec = if (still) snap() else motion.signatureSpec(),
        label = "end zone",
    )
    val spoken = buildString {
        if (banner != null) append("$banner. ")
        append("Ball on $ballLabel.")
        if (gainLabel != null) append(" Line to gain, $gainLabel.")
    }
    Box(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, contentAlignment = Alignment.Center) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp),
    ) {
        val endZoneWidth = size.width * 0.06f
        val field = size.width - endZoneWidth * 2
        fun x(yard: Float): Float {
            val fraction = yard.coerceIn(0f, 100f) / 100f
            val along = if (direction == Direction.RIGHT) fraction else 1f - fraction
            return endZoneWidth + field * along
        }

        drawRect(endZone, Offset.Zero, Size(endZoneWidth, size.height))
        drawRect(endZone, Offset(size.width - endZoneWidth, 0f), Size(endZoneWidth, size.height))

        for (yard in 0..100 step 10) {
            drawLine(
                c.turfLine,
                Offset(x(yard.toFloat()), size.height * 0.18f),
                Offset(x(yard.toFloat()), size.height * 0.82f),
                strokeWidth = 1.dp.toPx(),
            )
        }

        if (lineToGain != null) {
            drawLine(
                c.stripe,
                Offset(x(gain), 0f),
                Offset(x(gain), size.height),
                strokeWidth = 3.dp.toPx(),
            )
        }
        drawCircle(c.chalk, radius = 5.dp.toPx(), center = Offset(x(ball), size.height / 2))
    }
    if (banner != null) {
        // A new possession says so on the field, fading in when the play was watched.
        val shown = remember(banner) { MutableTransitionState(still) }.apply { targetState = true }
        AnimatedVisibility(visibleState = shown, enter = fadeIn(motion.standardSpec())) {
            Text(
                banner,
                style = NdTheme.type.caption.copy(fontWeight = FontWeight.W600),
                color = c.onPylon,
                maxLines = 1,
                modifier = Modifier
                    .background(c.pylon, NdTheme.shapes.tag)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
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

@Preview(name = "Change of possession")
@Composable
private fun TrackerChange() = PreviewFrame(dark = true) {
    DriveTracker(28, 38, Direction.RIGHT, banner = "Interception · DEN ball", animate = false)
}
