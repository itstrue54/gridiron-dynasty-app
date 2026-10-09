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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.Chip
import com.example.nflsimtext.ui.components.FilterChipRow
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.offseason.FreeAgency
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.SchemeFitGrade
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.season.Dynasty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Free agency (SPEC 7 phase 7), the club's own offers. An offer stands for
 * all ten days against every other club's, and a man takes the most
 * appealing package that clears his asking price - money first, but he
 * notices who wins and who runs his kind of scheme - and asks a little less
 * each day he waits.
 */
@Composable
fun FreeAgencyScreen(
    dynasty: Dynasty,
    store: DynastyStore,
    scope: CoroutineScope,
    onDraft: () -> Unit,
    onBack: () -> Unit,
) {
    val c = NdTheme.colors
    val pause = store.freeAgency
    if (pause == null) {
        Column(Modifier.padding(NdTheme.spacing.xl)) {
            Text("Free agency has not opened.", style = NdTheme.type.title, color = c.chalk)
            SecondaryButton("Back to the hub", onBack, Modifier.padding(top = NdTheme.spacing.m))
        }
        return
    }
    // Keyed to the dynasty, not the pause: talking to an agent makes a new
    // pause, and the offers on the table should survive it.
    val offers = remember(dynasty) { mutableStateMapOf<Int, FreeAgency.Offer>() }
    // How far to match each transition-tagged man's offer sheet; the suggestion until changed.
    val matches = remember(dynasty) { mutableStateMapOf<Int, Int>() }
    // A man signed before the market opens is no longer anyone to bid on.
    val onMarket = pause.candidates.map { it.player.id.v }.toSet()
    LaunchedEffect(onMarket) { offers.keys.filter { it !in onMarket }.forEach { offers.remove(it) } }
    var position by remember { mutableStateOf(ALL) }
    val committed = offers.values.sumOf { it.annual }
    val offence = SchemeCatalog.tuned(dynasty.team.offenseScheme, dynasty.league.tuning)
    val defence = SchemeCatalog.tuned(dynasty.team.defenseScheme, dynasty.league.tuning)
    fun go(list: List<FreeAgency.Offer>?) {
        scope.launch { store.runFreeAgency(list, matches.toMap()); if (store.draftRoom != null) onDraft() }
    }
    val shown = pause.candidates
        .filter { position == ALL || it.player.position.group.name == position }
        .take(SHOWN)
    val abbrev = dynasty.league.teams.associate { it.id to it.abbrev }

    // A man's card, over the market; the offers wait underneath.
    var card by remember { mutableStateOf<Int?>(null) }
    androidx.activity.compose.BackHandler(enabled = card != null) { card = null }
    card?.let { id ->
        val man = pause.candidates.first { it.player.id.v == id }.player
        PlayerCardScreen(
            dynasty, id, players = mapOf(id to man), backLabel = "Back to free agency",
            readAs = lensFor(dynasty, man),
        ) { card = null }
        return
    }
    val cut = (FreeAgency.dailyCut(dynasty.league.tuning.ai) * 100).toInt()

    ScreenList {
        item {
            Column {
                Text("Free agency", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "Ten days. Put an offer on anyone you want: it stands every day he is unsigned and " +
                        "you can still pay it, against every other club's. He takes the best package that " +
                        "clears his asking price, which falls about $cut% a day - so an offer at his market " +
                        "can lose him to a contender early, and one under it can land late.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        item {
            SituationBlock(
                "Your offers", meta = "${offers.size}",
                situation = if (committed > pause.capSpace) Situation.RED_ZONE else Situation.NORMAL,
            ) {
                Text(
                    "${dealMoney(pause.capSpace)} of room, ${pause.roster.size} on the roster. " +
                        if (offers.isEmpty()) "No offers yet."
                        else "Offers on the table come to ${dealMoney(committed)} a year; the ones that " +
                            "no longer fit once others sign are withdrawn.",
                    style = NdTheme.type.data, color = c.chalk,
                )
                offers.values.forEach { o ->
                    val man = pause.candidates.firstOrNull { it.player.id.v == o.player }?.player ?: return@forEach
                    Text(
                        "${man.position.label} ${man.name}: ${o.years} ${if (o.years == 1) "year" else "years"} " +
                            "at ${dealMoney(o.annual)}",
                        style = NdTheme.type.caption, color = c.chalkDim,
                    )
                }
                FlowRow(
                    Modifier.padding(top = NdTheme.spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                    verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                ) {
                    PrimaryButton(
                        if (offers.isEmpty()) "Open free agency with no offers" else "Open free agency",
                        { go(offers.values.toList()) }, enabled = !store.busy,
                    )
                    SecondaryButton("Let the front office bid", { go(null) }, enabled = !store.busy)
                }
            }
        }

        if (pause.tagged.isNotEmpty()) {
            item {
                SituationBlock("Your transition tags", meta = "${pause.tagged.size}") {
                    Text(
                        "Another club may make him an offer sheet; you can match it and keep him. The market " +
                            "runs in one go, so say now how far you would go. Hand free agency to the front " +
                            "office and it matches whatever fits.",
                        style = NdTheme.type.body, color = c.chalkDim,
                    )
                    pause.tagged.forEach { t ->
                        val man = t.candidate.player
                        val id = man.id.v
                        val chosen = matches[id] ?: t.advice.upTo
                        Text(
                            "${man.position.label} ${man.name}, worth ${dealMoney(t.candidate.market)} a year",
                            style = NdTheme.type.data, color = c.chalk,
                            modifier = Modifier.padding(top = NdTheme.spacing.s),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                            verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                        ) {
                            t.options.forEach { o -> Chip(o.label, chosen == o.upTo) { matches[id] = o.upTo } }
                        }
                        Text("Best: ${t.advice.label}", style = NdTheme.type.caption, color = c.chalk)
                        Text(t.why, style = NdTheme.type.caption, color = c.chalkDim)
                    }
                }
            }
        }

        store.message?.let { note ->
            item {
                SituationBlock("Signed", situation = Situation.THIRD_DOWN) {
                    Text(note, style = NdTheme.type.body, color = c.chalk)
                    SecondaryButton("Clear", { store.dismissMessage() }, Modifier.padding(top = NdTheme.spacing.s))
                }
            }
        }

        item {
            FilterChipRow(GROUPS, position, { position = it })
        }

        shown.forEach { cand ->
            item {
                val p = cand.player
                val id = p.id.v
                val advice = pause.advice(cand)
                val mine = offers[id]
                val scheme = if (p.position.isOffense) offence else defence
                SituationBlock(
                    "${p.position.label} ${p.name}",
                    meta = "${p.age(pause.year)}",
                    situation = if (mine != null) Situation.THIRD_DOWN else Situation.NORMAL,
                ) {
                    Text(
                        "Your read: ${lensFor(dynasty, p).view(overall(p, scheme)).text}, " +
                            "fit ${SchemeFitGrade.letter(schemeFit(p, scheme))}. " +
                            "Worth ${dealMoney(cand.market)} a year; opens asking ${dealMoney(cand.opening)}" +
                            (cand.from?.let { ", late of ${abbrev[it] ?: "?"}" } ?: "") + ".",
                        style = NdTheme.type.body, color = c.chalk,
                    )
                    Text(
                        "Best: ${advice.headline}",
                        style = NdTheme.type.data, color = c.chalk,
                        modifier = Modifier.padding(top = NdTheme.spacing.xs),
                    )
                    Text(advice.why, style = NdTheme.type.caption, color = c.chalkDim)
                    SecondaryButton("See his card", { card = id }, Modifier.padding(top = NdTheme.spacing.xs))
                    FlowRow(
                        Modifier.padding(top = NdTheme.spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                    ) {
                        Chip("No offer", mine == null) { offers.remove(id) }
                        OFFER_SHARES.forEach { (share, label) ->
                            val annual = (cand.market * share).toInt().coerceAtLeast(
                                com.nflsim.engine.offseason.FreeAgencyPause.MIN_OFFER)
                            val years = if (p.age(pause.year) > dynasty.league.tuning.ai.payThroughAge) 1 else cand.years
                            Chip("$label ${dealMoney(annual)}", mine?.annual == annual) {
                                offers[id] = FreeAgency.Offer(id, annual, years)
                            }
                        }
                    }
                    mine?.let { o ->
                        FlowRow(
                            Modifier.padding(top = NdTheme.spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                            verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                        ) {
                            (1..com.nflsim.engine.offseason.ContractOptions.MAX_YEARS).forEach { y ->
                                Chip("${y}y", o.years == y) { offers[id] = o.copy(years = y) }
                            }
                        }
                    }

                    // Haggling: an offer to his agent now, before anyone else can bid.
                    val left = pause.talksLeft(id)
                    store.agentReply?.takeIf { it.first == id }?.let { (_, said) ->
                        Text(
                            said, style = NdTheme.type.body, color = c.chalk,
                            modifier = Modifier.padding(top = NdTheme.spacing.s),
                        )
                    }
                    Text(
                        if (left == 0) "He is done talking: it is the market or nothing."
                        else "Or talk to his agent now, before the market opens. He signs on the spot " +
                            "if it clears what he will take; if not, you learn his floor. " +
                            "$left ${if (left == 1) "offer" else "offers"} left.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.s),
                    )
                    if (left > 0) {
                        FlowRow(
                            Modifier.padding(top = NdTheme.spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                            verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                        ) {
                            val years = mine?.years
                                ?: if (p.age(pause.year) > dynasty.league.tuning.ai.payThroughAge) 1 else cand.years
                            TALK_SHARES.forEach { share ->
                                val annual = (cand.market * share).toInt()
                                SecondaryButton(
                                    "Offer ${dealMoney(annual)} now",
                                    { store.negotiate(id, annual, years) },
                                    enabled = !store.busy,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Showing the ${shown.size} most valuable. Anyone you do not sign goes to other clubs, " +
                    "or waits for the phone to ring.",
                style = NdTheme.type.caption, color = c.chalkDim,
            )
        }
        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}

/** Offers to an agent before the market, as shares of what the man is worth. */
private val TALK_SHARES = listOf(0.95f, 1.0f, 1.1f)

/** The offers a club puts on the table, as shares of what the man is worth. */
private val OFFER_SHARES = listOf(0.9f to "Under", 1.0f to "Market", 1.15f to "Over")

private const val ALL = "All"
private const val SHOWN = 40
private val GROUPS = listOf(ALL) + com.nflsim.engine.model.Position.entries.map { it.group.name }.distinct()
