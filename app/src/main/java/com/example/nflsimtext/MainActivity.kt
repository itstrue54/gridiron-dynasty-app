package com.example.nflsimtext

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.nflsimtext.ui.DynastyApp
import com.example.nflsimtext.ui.DynastyStore
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.ThemeStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var theme by remember { mutableStateOf(ThemeStore.load(applicationContext)) }
            NdTheme(theme) {
                val store = remember { DynastyStore.forContext(applicationContext) }
                LaunchedEffect(Unit) { if (store.hasSave) store.load() }
                DynastyApp(store, theme) {
                    theme = it
                    ThemeStore.save(applicationContext, it)
                }
            }
        }
    }
}
