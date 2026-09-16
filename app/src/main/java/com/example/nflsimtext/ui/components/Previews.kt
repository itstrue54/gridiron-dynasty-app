package com.example.nflsimtext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.theme.NdTheme

/** Components are drawn on the ground they will sit on, in both themes. */
@Composable
internal fun PreviewFrame(dark: Boolean, content: @Composable () -> Unit) {
    NdTheme(dark = dark) {
        Box(Modifier.background(NdTheme.colors.turf).padding(NdTheme.spacing.l)) { content() }
    }
}
