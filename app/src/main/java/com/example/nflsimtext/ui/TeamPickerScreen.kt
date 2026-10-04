package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Column
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
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.League
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.season.Outlook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Choosing the club to take over. Every club in the league just generated,
 * by division, with what a new owner would want to know: what the pundits
 * expect, the schemes, who coaches it, how big the market is and how much
 * cap room there is. The outlook is a tier, never a rating (SPEC 4.6).
 */
@Composable
fun TeamPickerScreen(
    league: League,
    store: DynastyStore,
    scope: CoroutineScope,
    onStarted: () -> Unit = {},
) {
    val c = NdTheme.colors
    val outlook = remember(league) { Outlook.of(league) }
    var picked by remember(league) { mutableStateOf<String?>(null) }
    // SPEC 10.5: chosen here, and switchable later in Settings.
    var editPlayers by remember(league) { mutableStateOf(false) }

    ScreenList {
        // What his roster file did, before he picks a club from it.
        store.importSummary?.let { lines ->
            item {
                com.example.nflsimtext.ui.components.SituationBlock("Your roster file", meta = "Imported") {
                    lines.forEach { Text(it, style = NdTheme.type.caption, color = c.chalkDim) }
                }
            }
        }
        item {
            Column {
                Text("Choose your club", style = NdTheme.type.display, color = c.chalk)
                Text(
                    if (store.importSummary != null)
                        "Your clubs from the file, and the league's own in the places you left. " +
                            "Tap a club to see it, then start."
                    else "Thirty-two clubs, nobody you have heard of. Take over a contender, or a rebuild " +
                        "with a high pick coming. Tap a club to see it, then start.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        item {
            SituationBlock("Dynasty options") {
                com.example.nflsimtext.ui.components.Chip(
                    if (editPlayers) "Player editing: on" else "Player editing: off", editPlayers,
                    role = androidx.compose.ui.semantics.Role.Switch,
                ) { editPlayers = !editPlayers }
                Text(
                    "Lets you rewrite any player's position, ratings and hidden traits. " +
                        "Off plays it straight. You can change it later in Settings.",
                    style = NdTheme.type.caption, color = c.chalkDim, modifier = Modifier.padding(top = NdTheme.spacing.s),
                )
            }
        }

        picked?.let { abbrev ->
            val team = league.teams.first { it.abbrev == abbrev }
            item {
                val o = outlook.getValue(team.id)
                SituationBlock(team.name, meta = o.label) {
                    Text("${o.label}: ${o.blurb}.", style = NdTheme.type.body, color = c.chalk)
                    Text(
                        "${schemeName(team.offenseScheme)} on offense, ${schemeName(team.defenseScheme)} on defense.",
                        style = NdTheme.type.body, color = c.chalkDim,
                    )
                    Text(
                        (team.gm.name.takeIf { it.isNotBlank() }?.let { "$it, general manager. " } ?: "") +
                            (league.coaches[team.staff.headCoach]?.name?.let { "$it, head coach. " } ?: "") +
                            "${market(team.marketSize)} market. " +
                            "${dealMoney(com.nflsim.engine.season.Transactions.spaceFor(league, team.id))} of cap room.",
                        style = NdTheme.type.body, color = c.chalkDim,
                    )
                    PrimaryButton(
                        "Take over the ${team.nickname}",
                        { scope.launch { store.startWith(abbrev, editPlayers); onStarted() } },
                        Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s),
                        enabled = !store.busy,
                    )
                }
            }
        }

        league.divisions().toSortedMap(compareBy({ it.first.ordinal }, { it.second.ordinal }))
            .forEach { (key, teams) ->
                item {
                    SituationBlock(league.divisionName(teams.first())) {
                        DataTable(
                            columns = listOf(
                                ColumnSpec("Club", 2.2f, wrap = true),
                                ColumnSpec("Outlook", 1.8f, wrap = true),
                                ColumnSpec("Market", 0.9f),
                            ),
                            rows = teams.map { t ->
                                RowData(
                                    listOf(t.name, outlook.getValue(t.id).label, market(t.marketSize)),
                                    highlight = t.abbrev == picked,
                                    onClick = { picked = t.abbrev },
                                )
                            },
                        )
                    }
                }
            }

        item {
            SecondaryButton(
                "Surprise me", { scope.launch { store.startWith(null, editPlayers); onStarted() } },
                Modifier.fillMaxWidth(), enabled = !store.busy,
            )
        }
        item { SecondaryButton("Back", { store.cancelPreview() }, Modifier.fillMaxWidth()) }
    }
}

private fun schemeName(id: String) = SchemeCatalog.find(id)?.name ?: id

private fun market(size: Int) = when {
    size >= 70 -> "Large"
    size >= 40 -> "Mid"
    else -> "Small"
}
