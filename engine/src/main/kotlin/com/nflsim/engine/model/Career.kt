package com.nflsim.engine.model

import com.nflsim.engine.stats.StatLine
import kotlinx.serialization.Serializable

/** One season of a player's career: what he did, and who he did it for. */
@Serializable
data class CareerSeason(val year: Int, val team: Int? = null, val stats: StatLine = StatLine())

/**
 * Everything a player has done, season by season (SPEC 4, 9.2). Season stat
 * lines are kept forever - they are small, and they are what records, a hall
 * of fame and a player's own card are made of.
 */
@Serializable
data class CareerStats(val seasons: List<CareerSeason> = emptyList()) {

    val years: Int get() = seasons.size

    /** A career total, e.g. `total { it.passYards }`. */
    fun total(of: (StatLine) -> Int): Int = seasons.sumOf { of(it.stats) }

    /** The best single season by some measure. */
    fun best(of: (StatLine) -> Int): CareerSeason? = seasons.maxByOrNull { of(it.stats) }

    /**
     * The season added to the career. A year already recorded is replaced, so
     * folding the same season twice cannot double a man's numbers.
     */
    fun withSeason(year: Int, team: Int?, stats: StatLine): CareerStats =
        CareerStats(seasons.filterNot { it.year == year } + CareerSeason(year, team, stats))
}
