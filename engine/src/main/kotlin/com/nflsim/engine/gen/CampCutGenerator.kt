package com.nflsim.engine.gen

import com.nflsim.engine.model.League
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.PracticeSquads

/**
 * A new league's camp cuts: the unsigned men its practice squads are chosen
 * from, and whoever the squads leave is its street (SPEC 6.1).
 *
 * Every later season picks its squads from the men an offseason cuts, and
 * keeps the rest on the street for clubs to sign when reserve opens a place.
 * A league that has not had an offseason yet gets the same pool here - a
 * squad place for every club's sixteen and `ai.freeAgentPool` over - so its
 * first-season squads are chosen as every later one is, instead of being
 * generated camp bodies, and its first season has a street.
 *
 * They are made as the offseason's camp bodies are (`ai.campBody`, younger
 * by `ai.squadCampAgeBias`), and spread over the positions as a 53-man
 * roster is, so the street holds a quarterback or a long snapper when a
 * club needs one. Rosters are generated before them and do not change.
 */
object CampCutGenerator {

    /** One position per roster place, in the template's order: the pool's mix. */
    private val MIX = RosterGenerator.TEMPLATE.flatMap { (position, slots) -> List(slots.size) { position } }

    fun cut(league: League, seed: Long): League {
        val ai = league.tuning.ai
        val rng = SplitMixRng(seed).split("camp-cuts|${league.year}")
        var nextId = (league.players.maxOfOrNull { it.id.v } ?: 0) + 1
        val count = league.teams.size * PracticeSquads.SIZE + ai.freeAgentPool
        val cuts = List(count) { i ->
            val own = rng.split("man|$i")
            PlayerGenerator.generate(
                PlayerId(nextId++), MIX[i % MIX.size],
                targetOverall = ai.campBody + own.nextInt(ai.campBodySpread),
                year = league.year, rng = own,
                ageBias = ai.squadCampAgeBias,
            )
        }
        return league.copy(players = league.players + cuts)
    }
}
