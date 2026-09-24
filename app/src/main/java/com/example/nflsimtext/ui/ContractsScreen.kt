package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.Chip
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.offseason.ContractChoice
import com.nflsim.engine.season.Dynasty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The club's own expiring players (SPEC 7 phases 5-6): keep him at what he
 * asks, put the franchise tag on him, put the transition tag on him, or let
 * him go to market. Every other club is deciding the same thing on the same
 * numbers.
 */
@Composable
fun ContractsScreen(
    dynasty: Dynasty,
    store: DynastyStore,
    scope: CoroutineScope,
    onDraft: () -> Unit,
    onBack: () -> Unit,
) {
    val c = NdTheme.colors
    val pause = store.contracts
    if (pause == null) {
        Column(Modifier.padding(NdTheme.spacing.xl)) {
            Text("The offseason has not opened.", style = NdTheme.type.title, color = c.chalk)
            SecondaryButton("Back to the hub", onBack, Modifier.padding(top = NdTheme.spacing.m))
        }
        return
    }
    // Starts from what the club's own front office would do, so tapping
    // straight through is the offseason the AI would have run.
    val choices = remember(pause) {
        mutableStateMapOf<Int, ContractChoice>().apply { putAll(pause.suggested) }
    }
    val cost = pause.cost(choices)
    val left = pause.capSpace - cost
    val tagged = choices.values.any { it == ContractChoice.FRANCHISE || it == ContractChoice.TRANSITION }

    ScreenList {
        item {
            Column {
                Text("Expiring contracts", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "${pause.expiring.size} deals have run out. Your front office has made its calls; " +
                        "change any you disagree with. Anyone you do not keep goes to market when free " +
                        "agency opens.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        item {
            SituationBlock(
                "Your cap",
                meta = if (tagged) "tag used" else "one tag",
                situation = if (left < 0) Situation.RED_ZONE else Situation.NORMAL,
            ) {
                Text(
                    "${money(pause.capSpace)} of room. These choices take ${money(cost)}, " +
                        if (left >= 0) "leaving ${money(left)} for free agency and the draft."
                        else "${money(-left)} more than you have: the ones that do not fit will not happen.",
                    style = NdTheme.type.data, color = c.chalk,
                )
            }
        }

        pause.expiring.forEach { e ->
            item {
                val id = e.player.id.v
                val choice = choices[id] ?: ContractChoice.WALK
                SituationBlock(
                    "${e.player.position.label} ${e.player.name}",
                    meta = "${e.player.age(pause.year)}",
                    situation = if (choice == ContractChoice.WALK) Situation.NORMAL else Situation.THIRD_DOWN,
                ) {
                    Text(
                        "The market says ${money(e.market)} a year. He asks you for ${money(e.asking)} " +
                            "over ${e.years} ${if (e.years == 1) "year" else "years"}.",
                        style = NdTheme.type.body, color = c.chalk,
                    )
                    e.wish?.let {
                        Text("He ${it.note}.", style = NdTheme.type.caption, color = c.chalkDim)
                    }
                    FlowRow(
                        Modifier.padding(top = NdTheme.spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                    ) {
                        Chip("Let him go", choice == ContractChoice.WALK) { choices.remove(id) }
                        Chip("Re-sign", choice == ContractChoice.RESIGN) { choices[id] = ContractChoice.RESIGN }
                        val canTag = !tagged || choice == ContractChoice.FRANCHISE || choice == ContractChoice.TRANSITION
                        if (canTag) {
                            Chip("Franchise ${money(e.franchise)}", choice == ContractChoice.FRANCHISE) {
                                choices[id] = ContractChoice.FRANCHISE
                            }
                            Chip("Transition", choice == ContractChoice.TRANSITION) {
                                choices[id] = ContractChoice.TRANSITION
                            }
                        }
                    }
                }
            }
        }

        item {
            SituationBlock("The tags", divider = false) {
                Text(
                    "The franchise tag keeps him for a year on a fully guaranteed tender. The transition " +
                        "tag lets him test the market and keeps you the right to match what he signs for, " +
                        "which the club does if it has the room; if nobody bids, he is a free agent like " +
                        "anyone else. One tag a year.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                )
            }
        }

        item {
            PrimaryButton(
                "On to free agency and the draft",
                { scope.launch { store.decideContracts(choices.toMap()); if (store.draftRoom != null) onDraft() } },
                Modifier.fillMaxWidth(),
                enabled = !store.busy,
            )
        }
        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}

private fun money(thousands: Int): String =
    if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)
