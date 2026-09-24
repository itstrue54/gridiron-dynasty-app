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
import com.nflsim.engine.offseason.ContractDecision
import com.nflsim.engine.offseason.ContractOptions
import com.nflsim.engine.season.Dynasty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The club's own expiring players (SPEC 7 phases 5-6). For each: the best
 * option and why, then every way to keep him - his term and a year either
 * side, written three ways - the two tags, and letting him go. Every price
 * is the one the league's own clubs are quoted.
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
    val recs = remember(pause) { pause.expiring.associate { it.player.id.v to pause.recommend(it) } }
    // The calls are the user's: nothing is decided until he decides it. The
    // advice sits beside each man, and the front office will take the whole
    // thing off his hands if he asks it to.
    val decisions = remember(pause) { mutableStateMapOf<Int, ContractDecision>() }
    val undecided = pause.expiring.count { it.player.id.v !in decisions }
    fun go(choices: Map<Int, ContractDecision>?) {
        scope.launch { store.decideContracts(choices); if (store.draftRoom != null) onDraft() }
    }
    val cost = pause.cost(decisions)
    val left = pause.capSpace - cost
    val tagger = decisions.entries.firstOrNull {
        it.value.choice == ContractChoice.FRANCHISE || it.value.choice == ContractChoice.TRANSITION
    }?.key

    ScreenList {
        item {
            Column {
                Text("Expiring contracts", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "${pause.expiring.size} deals have run out, and the calls are yours. Each man " +
                        "shows the best option and why. Anyone you do not keep goes to market when " +
                        "free agency opens.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        item {
            SituationBlock("Rather not?", divider = false) {
                Text(
                    "Take the advice on every man in one go, or hand the lot to your front office " +
                        "and it will decide them the way it decides for every other club.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                )
                FlowRow(
                    Modifier.padding(top = NdTheme.spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                ) {
                    SecondaryButton("Take all the advice", {
                        recs.forEach { (id, r) -> decisions[id] = r.decision }
                    })
                    SecondaryButton("Let the front office decide", { go(null) }, enabled = !store.busy)
                }
            }
        }

        item {
            SituationBlock(
                "Your cap",
                meta = if (tagger != null) "tag used" else "one tag",
                situation = if (left < 0) Situation.RED_ZONE else Situation.NORMAL,
            ) {
                Text(
                    "${dealMoney(pause.capSpace)} of room. These choices take ${dealMoney(cost)}, " +
                        if (left >= 0) "leaving ${dealMoney(left)} for free agency and the draft."
                        else "${dealMoney(-left)} more than you have: the ones that do not fit will not happen.",
                    style = NdTheme.type.data, color = c.chalk,
                )
            }
        }

        pause.expiring.forEach { e ->
            item {
                val id = e.player.id.v
                val rec = recs.getValue(id)
                val d = decisions[id]
                val deals = pause.deals(e)
                val recDeal = deals.firstOrNull {
                    rec.decision.choice == ContractChoice.RESIGN &&
                        it.years == rec.decision.years && it.structure == rec.decision.structure
                }
                val following = d == rec.decision
                SituationBlock(
                    "${e.player.position.label} ${e.player.name}",
                    meta = "${e.player.age(pause.year)}",
                    situation = when (d?.choice) {
                        null -> Situation.RED_ZONE
                        ContractChoice.WALK -> Situation.NORMAL
                        else -> Situation.THIRD_DOWN
                    },
                ) {
                    Text(
                        "The market says ${dealMoney(e.market)} a year. He asks you for " +
                            "${dealMoney(e.asking)} over ${e.years} ${if (e.years == 1) "year" else "years"}.",
                        style = NdTheme.type.body, color = c.chalk,
                    )
                    e.wish?.let { Text("He ${it.note}.", style = NdTheme.type.caption, color = c.chalkDim) }

                    // The recommendation, and the reason for it.
                    Text(
                        "Best: ${rec.headline}",
                        style = NdTheme.type.data, color = c.chalk,
                        modifier = Modifier.padding(top = NdTheme.spacing.s),
                    )
                    Text(rec.why, style = NdTheme.type.caption, color = c.chalkDim)
                    if (rec.frontOffice != rec.decision.choice) {
                        Text(
                            "Your front office would have ${frontOfficeSays(rec.frontOffice)}.",
                            style = NdTheme.type.caption, color = c.chalkDim,
                        )
                    }
                    if (!following) {
                        SecondaryButton(
                            "Take the advice", { decisions[id] = rec.decision },
                            Modifier.padding(top = NdTheme.spacing.xs),
                        )
                    }

                    FlowRow(
                        Modifier.padding(top = NdTheme.spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                    ) {
                        Chip("Let him go", d?.choice == ContractChoice.WALK) {
                            decisions[id] = ContractDecision(ContractChoice.WALK)
                        }
                        Chip("Re-sign", d?.choice == ContractChoice.RESIGN) {
                            val start = recDeal ?: deals.first {
                                it.years == e.years && it.structure == ContractOptions.Structure.STANDARD
                            }
                            decisions[id] = ContractDecision(ContractChoice.RESIGN, start.years, start.structure)
                        }
                        if (tagger == null || tagger == id) {
                            Chip("Franchise ${dealMoney(e.franchise)}", d?.choice == ContractChoice.FRANCHISE) {
                                decisions[id] = ContractDecision(ContractChoice.FRANCHISE)
                            }
                            Chip("Transition", d?.choice == ContractChoice.TRANSITION) {
                                decisions[id] = ContractDecision(ContractChoice.TRANSITION)
                            }
                        }
                    }

                    // Every way to write it, once he is being kept.
                    if (d?.choice == ContractChoice.RESIGN) {
                        val picked = deals.firstOrNull { it.years == d.years && it.structure == d.structure }
                        Column(Modifier.padding(top = NdTheme.spacing.s)) {
                            DealTable(deals, picked, recDeal) { deal ->
                                decisions[id] = ContractDecision(ContractChoice.RESIGN, deal.years, deal.structure)
                            }
                            picked?.let {
                                Text(
                                    "${it.structure.label}: ${it.structure.blurb}.",
                                    style = NdTheme.type.caption, color = c.chalkDim,
                                    modifier = Modifier.padding(top = NdTheme.spacing.xs),
                                )
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
            Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                PrimaryButton(
                    if (undecided == 0) "On to free agency and the draft"
                    else "$undecided still to decide",
                    { go(decisions.toMap()) },
                    Modifier.fillMaxWidth(),
                    enabled = undecided == 0 && !store.busy,
                )
                if (undecided > 0) {
                    SecondaryButton(
                        "Let the $undecided undecided go to market",
                        {
                            pause.expiring.forEach { e ->
                                decisions.putIfAbsent(e.player.id.v, ContractDecision(ContractChoice.WALK))
                            }
                        },
                        Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}

private fun frontOfficeSays(choice: ContractChoice) = when (choice) {
    ContractChoice.RESIGN -> "re-signed him"
    ContractChoice.FRANCHISE -> "franchise tagged him"
    ContractChoice.TRANSITION -> "transition tagged him"
    ContractChoice.WALK -> "let him go"
}
