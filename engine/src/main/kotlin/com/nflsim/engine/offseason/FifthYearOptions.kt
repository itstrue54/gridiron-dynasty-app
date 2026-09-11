package com.nflsim.engine.offseason

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme

/**
 * The fifth-year option on first-round rookie deals (CBA Article 7).
 *
 * After a first-rounder's third season his club decides whether to add a
 * fifth year, fully guaranteed, at a salary set by his position and how he has
 * played rather than where he was picked: the average of the 3rd to 25th
 * highest salaries at his position, the 3rd to 20th if he has been a starter,
 * the transition tag for one Pro Bowl, the franchise tag for two - Pro Bowls
 * from the league's own vote, and tag prices by CBA tag position as the tag
 * itself sets them. A club takes the option when he is worth it.
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
        val franchise = FranchiseTag.prices(rostered, year, FranchiseTag.FRANCHISE_TOP)
        val transition = FranchiseTag.prices(rostered, year, FranchiseTag.TRANSITION_TOP)

        var exercised = 0
        var declined = 0
        val decided = players.map { p ->
            val c = p.contract
            if (p.teamId == null || c == null || !c.fifthYearOption || c.yearIndex(year) != DECISION_YEAR) {
                return@map p
            }
            val option = salary(p, salaries[p.position].orEmpty(), franchise, transition, depthRank)

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

    /**
     * The option year's salary, by CBA tier: [pay] is every salary at his
     * position, highest first; [franchise] and [transition] the tag prices.
     */
    fun salary(
        p: Player,
        pay: List<Int>,
        franchise: Map<String, Int>,
        transition: Map<String, Int>,
        depthRank: Map<Int, Int>,
    ): Int = when {
        p.proBowls >= 2 -> franchise[FranchiseTag.group(p.position)] ?: 0
        p.proBowls == 1 -> transition[FranchiseTag.group(p.position)] ?: 0
        depthRank[p.id.v] == 0 -> pay.averageOf(2, 20)   // playing time
        else -> pay.averageOf(2, 25)                     // basic
    }.coerceAtLeast(Contract.MIN_BASE_SALARY)

    private fun List<Int>.averageOf(from: Int, until: Int): Int {
        val slice = drop(from).take(until - from)
        return if (slice.isEmpty()) 0 else slice.average().toInt()
    }

    /** Contract year the decision falls before: after three seasons, ahead of the fourth. */
    private const val DECISION_YEAR = 3

    /** A club takes the option when the player is worth at least this much of it. */
    private const val OPTION_BAR = 0.9f
}
