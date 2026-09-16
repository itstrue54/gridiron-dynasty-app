package com.example.nflsimtext.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Small player settings that are not the theme: kept in the same preferences
 * file, read once at launch.
 */
object HapticPrefs {
    private const val PREFS = "call_sheet"
    private const val KEY = "haptics"

    fun load(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun save(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }
}

/** Whether a play result is allowed to buzz in the hand holding the phone. */
val LocalHaptics = staticCompositionLocalOf { true }
