package com.example.nflsimtext

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import com.example.nflsimtext.ui.DynastyApp
import com.example.nflsimtext.ui.HapticPrefs
import com.example.nflsimtext.ui.LocalHaptics
import com.example.nflsimtext.ui.DynastyStore
import com.example.nflsimtext.ui.theme.LocalDarkBars
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.ThemeSetting
import com.example.nflsimtext.ui.theme.ThemeStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Let the turf run under the system bars instead of the system
        // painting its own scrim behind them. Android 10 and up.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            var theme by remember { mutableStateOf(ThemeStore.load(applicationContext)) }
            var haptics by remember { mutableStateOf(HapticPrefs.load(applicationContext)) }
            // Bar icons follow the app's theme (and a dark screen's request),
            // not the phone's light or dark mode.
            var darkScreen by remember { mutableStateOf(false) }
            val dark = darkScreen || when (theme) {
                ThemeSetting.SYSTEM -> isSystemInDarkTheme()
                ThemeSetting.NIGHT -> true
                ThemeSetting.DAY -> false
            }
            LaunchedEffect(dark) {
                val clear = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            NdTheme(theme) {
                CompositionLocalProvider(LocalHaptics provides haptics, LocalDarkBars provides { darkScreen = it }) {
                    // Nothing loads by itself: the app opens on the start screen,
                    // where the player picks a save or starts a new dynasty.
                    val store = remember { DynastyStore.forContext(applicationContext) }
                    DynastyApp(
                        store = store,
                        theme = theme,
                        onTheme = {
                            theme = it
                            ThemeStore.save(applicationContext, it)
                        },
                        haptics = haptics,
                        onHaptics = {
                            haptics = it
                            HapticPrefs.save(applicationContext, it)
                        },
                    )
                }
            }
        }
    }
}
