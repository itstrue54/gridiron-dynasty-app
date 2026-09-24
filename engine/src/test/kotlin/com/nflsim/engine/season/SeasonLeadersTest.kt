package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.stats.StatLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeasonLeadersTest {

    private val league by lazy { LeagueGenerator.generate(2026, 5L) }

    @Test
    fun `the leader is whoever has the most, and nobody leads with nothing`() {
        val (a, b) = league.players.take(2).map { it.id.v }
        val stats = mapOf(a to StatLine(passYards = 300, sacks = 0), b to StatLine(passYards = 410, rushYards = 12))
        val leaders = SeasonLeaders.of(stats, league).associateBy { it.category }
        assertEquals(b, leaders.getValue("Passing yards").player)
        assertEquals(410, leaders.getValue("Passing yards").value)
        assertEquals(b, leaders.getValue("Rushing yards").player)
        assertTrue("Sacks" !in leaders && "Interceptions" !in leaders, "a zero leads nothing: ${leaders.keys}")
    }
}
