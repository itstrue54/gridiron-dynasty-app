package com.example.nflsimtext.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme

/** Everything else: a hairline border, no fill, quiet next to the pylon. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    shape = NdTheme.shapes.button,
    border = BorderStroke(1.dp, NdTheme.colors.chalkDim),
    colors = ButtonDefaults.outlinedButtonColors(
        containerColor = Color.Transparent,
        contentColor = NdTheme.colors.chalk,
        disabledContentColor = NdTheme.colors.chalkDim,
    ),
    modifier = modifier.heightIn(min = NdTheme.spacing.minTouch),
) { Text(text, style = NdTheme.type.title) }

@Preview(name = "Night")
@Composable
private fun SecondaryNight() = PreviewFrame(dark = true) { SecondaryButton("Sim drive", {}) }

@Preview(name = "Day")
@Composable
private fun SecondaryDay() = PreviewFrame(dark = false) { SecondaryButton("Sim drive", {}) }

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun SecondaryLarge() = PreviewFrame(dark = true) { SecondaryButton("Sim drive", {}) }
