package com.example.nflsimtext

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.example.nflsimtext.ui.DynastyApp
import com.example.nflsimtext.ui.DynastyStore
import com.example.nflsimtext.ui.theme.NFLSimTextTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NFLSimTextTheme {
                val store = remember { DynastyStore.forContext(applicationContext) }
                LaunchedEffect(Unit) { if (store.hasSave) store.load() }
                DynastyApp(store)
            }
        }
    }
}
