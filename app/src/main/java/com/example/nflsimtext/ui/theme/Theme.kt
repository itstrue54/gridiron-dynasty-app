package com.example.nflsimtext.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** What the player chose in settings; System follows the phone. */
enum class ThemeSetting(val label: String) {
    SYSTEM("System"),
    NIGHT("Night game"),
    DAY("Day game"),
}

/** The choice outlives the process, so the app opens the way it was left. */
object ThemeStore {
    private const val PREFS = "call_sheet"
    private const val KEY = "theme"

    fun load(context: Context): ThemeSetting {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return ThemeSetting.entries.firstOrNull { it.name == name } ?: ThemeSetting.SYSTEM
    }

    fun save(context: Context, setting: ThemeSetting) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, setting.name).apply()
    }
}

/** Cycles System -> Night game -> Day game, for a one-tap control. */
fun ThemeSetting.next() = ThemeSetting.entries[(ordinal + 1) % ThemeSetting.entries.size]

val LocalNdColors = staticCompositionLocalOf<NdColors> { error("NdTheme not provided") }
val LocalNdTypography = staticCompositionLocalOf<NdTypography> { error("NdTheme not provided") }
val LocalNdSpacing = staticCompositionLocalOf { NdSpacing() }
val LocalNdShapes = staticCompositionLocalOf { NdShapes() }
val LocalNdMotion = staticCompositionLocalOf { NdMotion() }

/** The design system's tokens, reached as NdTheme.colors and friends. */
object NdTheme {
    val colors: NdColors
        @Composable @ReadOnlyComposable get() = LocalNdColors.current
    val type: NdTypography
        @Composable @ReadOnlyComposable get() = LocalNdTypography.current
    val spacing: NdSpacing
        @Composable @ReadOnlyComposable get() = LocalNdSpacing.current
    val shapes: NdShapes
        @Composable @ReadOnlyComposable get() = LocalNdShapes.current
    val motion: NdMotion
        @Composable @ReadOnlyComposable get() = LocalNdMotion.current
}

/**
 * The Broadcast theme (docs/DESIGN.md). Depth comes from the surface step
 * turf -> turfRaised and a 1dp rule, never from a shadow, so Material's tonal
 * elevation is turned off by making its tint transparent.
 */
@Composable
fun NdTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (dark) NightColors else DayColors
    val type = remember { NdTypography() }
    val scheme = with(colors) {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = pylon, onPrimary = onPylon,
            primaryContainer = pylon, onPrimaryContainer = onPylon,
            secondary = stripe, onSecondary = onStripe,
            secondaryContainer = stripe, onSecondaryContainer = onStripe,
            tertiary = chalk, onTertiary = turf,
            background = turf, onBackground = chalk,
            surface = turfRaised, onSurface = chalk,
            surfaceVariant = turfRaised, onSurfaceVariant = chalkDim,
            surfaceContainerLowest = turf, surfaceContainerLow = turf,
            surfaceContainer = turfRaised, surfaceContainerHigh = turfRaised,
            surfaceContainerHighest = turfRaised,
            surfaceTint = Color.Transparent,
            outline = chalkDim, outlineVariant = turfLine,
            error = sitRedZone, onError = chalk,
            scrim = turf,
        )
    }
    CompositionLocalProvider(
        LocalNdColors provides colors,
        LocalNdTypography provides type,
        LocalNdSpacing provides NdSpacing(),
        LocalNdShapes provides NdShapes(),
        LocalNdMotion provides NdMotion(),
    ) {
        MaterialTheme(colorScheme = scheme, typography = materialTypography(type), content = content)
    }
}

/** The same theme, resolved from the player's setting. */
@Composable
fun NdTheme(setting: ThemeSetting, content: @Composable () -> Unit) = NdTheme(
    dark = when (setting) {
        ThemeSetting.SYSTEM -> isSystemInDarkTheme()
        ThemeSetting.NIGHT -> true
        ThemeSetting.DAY -> false
    },
    content = content,
)
