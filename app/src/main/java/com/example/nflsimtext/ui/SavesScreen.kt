package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.ActionDialog
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Five slots and three autosaves (SPEC 9.1). The slots are the user's: one
 * for the dynasty being played, the rest for another club, another league,
 * or the season before a trade that went wrong. The autosaves are the
 * game's, written whenever the phase turns over.
 */
@Composable
fun SavesScreen(store: DynastyStore, scope: CoroutineScope, onBack: () -> Unit = {}) {
    val c = NdTheme.colors
    var cards by remember { mutableStateOf<List<Saves.Card>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var confirming by remember { mutableStateOf<Saves.Card?>(null) }
    LaunchedEffect(reload, store.dynasty) { cards = store.cards() }
    fun refresh() { reload++ }

    val slots = cards.filterNot { it.auto }
    val autos = cards.filter { it.auto }
    val live = store.dynasty

    ScreenList {
        item {
            Column {
                Text("Saves", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "Five slots of your own. The game writes an autosave every time " +
                        "the phase turns over and keeps the last ${Saves.AUTOSAVES}.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        store.message?.let { note ->
            item {
                SituationBlock("Last word", situation = Situation.THIRD_DOWN) {
                    Text(note, style = NdTheme.type.body, color = c.chalk)
                    SecondaryButton(
                        "Clear", { store.dismissMessage() },
                        Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
            }
        }

        (1..Saves.SLOTS).forEach { n ->
            item {
                val card = slots.firstOrNull { it.slot == n }
                val here = store.slot == n && live != null
                SituationBlock(
                    "Slot $n",
                    meta = if (here) "playing here" else null,
                    situation = if (here) Situation.THIRD_DOWN else Situation.NORMAL,
                ) {
                    Text(
                        card?.summary ?: "Empty",
                        style = NdTheme.type.data, color = if (card == null) c.chalkDim else c.chalk,
                    )
                    card?.let {
                        Text(
                            "${it.record.enDashed()}, saved ${when {
                                it.savedAt == 0L -> "some time ago"
                                else -> DateFormat.getDateTimeInstance(
                                    DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.savedAt))
                            }}",
                            style = NdTheme.type.caption, color = c.chalkDim,
                        )
                    }
                    FlowRow(
                        Modifier.padding(top = NdTheme.spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                    ) {
                        if (card != null && !here) {
                            PrimaryButton(
                                "Play this one",
                                { scope.launch { store.load(n); refresh() } },
                                enabled = !store.busy,
                            )
                        }
                        if (live != null && !here) {
                            SecondaryButton(
                                if (card == null) "Save here" else "Overwrite",
                                { if (card == null) scope.launch { store.copyTo(n); refresh() } else confirming = card },
                            )
                        }
                        if (card != null && !here) {
                            SecondaryButton("Delete", { confirming = card })
                        }
                    }
                }
            }
        }

        item {
            SituationBlock("Autosaves", meta = "${autos.size} of ${Saves.AUTOSAVES}") {
                if (autos.isEmpty()) {
                    Text(
                        "None yet. One lands when a season ends, the playoffs start, " +
                            "or the offseason runs.",
                        style = NdTheme.type.body, color = c.chalkDim,
                    )
                }
                autos.forEach { card ->
                    Column(Modifier.padding(bottom = NdTheme.spacing.s)) {
                        Text(card.summary, style = NdTheme.type.data, color = c.chalk)
                        SecondaryButton(
                            "Restore into slot ${store.slot}",
                            { scope.launch { store.restore(card, store.slot); refresh() } },
                        )
                    }
                }
            }
        }

        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }

    confirming?.let { card ->
        val overwrite = live != null && !card.auto && store.slot != card.slot
        ActionDialog(
            if (overwrite) "Write over ${card.label}?" else "Delete ${card.label}?",
            onDismiss = { confirming = null },
            situation = Situation.RED_ZONE,
        ) {
            Text(
                "${card.summary} is in there. Nothing brings it back.",
                style = NdTheme.type.body, color = c.chalk,
            )
            PrimaryButton(
                if (overwrite) "Write over it" else "Delete it",
                {
                    scope.launch {
                        if (overwrite) store.copyTo(card.slot) else store.deleteSave(card)
                        refresh()
                    }
                    confirming = null
                },
                Modifier.padding(top = NdTheme.spacing.s),
            )
            SecondaryButton("Keep it", { confirming = null })
        }
    }
}
