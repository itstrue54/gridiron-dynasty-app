package com.example.nflsimtext.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The 4dp grid (docs/DESIGN.md 4): tight data rows, generous space between sections. */
@Immutable
data class NdSpacing(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    /** Screen horizontal padding, and the gap between blocks. */
    val screen: Dp = 16.dp,
    val blockGap: Dp = 24.dp,
    /** A data row, a two-line list item, and the smallest thing worth tapping. */
    val rowHeight: Dp = 40.dp,
    val twoLineHeight: Dp = 56.dp,
    val minTouch: Dp = 48.dp,
)
