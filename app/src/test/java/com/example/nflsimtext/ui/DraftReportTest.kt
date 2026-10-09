package com.example.nflsimtext.ui

import com.nflsim.engine.model.Position
import com.nflsim.engine.ratings.OverallWeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A prospect's scouting report leads with what his position is judged on. */
class DraftReportTest {

    @Test
    fun `every position's report shows its six weightiest ratings, heaviest first`() {
        Position.entries.forEach { pos ->
            val weights = OverallWeights.forPosition(pos)
            val shown = keyRatings(pos)
            assertEquals(pos.name, minOf(6, weights.size), shown.size)
            assertEquals(pos.name, shown.size, shown.distinct().size)
            val w = shown.map { weights.getValue(it) }
            assertEquals("$pos heaviest first", w.sortedDescending(), w)
            // Nothing left off outweighs what is shown.
            val rest = weights.filterKeys { it !in shown }.values
            assertTrue(pos.name, rest.all { it <= w.last() })
        }
    }
}
