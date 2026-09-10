package com.nflsim.engine.offseason

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall

/**
 * The fifth-year option on first-round rookie deals (CBA Article 7).
 *
 * After a first-rounder's third season his club decides whether to add a
 * fifth year, fully guaranteed, at a salary set by his position and how he has
 * played rather than where he was picked: the average of the 3rd to 25th
 * highest salaries at his position, the 3rd to 20th if he has been a starter,
 * the transition tag for one Pro Bowl, the franchise tag for two. This league
 * has no Pro Bowl, so standing at his position stands in for those tiers - the
 * ten best, the three best. A club takes the option when he is worth it.
 */
object FifthYearOptions {

    data class Result(val players: List<Player>, val exercised: Int, val declined: Int)

    fun decide(
        players: List<Player>,
        year: Int,
        scheme: (TeamId?, Position) -> Scheme,
        pricer: MarketValue.Pricer,
        depthRank: Map<Int, Int>,
    ): Result {
        val rostered = players.filter { it.teamId != null }
        val salaries = rostered.filter { it.contract != null }
            .groupBy { it.position }
            .mapValues { (_, group) -> group.map { it.capHit(year) }.sortedDescending() }
        val standing = rostered.groupBy { it.position }
            .mapValues { (position, group) ->
                group.sortedByDescending { overall(it, scheme(it.teamId, position)) }.map { it.id.v }
            }

        var exercised = 0
        var declined = 0
        val decided = players.map { p ->
            val c = p.contract
            if (p.teamId == null || c == null || !c.fifthYearOption || c.yearIndex(year) != DECISION_YEAR) {
                return@map p
            }
            val pay = salaries[p.position].orEmpty()
            val rank = standing[p.position]?.indexOf(p.id.v) ?: -1
            val option = when {
                rank in 0 until 3 -> pay.averageOf(0, 5)      // franchise tag
                rank in 3 until 10 -> pay.averageOf(0, 10)    // transition tag
                depthRank[p.id.v] == 0 -> pay.averageOf(2, 20) // playing time
                else -> pay.averageOf(2, 25)                   // basic
            }.coerceAtLeast(Contract.MIN_BASE_SALARY)

            val worth = pricer.annual(p, scheme(p.teamId, p.position), year)
            if (worth >= option * OPTION_BAR) {
                exercised++
                p.copy(contract = c.copy(
                    years = c.years + 1,
                    baseSalary = c.baseSalary + option,
                    guaranteed = c.guaranteed + option,
                    fifthYearOption = false,
                ))
            } else {
                declined++
                p.copy(contract = c.copy(fifthYearOption = false))
            }
        }
        return Result(decided, exercised, declined)
    }

    private fun List<Int>.averageOf(from: Int, until: Int): Int {
        val slice = drop(from).take(until - from)
        return if (slice.isEmpty()) 0 else slice.average().toInt()
    }

    /** Contract year the decision falls before: after three seasons, ahead of the fourth. */
    private const val DECISION_YEAR = 3

    /** A club takes the option when the player is worth at least this much of it. */
    private const val OPTION_BAR = 0.9f
}
