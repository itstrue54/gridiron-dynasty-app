package com.example.nflsimtext.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.ThemeSetting
import kotlinx.coroutines.launch

enum class Tab(val label: String) {
    HUB("Hub"),
    STANDINGS("Standings"),
    ROSTER("Roster"),
    SCHEDULE("Schedule"),
    OFFSEASON("Front office"),
    BOX("Box"),
    /** SPEC 12: behind the Hub's Advanced button, not on the bar. */
    TUNING("Tuning"),
    /** SPEC 5.5: from the Roster tab, not on the bar. */
    DEPTH("Depth chart"),
    /** SPEC 5.4: behind the Hub's Game plan button, not on the bar. */
    PLAN("Game plan"),
    /** docs/DESIGN.md: the component gallery, behind the Hub. */
    GALLERY("Design"),
    /** One player, from a tap on the roster. */
    PLAYER("Player"),
    /** The week's game, play by play, after the hub plays it. */
    GAME("Game day"),
    /** SPEC 4.6: where the club points its scouts, behind the Hub. */
    SCOUTING("Scouting"),
    /** SPEC 8.5: the draft, with the club in the room. */
    DRAFT("Draft room"),
    /** SPEC 10: what the league remembers, behind the Hub. */
    HISTORY("History"),
    /** SPEC 4.7: who coaches the club, behind the Hub. */
    STAFF("Staff"),
    /** SPEC 7: the market between markets, behind the Hub. */
    MARKET("Free agents"),
    /** SPEC 4.7: the transactions wire, behind the Hub. */
    WIRE("Transactions"),
    /** SPEC 10.1: men who have noticed what they are paid, behind the Hub. */
    DEMANDS("Demands"),
}

@Composable
fun DynastyApp(
    store: DynastyStore,
    theme: ThemeSetting,
    onTheme: (ThemeSetting) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.HUB) }
    var player by remember { mutableStateOf<Int?>(null) }
    // A game opened from the schedule; null is the one just played.
    var boxGame by remember { mutableStateOf<com.nflsim.engine.model.ArchivedGame?>(null) }
    val scope = rememberCoroutineScope()
    val dynasty = store.dynasty

    // The system back button belongs to the app's own navigation: from a
    // screen behind the hub it goes back, not out of the dynasty.
    BackHandler(enabled = dynasty != null && tab != Tab.HUB) {
        tab = when (tab) {
            Tab.DEPTH, Tab.PLAYER -> Tab.ROSTER
            Tab.BOX -> if (boxGame != null) Tab.SCHEDULE else Tab.HUB
            else -> Tab.HUB
        }
    }

    Scaffold(
        bottomBar = { if (dynasty != null) BottomBar(tab) { boxGame = null; tab = it } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                dynasty == null -> StartScreen(store, scope)
                else -> when (tab) {
                    Tab.HUB -> HubScreen(dynasty, store, scope, theme, onTheme, haptics, onHaptics) { tab = it }
                    Tab.STANDINGS -> StandingsScreen(dynasty)
                    Tab.ROSTER -> RosterScreen(
                        dynasty,
                        onDepthChart = { tab = Tab.DEPTH },
                        onPlayer = { player = it; tab = Tab.PLAYER },
                    )
                    Tab.SCHEDULE -> ScheduleScreen(dynasty) { boxGame = it; tab = Tab.BOX }
                    Tab.OFFSEASON -> OffseasonScreen(dynasty)
                    Tab.BOX -> BoxScoreScreen(dynasty, boxGame)
                    Tab.TUNING -> TuningScreen(dynasty, store, scope) { tab = Tab.HUB }
                    Tab.DEPTH -> DepthChartScreen(dynasty, store, scope) { tab = Tab.ROSTER }
                    Tab.PLAN -> GamePlanScreen(dynasty, store, scope) { tab = Tab.HUB }
                    Tab.GALLERY -> DesignGallery { tab = Tab.HUB }
                    Tab.PLAYER -> PlayerCardScreen(dynasty, player) { tab = Tab.ROSTER }
                    Tab.SCOUTING -> ScoutingScreen(dynasty, store, scope) { tab = Tab.HUB }
                    Tab.HISTORY -> HistoryScreen(dynasty) { tab = Tab.HUB }
                    Tab.STAFF -> StaffScreen(dynasty) { tab = Tab.HUB }
                    Tab.MARKET -> FreeAgentsScreen(dynasty, store, scope) { tab = Tab.HUB }
                    Tab.WIRE -> TransactionsScreen(dynasty) { tab = Tab.HUB }
                    Tab.DEMANDS -> DemandsScreen(dynasty, store, scope) { tab = Tab.HUB }
                    Tab.DRAFT -> DraftRoomScreen(
                        dynasty, store, scope,
                        onFinished = { tab = Tab.OFFSEASON },
                        onBack = { tab = Tab.HUB },
                    )
                    Tab.GAME -> GameDayScreen(
                        dynasty,
                        onBoxScore = { boxGame = null; tab = Tab.BOX },
                        onBack = { tab = Tab.HUB },
                    )
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
    Surface(color = NdTheme.colors.turfRaised, tonalElevation = 0.dp) {
        // Scrolls, because the tab bar grows every milestone and six labels
        // do not fit across a phone.
        Row(
            Modifier.fillMaxWidth()
                // Edge to edge: without this the bar sits under the system
                // gesture bar and its tabs cannot be tapped.
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Tab.entries.filter {
                it !in setOf(
                    Tab.TUNING, Tab.DEPTH, Tab.PLAN, Tab.GALLERY, Tab.PLAYER, Tab.GAME,
                    Tab.SCOUTING, Tab.DRAFT, Tab.HISTORY, Tab.STAFF, Tab.MARKET, Tab.WIRE, Tab.DEMANDS,
                )
            }.forEach { t ->
                TextButton(onClick = { onSelect(t) }) {
                    Text(
                        t.label,
                        style = NdTheme.type.label,
                        color = if (t == current) NdTheme.colors.pylonText else NdTheme.colors.chalkDim,
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
        Text("Gridiron Dynasty", style = NdTheme.type.display, color = NdTheme.colors.chalk)
        Spacer(Modifier.height(8.dp))
        Text(
            "32 teams. 1,696 players. Nobody you have heard of.",
            style = NdTheme.type.body,
            color = NdTheme.colors.chalkDim,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton("Start a new dynasty", { scope.launch { store.newDynasty() } })
        if (store.hasSave) {
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Load the saved dynasty", { scope.launch { store.load() } })
        }
        store.message?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, style = NdTheme.type.body, color = MaterialTheme.colorScheme.error)
        }
    }
}
