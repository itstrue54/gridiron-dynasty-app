package com.example.nflsimtext.ui

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
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.FilterChipRow
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.Transaction
import com.nflsim.engine.model.TransactionKind
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.Schedule

/**
 * The transactions wire (SPEC 4.7): every signing, release, trade, pick,
 * reserve move and retirement the league has made, kept forever (SPEC 9.2).
 * One season at a time, newest first.
 */
@Composable
fun TransactionsScreen(dynasty: Dynasty, onBack: () -> Unit = {}) {
    val c = NdTheme.colors
    val league = dynasty.league
    val wire = league.transactions
    val abbrev = league.teams.associate { it.id.v to it.abbrev }
    val years = wire.map { it.year }.distinct().sortedDescending()

    var whose by remember { mutableStateOf(YOURS) }
    var year by remember(years.firstOrNull()) { mutableStateOf(years.firstOrNull()) }
    var kind by remember { mutableStateOf(ALL) }

    val shown = wire.filter { line ->
        line.year == year &&
            (whose == LEAGUE || line.team == dynasty.userTeam || line.other == dynasty.userTeam) &&
            (kind == ALL || line.kind in KINDS.getValue(kind))
    }
    val byWeek = shown.groupBy { it.week }.toSortedMap(compareByDescending { it })

    ScreenList {
        item {
            Column {
                Text("Transactions", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "Every move the league has made, and kept. Newest first.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        item {
            Column {
                FilterChipRow(listOf(YOURS, LEAGUE), whose, { whose = it })
                if (years.size > 1) {
                    FilterChipRow(
                        years.map { "$it" }, "$year", { year = it.toInt() },
                        Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
                FilterChipRow(
                    listOf(ALL) + KINDS.keys, kind, { kind = it },
                    Modifier.padding(top = NdTheme.spacing.s),
                )
            }
        }

        if (byWeek.isEmpty()) {
            item {
                Text(
                    if (wire.isEmpty()) "Nothing on the wire yet. Moves are kept from here on."
                    else "No moves like that this season.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        byWeek.forEach { (week, lines) ->
            item {
                SituationBlock(weekTitle(week), meta = "${lines.size}") {
                    DataTable(
                        columns = listOfNotNull(
                            if (whose == LEAGUE) ColumnSpec("Club", 0.7f) else null,
                            ColumnSpec("Player", 2.7f),
                            ColumnSpec("Move", 1.5f),
                            ColumnSpec("Terms", 1.2f, numeric = true),
                        ),
                        rows = lines.take(ROWS).map { line ->
                            RowData(listOfNotNull(
                                if (whose == LEAGUE) abbrev[line.team] ?: "-" else null,
                                "${line.position} ${line.name}",
                                line.kind.short,
                                terms(line, abbrev),
                            ))
                        },
                    )
                    if (lines.size > ROWS) {
                        Text(
                            "And ${lines.size - ROWS} more. Pick a kind of move to see them.",
                            style = NdTheme.type.caption, color = c.chalkDim,
                            modifier = Modifier.padding(top = NdTheme.spacing.s),
                        )
                    }
                }
            }
        }

        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}

/** A week in a column: "Wk 6", or when in the year it was. */
internal fun weekShort(week: Int): String = when {
    week == 0 -> "Spring"
    week > Schedule.WEEKS -> "Post"
    else -> "Wk $week"
}

private fun weekTitle(week: Int): String = when {
    week == 0 -> "The offseason"
    week > Schedule.WEEKS -> "After the regular season"
    else -> "Before week $week"
}

/** The part of a move a reader wants next to it: money, a pick, a club. */
internal fun terms(line: Transaction, abbrev: Map<Int, String>): String = when (line.kind) {
    TransactionKind.SIGNED ->
        if (line.years > 1) "${money(line.amount)} x ${line.years}" else money(line.amount)
    TransactionKind.PROMOTED -> money(line.amount)
    TransactionKind.SIGNED_OFF_SQUAD -> "${money(line.amount)}, ${abbrev[line.other] ?: ""}"
    TransactionKind.RELEASED -> if (line.amount > 0) "${money(line.amount)} dead" else ""
    TransactionKind.DRAFTED -> "Rd ${line.years}, #${line.amount}"
    TransactionKind.TRADED -> "from ${abbrev[line.other] ?: "?"}"
    else -> ""
}

private fun money(thousands: Int): String =
    if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)

private const val YOURS = "Your club"
private const val LEAGUE = "League"
private const val ALL = "All"
/** A season's offseason runs to a thousand lines; a week's block shows this many. */
private const val ROWS = 60

private val KINDS: Map<String, Set<TransactionKind>> = linkedMapOf(
    "Signings" to setOf(
        TransactionKind.SIGNED, TransactionKind.PROMOTED,
        TransactionKind.SIGNED_OFF_SQUAD, TransactionKind.TO_SQUAD,
    ),
    "Releases" to setOf(TransactionKind.RELEASED, TransactionKind.OFF_SQUAD),
    "Reserve" to setOf(TransactionKind.INJURED_RESERVE, TransactionKind.ACTIVATED),
    "Draft" to setOf(TransactionKind.DRAFTED),
    "Trades" to setOf(TransactionKind.TRADED),
    "Retired" to setOf(TransactionKind.RETIRED),
)
