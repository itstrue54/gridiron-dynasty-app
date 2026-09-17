package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.FilterChipRow
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.SchemeFitGrade
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.Transactions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The market between markets (SPEC 7's ten days run in the spring; this is the
 * rest of the year). Whoever is left is available for the league minimum on a
 * one-year deal, which is what a man on the street signs for: the clubs that
 * wanted to pay him more did it in the spring.
 *
 * Nothing here is free. The roster stops at 53, so signing somebody means
 * releasing somebody, and releasing charges the dead money his contract says.
 */
@Composable
fun FreeAgentsScreen(
    dynasty: Dynasty,
    store: DynastyStore,
    scope: CoroutineScope,
    onBack: () -> Unit = {},
) {
    val c = NdTheme.colors
    val team = dynasty.team
    val roster = dynasty.league.roster(team.id)
    val space = Transactions.spaceFor(dynasty.league, team.id)
    val offence = SchemeCatalog.tuned(team.offenseScheme, dynasty.league.tuning)
    val defence = SchemeCatalog.tuned(team.defenseScheme, dynasty.league.tuning)
    fun scheme(p: Player) = if (p.position.isOffense) offence else defence
    fun read(p: Player) = lensFor(dynasty, p).view(overall(p, scheme(p)))

    var position by remember { mutableStateOf(ALL) }
    var releasing by remember { mutableStateOf<Int?>(null) }

    val available = Transactions.freeAgents(dynasty.league)
        .filter { position == ALL || it.position.group.name == position }
        .sortedByDescending { read(it).point }
        .take(SHOWN)

    ScreenList {
        item {
            Column {
                Text("Free agents", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "Anyone left signs for the league minimum, one year. The roster " +
                        "stops at ${Transactions.ROSTER_LIMIT}, so somebody has to go first.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        store.message?.let { note ->
            item {
                SituationBlock("Last move", situation = Situation.THIRD_DOWN) {
                    Text(note, style = NdTheme.type.body, color = c.chalk)
                    SecondaryButton(
                        "Clear", { store.dismissMessage() },
                        Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
            }
        }

        item {
            SituationBlock(
                "Your room",
                meta = "${roster.size} of ${Transactions.ROSTER_LIMIT}",
                situation = if (roster.size >= Transactions.ROSTER_LIMIT) Situation.RED_ZONE
                else Situation.NORMAL,
            ) {
                Text(
                    "${money(space)} under the cap. The minimum is ${money(Transactions.askingPrice)}.",
                    style = NdTheme.type.data, color = c.chalk,
                )
                if (roster.size >= Transactions.ROSTER_LIMIT) {
                    Text(
                        "The roster is full. Release somebody below to make room.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                    )
                }
            }
        }

        item {
            SituationBlock("Available", meta = "${available.size} shown") {
                FilterChipRow(
                    options = GROUPS,
                    selected = position,
                    onSelect = { position = it },
                    Modifier.padding(bottom = NdTheme.spacing.s),
                )
                DataTable(
                    columns = listOf(
                        ColumnSpec("Pos", 1.0f),
                        ColumnSpec("Player", 2.2f),
                        ColumnSpec("Age", 0.6f, numeric = true),
                        ColumnSpec("Ovr", 1.1f, numeric = true, tier = true),
                        ColumnSpec("Fit", 0.6f, numeric = true),
                    ),
                    rows = available.map { man ->
                        RowData(
                            listOf(
                                man.position.label,
                                man.name,
                                "${man.age(dynasty.year)}",
                                read(man).text,
                                SchemeFitGrade.letter(schemeFit(man, scheme(man))),
                            ),
                            onClick = { scope.launch { store.signFreeAgent(man.id.v) } },
                        )
                    },
                )
                Text(
                    "Tap a man to sign him. A range is what your scouts would put him " +
                        "between; nobody has watched these players closely.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                    modifier = Modifier.padding(top = NdTheme.spacing.s),
                )
            }
        }

        val candidate = releasing?.let { id -> roster.firstOrNull { it.id.v == id } }
        if (candidate != null) {
            item {
                SituationBlock("Release ${candidate.name}?", situation = Situation.RED_ZONE) {
                    val dead = candidate.contract?.deadCap(dynasty.year)
                    Text(
                        if (dead == null || (dead.thisYear == 0 && dead.nextYear == 0)) {
                            "Nothing is owed. His spot comes free."
                        } else {
                            "${money(dead.thisYear)} stays on this year's cap" +
                                if (dead.nextYear > 0) ", ${money(dead.nextYear)} on next year's." else "."
                        },
                        style = NdTheme.type.body, color = c.chalk,
                    )
                    Row(
                        Modifier.padding(top = NdTheme.spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                    ) {
                        PrimaryButton(
                            "Release him",
                            {
                                scope.launch { store.releasePlayer(candidate.id.v) }
                                releasing = null
                            },
                            enabled = !store.busy,
                        )
                        SecondaryButton("Keep him", { releasing = null })
                    }
                }
            }
        }

        item {
            SituationBlock("Your roster", meta = "Tap to release") {
                DataTable(
                    columns = listOf(
                        ColumnSpec("Pos", 1.0f),
                        ColumnSpec("Player", 2.2f),
                        ColumnSpec("Ovr", 1.1f, numeric = true, tier = true),
                        ColumnSpec("Cap", 1.2f, numeric = true),
                    ),
                    rows = roster.sortedBy { read(it).point }.map { man ->
                        RowData(
                            listOf(
                                man.position.label,
                                man.name,
                                read(man).text,
                                money(man.capHit(dynasty.year)),
                            ),
                            highlight = man.id.v == releasing,
                            onClick = { releasing = man.id.v },
                        )
                    },
                )
                Text(
                    "Worst first, as the club reads them.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                    modifier = Modifier.padding(top = NdTheme.spacing.s),
                )
            }
        }

        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}

private fun money(thousands: Int): String =
    if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)

private const val ALL = "All"
private const val SHOWN = 25

private val GROUPS = listOf(ALL) + Position.entries
    .map { it.group.name }
    .distinct()
