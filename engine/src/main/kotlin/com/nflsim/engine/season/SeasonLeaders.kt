package com.nflsim.engine.season

import com.nflsim.engine.model.League
import com.nflsim.engine.model.LeaderEntry
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.stats.StatLine

/**
 * Who led the league in the numbers a season is remembered by (SPEC 9.2).
 * Filed with the season at the new year, and read mid-season by anything
 * that wants the leaders so far.
 */
object SeasonLeaders {

    fun of(stats: Map<Int, StatLine>, league: League): List<LeaderEntry> {
        fun leader(category: String, of: (StatLine) -> Int): LeaderEntry? {
            val top = stats.entries.maxByOrNull { of(it.value) } ?: return null
            val id = top.key
            val line = top.value
            if (of(line) <= 0) return null
            val name = league.playersById[PlayerId(id)]?.name ?: return null
            return LeaderEntry(category, id, name, of(line))
        }
        return listOfNotNull(
            leader("Passing yards") { it.passYards },
            leader("Rushing yards") { it.rushYards },
            leader("Receiving yards") { it.receivingYards },
            leader("Sacks") { it.sacks },
            leader("Interceptions") { it.interceptions },
        )
    }
}
