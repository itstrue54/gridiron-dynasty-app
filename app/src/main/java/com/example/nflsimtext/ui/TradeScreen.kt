package com.example.nflsimtext.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import com.example.nflsimtext.ui.components.Chip
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.FilterChipRow
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.PickAsset
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.offseason.PickValue
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.SchemeFitGrade
import com.nflsim.engine.ratings.schemeFit
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
 * Three views, one at a time: the clubs that called, the deal being built,
 * and the user's block. Building a deal keeps the table and the answer
 * pinned at the top while the rosters scroll underneath, so every piece
 * added is answered where the user is looking.
 *
 * Nothing here reads a true rating: the user's own players read as his
 * staff knows them, another club's as a newcomer would (SPEC 4.6). Any man
 * in any view opens his card - held in a table, tapped in a call or on the
 * block - over the screen, so the deal being built stays as it was.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
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
    // Calls first when a club has called; otherwise straight to building a deal.
    var view by remember { mutableStateOf(if (store.tradeOffers.isNotEmpty()) TradeView.CALLS else TradeView.BUILD) }
    // Which roster is showing, and whether the club list is open.
    var theirSide by remember { mutableStateOf(false) }
    var choosingClub by remember { mutableStateOf(false) }
    val partner = book.league.team(partnerId)

    fun clear() { give = emptySet(); get = emptySet(); givePicks = emptyList(); getPicks = emptyList() }
    val proposal = TradeDesk.Proposal(partnerId, give, get, givePicks, getPicks)
    val verdict = if (proposal.empty) null else TradeDesk.evaluate(book, user, proposal)
    val said = verdict?.let { TradeDesk.answer(book, user, proposal, it) }

    // How the user's club reads a man: his own as his staff knows them, theirs as a newcomer would.
    // The rating the roster leads with, so a man reads the same on both screens.
    fun rating(p: Player): String = lensOf(dynasty, p).view(overall(p)).text
    // How he would suit the user's schemes, whoever's he is now.
    val offense = SchemeCatalog.tuned(dynasty.team.offenseScheme, book.league.tuning)
    val defense = SchemeCatalog.tuned(dynasty.team.defenseScheme, book.league.tuning)
    fun fit(p: Player): String = SchemeFitGrade.letter(schemeFit(p, if (p.position.isOffense) offense else defense))
    fun years(p: Player): Int = p.contract?.let { k -> (k.signedYear + k.years - book.year).coerceAtLeast(1) } ?: 0
    fun label(p: Player): String {
        val contract = p.contract?.let { ", ${money(p.capHit(book.year))} ×${years(p)}" } ?: ""
        val reserve = if (p.status == PlayerStatus.IR) ", on reserve" else ""
        return "${p.position.label} ${p.name}, ${p.age(book.year)}, Ovr ${rating(p)}$contract$reserve"
    }
    fun pickLabel(pick: PickAsset): String {
        val from = if (pick.original != pick.owner) " (${book.league.team(TeamId(pick.original)).abbrev})" else ""
        val comp = if (pick.compensatory) " comp" else ""
        return "${pick.year} round ${pick.round}$comp$from"
    }
    val byId = remember(book) { book.players.associateBy { it.id.v } }
    androidx.compose.runtime.LaunchedEffect(dynasty, book) { store.refreshTradeOffers() }

    // A man's card, over the screen: the deal and the view wait underneath.
    var card by remember { mutableStateOf<Int?>(null) }
    BackHandler(enabled = card != null) { card = null }
    card?.let { id ->
        PlayerCardScreen(dynasty, id, store, scope, players = byId, backLabel = "Back to the trade") { card = null }
        return
    }
    // A line in a call or on the block: tap it for his card.
    @Composable
    fun Man(p: Player) {
        Text(label(p), style = NdTheme.type.body, color = c.chalk,
            modifier = Modifier.clickable(onClickLabel = "open his card") { card = p.id.v })
    }

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

        val block = com.nflsim.engine.season.TradeOffers.block(dynasty).mapNotNull { byId[it] }
        item {
            ViewTabs(view, store.tradeOffers.size, block.size) { view = it }
        }

        when (view) {
            TradeView.CALLS -> {
                if (store.tradeOffers.isEmpty()) item {
                    Text(
                        "Nobody is calling. Clubs call in the season and at the draft room, " +
                            "most often about a man on your block.",
                        style = NdTheme.type.body, color = c.chalkDim,
                    )
                }
                store.tradeOffers.forEach { offer ->
                    val caller = book.league.team(offer.proposal.partner)
                    item(key = "call-${caller.abbrev}-${offer.target}") {
                        SituationBlock("${caller.abbrev} calling", meta = if (offer.onBlock) "About your block" else caller.name,
                            situation = Situation.TWO_MINUTE) {
                            Text("\u201C${offer.pitch.line}\u201D", style = NdTheme.type.body, color = c.chalk)
                            Text("\u2014 ${offer.pitch.speaker}", style = NdTheme.type.caption, color = c.chalkDim)
                            Text("They want", style = NdTheme.type.label, color = c.chalkDim,
                                modifier = Modifier.padding(top = NdTheme.spacing.s))
                            offer.proposal.give.mapNotNull { byId[it] }.forEach { Man(it) }
                            Text("They offer", style = NdTheme.type.label, color = c.chalkDim,
                                modifier = Modifier.padding(top = NdTheme.spacing.s))
                            offer.proposal.get.mapNotNull { byId[it] }.forEach { Man(it) }
                            offer.proposal.getPicks.forEach { Text(pickLabel(it), style = NdTheme.type.body, color = c.chalk) }
                            Text("Tap a player for his card.", style = NdTheme.type.caption, color = c.chalkDim,
                                modifier = Modifier.padding(top = NdTheme.spacing.xs))
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
                                view = TradeView.BUILD
                            }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s))
                            SecondaryButton("Not interested", { store.declineOffer(offer) },
                                Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s))
                        }
                    }
                }
            }

            TradeView.BLOCK -> item {
                SituationBlock("Your trade block", meta = if (block.isEmpty()) "nobody" else "${block.size}") {
                    if (block.isEmpty()) Text("Put a man on the block from his player card, and clubs he would help call about him.",
                        style = NdTheme.type.caption, color = c.chalkDim)
                    if (block.isNotEmpty()) Text("Tap a player for his card.", style = NdTheme.type.caption, color = c.chalkDim)
                    block.forEach { p ->
                        Man(p)
                        SecondaryButton("Take him off", { scope.launch { store.setOnBlock(p.id.v, false) } },
                            Modifier.padding(bottom = NdTheme.spacing.s))
                    }
                }
            }

            TradeView.BUILD -> {
                // The answer, pinned: it stays in sight while the rosters scroll.
                stickyHeader(key = "answer-bar") {
                    AnswerBar(
                        status = tradeStatus(give.size + givePicks.size, get.size + getPicks.size,
                            verdict?.accepted, verdict?.reasons?.firstOrNull()),
                        accepted = verdict?.accepted == true,
                        busy = store.busy,
                        onTrade = { scope.launch { if (store.trade(proposal)) clear() } },
                    )
                }

                item(key = "partner") {
                    SituationBlock("Trading with", meta = partner.abbrev) {
                        Text(partner.name, style = NdTheme.type.title, color = c.chalk)
                        SecondaryButton(if (choosingClub) "Keep ${partner.abbrev}" else "Change club",
                            { choosingClub = !choosingClub }, Modifier.padding(top = NdTheme.spacing.s))
                        if (choosingClub) {
                            FlowRow(Modifier.padding(top = NdTheme.spacing.s),
                                horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                                verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                                clubs.forEach { club ->
                                    Chip(club.abbrev, club.id == partnerId, role = Role.RadioButton) {
                                        if (club.id != partnerId) { partnerId = club.id; get = emptySet(); getPicks = emptyList() }
                                        choosingClub = false
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "table") {
                    SituationBlock(
                        "On the table",
                        meta = "send ${count(give.size, givePicks.size)}, get ${count(get.size, getPicks.size)}",
                        situation = when {
                            verdict == null -> Situation.NORMAL
                            verdict.accepted -> Situation.TWO_MINUTE
                            else -> Situation.RED_ZONE
                        },
                    ) {
                        if (proposal.empty) {
                            Text("Tap players and picks below to put them on the table.",
                                style = NdTheme.type.body, color = c.chalkDim)
                        } else {
                            // Every piece, both ways; tap one to take it off.
                            DataTable(
                                columns = listOf(ColumnSpec("", 0.8f), ColumnSpec("Piece", 3.2f, wrap = true)),
                                rows = give.mapNotNull { byId[it] }.map { p -> RowData(listOf("Send", label(p)), onClick = { give = give - p.id.v },
                                        onLongClick = { card = p.id.v }, longClickLabel = CARD) } +
                                    givePicks.map { pk -> RowData(listOf("Send", pickLabel(pk)), onClick = { givePicks = givePicks - pk }) } +
                                    get.mapNotNull { byId[it] }.map { p -> RowData(listOf("Get", label(p)), onClick = { get = get - p.id.v },
                                        onLongClick = { card = p.id.v }, longClickLabel = CARD) } +
                                    getPicks.map { pk -> RowData(listOf("Get", pickLabel(pk)), onClick = { getPicks = getPicks - pk }) },
                            )
                            Text("Tap a piece to take it off; hold a player for his card.", style = NdTheme.type.caption, color = c.chalkDim,
                                modifier = Modifier.padding(top = NdTheme.spacing.xs))
                        }
                        // What their GM says, then the facts behind it.
                        said?.let { q ->
                            Column(Modifier.padding(top = NdTheme.spacing.s)) {
                                Text("\u201C${q.line}\u201D", style = NdTheme.type.body, color = c.chalk)
                                Text("\u2014 ${q.speaker}", style = NdTheme.type.caption, color = c.chalkDim)
                            }
                        }
                        if (verdict != null && !verdict.accepted) {
                            Column(Modifier.padding(top = NdTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.xs)) {
                                verdict.reasons.forEach { Text(it, style = NdTheme.type.body, color = c.chalk) }
                                if (verdict.shortBy > 0f) {
                                    Text(shortfall(book, partner.gm.winNowVsFuture, verdict.shortBy),
                                        style = NdTheme.type.caption, color = c.chalkDim)
                                }
                            }
                        }
                        if (!proposal.empty) {
                            SecondaryButton("Clear the table", { clear() }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s))
                        }
                    }
                }

                // One roster at a time: yours, or theirs.
                item(key = "side") {
                    Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                            Chip("Your players", !theirSide, role = Role.Tab) { theirSide = false }
                            Chip("${partner.abbrev} players", theirSide, role = Role.Tab) { theirSide = true }
                        }
                        FilterChipRow(GROUPS, group, { group = it })
                    }
                }

                item(key = "roster") {
                    val side = if (theirSide) partnerId else user
                    val chosen = if (theirSide) get else give
                    val men = roster(side)
                    if (men.isEmpty()) {
                        Text("Nobody at this position.", style = NdTheme.type.caption, color = c.chalkDim)
                    } else {
                        DataTable(
                            columns = listOf(
                                ColumnSpec("Pos", 0.7f),
                                ColumnSpec("Player", 2.2f),
                                ColumnSpec("Age", 0.55f, numeric = true),
                                ColumnSpec("Ovr", 1.05f, numeric = true, tier = true),
                                ColumnSpec("Fit", 0.5f, numeric = true),
                                ColumnSpec("Cap", 1.6f, numeric = true),
                            ),
                            rows = men.map { p ->
                                RowData(
                                    listOf(
                                        p.position.label,
                                        p.name + if (p.status == PlayerStatus.IR) " (IR)" else "",
                                        "${p.age(book.year)}",
                                        rating(p),
                                        fit(p),
                                        p.contract?.let { "${money(p.capHit(book.year))} ×${years(p)}" } ?: "-",
                                    ),
                                    highlight = p.id.v in chosen,
                                    onClick = {
                                        if (theirSide) get = get.toggle(p.id.v) else give = give.toggle(p.id.v)
                                    },
                                    onLongClick = { card = p.id.v },
                                    longClickLabel = CARD,
                                )
                            },
                        )
                        Text("Tap a player to put him on the table; hold him for his card. Fit grades how he suits your schemes, A to F.",
                            style = NdTheme.type.caption, color = c.chalkDim, modifier = Modifier.padding(top = NdTheme.spacing.xs))
                    }
                }

                item(key = "picks") {
                    val side = if (theirSide) partnerId else user
                    Column {
                        PickPieces(picks(side), if (theirSide) getPicks else givePicks, ::pickLabel) { pick ->
                            if (theirSide) getPicks = getPicks.toggle(pick) else givePicks = givePicks.toggle(pick)
                        }
                        Chip("Later years' picks", laterYears, Modifier.padding(top = NdTheme.spacing.s), role = Role.Checkbox) {
                            laterYears = !laterYears
                        }
                    }
                }
            }
        }
    }
}

/** The three things the trade screen does, one at a time. */
internal enum class TradeView { CALLS, BUILD, BLOCK }

/** The tabs, each with how many things are waiting in it. */
@Composable
private fun ViewTabs(view: TradeView, calls: Int, block: Int, onSelect: (TradeView) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
        Chip(if (calls > 0) "Calls ($calls)" else "Calls", view == TradeView.CALLS) { onSelect(TradeView.CALLS) }
        Chip("Build a deal", view == TradeView.BUILD) { onSelect(TradeView.BUILD) }
        Chip(if (block > 0) "Your block ($block)" else "Your block", view == TradeView.BLOCK) { onSelect(TradeView.BLOCK) }
    }
}

/**
 * The pinned bar: where the deal stands, and the button that makes it. It
 * takes every touch that lands on it: the list scrolls underneath, and a tap
 * on the bar - its button turned off - must not reach a row hidden below.
 */
@Composable
private fun AnswerBar(status: String, accepted: Boolean, busy: Boolean, onTrade: () -> Unit) {
    val c = NdTheme.colors
    val bar = Modifier
        .fillMaxWidth()
        .background(c.turf)
        .pointerInput(Unit) { detectTapGestures { } }
        .padding(vertical = NdTheme.spacing.s)
    // Big type: the line over a full-width button. Beside the button, the
    // line had a word a line, broken mid-word, a third of the screen tall.
    if (androidx.compose.ui.platform.LocalConfiguration.current.fontScale > com.example.nflsimtext.ui.components.STACK_FONT_SCALE) {
        Column(bar, verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.xs)) {
            Text(status, style = NdTheme.type.body, color = if (accepted) c.chalk else c.chalkDim)
            PrimaryButton("Make the trade", onTrade, Modifier.fillMaxWidth(), enabled = accepted && !busy)
        }
    } else Row(bar, horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Text(status, style = NdTheme.type.body, color = if (accepted) c.chalk else c.chalkDim,
            modifier = Modifier.weight(1f))
        PrimaryButton("Make the trade", onTrade, enabled = accepted && !busy)
    }
}

/**
 * The pinned bar's one line: what is on the table and whether they would
 * take it. [accepted] is null while the table is empty; [firstReason] is the
 * first thing in the way.
 */
internal fun tradeStatus(sending: Int, getting: Int, accepted: Boolean?, firstReason: String?): String {
    if (accepted == null) return "Nothing on the table yet."
    val pieces = "Sending $sending, getting $getting."
    return if (accepted) "$pieces They'd take it." else "$pieces ${firstReason ?: "They'd say no."}"
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
/** What holding a man's row does, as a screen reader says it. */
private const val CARD = "open his card"
private val GROUPS = listOf(ALL) + com.nflsim.engine.model.PositionGroup.entries.map { it.name }

private fun money(thousands: Int): String =
    if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)
