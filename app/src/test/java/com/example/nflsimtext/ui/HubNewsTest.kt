package com.example.nflsimtext.ui

import com.nflsim.engine.model.NewsEvent
import com.nflsim.engine.model.NewsKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hub's five lines should be a week, not four benchings. */
class HubNewsTest {

    private fun item(kind: NewsKind, n: Int) = NewsEvent(1, kind, "$kind $n")

    @Test
    fun `no more than two of any one kind, newest first`() {
        val week = listOf(
            item(NewsKind.PERFORMANCE, 1), item(NewsKind.MILESTONE, 1),
            item(NewsKind.INJURY, 1), item(NewsKind.DISPUTE, 1),
            item(NewsKind.BENCHING, 1), item(NewsKind.BENCHING, 2),
            item(NewsKind.BENCHING, 3), item(NewsKind.BENCHING, 4),
        )
        val shown = hubNews(week)
        assertEquals(5, shown.size)
        assertEquals("benchings should not take the block", 2, shown.count { it.kind == NewsKind.BENCHING })
        assertTrue("the block should carry a mix: $shown", shown.map { it.kind }.distinct().size >= 3)
        // Newest first: the last benching filed leads the benchings.
        assertEquals("BENCHING 4", shown.first { it.kind == NewsKind.BENCHING }.headline)
    }

    @Test
    fun `a quiet week shows what there is`() {
        assertEquals(emptyList<NewsEvent>(), hubNews(emptyList()))
        val two = listOf(item(NewsKind.INJURY, 1), item(NewsKind.PERFORMANCE, 1))
        assertEquals(2, hubNews(two).size)
    }
}
