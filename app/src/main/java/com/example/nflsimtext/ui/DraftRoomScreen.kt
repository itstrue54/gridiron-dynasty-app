package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.components.StatusTag
import com.example.nflsimtext.ui.components.TagTone
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.Player
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.SchemeFitGrade
import com.nflsim.engine.ratings.Scouting
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.season.Dynasty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The draft, with the club in the room (SPEC 8.5). The board is what this club
 * believes, not what the prospects are: its scouts have watched the positions
 * it told them to watch, and everyone else is a guess. Every other club is
 * picking through its own scouting at the same time.
 */
@Composable
fun DraftRoomScreen(
    dynasty: Dynasty,
    store: DynastyStore,
    scope: CoroutineScope,
    onFinished: () -> Unit = {},
    onBack: () -> Unit = {},
    /** SPEC 8.4: trade picks and players before the first pick. */
    onTrade: () -> Unit = {},
) {
    val c = NdTheme.colors
    val room = store.draftRoom
    if (room == null) {
        Column(Modifier.fillMaxSize().padding(NdTheme.spacing.xl)) {
            Text("The draft has not opened.", style = NdTheme.type.title, color = c.chalk)
            Text(
                "Finish the season and open the draft room from the hub.",
                style = NdTheme.type.body, color = c.chalkDim,
            )
            SecondaryButton("Back to the hub", onBack, Modifier.padding(top = NdTheme.spacing.m))
        }
        return
    }

    val team = dynasty.team
    val dept = com.nflsim.engine.ratings.Scouting.department(team, dynasty.league)
    val focus = team.scoutingFocus
    val offense = SchemeCatalog.tuned(team.offenseScheme, dynasty.league.tuning)
    val defense = SchemeCatalog.tuned(team.defenseScheme, dynasty.league.tuning)
    fun scheme(p: Player) = if (p.position.isOffense) offense else defense
    fun lens(p: Player) = Scouting.lens(p.id.v, team.id.v, p.position, dept, focus, dynasty.league.tuning.scouting)

    val clock = room.onTheClock
    val board = room.board.available
        .sortedByDescending { lens(it).view(overall(it)).point }
        .take(BOARD_DEPTH)

    ScreenList {
        // How the user's free-agency offers went, when he made them.
        store.message?.let { note ->
            item {
                com.example.nflsimtext.ui.components.SituationBlock(
                    "Free agency", situation = com.example.nflsimtext.ui.components.Situation.THIRD_DOWN,
                ) {
                    Text(note, style = NdTheme.type.body, color = c.chalk)
                    SecondaryButton(
                        "Clear", { store.dismissMessage() },
                        Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
            }
        }
        item {
            Column {
                Text("Draft room", style = NdTheme.type.display, color = c.chalk)
                Text(
                    if (clock != null) "You are on the clock with pick $clock overall."
                    else "Your picks are in. The rest of the class goes to the other clubs.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
                Text(
                    if (focus.isEmpty()) "Your scouts watched the whole board, thinly."
                    else "Your scouts watched ${focus.joinToString(", ") { it.label }}.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                )
                // Before the first pick, the board can still be traded (SPEC 8.4).
                if (room.picks.isEmpty()) {
                    androidx.compose.runtime.LaunchedEffect(room) { store.refreshTradeOffers() }
                    val calling = store.tradeOffers.size
                    SecondaryButton(if (calling > 0) "Trade picks or players ($calling calling)" else "Trade picks or players",
                        onTrade, Modifier.padding(top = NdTheme.spacing.s))
                }
            }
        }

        if (clock != null) {
            item {
                SituationBlock(
                    "On the board",
                    situation = Situation.THIRD_DOWN,
                    meta = "${room.board.available.size} left",
                ) {
                    DataTable(
                        columns = listOf(
                            ColumnSpec("Pos", 1.0f),
                            ColumnSpec("Prospect", 2.2f),
                            ColumnSpec("Ovr", 1.1f, numeric = true, tier = true),
                            ColumnSpec("Fit", 0.6f, numeric = true),
                        ),
                        rows = board.map { p ->
                            RowData(
                                listOf(
                                    p.position.label,
                                    p.name,
                                    lens(p).view(overall(p)).text,
                                    SchemeFitGrade.letter(schemeFit(p, scheme(p))),
                                ),
                                onClick = { scope.launch { store.draftPlayer(p.id.v) } },
                            )
                        },
                    )
                    Text(
                        "Tap a man to draft him. A range is what your scouts " +
                            "would put him between.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
            }
            item {
                PrimaryButton(
                    "Let the scouts pick",
                    { board.firstOrNull()?.let { scope.launch { store.draftPlayer(it.id.v) } } },
                    Modifier.fillMaxWidth(),
                    enabled = !store.busy && board.isNotEmpty(),
                )
            }
        }

        if (room.picks.isNotEmpty()) {
            item {
                SituationBlock("Your picks", meta = "${room.picks.size} made") {
                    val drafted = room.pause.prospectsById
                    room.picks.entries.sortedBy { it.key }.forEach { (overallPick, id) ->
                        val p = drafted[id]
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = NdTheme.spacing.xs),
                        ) {
                            Text(
                                "$overallPick",
                                style = NdTheme.type.label, color = c.chalkDim,
                                modifier = Modifier.padding(end = NdTheme.spacing.s),
                            )
                            Text(
                                if (p == null) "a prospect" else "${p.position.label} ${p.name}",
                                style = NdTheme.type.data, color = c.chalk,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        val recent = room.board.picks.takeLast(RECENT_PICKS).reversed()
        if (recent.isNotEmpty()) {
            item {
                SituationBlock("Off the board", meta = "Newest first") {
                    recent.forEach { pick ->
                        val club = dynasty.league.teams.firstOrNull { it.id.v == pick.team }
                        val p = room.pause.prospectsById[pick.player]
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = NdTheme.spacing.xs),
                        ) {
                            Text(
                                "R${pick.round}.${pick.overallPick}",
                                style = NdTheme.type.label, color = c.chalkDim,
                                modifier = Modifier.padding(end = NdTheme.spacing.s),
                            )
                            Text(
                                "${club?.abbrev ?: "?"} took " +
                                    (p?.let { "${it.position.label} ${it.name}" } ?: "a prospect"),
                                style = NdTheme.type.data, color = c.chalk,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                if (clock == null) {
                    Row {
                        StatusTag("All your picks are in", TagTone.INFO)
                    }
                    PrimaryButton(
                        "On to camp and the cut to 53",
                        { scope.launch { store.goToCamp(); if (store.cutdown != null) onFinished() } },
                        Modifier.fillMaxWidth(),
                        enabled = !store.busy,
                    )
                } else {
                    SecondaryButton(
                        "Let the scouts run the rest of the draft",
                        { scope.launch { store.goToCamp(); if (store.cutdown != null) onFinished() } },
                        Modifier.fillMaxWidth(),
                        enabled = !store.busy,
                    )
                }
            }
        }
    }
}

/** How much of the board to show at once. */
private const val BOARD_DEPTH = 20

/** How many picks of the league's draft to list. */
private const val RECENT_PICKS = 12
