package com.example.nflsimtext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.ratingColor

/**
 * A column: what it is called, how wide it sits, whether it holds numbers, and
 * whether those numbers are ratings that should carry their tier colour.
 */
data class ColumnSpec(
    val label: String,
    val weight: Float,
    val numeric: Boolean = false,
    val tier: Boolean = false,
)

/** A row of already-formatted cells. */
data class RowData(
    val cells: List<String>,
    val highlight: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/** Which column the table is sorted by, and which way. */
data class SortState(val column: Int, val descending: Boolean = true)

/**
 * Numbers right-aligned in tabular figures, names left, a 1dp rule between
 * rows and no rounding: a spreadsheet with good typography (docs/DESIGN.md 5).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataTable(
    columns: List<ColumnSpec>,
    rows: List<RowData>,
    modifier: Modifier = Modifier,
    sort: SortState? = null,
    onSort: ((Int) -> Unit)? = null,
) {
    val c = NdTheme.colors
    val stacked = LocalConfiguration.current.fontScale > 1.3f
    Column(modifier.fillMaxWidth()) {
        // Stacked rows carry their own labels, so a header that no longer lines
        // up with anything is noise - unless it is the only way to sort.
        val header = !stacked || onSort != null
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (header) 32.dp else 0.dp)
                .padding(horizontal = NdTheme.spacing.xs)
                .then(if (stacked) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!header) return@Row
            columns.forEachIndexed { i, col ->
                val active = sort?.column == i
                val arrow = if (!active) "" else if (sort.descending) " ▼" else " ▲"
                Text(
                    col.label + arrow,
                    style = NdTheme.type.label,
                    color = if (active) c.chalk else c.chalkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (col.numeric) TextAlign.End else TextAlign.Start,
                    modifier = Modifier
                        .then(
                            if (stacked) Modifier.padding(end = NdTheme.spacing.m)
                            else Modifier.weight(col.weight)
                        )
                        .then(if (onSort != null) Modifier.clickable { onSort(i) } else Modifier),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.turfLine))
        rows.forEach { row ->
            val rowModifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (stacked) NdTheme.spacing.twoLineHeight else NdTheme.spacing.rowHeight)
                .background(if (row.highlight) c.stripe else c.turfRaised)
                .then(if (row.onClick != null) Modifier.clickable { row.onClick.invoke() } else Modifier)
                .padding(horizontal = NdTheme.spacing.xs)
            fun ink(col: ColumnSpec, cell: String): Color = when {
                row.highlight -> c.onStripe
                col.tier -> cell.takeWhile { it.isDigit() }.toIntOrNull()
                    ?.let { ratingColor(it, c) } ?: c.chalk
                else -> c.chalk
            }
            if (stacked) {
                // Big type: names on one line, numbers on the next, so nothing
                // is squeezed out of the row (docs/DESIGN.md 5).
                Column(rowModifier, verticalArrangement = Arrangement.Center) {
                    Text(
                        columns.indices
                            .filterNot { columns[it].numeric }
                            .map { row.cells.getOrElse(it) { "" } }
                            .filter { it.isNotBlank() }
                            .joinToString("  "),
                        style = NdTheme.type.data,
                        color = if (row.highlight) c.onStripe else c.chalk,
                    )
                    // Wrap rather than run off the edge: at 2.0 the numbers of a
                    // roster row do not fit one line.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.m)) {
                        columns.indices.filter { columns[it].numeric }.forEach { i ->
                            val cell = row.cells.getOrElse(i) { "" }
                            Row(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.xs)) {
                                Text(
                                    columns[i].label,
                                    style = NdTheme.type.caption,
                                    color = if (row.highlight) c.onStripe else c.chalkDim,
                                )
                                Text(cell, style = NdTheme.type.data, color = ink(columns[i], cell))
                            }
                        }
                    }
                }
            } else {
                Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                    columns.forEachIndexed { i, col ->
                        val cell = row.cells.getOrElse(i) { "" }
                        Text(
                            cell,
                            style = NdTheme.type.data,
                            color = ink(col, cell),
                            maxLines = 1,
                            // A clipped name should read as clipped, not as somebody else.
                            overflow = TextOverflow.Ellipsis,
                            textAlign = if (col.numeric) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.weight(col.weight),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.turfLine))
        }
    }
}

private val demoColumns = listOf(
    ColumnSpec("Pos", 0.8f),
    ColumnSpec("Player", 2.4f),
    ColumnSpec("Age", 0.8f, numeric = true),
    ColumnSpec("OVR", 0.8f, numeric = true),
    ColumnSpec("Pot", 0.8f, numeric = true),
)

private val demoRows = listOf(
    RowData(listOf("QB", "R. Harlan", "27", "88", "90"), highlight = true),
    RowData(listOf("WR", "D. Okafor", "24", "84", "91")),
    RowData(listOf("RB", "T. Dunn", "29", "78", "78")),
)

@Preview(name = "Night")
@Composable
private fun TableNight() = PreviewFrame(dark = true) {
    DataTable(demoColumns, demoRows, sort = SortState(3), onSort = {})
}

@Preview(name = "Day")
@Composable
private fun TableDay() = PreviewFrame(dark = false) {
    DataTable(demoColumns, demoRows, sort = SortState(3), onSort = {})
}

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun TableLarge() = PreviewFrame(dark = true) {
    DataTable(demoColumns, demoRows, sort = SortState(3), onSort = {})
}
