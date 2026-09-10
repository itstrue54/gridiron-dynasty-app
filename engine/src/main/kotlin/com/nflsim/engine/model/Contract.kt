package com.nflsim.engine.model

import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * Money, in thousands of dollars. $12.5M is 12_500.
 *
 * The cap is the strategy game (docs/SPEC.md 8.1), so this models the parts
 * that actually create decisions: signing bonus prorates over up to five years
 * whatever the contract length, releasing a player accelerates the unamortised
 * remainder onto this year's books, and a post-June-1 designation splits that
 * across two years. Those three rules are what make a bad contract hurt for
 * seasons rather than being undoable at will.
 */
@Serializable
data class Contract(
    val years: Int,
    /** Base salary per contract year, index 0 being the first. */
    val baseSalary: List<Int>,
    val signingBonus: Int = 0,
    /** Base salary that is guaranteed regardless of release. */
    val guaranteed: Int = 0,
    val signedYear: Int,
    /** A first-round rookie deal the club can extend by a fifth year (CBA Article 7). */
    val fifthYearOption: Boolean = false,
) {
    init {
        require(years >= 1) { "a contract must run at least a year" }
        require(baseSalary.size == years) { "expected $years salaries, got ${baseSalary.size}" }
    }

    /** Bonus proration is capped at five years no matter how long the deal is. */
    val proratedBonus: Int get() = signingBonus / min(years, MAX_PRORATION_YEARS)

    fun yearIndex(currentYear: Int): Int = currentYear - signedYear

    fun isActive(currentYear: Int): Boolean = yearIndex(currentYear) in 0 until years

    fun capHit(currentYear: Int): Int {
        val i = yearIndex(currentYear)
        if (i !in 0 until years) return 0
        return baseSalary[i] + proratedBonus
    }

    val totalValue: Int get() = baseSalary.sum() + signingBonus

    val averagePerYear: Int get() = totalValue / years

    /** Bonus money already charged to previous seasons. */
    fun amortised(currentYear: Int): Int =
        proratedBonus * yearIndex(currentYear).coerceIn(0, min(years, MAX_PRORATION_YEARS))

    /** Bonus money that has not hit the books yet. */
    fun unamortised(currentYear: Int): Int = (signingBonus - amortised(currentYear)).coerceAtLeast(0)

    /**
     * What releasing him costs. Pre-June-1 the whole remaining bonus hits at
     * once; post-June-1 this year takes one more year of proration and the
     * rest lands next season.
     */
    /**
     * Guaranteed money still owed. Guarantees are consumed as they are paid,
     * which is what makes the back of a contract cuttable.
     *
     * Treating the whole `guaranteed` figure as covering every year's base
     * made a release mathematically incapable of saving money - the saving
     * works out to proration minus unamortised bonus, which is never positive
     * - so no team in a ten season league ever cut anybody, whatever the
     * contract was worth. Guarantees are front-loaded in reality: the first
     * year or two are locked, and the last years are where a team gets out.
     */
    fun guaranteedRemaining(currentYear: Int): Int {
        val i = yearIndex(currentYear).coerceAtLeast(0)
        val alreadyPaid = baseSalary.take(i).sum()
        return (guaranteed - alreadyPaid).coerceAtLeast(0)
    }

    fun deadCap(currentYear: Int, postJune1: Boolean = false): DeadMoney {
        if (!isActive(currentYear)) return DeadMoney(0, 0)
        val remaining = unamortised(currentYear)
        val guaranteedBase = min(
            guaranteedRemaining(currentYear),
            baseSalary.getOrElse(yearIndex(currentYear)) { 0 },
        )
        return if (!postJune1) {
            DeadMoney(remaining + guaranteedBase, 0)
        } else {
            DeadMoney(proratedBonus + guaranteedBase, remaining - proratedBonus)
        }
    }

    /** Converting base salary into bonus: relief now, more dead money later. */
    fun restructure(currentYear: Int, amount: Int): Contract {
        val i = yearIndex(currentYear)
        require(i in 0 until years) { "cannot restructure an inactive contract" }
        val movable = min(amount, baseSalary[i] - MIN_BASE_SALARY)
        if (movable <= 0) return this
        val newBase = baseSalary.toMutableList()
        newBase[i] = newBase[i] - movable
        return copy(baseSalary = newBase, signingBonus = signingBonus + movable)
    }

    companion object {
        const val MAX_PRORATION_YEARS = 5
        const val MIN_BASE_SALARY = 840   // league minimum, thousands

        /** A straightforward deal: even base salaries plus a bonus. */
        fun of(
            years: Int,
            totalValue: Int,
            signedYear: Int,
            bonusShare: Float = 0.35f,
            guaranteedShare: Float = 0.45f,
        ): Contract {
            val bonus = (totalValue * bonusShare).toInt()
            val baseTotal = totalValue - bonus
            // Back-loaded, the way real deals are - which is what makes year
            // four of a big contract the problem it always turns out to be.
            val weights = (0 until years).map { 0.7f + it * (0.6f / years.coerceAtLeast(1)) }
            val sum = weights.sum()
            val bases = weights.map { (baseTotal * it / sum).toInt().coerceAtLeast(MIN_BASE_SALARY) }
            return Contract(
                years = years,
                baseSalary = bases,
                signingBonus = bonus,
                guaranteed = (totalValue * guaranteedShare).toInt(),
                signedYear = signedYear,
            )
        }
    }
}

@Serializable
data class DeadMoney(val thisYear: Int, val nextYear: Int) {
    val total: Int get() = thisYear + nextYear
}

@Serializable
data class TeamFinances(
    val salaryCap: Int = LEAGUE_CAP,
    val carryover: Int = 0,
    val deadMoney: Int = 0,
) {
    val available: Int get() = salaryCap + carryover - deadMoney

    companion object {
        /** Thousands of dollars. Grows each offseason. */
        const val LEAGUE_CAP = 255_000
        const val CAP_GROWTH_LOW = 0.055f
        const val CAP_GROWTH_HIGH = 0.085f
    }
}
