package com.nflsim.engine.offseason

import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.Player
import com.nflsim.engine.tuning.TuningTable
import kotlin.math.roundToInt

/**
 * Every way a club can write a deal a man will sign, and which one it
 * should. Used wherever the user decides a contract: his expiring players
 * in the offseason, a demand in season, and a restructure on a player's
 * card.
 *
 * The prices are the league's own. A man names a figure for the length he
 * wants; a year shorter costs more a year and a year longer less, because
 * security is what a contract is for. The structure decides when the money
 * lands - bonus is spread over the deal and follows a release as dead
 * money, salary is paid as it falls due.
 */
object ContractOptions {

    /**
     * When the money lands. Written out rather than left to a bonus share:
     * this engine back-loads salary and spreads bonus evenly, so more bonus
     * alone makes this year dearer, not cheaper - the opposite of what a
     * club reaching for a bonus is after.
     */
    enum class Structure(val label: String, val blurb: String) {
        STANDARD("Standard", "the usual deal: a third of it as bonus, salary rising year on year"),
        CAP_LIGHT("Cap-light now",
            "the minimum in salary this year and a bonus spread over the deal: the least cap now, " +
                "more every year after, and the most dead money if you cut him"),
        PAY_AS_YOU_GO("Pay as you go",
            "the same salary every year and no bonus: more cap now, and no dead money beyond what " +
                "is guaranteed"),
    }

    /** A deal of [total] over [years], written the way [structure] says. */
    fun write(years: Int, total: Int, year: Int, structure: Structure, guaranteedShare: Float): Contract {
        val guaranteed = (total * guaranteedShare).toInt()
        val min = Contract.MIN_BASE_SALARY
        return when {
            structure == Structure.STANDARD || years == 1 ->
                Contract.of(years, total, year, guaranteedShare = guaranteedShare)
            structure == Structure.PAY_AS_YOU_GO -> Contract(
                years = years,
                baseSalary = List(years) { (total / years).coerceAtLeast(min) },
                signingBonus = 0, guaranteed = guaranteed, signedYear = year,
            )
            else -> {
                val bonus = (total * CAP_LIGHT_BONUS).toInt()
                val later = ((total - bonus - min) / (years - 1)).coerceAtLeast(min)
                Contract(
                    years = years,
                    baseSalary = listOf(min) + List(years - 1) { later },
                    signingBonus = bonus, guaranteed = guaranteed, signedYear = year,
                )
            }
        }
    }

    /** Of a cap-light deal, how much is bonus. */
    private const val CAP_LIGHT_BONUS = 0.35f

    data class Deal(
        val years: Int,
        val annual: Int,
        val structure: Structure,
        val contract: Contract,
        /** Cap this year and next, and dead money if he is cut next year. */
        val capNow: Int,
        val capNext: Int,
        val deadIfCutNextYear: Int,
    ) {
        val total: Int get() = annual * years
    }

    /**
     * His term and a year either side, each written three ways. [asking] is
     * what he wants a year for [preferred] years.
     */
    fun deals(
        asking: Int,
        preferred: Int,
        year: Int,
        tuning: TuningTable,
        /** Of the total, how much is guaranteed; a re-signing guarantees half. */
        guaranteedShare: Float = 0.45f,
    ): List<Deal> {
        val t = tuning.ai
        return ((preferred - 1)..(preferred + 1)).filter { it in 1..MAX_YEARS }.flatMap { years ->
            val shift = preferred - years
            val annual = (asking * if (shift > 0) 1f + shift * t.termShorterPremium
                else 1f + shift * t.termLongerDiscount).roundToInt().coerceAtLeast(Contract.MIN_BASE_SALARY)
            // A one-year deal is one deal however it is written: the bonus
            // lands this year either way, so it is offered one way only.
            (if (years == 1) listOf(Structure.STANDARD) else Structure.entries).map { s ->
                val c = write(years, annual * years, year, s, guaranteedShare)
                Deal(years, annual, s, c, c.capHit(year), c.capHit(year + 1),
                    if (years > 1) c.deadCap(year + 1).thisYear else 0)
            }
        }
    }

    /** A recommendation, and the reason it is the one. */
    data class Advice<T>(val pick: T, val why: String)

    /**
     * The deal to write, for a man the club means to keep: no longer than
     * he will be worth it, and structured for the room the club has.
     */
    fun bestDeal(
        player: Player,
        deals: List<Deal>,
        preferred: Int,
        capSpace: Int,
        cap: Int,
        year: Int,
        tuning: TuningTable,
    ): Advice<Deal> {
        val t = tuning.ai
        val age = player.age(year)
        // Pay him through his prime and no further.
        val prime = (t.payThroughAge - age + 1).coerceAtLeast(1)
        val term = deals.map { it.years }.distinct().filter { it <= maxOf(prime, 1) }.maxOrNull()
            ?: deals.minOf { it.years }
        val atTerm = deals.filter { it.years == term }
        // [deals] may be only the ones the club can afford: with the standard
        // way of writing it gone, the room is tight by definition.
        val standard = atTerm.firstOrNull { it.structure == Structure.STANDARD }
        val tight = standard == null || capSpace - standard.capNow < cap * t.tightCapShare
        val structure = when {
            tight -> Structure.CAP_LIGHT
            age >= t.payThroughAge - 1 -> Structure.PAY_AS_YOU_GO
            else -> Structure.STANDARD
        }
        // A one-year deal is written one way only.
        val pick = atTerm.firstOrNull { it.structure == structure } ?: atTerm.first()
        val length = when {
            term < preferred -> "$term ${years(term)}, one short of what he wants, because at $age he " +
                "is paid past his best after that"
            term > preferred -> "$term ${years(term)}, a year longer than he asked, for less a year"
            else -> "the $term ${years(term)} he wants"
        }
        val shape = when (pick.structure) {
            Structure.CAP_LIGHT -> "cap-light, because the room is tight and this keeps it for free agency"
            Structure.PAY_AS_YOU_GO -> "with no bonus, so a decline at $age leaves no dead money behind"
            Structure.STANDARD -> "standard, since there is room and no reason to push money around"
        }
        return Advice(pick, "Sign him for $length, $shape.")
    }

    /** A restructure a club could do: how much of the base it moves into bonus. */
    data class Restructure(val share: Float, val label: String, val preview: com.nflsim.engine.season.Transactions.Restructure)

    /** A quarter, half, or all of what can be moved - where there is anything to move. */
    fun restructures(player: Player, year: Int): List<Restructure> =
        listOf(0.25f to "A quarter", 0.5f to "Half", 1f to "All of it").mapNotNull { (share, label) ->
            com.nflsim.engine.season.Transactions.restructurePreview(player, year, share)
                ?.let { Restructure(share, label, it) }
        }

    /**
     * Whether to restructure at all, and how much: a club with room should
     * not - the money does not go away, it moves to years he may not be
     * worth it - and a club that is tight should move the least that makes
     * it comfortable.
     */
    fun bestRestructure(options: List<Restructure>, capSpace: Int, cap: Int, tuning: TuningTable): Advice<Restructure?> {
        val comfortable = (cap * tuning.ai.tightCapShare).toInt()
        if (options.isEmpty()) return Advice(null, "There is nothing to move on this deal.")
        if (capSpace >= comfortable) {
            return Advice(null, "Leave it. You have ${money(capSpace)} of room, and a restructure only " +
                "moves money into years he may not be worth it.")
        }
        val enough = options.firstOrNull { capSpace + it.preview.frees >= comfortable } ?: options.last()
        return Advice(enough, "${enough.label}: it frees ${money(enough.preview.frees)}, enough to be " +
            "comfortable, for ${money(enough.preview.addsPerYear)} on every year of the deal.")
    }

    private fun years(n: Int) = if (n == 1) "year" else "years"

    private fun money(thousands: Int): String =
        if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)

    const val MAX_YEARS = 5
}
