package com.example.nflsimtext.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * Motion only when a play result changes state (docs/DESIGN.md 7). Three
 * durations, one easing; the play-resolution sequence is the only orchestrated
 * moment in the app.
 */
@Immutable
data class NdMotion(
    val quick: Int = 120,
    val standard: Int = 220,
    val signature: Int = 450,
) {
    fun <T> quickSpec() = tween<T>(quick, easing = FastOutSlowInEasing)
    fun <T> standardSpec() = tween<T>(standard, easing = FastOutSlowInEasing)
    fun <T> signatureSpec() = tween<T>(signature, easing = FastOutSlowInEasing)
}

/**
 * True when the player has turned animation off system-wide, in which case
 * every animated value jumps to its end state.
 */
@Composable
@ReadOnlyComposable
fun reducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    val scale = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return scale == 0f
}
