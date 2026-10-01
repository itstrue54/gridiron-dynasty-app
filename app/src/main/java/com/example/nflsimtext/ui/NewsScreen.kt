package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.FilterChipRow
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.season.Dynasty

/**
 * The season's news, all of it (SPEC 10.1). The hub carries a few lines of
 * the latest week; a busy one - camp's holdouts, the deadline's deals - files
 * more than that, and this is where the rest is read. Newest week first, and
 * the user's club's stories on their own on request.
 */
@Composable
fun NewsScreen(dynasty: Dynasty, onBack: () -> Unit = {}) {
    val c = NdTheme.colors
    var mine by remember { mutableStateOf(false) }
    val stories = dynasty.news.asReversed().filter { !mine || it.team == dynasty.userTeam }

    ScreenList {
        item {
            Column {
                Text("News", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "The season so far, newest first. A league remembers its standings and its records, not its headlines.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
                FilterChipRow(listOf(ALL, MINE), if (mine) MINE else ALL, { mine = it == MINE },
                    Modifier.padding(top = NdTheme.spacing.s))
            }
        }
        if (stories.isEmpty()) {
            item {
                Text(if (mine) "Nothing about your club yet." else "Nothing filed yet.",
                    style = NdTheme.type.body, color = c.chalkDim)
            }
        }
        // Newest first, so grouping keeps the weeks in order.
        stories.groupBy { it.week }.forEach { (week, items) ->
            item(key = "week-$week") {
                SituationBlock("Week $week", meta = "${items.size} ${if (items.size == 1) "story" else "stories"}") {
                    items.forEach { NewsLine(it, dynasty.userTeam) }
                }
            }
        }
        item { SecondaryButton("Back to the hub", onBack) }
    }
}

private const val ALL = "All"
private const val MINE = "Your club"
