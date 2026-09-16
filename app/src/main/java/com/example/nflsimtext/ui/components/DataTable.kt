package com.example.nflsimtext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.theme.NdTheme

/** A column: what it is called, how wide it sits, and whether it holds numbers. */
data class ColumnSpec(val label: String, val weight: Float, val numeric: Boolean = false)

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
@Composable
fun DataTable(
    columns: List<ColumnSpec>,
    rows: List<RowData>,
    modifier: Modifier = Modifier,
    sort: SortState? = null,
    onSort: ((Int) -> Unit)? = null,
) {
    val c = NdTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            columns.forEachIndexed { i, col ->
                val active = sort?.column == i
                val arrow = if (!active) "" else if (sort.descending) " ▼" else " ▲"
                Text(
                    col.label + arrow,
                    style = NdTheme.type.label,
                    color = if (active) c.chalk else c.chalkDim,
                    maxLines = 1,
                    textAlign = if (col.numeric) TextAlign.End else TextAlign.Start,
                    modifier = Modifier
                        .weight(col.weight)
                        .then(if (onSort != null) Modifier.clickable { onSort(i) } else Modifier)
                        .padding(horizontal = NdTheme.spacing.xs),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.turfLine))
        rows.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = NdTheme.spacing.rowHeight)
                    .background(if (row.highlight) c.stripe else c.turfRaised)
                    .then(if (row.onClick != null) Modifier.clickable { row.onClick.invoke() } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                columns.forEachIndexed { i, col ->
                    Text(
                        row.cells.getOrElse(i) { "" },
                        style = NdTheme.type.data,
                        color = if (row.highlight) c.onStripe else c.chalk,
                        maxLines = 1,
                        textAlign = if (col.numeric) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.weight(col.weight).padding(horizontal = NdTheme.spacing.xs),
                    )
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
