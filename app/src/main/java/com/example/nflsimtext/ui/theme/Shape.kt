package com.example.nflsimtext.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Radius is hierarchy, not decoration (docs/DESIGN.md 4): table rows are
 * square, tags barely rounded, blocks and buttons a little, sheets the most.
 */
@Immutable
data class NdShapes(
    val tableRow: Shape = RoundedCornerShape(0.dp),
    val tag: Shape = RoundedCornerShape(3.dp),
    val block: Shape = RoundedCornerShape(6.dp),
    val sheet: Shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
)
