package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.ActionDialog
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.FilterChipRow
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.Player
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.SchemeFitGrade
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.PracticeSquads
import com.nflsim.engine.season.RosterMoves
import com.nflsim.engine.season.Transactions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The market between markets (SPEC 7's ten days run in the spring; this is the
 * rest of the year). Whoever is left is available for the league minimum on a
 * one-year deal, which is what a man on the street signs for: the clubs that
 * wanted to pay him more did it in the spring.
 *
 * Practice squad players are free agents their clubs happen to train, so
 * anyone may sign one to a 53 - that is where the league keeps its injury
 * replacements. Nothing here is free: the roster stops at 53, and releasing
 * charges the dead money the contract says.
 */
@Composable
fun FreeAgentsScreen(
    dynasty: Dynasty,
    store: DynastyStore,
    scope: CoroutineScope,
    onBack: () -> Unit = {},
) {
    val c = NdTheme.colors
    val team = dynasty.team
    val league = dynasty.league
    val everyone = league.roster(team.id)
    val roster = everyone.filterNot(RosterMoves::onReserve)
    val reserve = everyone.filter(RosterMoves::onReserve)
    val cost = Transactions.price(dynasty.weeksLeft)
    val squad = team.practiceSquad.map { league.player(it) }
    val space = Transactions.spaceFor(league, team.id)
    val offence = SchemeCatalog.tuned(team.offenseScheme, league.tuning)
    val defence = SchemeCatalog.tuned(team.defenseScheme, league.tuning)
    fun scheme(p: Player) = if (p.position.isOffense) offence else defence
    fun read(p: Player) = lensFor(dynasty, p).view(overall(p, scheme(p)))
    fun fit(p: Player) = SchemeFitGrade.letter(schemeFit(p, scheme(p)))
    val full = roster.size >= Transactions.ROSTER_LIMIT

    var position by remember { mutableStateOf(ALL) }
    var chosen by remember { mutableStateOf<Choice?>(null) }
    fun wanted(p: Player) = position == ALL || p.position.group.name == position

    val available = Transactions.freeAgents(league)
        .filter(::wanted)
        .sortedByDescending { read(it).point }
        .take(SHOWN)
    val poachable = Transactions.poachable(league, team.id)
        .filter { wanted(it.first) }
        .sortedByDescending { read(it.first).point }
        .take(SHOWN)

    ScreenList {
        item {
            Column {
                Text("Free agents", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "Anyone left signs for the league minimum, one year. The roster " +
                        "stops at ${Transactions.ROSTER_LIMIT}, so somebody has to go first.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        store.message?.let { note ->
            item {
                SituationBlock("Last move", situation = Situation.THIRD_DOWN) {
                    Text(note, style = NdTheme.type.body, color = c.chalk)
                    SecondaryButton(
                        "Clear", { store.dismissMessage() },
                        Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
            }
        }

        item {
            SituationBlock(
                "Your room",
                meta = "${roster.size} of ${Transactions.ROSTER_LIMIT}",
                situation = if (full) Situation.RED_ZONE else Situation.NORMAL,
            ) {
                Text(
                    "${money(space)} under the cap. A man signed now costs ${money(cost)}" +
                        if (cost < Transactions.askingPrice) ", the minimum for the weeks left." else ", the minimum.",
                    style = NdTheme.type.data, color = c.chalk,
                )
                Text(
                    "Practice squad ${squad.size} of ${PracticeSquads.SIZE}, " +
                        "${squad.count(PracticeSquads::isVeteran)} of ${PracticeSquads.VETERANS} veteran places.",
                    style = NdTheme.type.data, color = c.chalk,
                )
                if (full) {
                    Text(
                        "The roster is full. Release somebody below to make room on the 53.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                    )
                }
                // The moves are the user's; the front office makes them only if asked.
                SecondaryButton(
                    if (dynasty.frontOfficeRoster) "Front office fills injured places: on"
                    else "Let the front office fill injured places",
                    { scope.launch { store.setFrontOfficeRoster(!dynasty.frontOfficeRoster) } },
                    Modifier.padding(top = NdTheme.spacing.s),
                )
                FilterChipRow(
                    options = GROUPS,
                    selected = position,
                    onSelect = { position = it },
                    Modifier.padding(top = NdTheme.spacing.s),
                )
            }
        }

        item {
            SituationBlock("On the street", meta = "${available.size} shown") {
                PlayerTable(
                    available, dynasty, ::read, ::fit,
                    onClick = { chosen = Choice(Kind.STREET, it) },
                )
                Hint("Nobody has watched these men closely, so the read is a wide range.")
            }
        }

        item {
            SituationBlock("Other practice squads", meta = "${poachable.size} shown") {
                PlayerTable(
                    poachable.map { it.first }, dynasty, ::read, ::fit,
                    club = poachable.associate { it.first.id.v to league.team(it.second).abbrev },
                    onClick = { chosen = Choice(Kind.POACH, it) },
                )
                Hint("Signed away, a squad player has to go on your 53.")
            }
        }

        item {
            SituationBlock("Your practice squad", meta = "${squad.size} of ${PracticeSquads.SIZE}") {
                PlayerTable(
                    squad.sortedByDescending { read(it).point }, dynasty, ::read, ::fit,
                    onClick = { chosen = Choice(Kind.SQUAD, it) },
                )
            }
        }

        if (reserve.isNotEmpty()) {
            item {
                SituationBlock("Injured reserve", meta = "${reserve.size} off the 53") {
                    reserve.forEach { man ->
                        Text(
                            "${man.position.label} ${man.name} - " +
                                if (man.injuryWeeks == 0) "healthy, waiting for a place"
                                else injuryLabel(man.injuryWeeks, dynasty).lowercase(),
                            style = NdTheme.type.data, color = c.chalk,
                        )
                        if (man.injuryWeeks == 0) {
                            SecondaryButton(
                                "Bring him back",
                                { scope.launch { store.activateFromReserve(man.id.v) } },
                                enabled = !full && !store.busy,
                            )
                        }
                    }
                    Hint("Out ${RosterMoves.IR_WEEKS} weeks or more goes on reserve: still paid, not on the 53. " +
                        "A healed man comes back on his own while there is a place for him.")
                }
            }
        }

        item {
            SituationBlock("Your roster", meta = "Tap to release") {
                PlayerTable(
                    roster.sortedBy { read(it).point }, dynasty, ::read, ::fit,
                    cap = true,
                    onClick = { chosen = Choice(Kind.ROSTER, it) },
                )
                Hint("Worst first, as the club reads them.")
            }
        }

        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }

    val choice = chosen
    if (choice != null) {
        val man = league.player(com.nflsim.engine.model.PlayerId(choice.id))
        fun act(move: suspend () -> Unit) {
            scope.launch { move() }
            chosen = null
        }
        val title = "${man.position.label} ${man.name}"
        ActionDialog(
            title,
            onDismiss = { chosen = null },
            situation = if (choice.kind == Kind.ROSTER) Situation.RED_ZONE else Situation.NORMAL,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                Text(
                    "${man.age(dynasty.year)}, ${man.archetype.label}. Your read: ${read(man).text}, fit ${fit(man)}.",
                    style = NdTheme.type.body, color = c.chalk,
                )
                when (choice.kind) {
                    Kind.STREET -> {
                        PrimaryButton(
                            "Sign to the 53 for ${money(cost)}",
                            { act { store.signFreeAgent(choice.id) } },
                            enabled = !full && !store.busy,
                        )
                        SecondaryButton(
                            "Sign to the practice squad",
                            { act { store.signToPracticeSquad(choice.id) } },
                        )
                    }
                    Kind.POACH -> PrimaryButton(
                        "Sign him away to the 53 for ${money(cost)}",
                        { act { store.signFreeAgent(choice.id) } },
                        enabled = !full && !store.busy,
                    )
                    Kind.SQUAD -> {
                        PrimaryButton(
                            "Promote to the 53 for ${money(cost)}",
                            { act { store.signFreeAgent(choice.id) } },
                            enabled = !full && !store.busy,
                        )
                        SecondaryButton(
                            "Release from the squad",
                            { act { store.releaseFromPracticeSquad(choice.id) } },
                        )
                    }
                    Kind.ROSTER -> {
                        val dead = man.contract?.deadCap(dynasty.year)
                        Text(
                            if (dead == null || (dead.thisYear == 0 && dead.nextYear == 0)) {
                                "Nothing is owed. His spot comes free."
                            } else {
                                "${money(dead.thisYear)} stays on this year's cap" +
                                    if (dead.nextYear > 0) ", ${money(dead.nextYear)} on next year's." else "."
                            },
                            style = NdTheme.type.body, color = c.chalk,
                        )
                        PrimaryButton(
                            "Release him",
                            { act { store.releasePlayer(choice.id) } },
                            enabled = !store.busy,
                        )
                    }
                }
                if (full && choice.kind != Kind.ROSTER) {
                    Text(
                        "The 53 is full: release somebody first.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                    )
                }
                SecondaryButton("Never mind", { chosen = null })
            }
        }
    }
}

@Composable
private fun PlayerTable(
    players: List<Player>,
    dynasty: Dynasty,
    read: (Player) -> com.nflsim.engine.ratings.RatingView,
    fit: (Player) -> String,
    club: Map<Int, String>? = null,
    cap: Boolean = false,
    onClick: (Int) -> Unit,
) {
    if (players.isEmpty()) {
        Hint("Nobody here at this position.")
        return
    }
    DataTable(
        columns = listOfNotNull(
            ColumnSpec("Pos", 0.9f),
            ColumnSpec("Player", 2.2f),
            club?.let { ColumnSpec("Club", 0.8f) },
            ColumnSpec("Age", 0.6f, numeric = true),
            ColumnSpec("Ovr", 1.1f, numeric = true, tier = true),
            if (cap) ColumnSpec("Cap", 1.1f, numeric = true) else ColumnSpec("Fit", 0.6f, numeric = true),
        ),
        rows = players.map { man ->
            RowData(
                listOfNotNull(
                    man.position.label,
                    man.name,
                    club?.get(man.id.v),
                    "${man.age(dynasty.year)}",
                    read(man).text,
                    if (cap) money(man.capHit(dynasty.year)) else fit(man),
                ),
                onClick = { onClick(man.id.v) },
            )
        },
    )
}

@Composable
private fun Hint(text: String) = Text(
    text, style = NdTheme.type.caption, color = NdTheme.colors.chalkDim,
    modifier = Modifier.padding(top = NdTheme.spacing.s),
)

private enum class Kind { STREET, POACH, SQUAD, ROSTER }
private data class Choice(val kind: Kind, val id: Int)

private fun money(thousands: Int): String =
    if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)

private const val ALL = "All"
private const val SHOWN = 25

private val GROUPS = listOf(ALL) + com.nflsim.engine.model.Position.entries
    .map { it.group.name }
    .distinct()
