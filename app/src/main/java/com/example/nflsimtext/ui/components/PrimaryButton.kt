package com.example.nflsimtext.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.nflsimtext.ui.theme.NdTheme

/** The one action a screen is for. Pylon fill, and it says what happens. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = Button(
    onClick = onClick,
    enabled = enabled,
    shape = NdTheme.shapes.button,
    colors = ButtonDefaults.buttonColors(
        containerColor = NdTheme.colors.pylon,
        contentColor = NdTheme.colors.onPylon,
        disabledContainerColor = NdTheme.colors.turfLine,
        disabledContentColor = NdTheme.colors.chalkDim,
    ),
    modifier = modifier.heightIn(min = NdTheme.spacing.minTouch),
) { Text(text, style = NdTheme.type.title) }

@Preview(name = "Night")
@Composable
private fun PrimaryNight() = PreviewFrame(dark = true) { PrimaryButton("Play week 7", {}) }

@Preview(name = "Day")
@Composable
private fun PrimaryDay() = PreviewFrame(dark = false) { PrimaryButton("Play week 7", {}) }

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun PrimaryLarge() = PreviewFrame(dark = true) { PrimaryButton("Play week 7", {}) }
