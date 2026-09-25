package com.example.nflsimtext.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Broadcast angles (docs/DESIGN.md 4): a TV package cuts its panels on the
 * diagonal, so blocks lose two opposite corners and buttons slant. Table rows
 * stay square - a spreadsheet does not tilt - and tags take the same cut,
 * small.
 */
@Immutable
data class NdShapes(
    val tableRow: Shape = RoundedCornerShape(0.dp),
    val tag: Shape = CutCornerShape(topEnd = 4.dp, bottomStart = 4.dp),
    val block: Shape = CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp),
    val button: Shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
    val sheet: Shape = CutCornerShape(topStart = 16.dp, topEnd = 16.dp),
)
