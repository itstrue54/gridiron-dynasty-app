package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.example.nflsimtext.ui.components.Chip
import com.example.nflsimtext.ui.components.FilterChipRow
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.PickAsset
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.offseason.PickValue
import com.nflsim.engine.ratings.ScoutingLens
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.TradeDesk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The user's trades (SPEC 8.4): a club to deal with, what goes and what
 * comes - players and picks - and the other club's answer as the offer
 * stands, before anything is made. In the season up to the deadline; in the
 * offseason at the draft room, before the first pick.
 *
 * Nothing here reads a true rating: the user's own players read as his
 * staff knows them, another club's as a newcomer would (SPEC 4.6).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TradeScreen(dynasty: Dynasty, store: DynastyStore, scope: CoroutineScope) {
    val c = NdTheme.colors
    val book = store.tradeBook()
    if (book == null) {
        Column(Modifier.padding(NdTheme.spacing.xl)) {
            Text("Trades", style = NdTheme.type.display, color = c.chalk)
            Text("The trade deadline has passed. Trading opens again at the draft room.",
                style = NdTheme.type.body, color = c.chalkDim)
        }
        return
    }
    val user = dynasty.userTeamId
    val clubs = book.league.teams.filter { it.id != user }
    var partnerId by remember { mutableStateOf(clubs.first().id) }
    var give by remember { mutableStateOf(setOf<Int>()) }
    var get by remember { mutableStateOf(setOf<Int>()) }
    var givePicks by remember { mutableStateOf(listOf<PickAsset>()) }
    var getPicks by remember { mutableStateOf(listOf<PickAsset>()) }
    var group by remember { mutableStateOf(ALL) }
    // The next draft's picks by default; later years on request. A pick on the table always shows.
    var laterYears by remember { mutableStateOf(false) }
    val partner = book.league.team(partnerId)

    fun clear() { give = emptySet(); get = emptySet(); givePicks = emptyList(); getPicks = emptyList() }
    val proposal = TradeDesk.Proposal(partnerId, give, get, givePicks, getPicks)
    val verdict = if (proposal.empty) null else TradeDesk.evaluate(book, user, proposal)
    val said = verdict?.let { TradeDesk.answer(book, user, proposal, it) }

    // How the user's club reads a man: his own as his staff knows them, theirs as a newcomer would.
    fun read(p: Player): String {
        val lens = if (p.teamId == user) lensFor(dynasty, p)
            else ScoutingLens.of(p.id.v, user.v, ScoutingLens.ownPlayer(0, dynasty.team.staff.scoutingDept))
        return lens.view(overall(p, book.scheme(user, p.position))).text
    }
    fun label(p: Player): String {
        val contract = p.contract?.let { k -> ", ${money(p.capHit(book.year))} ×${(k.signedYear + k.years - book.year).coerceAtLeast(1)}" } ?: ""
        val reserve = if (p.status == PlayerStatus.IR) ", on reserve" else ""
        return "${p.position.label} ${p.name}, ${p.age(book.year)}, ${read(p)}$contract$reserve"
    }
    fun pickLabel(pick: PickAsset): String {
        val from = if (pick.original != pick.owner) " (${book.league.team(TeamId(pick.original)).abbrev})" else ""
        val comp = if (pick.compensatory) " comp" else ""
        return "${pick.year} round ${pick.round}$comp$from"
    }
    val byId = remember(book) { book.players.associateBy { it.id.v } }
    androidx.compose.runtime.LaunchedEffect(dynasty) { store.refreshTradeOffers() }

    fun roster(team: TeamId) = book.players
        .filter { it.teamId == team && (it.status == PlayerStatus.ACTIVE || it.status == PlayerStatus.IR) }
        .filter { group == ALL || it.position.group.name == group }
        .sortedWith(compareBy({ it.position.ordinal }, { it.lastName }))
    fun picks(team: TeamId) = book.picks
        .filter { it.owner == team.v && (laterYears || it.year == book.draftYear || it in givePicks || it in getPicks) }
        .sortedWith(compareBy({ it.year }, { it.round }))

    ScreenList {
        item {
            Column {
                Text("Trades", style = NdTheme.type.display, color = c.chalk)
                Text(
                    if (book.rosterLimit == com.nflsim.engine.season.Transactions.ROSTER_LIMIT)
                        "Open until week ${TradeDesk.DEADLINE_WEEK} kicks off. Contracts move as they stand; " +
                            "the club that sends a man away eats what's left of his bonus."
                    else "At the draft room, before the first pick: picks and players, both ways.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        store.message?.let { note ->
            item {
                SituationBlock("Last move", situation = Situation.THIRD_DOWN) {
                    Text(note, style = NdTheme.type.body, color = c.chalk)
                    SecondaryButton("Clear", { store.dismissMessage() }, Modifier.padding(top = NdTheme.spacing.s))
                }
            }
        }

        // Clubs that called this week (in the season only: the draft room has no calls).
        if (book.rosterLimit == com.nflsim.engine.season.Transactions.ROSTER_LIMIT) {
            store.tradeOffers.forEach { offer ->
                val caller = book.league.team(offer.proposal.partner)
                item(key = "call-${caller.abbrev}-${offer.target}") {
                    SituationBlock("${caller.abbrev} calling", meta = if (offer.onBlock) "About your block" else caller.name,
                        situation = Situation.TWO_MINUTE) {
                        Text("\u201C${offer.pitch.line}\u201D", style = NdTheme.type.body, color = c.chalk)
                        Text("\u2014 ${offer.pitch.speaker}", style = NdTheme.type.caption, color = c.chalkDim)
                        Text("They want", style = NdTheme.type.label, color = c.chalkDim,
                            modifier = Modifier.padding(top = NdTheme.spacing.s))
                        offer.proposal.give.mapNotNull { byId[it] }.forEach {
                            Text(label(it), style = NdTheme.type.body, color = c.chalk)
                        }
                        Text("They offer", style = NdTheme.type.label, color = c.chalkDim,
                            modifier = Modifier.padding(top = NdTheme.spacing.s))
                        offer.proposal.get.mapNotNull { byId[it] }.forEach {
                            Text(label(it), style = NdTheme.type.body, color = c.chalk)
                        }
                        offer.proposal.getPicks.forEach { Text(pickLabel(it), style = NdTheme.type.body, color = c.chalk) }
                        PrimaryButton(
                            "Take the deal",
                            { scope.launch { if (store.trade(offer.proposal)) clear() } },
                            Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s),
                            enabled = !store.busy,
                        )
                        SecondaryButton("Work from it", {
                            partnerId = offer.proposal.partner
                            give = offer.proposal.give; get = offer.proposal.get
                            givePicks = offer.proposal.givePicks; getPicks = offer.proposal.getPicks
                        }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s))
                        SecondaryButton("Not interested", { store.declineOffer(offer) },
                            Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s))
                    }
                }
            }
        }

        // The user's block: who he has said is available (in the season; calls come only then).
        if (book.rosterLimit == com.nflsim.engine.season.Transactions.ROSTER_LIMIT) {
            val block = com.nflsim.engine.season.TradeOffers.block(dynasty).mapNotNull { byId[it] }
            item {
                SituationBlock("Your trade block", meta = if (block.isEmpty()) "nobody" else "${block.size}") {
                    if (block.isEmpty()) Text("Put a man on the block from his player card, and clubs he would help call about him.",
                        style = NdTheme.type.caption, color = c.chalkDim)
                    block.forEach { p ->
                        Text(label(p), style = NdTheme.type.body, color = c.chalk)
                        SecondaryButton("Take him off", { scope.launch { store.setOnBlock(p.id.v, false) } },
                            Modifier.padding(bottom = NdTheme.spacing.s))
                    }
                }
            }
        }

        item {
            SituationBlock("Trade with", meta = partner.name) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                    verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                    clubs.forEach { club ->
                        Chip(club.abbrev, club.id == partnerId, role = Role.RadioButton) {
                            if (club.id != partnerId) { partnerId = club.id; get = emptySet(); getPicks = emptyList() }
                        }
                    }
                }
                FilterChipRow(GROUPS, group, { group = it }, Modifier.padding(top = NdTheme.spacing.s))
                Chip("Later years' picks", laterYears, Modifier.padding(top = NdTheme.spacing.s), role = Role.Checkbox) {
                    laterYears = !laterYears
                }
            }
        }

        item {
            SituationBlock("You send", meta = count(give.size, givePicks.size)) {
                Pieces(roster(user).map { it.id.v to label(it) }, give) { id -> give = give.toggle(id) }
                PickPieces(picks(user), givePicks, ::pickLabel) { pick -> givePicks = givePicks.toggle(pick) }
            }
        }

        item {
            SituationBlock("You get", meta = count(get.size, getPicks.size)) {
                Pieces(roster(partnerId).map { it.id.v to label(it) }, get) { id -> get = get.toggle(id) }
                PickPieces(picks(partnerId), getPicks, ::pickLabel) { pick -> getPicks = getPicks.toggle(pick) }
            }
        }

        item {
            SituationBlock(
                "${partner.abbrev}'s answer",
                situation = when {
                    verdict == null -> Situation.NORMAL
                    verdict.accepted -> Situation.TWO_MINUTE
                    else -> Situation.RED_ZONE
                },
            ) {
                // What their GM says, then the facts behind it.
                said?.let { q ->
                    Column(Modifier.padding(bottom = NdTheme.spacing.s)) {
                        Text("\u201C${q.line}\u201D", style = NdTheme.type.body, color = c.chalk)
                        Text("\u2014 ${q.speaker}", style = NdTheme.type.caption, color = c.chalkDim)
                    }
                }
                when {
                    verdict == null -> Text("Put something on the table.", style = NdTheme.type.body, color = c.chalkDim)
                    verdict.accepted -> Text("They'd take it.", style = NdTheme.type.body, color = c.chalk)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.xs)) {
                        verdict.reasons.forEach { Text(it, style = NdTheme.type.body, color = c.chalk) }
                        if (verdict.shortBy > 0f) {
                            Text(shortfall(book, partner.gm.winNowVsFuture, verdict.shortBy),
                                style = NdTheme.type.caption, color = c.chalkDim)
                        }
                    }
                }
                PrimaryButton(
                    "Make the trade",
                    { scope.launch { if (store.trade(proposal)) clear() } },
                    Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s),
                    enabled = verdict?.accepted == true && !store.busy,
                )
                SecondaryButton("Clear the table", { clear() }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s))
            }
        }
    }
}

/** Men to pick from, as checkboxes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pieces(men: List<Pair<Int, String>>, chosen: Set<Int>, onToggle: (Int) -> Unit) {
    if (men.isEmpty()) {
        Text("Nobody at this position.", style = NdTheme.type.caption, color = NdTheme.colors.chalkDim)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
        men.forEach { (id, text) -> Chip(text, id in chosen, role = Role.Checkbox) { onToggle(id) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickPieces(picks: List<PickAsset>, chosen: List<PickAsset>, label: (PickAsset) -> String, onToggle: (PickAsset) -> Unit) {
    if (picks.isEmpty()) return
    Text("Picks", style = NdTheme.type.label, color = NdTheme.colors.chalkDim,
        modifier = Modifier.padding(top = NdTheme.spacing.s))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
        picks.forEach { pick -> Chip(label(pick), pick in chosen, role = Role.Checkbox) { onToggle(pick) } }
    }
}

/**
 * How far short an offer is, as the pick that would cover it: the latest
 * round whose mid pick is worth at least the gap to them.
 */
private fun shortfall(book: TradeDesk.Book, winNow: Float, short: Float): String {
    val mid = book.order.getOrNull(book.order.size / 2)?.v ?: 0
    val cover = (7 downTo 1).firstOrNull { round ->
        PickValue.value(PickAsset(book.draftYear, round, mid, mid), book.draftYear, book.order, winNow, book.league.tuning.trades) >= short
    }
    return if (cover == null) "They'd want more than a first-round pick besides."
        else "About a round $cover pick short of what they'd want."
}

private fun count(men: Int, picks: Int): String = listOfNotNull(
    if (men > 0) "$men ${if (men == 1) "player" else "players"}" else null,
    if (picks > 0) "$picks ${if (picks == 1) "pick" else "picks"}" else null,
).joinToString(", ").ifBlank { "nothing" }

private fun <T> Set<T>.toggle(x: T): Set<T> = if (x in this) this - x else this + x
private fun <T> List<T>.toggle(x: T): List<T> = if (x in this) this - x else this + x

private const val ALL = "ALL"
private val GROUPS = listOf(ALL) + com.nflsim.engine.model.PositionGroup.entries.map { it.name }

private fun money(thousands: Int): String =
    if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)
