package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.season.ContractDisputes
import com.nflsim.engine.season.Dynasty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Men who have noticed what they are paid (SPEC 10.1). Each one is a
 * decision with a price on it: the market rate against this year's cap, or
 * a refusal that costs him morale now and patience in the spring.
 */
@Composable
fun DemandsScreen(
    dynasty: Dynasty,
    store: DynastyStore,
    scope: CoroutineScope,
    onBack: () -> Unit = {},
) {
    val c = NdTheme.colors
    val asks = ContractDisputes.pending(dynasty.league, dynasty.userTeamId, dynasty.playerStats)
    val space = com.nflsim.engine.season.Transactions.spaceFor(dynasty.league, dynasty.userTeamId)

    ScreenList {
        item {
            Column {
                Text("Contract demands", style = NdTheme.type.display, color = c.chalk)
                Text(
                    if (asks.isEmpty()) "Nobody is asking. ${money(space)} of cap room."
                    else "${money(space)} of cap room. Every week a demand sits unanswered " +
                        "costs him morale, and morale is worth a little of his rating.",
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

        asks.forEach { ask ->
            item {
                val affordable = space >= ask.capChange
                SituationBlock(
                    "${ask.player.position.label} ${ask.player.name}",
                    meta = "${ask.player.age(dynasty.year)}",
                    situation = if (affordable) Situation.THIRD_DOWN else Situation.RED_ZONE,
                ) {
                    Text(
                        "On ${money(ask.paid)} this year. The market says ${money(ask.market)} a year.",
                        style = NdTheme.type.data, color = c.chalk,
                    )
                    Text(
                        "He wants ${ask.years} years at that rate: " +
                            "${money(ask.capChange)} more against this year's cap.",
                        style = NdTheme.type.body, color = c.chalkDim,
                    )
                    if (!affordable) {
                        Text(
                            "You have not got the room for it.",
                            style = NdTheme.type.caption, color = c.chalkDim,
                        )
                    }
                    Row(
                        Modifier.padding(top = NdTheme.spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                    ) {
                        PrimaryButton(
                            "Pay him",
                            { scope.launch { store.extendContract(ask.player.id.v) } },
                            enabled = affordable && !store.busy,
                        )
                        SecondaryButton(
                            "Tell him no",
                            { scope.launch { store.refuseDemand(ask.player.id.v) } },
                        )
                    }
                    Text(
                        "Refused, he plays out his deal with his morale down, and remembers " +
                            "it in the spring when he decides whether to ask for a trade.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
            }
        }

        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}

private fun money(thousands: Int): String =
    if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)
