package com.example.nflsimtext

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.example.nflsimtext.ui.theme.NdTheme
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
            NdTheme(theme) {
                CompositionLocalProvider(LocalHaptics provides haptics) {
                    val store = remember { DynastyStore.forContext(applicationContext) }
                    LaunchedEffect(Unit) { if (store.hasSave) store.load() }
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
