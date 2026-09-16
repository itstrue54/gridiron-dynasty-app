package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.AttributeBar
import androidx.compose.ui.semantics.Role
import com.example.nflsimtext.ui.components.Chip
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.Position
import com.nflsim.engine.ratings.Scouting
import com.nflsim.engine.ratings.ScoutingLens
import com.nflsim.engine.season.Dynasty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Where the club's scouts spend the spring (SPEC 4.6). Naming a position or
 * two buys certainty about those men and leaves the rest of the board a guess;
 * naming none spreads them thin and the club knows a little about everybody.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoutingScreen(
    dynasty: Dynasty,
    store: DynastyStore,
    scope: CoroutineScope,
    onBack: () -> Unit = {},
) {
    val c = NdTheme.colors
    val team = dynasty.team
    val dept = team.staff.scoutingDept
    var focus by remember { mutableStateOf(team.scoutingFocus) }
    fun save(next: Set<Position>) {
        focus = next
        scope.launch { store.setScoutingFocus(next) }
    }

    ScreenList {
        item {
            Column {
                Text("Scouting", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "Your scouts cannot watch everyone. Name the positions they " +
                        "should watch closely and the club goes into the draft sure " +
                        "about those men and guessing at the rest. Name none and it " +
                        "knows a little about everybody.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        item {
            SituationBlock("Your department", meta = "$dept of 99") {
                AttributeBar("Scouting", dept)
                Text(
                    "A better department has more to spend, wherever you point it.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                )
            }
        }

        item {
            SituationBlock(
                "Where they watch",
                meta = if (focus.isEmpty()) "The whole board" else "${focus.size} chosen",
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                    verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                ) {
                    SCOUTABLE.forEach { position ->
                        val on = position in focus
                        Chip(position.label, on, role = Role.Checkbox) {
                            save(if (on) focus - position else focus + position)
                        }
                    }
                }
                if (focus.isNotEmpty()) {
                    SecondaryButton(
                        "Spread them across the board instead",
                        { save(emptySet()) },
                        Modifier.padding(top = NdTheme.spacing.s).fillMaxWidth(),
                    )
                }
            }
        }

        item {
            SituationBlock("What that buys you", meta = "On draft day") {
                val watched = if (focus.isEmpty()) SCOUTABLE.take(3) else focus.toList()
                val ignored = SCOUTABLE.filterNot { it in focus }
                watched.forEach { position ->
                    Reading(position, dept, focus, watched = true)
                }
                if (focus.isNotEmpty() && ignored.isNotEmpty()) {
                    Reading(ignored.first(), dept, focus, watched = false)
                }
                Text(
                    "A band is what the club would put a prospect between. " +
                        "Nobody is ever a certainty.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                    modifier = Modifier.padding(top = NdTheme.spacing.s),
                )
            }
        }

        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}

/** One position, and how wide a prospect's band would be there. */
@Composable
private fun Reading(position: Position, dept: Int, focus: Set<Position>, watched: Boolean) {
    val c = NdTheme.colors
    // A prospect rated 80 stands in for the board: what matters is the width.
    val confidence = Scouting.prospect(SAMPLE_PROSPECT, position, dept, focus)
    val view = ScoutingLens.of(SAMPLE_PROSPECT, 0, confidence).view(80)
    Row(
        Modifier.fillMaxWidth().padding(vertical = NdTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            position.label,
            style = NdTheme.type.data, color = if (watched) c.chalk else c.chalkDim,
            modifier = Modifier.weight(1f),
        )
        Text(
            "within ${view.high - view.low} points",
            style = NdTheme.type.data, color = if (watched) c.chalk else c.chalkDim,
        )
        Text(
            "  ${(confidence * 100).roundToInt()}%",
            style = NdTheme.type.caption, color = c.chalkDim,
        )
    }
}

/** A prospect id that is nobody in particular, for showing a band's width. */
private const val SAMPLE_PROSPECT = 7

/** The positions a club drafts for. Long snappers do not get a scouting budget. */
private val SCOUTABLE = listOf(
    Position.QB, Position.RB, Position.WR, Position.TE,
    Position.LT, Position.LG, Position.C, Position.RG, Position.RT,
    Position.EDGE, Position.DT, Position.LB, Position.CB, Position.S,
)
