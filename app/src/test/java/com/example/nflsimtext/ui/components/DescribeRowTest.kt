package com.example.nflsimtext.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** SPEC 11: a table row reads to a screen reader with every number named. */
class DescribeRowTest {

    private val roster = listOf(
        ColumnSpec("Pos", 0.8f),
        ColumnSpec("Player", 2.4f),
        ColumnSpec("Age", 0.8f, numeric = true),
        ColumnSpec("OVR", 0.8f, numeric = true),
    )

    @Test
    fun `names first, then each number with its column`() {
        assertEquals("QB, R. Harlan, Age 27, OVR 58-71",
            describeRow(roster, RowData(listOf("QB", "R. Harlan", "27", "58-71"))))
    }

    @Test
    fun `blank cells and unnamed columns say only what is there`() {
        val stats = listOf(ColumnSpec("", 2f), ColumnSpec("", 1f, numeric = true), ColumnSpec("IND", 1f, numeric = true))
        assertEquals("First downs, 18, IND 22", describeRow(stats, RowData(listOf("First downs", "18", "22"))))
        assertEquals("QB, R. Harlan, OVR 88", describeRow(roster, RowData(listOf("QB", "R. Harlan", " ", "88"))))
        // A short row just stops.
        assertEquals("QB", describeRow(roster, RowData(listOf("QB"))))
    }
}
