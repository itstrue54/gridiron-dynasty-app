package com.example.nflsimtext.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

enum class Tab(val label: String) {
    HUB("Hub"),
    STANDINGS("Standings"),
    ROSTER("Roster"),
    SCHEDULE("Schedule"),
    OFFSEASON("Front Office"),
    BOX("Box"),
    /** SPEC 12: behind the Hub's Advanced button, not on the bar. */
    TUNING("Tuning"),
}

/** Numbers line up or tables are unreadable. */
val Mono = FontFamily.Monospace

@Composable
fun DynastyApp(store: DynastyStore) {
    var tab by remember { mutableStateOf(Tab.HUB) }
    val scope = rememberCoroutineScope()
    val dynasty = store.dynasty

    Scaffold(
        bottomBar = { if (dynasty != null) BottomBar(tab) { tab = it } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                dynasty == null -> StartScreen(store, scope)
                else -> when (tab) {
                    Tab.HUB -> HubScreen(dynasty, store, scope) { tab = it }
                    Tab.STANDINGS -> StandingsScreen(dynasty)
                    Tab.ROSTER -> RosterScreen(dynasty)
                    Tab.SCHEDULE -> ScheduleScreen(dynasty)
                    Tab.OFFSEASON -> OffseasonScreen(dynasty)
                    Tab.BOX -> BoxScoreScreen(dynasty)
                    Tab.TUNING -> TuningScreen(dynasty, store, scope) { tab = Tab.HUB }
                }
            }

            if (store.busy) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        // Scrolls, because the tab bar grows every milestone and six labels
        // do not fit across a phone.
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Tab.entries.filter { it != Tab.TUNING }.forEach { t ->
                TextButton(onClick = { onSelect(t) }) {
                    Text(
                        t.label,
                        fontSize = 12.sp,
                        fontWeight = if (t == current) FontWeight.Bold else FontWeight.Normal,
                        color = if (t == current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StartScreen(store: DynastyStore, scope: kotlinx.coroutines.CoroutineScope) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NFL SIM TEXT", fontFamily = Mono, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "32 teams. 1,696 players. Nobody you have heard of.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = { scope.launch { store.newDynasty() } }) {
            Text("Start a new dynasty")
        }
        if (store.hasSave) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { scope.launch { store.load() } }) { Text("Load saved dynasty") }
        }
        store.message?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---------------------------------------------------------------------------
// Shared table pieces. Dense and tabular on purpose - this is a spreadsheet
// with good typography, not a mobile game (docs/SPEC.md 10.1).
// ---------------------------------------------------------------------------

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        fontFamily = Mono,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 12.dp, end = 12.dp),
    )
}

@Composable
fun Cell(text: String, weight: Float, bold: Boolean = false, dim: Boolean = false) {
    Text(
        text,
        fontFamily = Mono,
        fontSize = 12.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier.width((weight * 8).dp),
    )
}

@Composable
fun TableRow(highlight: Boolean = false, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (highlight) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun Rule() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
fun Spacer8() = Spacer(Modifier.height(8.dp))
