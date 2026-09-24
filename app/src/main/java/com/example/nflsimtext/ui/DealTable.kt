package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.offseason.ContractOptions

/**
 * Every way to write a deal, side by side, so the choice is between
 * numbers rather than labels. The row picked is highlighted; tap another
 * to change it.
 */
@Composable
internal fun DealTable(
    deals: List<ContractOptions.Deal>,
    picked: ContractOptions.Deal?,
    recommended: ContractOptions.Deal?,
    onPick: (ContractOptions.Deal) -> Unit,
) {
    val c = NdTheme.colors
    Column {
        DataTable(
            columns = listOf(
                ColumnSpec("Deal", 1.5f),
                ColumnSpec("A year", 1.15f, numeric = true),
                ColumnSpec("Now", 1.15f, numeric = true),
                ColumnSpec("Next", 1.15f, numeric = true),
                ColumnSpec("Dead*", 1.15f, numeric = true),
            ),
            rows = deals.map { d ->
                val star = if (d == recommended) " ★" else ""
                RowData(
                    listOf(
                        "${d.years}y ${shortLabel(d.structure)}$star",
                        dealMoney(d.annual),
                        dealMoney(d.capNow),
                        if (d.years > 1) dealMoney(d.capNext) else "-",
                        if (d.years > 1) dealMoney(d.deadIfCutNextYear) else "-",
                    ),
                    highlight = d == picked,
                    onClick = { onPick(d) },
                )
            },
        )
        Text(
            "★ the recommendation. *Dead money if you cut him next year. Cap-light costs the least now " +
                "and leaves the most behind; pay as you go is the reverse.",
            style = NdTheme.type.caption, color = c.chalkDim,
            modifier = Modifier.padding(top = NdTheme.spacing.xs),
        )
    }
}

private fun shortLabel(s: ContractOptions.Structure) = when (s) {
    ContractOptions.Structure.STANDARD -> "standard"
    ContractOptions.Structure.CAP_LIGHT -> "cap-light"
    ContractOptions.Structure.PAY_AS_YOU_GO -> "pay-go"
}

internal fun dealMoney(thousands: Int): String =
    if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)
