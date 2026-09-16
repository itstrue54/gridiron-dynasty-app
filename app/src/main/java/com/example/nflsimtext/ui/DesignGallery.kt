package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.AttributeBar
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.Direction
import com.example.nflsimtext.ui.components.DriveTracker
import com.example.nflsimtext.ui.components.FilterChipRow
import com.example.nflsimtext.ui.components.PlayEvent
import com.example.nflsimtext.ui.components.PlayLogEntry
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.RatingValue
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.Scoreboard
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Side
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.components.SortState
import com.example.nflsimtext.ui.components.StatusTag
import com.example.nflsimtext.ui.components.TagTone
import com.example.nflsimtext.ui.components.TeamMark
import com.example.nflsimtext.ui.components.TeamScore
import com.example.nflsimtext.ui.theme.NdTheme

/**
 * Every component on one screen, on the real device in the real theme. A
 * design system is easier to keep honest when you can see all of it at once.
 */
@Composable
fun DesignGallery(onBack: () -> Unit = {}) {
    val c = NdTheme.colors
    var position by remember { mutableStateOf("All") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(NdTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.blockGap),
    ) {
        item {
            Text("Call Sheet", style = NdTheme.type.display, color = c.chalk)
            Text("Every component, this device, this theme.", style = NdTheme.type.body, color = c.chalkDim)
        }
        item {
            Scoreboard(
                TeamScore("AUS", "Austin", 24), TeamScore("MEM", "Memphis", 17),
                quarter = 3, clock = "04:12", possession = Side.AWAY,
            )
        }
        item {
            SituationBlock("3rd & 7 at MEM 34", situation = Situation.THIRD_DOWN, meta = "Drive 6") {
                DriveTracker(66, 73, Direction.RIGHT, ballLabel = "the Memphis 34", gainLabel = "the Memphis 27")
            }
        }
        item {
            SituationBlock("Play log", meta = "Newest first") {
                PlayLogEntry("3rd & 7", "Harlan pass short right to Okafor, +9. First down.", event = PlayEvent.FIRST_DOWN)
                PlayLogEntry("2nd & 7", "Dunn run middle, +0.")
                PlayLogEntry("1st & 10", "Harlan sacked, −3.", event = PlayEvent.TURNOVER)
            }
        }
        item {
            SituationBlock("Roster", meta = "Sorted by overall") {
                FilterChipRow(
                    listOf("All", "QB", "RB", "WR", "TE", "OL", "DL", "LB", "DB", "ST"),
                    position, { position = it },
                    Modifier.padding(bottom = NdTheme.spacing.s),
                )
                DataTable(
                    listOf(
                        ColumnSpec("Pos", 0.8f),
                        ColumnSpec("Player", 2.4f),
                        ColumnSpec("Age", 0.8f, numeric = true),
                        ColumnSpec("OVR", 0.8f, numeric = true),
                        ColumnSpec("Pot", 0.8f, numeric = true),
                    ),
                    listOf(
                        RowData(listOf("QB", "R. Harlan", "27", "88", "90"), highlight = true),
                        RowData(listOf("WR", "D. Okafor", "24", "84", "91")),
                        RowData(listOf("RB", "T. Dunn", "29", "78", "78")),
                        RowData(listOf("OL", "M. Vance", "31", "111", "111")),
                    ),
                    sort = SortState(3), onSort = {},
                )
            }
        }
        item {
            SituationBlock("Passing", meta = "Ray Harlan") {
                AttributeBar("Accuracy short", 84)
                AttributeBar("Arm strength", 91)
                AttributeBar("Pocket presence", 68)
            }
        }
        item {
            SituationBlock("Marks, tags and ratings") {
                Row(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                    TeamMark("AUS", c.sitThirdDown, c.stripe)
                    TeamMark("MEM", c.sitRedZone, c.sitRedZone)
                    TeamMark("SEA", c.chalk, c.chalkDim)
                }
                Row(
                    Modifier.padding(top = NdTheme.spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                ) {
                    StatusTag("Q", TagTone.CAUTION)
                    StatusTag("D", TagTone.INFO)
                    StatusTag("O", TagTone.URGENT)
                    StatusTag("IR", TagTone.NEUTRAL)
                }
                Row(
                    Modifier.padding(top = NdTheme.spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.m),
                ) {
                    listOf(94, 86, 74, 61).forEach { RatingValue(it) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                PrimaryButton("Play week 7", {})
                SecondaryButton("Sim to end", {})
                SecondaryButton("Back to hub", onBack)
            }
        }
        items(listOf("0123456789", "1111111111", "8888888888")) { digits ->
            Text(digits, style = NdTheme.type.data, color = c.chalkDim)
        }
    }
}
