package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.example.nflsimtext.ui.components.StatusTag
import com.example.nflsimtext.ui.components.TagTone
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.nflsim.engine.offseason.CutdownPause
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.season.Dynasty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Camp (SPEC 7 phases 10-11): the club fills its own camp off the street
 * and makes its own cut to 53. Every cut costs what the contract says; every
 * signing is a year at the minimum. The front office will do both, the way
 * it does for every other club, if asked.
 */
@Composable
fun CutdownScreen(
    dynasty: Dynasty,
    store: DynastyStore,
    scope: CoroutineScope,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val c = NdTheme.colors
    val pause = store.cutdown
    if (pause == null) {
        Column(Modifier.padding(NdTheme.spacing.xl)) {
            Text("Camp has not opened.", style = NdTheme.type.title, color = c.chalk)
            SecondaryButton("Back to the hub", onBack, Modifier.padding(top = NdTheme.spacing.m))
        }
        return
    }
    // The calls are the user's: nobody is cut or signed until he says so.
    val release = remember(pause) { mutableStateListOf<Int>() }
    val sign = remember(pause) { mutableStateListOf<Int>() }
    var position by remember { mutableStateOf(ALL) }

    val count = pause.roster.size - release.size + sign.size
    val dead = pause.roster.filter { it.id.v in release }.sumOf { pause.deadIfCut(it) }
    val freed = pause.roster.filter { it.id.v in release }.sumOf { it.capHit(pause.year) - pause.deadIfCut(it) }
    val room = pause.capSpace + freed - sign.size * com.nflsim.engine.model.Contract.MIN_BASE_SALARY
    val legal = count in CutdownPause.GAME_DAY..CutdownPause.ROSTER_LIMIT
    val suggested = pause.suggested
    val offence = SchemeCatalog.tuned(dynasty.team.offenseScheme, dynasty.league.tuning)
    val defence = SchemeCatalog.tuned(dynasty.team.defenseScheme, dynasty.league.tuning)
    fun read(p: Player) = lensFor(dynasty, p).view(overall(p, if (p.position.isOffense) offence else defence)).text
    // What camp did, as the staff reads it: the same lens before and after.
    fun point(p: Player) = lensFor(dynasty, p).view(overall(p, if (p.position.isOffense) offence else defence)).point
    val camp = remember(pause) { campReport(pause.campBefore, pause.roster, ::point) }
    val hurt = pause.campInjuries.filter { it.team == pause.userTeam.v }.associateBy { it.player }
    fun wanted(p: Player) = position == ALL || p.position.group.name == position
    fun done(cut: CutdownPause.Cut?) { scope.launch { store.finishCamp(cut); if (store.dynasty?.phase != com.nflsim.engine.season.DynastyPhase.OFFSEASON) onDone() } }

    ScreenList {
        item {
            Column {
                Text("Training camp", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "${pause.roster.size} in camp. Cut to ${CutdownPause.ROSTER_LIMIT} - and no fewer than " +
                        "${CutdownPause.GAME_DAY}, the most a club dresses - and sign off the street to fill " +
                        "a thin room. Every cut costs what his contract says; a signing is a year at the minimum.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        // What camp did to each position group, and who it hurt.
        item {
            SituationBlock("What camp showed", meta = "by position group") {
                DataTable(
                    columns = listOf(
                        ColumnSpec("Group", 0.9f),
                        ColumnSpec("Men", 0.6f, numeric = true),
                        ColumnSpec("Change", 0.9f, numeric = true),
                        ColumnSpec("Most improved", 2.4f, wrap = true),
                    ),
                    rows = camp.map { g ->
                        RowData(listOf(
                            g.group.name, "${g.men}", signed(g.change),
                            g.riser?.let { (p, d) -> "${p.lastName} ${signed(d.toFloat())}" } ?: "-",
                        ))
                    },
                )
                camp.mapNotNull { it.faller }.filter { it.second < 0 }.sortedBy { it.second }.take(CAMP_FALLERS).let { fallers ->
                    if (fallers.isNotEmpty()) Text(
                        "Went backwards: " + fallers.joinToString { (p, d) -> "${p.position.label} ${p.name} $d" } + ".",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.xs),
                    )
                }
                Text(
                    "Change is the average move in your staff's read of the group's men in your schemes, from " +
                        "reporting day to now: the young grow, the old slip.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                    modifier = Modifier.padding(top = NdTheme.spacing.xs),
                )
                if (hurt.isNotEmpty()) {
                    Text("Hurt in camp", style = NdTheme.type.label, color = c.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.s))
                    pause.roster.filter { it.id.v in hurt }.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = NdTheme.spacing.xs)) {
                            StatusTag("Injury", TagTone.URGENT)
                            Text("${p.position.label} ${p.name}: ${injuryLength(hurt.getValue(p.id.v).gamesOut)}",
                                style = NdTheme.type.body, color = c.chalk,
                                modifier = Modifier.padding(start = NdTheme.spacing.s))
                        }
                    }
                } else {
                    Text("Nobody hurt in camp.", style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.s))
                }
            }
        }

        item {
            SituationBlock("Rather not?", divider = false) {
                Text(
                    "Take the suggestions in one go, or hand the camp to your front office and it will " +
                        "fill and cut the way it does for every other club.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                )
                FlowRow(
                    Modifier.padding(top = NdTheme.spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                    verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                ) {
                    SecondaryButton("Take the suggestions", {
                        release.clear(); release.addAll(suggested.release)
                        sign.clear(); sign.addAll(suggested.sign)
                    })
                    SecondaryButton("Let the front office fill and cut", { done(null) }, enabled = !store.busy)
                }
            }
        }

        item {
            SituationBlock(
                "The 53", meta = "$count",
                situation = if (legal) Situation.NORMAL else Situation.RED_ZONE,
            ) {
                Text(
                    "$count on the roster: ${release.size} cut, ${sign.size} signed. ${dealMoney(room)} of cap " +
                        "room after" + if (dead > 0) ", with ${dealMoney(dead)} of dead money." else ".",
                    style = NdTheme.type.data, color = c.chalk,
                )
                val empty = pause.mustField.filter { pos ->
                    (pause.roster.filter { it.id.v !in release } + pause.pool.filter { it.id.v in sign })
                        .none { it.position == pos }
                }
                if (empty.isNotEmpty()) {
                    Text(
                        "No ${empty.joinToString { it.label }}: every club has to field one, so the front " +
                            "office will sign one off the street.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                    )
                }
                PrimaryButton(
                    when {
                        count > CutdownPause.ROSTER_LIMIT -> "${count - CutdownPause.ROSTER_LIMIT} more to cut"
                        count < CutdownPause.GAME_DAY -> "${CutdownPause.GAME_DAY - count} short of the fewest a club dresses"
                        else -> "Set the $count and finish the offseason"
                    },
                    { done(CutdownPause.Cut(release.toSet(), sign.toSet())) },
                    Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s),
                    enabled = legal && !store.busy,
                )
            }
        }

        item { FilterChipRow(GROUPS, position, { position = it }) }

        item {
            SituationBlock("Your camp", meta = "Tap to cut") {
                val shown = pause.roster.filter(::wanted).sortedWith(
                    compareBy<Player>({ it.position.ordinal }, { -pause.value(it) }))
                DataTable(
                    columns = listOf(
                        ColumnSpec("Pos", 0.8f),
                        ColumnSpec("Player", 2.2f),
                        ColumnSpec("Ovr", 1.1f, numeric = true, tier = true),
                        ColumnSpec("Cap", 1.0f, numeric = true),
                        ColumnSpec("Dead", 1.0f, numeric = true),
                    ),
                    rows = shown.map { p ->
                        val cutting = p.id.v in release
                        RowData(
                            listOf(
                                p.position.label,
                                // The highlight says he is being cut; the star leads so a
                                // long name cannot push it off the end.
                                (if (p.id.v in suggested.release) "★ " else "") + p.name +
                                    (hurt[p.id.v]?.let { " (hurt)" } ?: ""),
                                read(p),
                                dealMoney(p.capHit(pause.year)),
                                dealMoney(pause.deadIfCut(p)),
                            ),
                            highlight = cutting,
                            onClick = { if (cutting) release.remove(p.id.v) else release.add(p.id.v) },
                        )
                    },
                )
                Text(
                    "Highlighted: being cut. ★ a suggested cut: the ones the front office would let go, " +
                        "counting what each costs to release. Tap a man to cut him, and again to keep him.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                    modifier = Modifier.padding(top = NdTheme.spacing.s),
                )
                shown.filter { it.id.v in suggested.release }.take(SUGGESTION_REASONS).forEach { p ->
                    Text(
                        "${p.position.label} ${p.name}: ${pause.whyCut(p)}",
                        style = NdTheme.type.caption, color = c.chalkDim,
                    )
                }
            }
        }

        item {
            SituationBlock("The street", meta = "Tap to sign") {
                val street = pause.pool.filter(::wanted).take(STREET_SHOWN)
                DataTable(
                    columns = listOf(
                        ColumnSpec("Pos", 0.8f),
                        ColumnSpec("Player", 2.4f),
                        ColumnSpec("Age", 0.7f, numeric = true),
                        ColumnSpec("Ovr", 1.1f, numeric = true, tier = true),
                    ),
                    rows = street.map { p ->
                        val signing = p.id.v in sign
                        RowData(
                            listOf(
                                p.position.label,
                                (if (p.id.v in suggested.sign) "★ " else "") + p.name,
                                "${p.age(pause.year)}",
                                read(p),
                            ),
                            highlight = signing,
                            onClick = { if (signing) sign.remove(p.id.v) else sign.add(p.id.v) },
                        )
                    },
                )
                Text(
                    "Undrafted men and veterans nobody signed, the best for your schemes first. Highlighted: " +
                        "signed. ★ the ones the front office would bring to camp. A year at the minimum each.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                    modifier = Modifier.padding(top = NdTheme.spacing.s),
                )
            }
        }

        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}

/** One position group's camp: how many men, the average move in the staff's read, and its biggest riser and faller. */
internal data class GroupCamp(
    val group: com.nflsim.engine.model.PositionGroup,
    val men: Int,
    val change: Float,
    val riser: Pair<Player, Int>?,
    val faller: Pair<Player, Int>?,
)

/**
 * What camp did to the men who reported ([before]) and are still in camp
 * ([after]), group by group in the order the roster lists them: each man's
 * move in [read], the staff's read of him.
 */
internal fun campReport(before: List<Player>, after: List<Player>, read: (Player) -> Int): List<GroupCamp> {
    val was = before.associateBy { it.id.v }
    val moves = after.mapNotNull { p -> was[p.id.v]?.let { p to read(p) - read(it) } }
    return moves.groupBy { it.first.position.group }.toSortedMap(compareBy { it.ordinal }).map { (group, men) ->
        GroupCamp(
            group, men.size,
            men.sumOf { it.second }.toFloat() / men.size,
            men.maxByOrNull { it.second }?.takeIf { it.second > 0 },
            men.minByOrNull { it.second },
        )
    }
}

/** A change with its sign: +1.5, -2, 0. */
internal fun signed(x: Float): String {
    val r = kotlin.math.round(x * 10f) / 10f
    val text = if (r == r.toInt().toFloat()) "${r.toInt()}" else "$r"
    return if (r > 0f) "+$text" else text
}

private const val CAMP_FALLERS = 4
private const val ALL = "All"
private const val STREET_SHOWN = 30
private const val SUGGESTION_REASONS = 8
private val GROUPS = listOf(ALL) + com.nflsim.engine.model.Position.entries.map { it.group.name }.distinct()
