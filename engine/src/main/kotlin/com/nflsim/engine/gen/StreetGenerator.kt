package com.nflsim.engine.gen

import com.nflsim.engine.model.League
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.rng.SplitMixRng

/**
 * A new league's street: the unsigned men a club signs when reserve opens a
 * place (SPEC 6.1). An offseason leaves `ai.freeAgentPool` of them behind;
 * a league that has not had one yet gets the same number here, or its first
 * season would have nobody to sign but other clubs' practice squads.
 *
 * They are made as the squads' camp bodies are - undrafted-rookie ratings
 * and ages - and spread over the positions as a 53-man roster is, so the
 * wire holds a quarterback or a long snapper when a club needs one.
 *
 * The draws come from a stream of their own, so every roster and practice
 * squad comes out exactly as it did before the street existed.
 */
object StreetGenerator {

    /** One position per roster place, in the template's order: the street's mix. */
    private val MIX = RosterGenerator.TEMPLATE.flatMap { (position, slots) -> List(slots.size) { position } }

    fun fill(league: League, seed: Long): League {
        val ai = league.tuning.ai
        val rng = SplitMixRng(seed).split("street|${league.year}")
        var nextId = (league.players.maxOfOrNull { it.id.v } ?: 0) + 1
        val street = List(ai.freeAgentPool) { i ->
            val own = rng.split("man|$i")
            PlayerGenerator.generate(
                PlayerId(nextId++), MIX[i % MIX.size],
                targetOverall = ai.squadCampOverall + own.nextInt(ai.squadCampSpread),
                year = league.year, rng = own,
                ageBias = ai.squadCampAgeBias,
            )
        }
        return league.copy(players = league.players + street)
    }
}
