package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.LeaderEntry
import com.nflsim.engine.season.Dynasty

/**
 * What the league remembers (SPEC 10's history screen): who won it, who won
 * the hardware, the best seasons anyone has had, and the men who have
 * finished. A league with no past behind it says so.
 */
@Composable
fun HistoryScreen(dynasty: Dynasty, onBack: () -> Unit = {}) {
    val c = NdTheme.colors
    val history = dynasty.league.history
    if (history.seasons.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(NdTheme.spacing.xl)) {
            // A season joins the record when the year turns over, not when its
            // last game is played, and finishing 10-7 and being told nothing is
            // behind you reads like a fault.
            val played = dynasty.phase == com.nflsim.engine.season.DynastyPhase.OFFSEASON
            Text(
                if (played) "${dynasty.year} is not in the record yet."
                else "No seasons behind you yet.",
                style = NdTheme.type.title, color = c.chalk,
            )
            Text(
                if (played)
                    "The year joins the record when it turns over. Start the " +
                        "offseason from the hub and ${dynasty.year} lands here with its " +
                        "champion, its awards and everyone who finished."
                else "Play a year through the offseason and it lands here: champions, " +
                    "awards, the best seasons anyone has had, and the men who finish.",
                style = NdTheme.type.body, color = c.chalkDim,
            )
            SecondaryButton("Back to the hub", onBack, Modifier.padding(top = NdTheme.spacing.m))
        }
        return
    }

    val seasons = history.seasons.sortedByDescending { it.year }
    fun club(id: Int?) = dynasty.league.teams.firstOrNull { it.id.v == id }
    val yourTitles = history.champions.count { it.second == dynasty.userTeam }

    ScreenList {
        item {
            Column {
                Text("History", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "${history.seasons.size} " +
                        (if (history.seasons.size == 1) "season" else "seasons") +
                        " played. " +
                        when (yourTitles) {
                            0 -> "${dynasty.team.nickname} have not won it yet."
                            1 -> "${dynasty.team.nickname} have won it once."
                            else -> "${dynasty.team.nickname} have won it $yourTitles times."
                        },
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        if (history.hallOfFame.isNotEmpty()) {
            item {
                SituationBlock("Hall of fame", meta = "${history.hallOfFame.size} in") {
                    DataTable(
                        columns = listOf(
                            ColumnSpec("Pos", 0.8f),
                            ColumnSpec("Player", 2.2f),
                            ColumnSpec("Class", 0.9f, numeric = true),
                            ColumnSpec("Yrs", 0.7f, numeric = true),
                            ColumnSpec("Career", 1.2f, numeric = true),
                        ),
                        rows = history.hallOfFame
                            .sortedByDescending { it.inducted }
                            .map { man ->
                                RowData(listOf(
                                    man.position,
                                    man.name,
                                    "${man.inducted}",
                                    "${man.seasons}",
                                    if (man.headline == 0) "--" else "${man.headline}",
                                ))
                            },
                    )
                    Text(
                        "Voted three years after a man finishes, on what he did and " +
                            "what the league said about him while he did it.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
            }
        }

        item {
            SituationBlock("Champions", meta = "Newest first") {
                DataTable(
                    columns = listOf(
                        ColumnSpec("Year", 0.9f),
                        ColumnSpec("Champion", 2.4f),
                        ColumnSpec("Record", 1.0f),
                    ),
                    rows = seasons.map { record ->
                        val won = club(record.champion)
                        val line = record.standings.firstOrNull { it.team.v == record.champion }
                        RowData(
                            listOf(
                                "${record.year}",
                                won?.name ?: "not decided",
                                line?.recordText?.enDashed() ?: "",
                            ),
                            highlight = record.champion == dynasty.userTeam,
                        )
                    },
                )
            }
        }

        item {
            SituationBlock("Most valuable player", meta = "By year") {
                DataTable(
                    columns = listOf(
                        ColumnSpec("Year", 0.9f),
                        ColumnSpec("Player", 2.4f),
                        ColumnSpec("Club", 0.9f),
                    ),
                    rows = seasons.map { record ->
                        val mvp = record.awards?.mostValuablePlayer
                        RowData(listOf(
                            "${record.year}",
                            mvp?.let { "${it.position} ${it.name}".trim() } ?: "not voted",
                            club(mvp?.team)?.abbrev ?: "",
                        ))
                    },
                )
            }
        }

        // The best season anyone has had in each category, across every year.
        val bests = seasons
            .flatMap { record -> record.leaders.map { record.year to it } }
            .groupBy { it.second.category }
        if (bests.isNotEmpty()) {
            item {
                SituationBlock("League records", meta = "Best season on record") {
                    DataTable(
                        columns = listOf(
                            ColumnSpec("Record", 1.9f),
                            ColumnSpec("Player", 2.0f),
                            ColumnSpec("Year", 0.8f, numeric = true),
                            ColumnSpec("Most", 1.0f, numeric = true),
                        ),
                        rows = RECORD_ORDER.mapNotNull { (label, category) ->
                            val best: Pair<Int, LeaderEntry> = bests[category]
                                ?.maxByOrNull { it.second.value } ?: return@mapNotNull null
                            RowData(listOf(
                                label, best.second.name, "${best.first}", "${best.second.value}",
                            ))
                        },
                    )
                }
            }
        }

        val retired = history.retired.sortedByDescending { it.career.total { s -> s.passYards + s.rushYards + s.receivingYards } }
        if (retired.isNotEmpty()) {
            item {
                SituationBlock("Finished", meta = "${history.retired.size} careers") {
                    DataTable(
                        columns = listOf(
                            ColumnSpec("Pos", 0.8f),
                            ColumnSpec("Player", 2.2f),
                            ColumnSpec("Yrs", 0.7f, numeric = true),
                            ColumnSpec("Career", 1.4f, numeric = true),
                        ),
                        rows = retired.take(12).map { man ->
                            RowData(listOf(
                                man.position,
                                man.name,
                                "${man.career.years}",
                                headline(man.career),
                            ))
                        },
                    )
                    Text(
                        "Career is his headline number: yards thrown, run or caught.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
            }
        }

        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}

/** The number a career is remembered by. */
private fun headline(career: com.nflsim.engine.model.CareerStats): String {
    val passing = career.total { it.passYards }
    val rushing = career.total { it.rushYards }
    val receiving = career.total { it.receivingYards }
    val best = maxOf(passing, rushing, receiving)
    return if (best == 0) "--" else "$best"
}

/** What to call each record on screen, against the category it is kept under. */
private val RECORD_ORDER = listOf(
    "Passing yards" to "Passing yards",
    "Rushing yards" to "Rushing yards",
    "Receiving" to "Receiving yards",
    "Sacks" to "Sacks",
    "Interceptions" to "Interceptions",
)
