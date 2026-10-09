package com.example.nflsimtext.ui

import com.nflsim.engine.stats.StatLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** The defenders' tally, worst first. */
class DefenderTallyTest {

    @Test
    fun `defenders read worst first, and a man with no reps counted is left out`() {
        val lines = mapOf(
            1 to StatLine(coverageWins = 3, sacks = 1),
            2 to StatLine(timesBeaten = 2, coverageWins = 1),
            3 to StatLine(defensiveFlags = 1, timesBeaten = 1),
            4 to StatLine(tackles = 6),
            5 to StatLine(timesBeaten = 2, stuffs = 2, interceptions = 1),
        )
        val rows = defenderRows(lines, { "Man $it" })
        // Three men lost two reps each: the one who made fewest plays reads first.
        assertEquals(listOf("Man 3", "Man 2", "Man 5", "Man 1"), rows.map { it.cells[0] })
        assertEquals(listOf("Man 3", "0", "1", "1"), rows[0].cells)
        assertEquals(listOf("Man 5", "3", "2", "0"), rows[2].cells)
    }
}
