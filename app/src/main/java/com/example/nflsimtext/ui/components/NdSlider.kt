package com.example.nflsimtext.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme

/**
 * A lever. Material's own slider draws a gap around the thumb and a stop dot
 * at the end of the track; both read as decoration here, so the track runs
 * straight through under a plain pylon thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NdSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    val c = NdTheme.colors
    val colors = SliderDefaults.colors(
        thumbColor = c.pylon,
        activeTrackColor = c.pylon,
        inactiveTrackColor = c.turfLine,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        colors = colors,
        modifier = modifier,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = remember { MutableInteractionSource() },
                colors = colors,
                modifier = Modifier.size(20.dp),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                drawStopIndicator = null,
                drawTick = { _, _ -> },
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}

@Preview(name = "Night")
@Composable
private fun SliderNight() = PreviewFrame(dark = true) {
    NdSlider(0.62f, {}, {}, 0f..1f)
}

@Preview(name = "Day")
@Composable
private fun SliderDay() = PreviewFrame(dark = false) {
    NdSlider(0.62f, {}, {}, 0f..1f)
}

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun SliderLarge() = PreviewFrame(dark = true) {
    NdSlider(0.62f, {}, {}, 0f..1f)
}
