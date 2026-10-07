package com.example.nflsimtext.ui

import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
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
    /** SPEC 4.7: one job on the staff, and in the spring its pool, behind Staff. */
    STAFF_JOB("Staff job"),
    /** SPEC 7: the market between markets, behind the Hub. */
    MARKET("Free agents"),
    /** SPEC 4.7: the transactions wire, behind the Hub. */
    WIRE("Transactions"),
    NEWS("News"),
    SETTINGS("Settings"),
    GLOSSARY("Glossary"),
    /** SPEC 8.4: trades, behind the Hub to the deadline and in the draft room before the first pick. */
    TRADES("Trades"),
    /** SPEC 10.1: men who have noticed what they are paid, behind the Hub. */
    DEMANDS("Demands"),
    /** SPEC 9.1: five slots and the autosaves, behind the Hub. */
    SAVES("Saves"),
    /** SPEC 7 phases 5-6: the club's own expiring players, before the draft. */
    CONTRACTS("Contracts"),
    /** SPEC 7 phase 7: the club's own free-agency offers. */
    FREE_AGENCY("Free agency"),
    /** SPEC 7 phases 10-11: the club's own camp and cut to 53. */
    CUTDOWN("Camp"),
    /** SPEC 5.4: the user's game, with the user calling it. */
    LIVE("Live"),
    /** SPEC 10.5: every player in the league, to pick one to edit, behind Settings. */
    EDIT_FIND("Edit players"),
    /** SPEC 10.5: one player's true ratings, traits and position. */
    EDIT_PLAYER("Edit player"),
    /** The version, the privacy policy in short, and support: behind Settings. */
    ABOUT("About"),
}

@Composable
fun DynastyApp(
    store: DynastyStore,
    theme: ThemeSetting,
    onTheme: (ThemeSetting) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
) {
    // Which screen he was on survives the Activity being rebuilt.
    var tab by rememberSaveable { mutableStateOf(Tab.HUB) }
    // A fresh launch opens on the start screen, even with a dynasty still in
    // memory; a rotation or a trip to another app does not.
    var started by rememberSaveable { mutableStateOf(false) }
    val begin = { started = true; tab = Tab.HUB }
    var player by remember { mutableStateOf<Int?>(null) }
    // The player being edited, and the screen the editor goes back to.
    var editing by remember { mutableStateOf<Int?>(null) }
    var editFrom by remember { mutableStateOf(Tab.EDIT_FIND) }
    // The staff job open behind Staff; null is the general manager's chair.
    var staffJob by remember { mutableStateOf<com.nflsim.engine.offseason.StaffJob?>(null) }
    // A game opened from the schedule; null is the one just played.
    var boxGame by remember { mutableStateOf<com.nflsim.engine.model.ArchivedGame?>(null) }
    val scope = rememberCoroutineScope()
    val dynasty = store.dynasty

    // The system back button belongs to the app's own navigation: from a
    // screen behind the hub it goes back, not out of the dynasty.
    // Back from choosing a club returns to where he chose to start one.
    BackHandler(enabled = store.pendingLeague != null) { store.cancelPreview() }
    // Back from the hub goes to the title screen, where Continue picks the
    // dynasty up again; back from the title screen leaves the app.
    BackHandler(enabled = dynasty != null && started && store.pendingLeague == null && tab == Tab.HUB) {
        started = false
    }
    BackHandler(enabled = dynasty != null && started && store.pendingLeague == null && tab != Tab.HUB) {
        tab = when (tab) {
            Tab.DEPTH, Tab.PLAYER -> Tab.ROSTER
            Tab.BOX -> if (boxGame != null) Tab.SCHEDULE else Tab.HUB
            Tab.TRADES -> if (store.draftRoom != null) Tab.DRAFT else Tab.HUB
            Tab.TUNING, Tab.GALLERY, Tab.SAVES, Tab.EDIT_FIND, Tab.ABOUT -> Tab.SETTINGS
            Tab.EDIT_PLAYER -> editFrom
            Tab.STAFF_JOB -> Tab.STAFF
            else -> Tab.HUB
        }
    }

    // The title screen is a night game in either theme, and so is the frame
    // around it, under the status and navigation bars.
    val onTitle = store.pendingLeague == null && (dynasty == null || !started)
    Scaffold(
        containerColor = if (onTitle) com.example.nflsimtext.ui.theme.NightColors.turf else NdTheme.colors.turf,
        // No tabs while choosing a club: they lead to the dynasty being left behind.
        // Nor while a called game is waiting on him: it would be left mid-snap.
        bottomBar = { if (dynasty != null && started && store.pendingLeague == null && tab != Tab.LIVE) BottomBar(tab) { boxGame = null; tab = it } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                // Choosing a club takes the whole screen, whether or not a
                // dynasty is already in hand.
                store.pendingLeague != null ->
                    TeamPickerScreen(store.pendingLeague!!, store, scope) { begin() }
                dynasty == null || !started -> StartScreen(store, scope) { begin() }
                else -> when (tab) {
                    Tab.HUB -> HubScreen(dynasty, store, scope, onNavigate = { tab = it })
                    Tab.STANDINGS -> StandingsScreen(dynasty)
                    Tab.ROSTER -> RosterScreen(
                        dynasty,
                        onDepthChart = { tab = Tab.DEPTH },
                        onPlayer = { player = it; tab = Tab.PLAYER },
                    )
                    Tab.SCHEDULE -> ScheduleScreen(dynasty) { boxGame = it; tab = Tab.BOX }
                    Tab.OFFSEASON -> OffseasonScreen(dynasty)
                    Tab.BOX -> BoxScoreScreen(dynasty, boxGame)
                    Tab.TUNING -> TuningScreen(dynasty, store, scope) { tab = Tab.SETTINGS }
                    Tab.DEPTH -> DepthChartScreen(dynasty, store, scope) { tab = Tab.ROSTER }
                    Tab.PLAN -> GamePlanScreen(dynasty, store, scope) { tab = Tab.HUB }
                    Tab.GALLERY -> DesignGallery { tab = Tab.SETTINGS }
                    Tab.PLAYER -> PlayerCardScreen(dynasty, player, store, scope,
                        onEdit = { editing = it; editFrom = Tab.PLAYER; tab = Tab.EDIT_PLAYER }) { tab = Tab.ROSTER }
                    Tab.EDIT_FIND -> PlayerFinderScreen(dynasty,
                        onEdit = { editing = it; editFrom = Tab.EDIT_FIND; tab = Tab.EDIT_PLAYER }) { tab = Tab.SETTINGS }
                    Tab.EDIT_PLAYER -> PlayerEditScreen(dynasty, editing, store, scope) { tab = editFrom }
                    Tab.ABOUT -> AboutScreen { tab = Tab.SETTINGS }
                    Tab.SCOUTING -> ScoutingScreen(dynasty, store, scope) { tab = Tab.HUB }
                    Tab.HISTORY -> HistoryScreen(dynasty) { tab = Tab.HUB }
                    Tab.STAFF -> {
                        // In the window, the staff with the men the user agreed to
                        // hire in their jobs; once the offseason is under way, as
                        // its first step has it - those men joined, and the front
                        // office's hires in the jobs left open (offseason.Staffing).
                        val open = store.staffingOpen
                        val shown = remember(dynasty, open) {
                            when {
                                open -> com.nflsim.engine.offseason.Staffing.withPending(dynasty)
                                dynasty.phase == com.nflsim.engine.season.DynastyPhase.OFFSEASON -> dynasty.copy(
                                    league = com.nflsim.engine.offseason.Staffing.settle(
                                        com.nflsim.engine.offseason.Staffing.market(dynasty), dynasty))
                                else -> dynasty
                            }
                        }
                        val joining = if (open) dynasty.pendingHires.map { com.nflsim.engine.model.CoachId(it.coach) }.toSet() else emptySet()
                        StaffScreen(shown, open, joining, onJob = { staffJob = it; tab = Tab.STAFF_JOB }) { tab = Tab.HUB }
                    }
                    Tab.STAFF_JOB -> StaffJobScreen(dynasty, staffJob, store, scope) { tab = Tab.STAFF }
                    Tab.MARKET -> FreeAgentsScreen(dynasty, store, scope) { tab = Tab.HUB }
                    Tab.WIRE -> TransactionsScreen(dynasty) { tab = Tab.HUB }
                    Tab.NEWS -> NewsScreen(dynasty) { tab = Tab.HUB }
                    Tab.DEMANDS -> DemandsScreen(dynasty, store, scope) { tab = Tab.HUB }
                    Tab.SAVES -> SavesScreen(store, scope) { tab = Tab.SETTINGS }
                    Tab.GLOSSARY -> GlossaryScreen { tab = Tab.HUB }
                    Tab.SETTINGS -> SettingsScreen(theme, onTheme, haptics, onHaptics,
                        editPlayers = dynasty.editPlayers,
                        onEditPlayers = { on -> scope.launch { store.setEditPlayers(on) } },
                        onNavigate = { tab = it }, onTitle = { started = false }, onBack = { tab = Tab.HUB })
                    Tab.CONTRACTS -> ContractsScreen(
                        dynasty, store, scope,
                        onDraft = { tab = Tab.FREE_AGENCY },
                        onBack = { tab = Tab.HUB },
                    )
                    Tab.FREE_AGENCY -> FreeAgencyScreen(
                        dynasty, store, scope,
                        onDraft = { tab = Tab.DRAFT },
                        onBack = { tab = Tab.HUB },
                    )
                    Tab.CUTDOWN -> CutdownScreen(
                        dynasty, store, scope,
                        onDone = { tab = Tab.OFFSEASON },
                        onBack = { tab = Tab.HUB },
                    )
                    Tab.DRAFT -> DraftRoomScreen(
                        dynasty, store, scope,
                        onFinished = { tab = Tab.CUTDOWN },
                        onBack = { tab = Tab.HUB },
                        onTrade = { tab = Tab.TRADES },
                    )
                    Tab.TRADES -> TradeScreen(dynasty, store, scope)
                    Tab.LIVE -> LiveGameScreen(dynasty, store) {
                        // A week ends on the game just played; a postseason on his last playoff game.
                        boxGame = store.dynasty?.let { d ->
                            if (d.phase != com.nflsim.engine.season.DynastyPhase.OFFSEASON) null
                            else d.league.history.games.lastOrNull { g ->
                                g.year == d.year && g.week > com.nflsim.engine.season.Schedule.WEEKS &&
                                    (g.home == d.userTeam || g.away == d.userTeam)
                            }
                        }
                        tab = Tab.BOX
                    }
                    Tab.GAME -> GameDayScreen(
                        dynasty,
                        onBoxScore = { boxGame = null; tab = Tab.BOX },
                        onBack = { tab = Tab.HUB },
                    )
                }
            }

            if (store.busy) {
                Box(
                    // The scrim is the turf colour, so in the day theme it has to be
                    // nearly opaque to veil the screen at all.
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center,
                ) {
                    // A week says how far along it is; anything else spins (SPEC 11).
                    val shown = store.progress
                    if (shown == null) CircularProgressIndicator()
                    else Column(
                        Modifier.fillMaxWidth(0.7f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { shown.first.toFloat() / shown.second },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            // After the last game come the news and the save.
                            if (shown.first == shown.second) "Finishing the week"
                            else "Game ${shown.first} of ${shown.second}",
                            style = NdTheme.type.caption, color = NdTheme.colors.chalk,
                            modifier = Modifier.padding(top = NdTheme.spacing.s),
                        )
                    }
                }
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
                    Tab.TUNING, Tab.DEPTH, Tab.PLAN, Tab.GALLERY, Tab.PLAYER, Tab.GAME, Tab.LIVE,
                    Tab.SCOUTING, Tab.DRAFT, Tab.HISTORY, Tab.STAFF, Tab.STAFF_JOB, Tab.MARKET, Tab.WIRE, Tab.DEMANDS, Tab.SAVES, Tab.CONTRACTS, Tab.FREE_AGENCY, Tab.CUTDOWN,
                    Tab.TRADES, Tab.NEWS, Tab.SETTINGS, Tab.GLOSSARY, Tab.EDIT_FIND, Tab.EDIT_PLAYER, Tab.ABOUT,
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
private fun StartScreen(store: DynastyStore, scope: kotlinx.coroutines.CoroutineScope, onStarted: () -> Unit) =
    // The art is a night game, so the title screen is too, whatever the
    // theme: a day-theme fade washed the stadium out.
    com.example.nflsimtext.ui.theme.NdTheme(dark = true) { TitleScreen(store, scope, onStarted) }

@Composable
private fun TitleScreen(store: DynastyStore, scope: kotlinx.coroutines.CoroutineScope, onStarted: () -> Unit) {
    val c = NdTheme.colors
    val darkBars = com.example.nflsimtext.ui.theme.LocalDarkBars.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        darkBars(true)
        onDispose { darkBars(false) }
    }
    Box(Modifier.fillMaxSize().background(c.turf)) {
        // The night stadium (docs/DESIGN.md 11), fading to plain navy under the
        // buttons so they read on the ground and not on the art.
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.example.nflsimtext.R.drawable.title_background),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.0f to c.turf.copy(alpha = 0.15f),
                    0.45f to c.turf.copy(alpha = 0.35f),
                    0.72f to c.turf.copy(alpha = 0.92f),
                    1.0f to c.turf,
                ),
            ),
        )
        // At least a screen tall, so the title sits up top and the actions at
        // the foot; taller at big font sizes, and then it scrolls.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = screen)
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(72.dp))
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.example.nflsimtext.R.drawable.title_mark),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(0.42f),
            )
            Spacer(Modifier.height(20.dp))
            // The wordmark is type, so it reads to a screen reader as the name.
            Text("GRIDIRON", style = NdTheme.type.scoreboard.copy(
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 64.sp, lineHeight = 60.sp),
                color = c.chalk)
            Text("DYNASTY", style = NdTheme.type.scoreboard.copy(
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 64.sp, lineHeight = 60.sp),
                color = c.pylonText)
            Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.5f).height(3.dp).background(c.accent))
            Spacer(Modifier.height(12.dp))
            Text(
                "32 teams. 1,696 players. Nobody you have heard of.",
                style = NdTheme.type.body,
                color = c.chalkDim,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
          }
          Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {

            // A dynasty still in memory - an offseason half done included - is
            // picked up where it was, not reloaded from its save.
            store.dynasty?.let { d ->
                PrimaryButton(
                    "Continue: ${d.team.name}, ${d.year}" +
                        if (d.phase == com.nflsim.engine.season.DynastyPhase.REGULAR_SEASON) " week ${d.week}" else "",
                    onStarted,
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }

            // What is already on the phone, in the slots it is in (SPEC 9.1).
            var cards by remember { mutableStateOf<List<Saves.Card>>(emptyList()) }
            LaunchedEffect(Unit) { cards = store.cards() }
            // Each slot loads with a tap, and can be deleted from here too - no need
            // to open a dynasty just to reach the Saves screen.
            var deleting by remember { mutableStateOf<Saves.Card?>(null) }
            // Big type: Delete goes under its save. Beside it, the save's name
            // had a word a line.
            val stackSlots = androidx.compose.ui.platform.LocalConfiguration.current.fontScale >
                com.example.nflsimtext.ui.components.STACK_FONT_SCALE
            cards.filterNot { it.auto }.forEach { card ->
                if (stackSlots) {
                    SecondaryButton(
                        "Slot ${card.slot}: ${card.summary}",
                        { scope.launch { if (store.load(card.slot)) onStarted() } },
                        Modifier.fillMaxWidth(),
                        enabled = !store.busy,
                    )
                    SecondaryButton("Delete", { deleting = card }, Modifier.padding(top = 4.dp), enabled = !store.busy)
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(
                        "Slot ${card.slot}: ${card.summary}",
                        { scope.launch { if (store.load(card.slot)) onStarted() } },
                        Modifier.weight(1f),
                        enabled = !store.busy,
                    )
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("Delete", { deleting = card }, enabled = !store.busy)
                }
                Spacer(Modifier.height(8.dp))
            }
            deleting?.let { card ->
                com.example.nflsimtext.ui.components.ActionDialog(
                    "Delete ${card.label}?",
                    onDismiss = { deleting = null },
                    situation = com.example.nflsimtext.ui.components.Situation.RED_ZONE,
                ) {
                    Text(
                        "${card.summary} is in there. Nothing brings it back.",
                        style = NdTheme.type.body, color = c.chalk,
                    )
                    PrimaryButton(
                        "Delete it",
                        {
                            scope.launch {
                                // Deleting the dynasty in hand closes it too (DynastyStore.deleteSave).
                                store.deleteSave(card)
                                cards = store.cards()
                            }
                            deleting = null
                        },
                        Modifier.padding(top = 8.dp),
                    )
                    SecondaryButton("Keep it", { deleting = null })
                }
            }
            val fresh = {
                scope.launch {
                    val free = (1..Saves.SLOTS).firstOrNull { n -> cards.none { !it.auto && it.slot == n } }
                    // The league first, so the user can choose which club to take over.
                    store.previewLeague(into = free ?: 1)
                }
                Unit
            }
            if (store.dynasty == null) PrimaryButton("Start a new dynasty", fresh, Modifier.fillMaxWidth())
            else SecondaryButton("Start a new dynasty", fresh, Modifier.fillMaxWidth())

            // His own rosters (SPEC 9.4): a JSON or CSV file he brings, never
            // one the game ships. The phone's own picker finds it.
            val context = androidx.compose.ui.platform.LocalContext.current
            val pick = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) scope.launch {
                    val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }.getOrNull()
                    }
                    val free = (1..Saves.SLOTS).firstOrNull { n -> cards.none { !it.auto && it.slot == n } }
                    if (text != null) store.previewImport(text, into = free ?: 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Start with my own rosters", {
                pick.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
            }, Modifier.fillMaxWidth())
            var saved by remember { mutableStateOf<String?>(null) }
            androidx.compose.material3.TextButton({
                saved = try {
                    "Template saved to " + Downloads.write(context, "gridiron-dynasty-rosters.json", "application/json",
                        com.nflsim.data.roster.RosterJson.template())
                } catch (e: Exception) {
                    e.message ?: "The template would not save."
                }
            }) { Text("Save a roster template", style = NdTheme.type.label, color = c.pylonText) }
            saved?.let { Text(it, style = NdTheme.type.caption, color = c.chalkDim) }
            if (cards.none { !it.auto } && store.hasSave) {
                Spacer(Modifier.height(12.dp))
                SecondaryButton("Load the saved dynasty", { scope.launch { if (store.load()) onStarted() } },
                    Modifier.fillMaxWidth())
            }
            store.message?.let {
                Spacer(Modifier.height(20.dp))
                // Notices and failures alike; the words say which.
                Text(it, style = NdTheme.type.body, color = c.chalk,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Spacer(Modifier.height(28.dp))
          }
        }
        }
    }
}
