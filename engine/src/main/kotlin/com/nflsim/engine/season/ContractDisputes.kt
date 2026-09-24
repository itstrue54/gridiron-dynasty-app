package com.nflsim.engine.season

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.econ.Production
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.DemandState
import com.nflsim.engine.model.League
import com.nflsim.engine.model.NewsEvent
import com.nflsim.engine.model.NewsKind
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.model.Transaction
import com.nflsim.engine.model.TransactionKind
import com.nflsim.engine.offseason.CapManagement
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.stats.StatLine
import com.nflsim.engine.tuning.TuningTable

/**
 * Men who have noticed what they are paid (SPEC 10.1's contract disputes).
 *
 * A veteran whose market has moved well past his deal asks his club to fix
 * it, and says so publicly. The league's clubs answer the same week: they
 * pay him if they have the room and think he is worth it, and refuse if
 * they do not. The user's club is asked and left to decide, and the asking
 * gets louder - every week a demand sits unanswered costs the man a little
 * morale, which is worth a little of his rating.
 *
 * Refused, he does not sulk forever: he plays on with his morale down, and
 * the spring's [com.nflsim.engine.offseason.PlayerIntent] finds him with
 * less patience than a man whose club never told him no.
 *
 * Rookies on slotted deals are underpaid by construction, which is what a
 * rookie contract is, so nobody asks until he has accrued seasons behind him.
 */
object ContractDisputes {

    data class Ask(
        val player: Player,
        /** What he is on this year, and what the market says he is worth a year. */
        val paid: Int,
        val market: Int,
        val years: Int,
    ) {
        val total: Int get() = market * years
        /** What saying yes does to this year's cap. */
        val capChange: Int get() = Contract.of(years, total, 0).capHit(0) - paid
    }

    data class Result(val league: League, val news: List<NewsEvent>)

    /**
     * The demands the user's club has been handed and not answered. Priced
     * against the season's stats, because a club pays for a stat line - the
     * same basis the man was told he was worth when he asked.
     */
    fun pending(league: League, team: TeamId, stats: Map<Int, StatLine> = emptyMap()): List<Ask> {
        val waiting = league.roster(team).filter { it.demand == DemandState.PENDING }
        if (waiting.isEmpty()) return emptyList()
        val pricer = pricer(league, Production.index(league.players, stats))
        return waiting.map { ask(league, it, pricer) }.sortedByDescending { it.market }
    }

    fun ask(league: League, player: Player, stats: Map<Int, StatLine> = emptyMap()): Ask? {
        if (player.contract == null) return null
        return ask(league, player, pricer(league, Production.index(league.players, stats)))
    }

    private fun ask(league: League, player: Player, pricer: MarketValue.Pricer): Ask = Ask(
        player,
        player.capHit(league.year),
        pricer.annual(player, scheme(league, player), league.year),
        years(player, league.year),
    )

    /**
     * The week's asking and answering. Called after a week is played, with
     * the season's stats behind it, because a club pays for a stat line.
     */
    fun afterWeek(
        league: League,
        week: Int,
        userTeam: TeamId?,
        stats: Map<Int, StatLine>,
        rng: Rng,
    ): Result {
        val t = league.tuning.ai
        if (week < t.disputeFirstWeek || week > t.disputeLastWeek) {
            return Result(pressure(league, userTeam), emptyList())
        }
        val pricer = pricer(league, Production.index(league.players, stats))
        val news = mutableListOf<NewsEvent>()
        var out = league
        // The wording has its own stream, so how a story is told never moves
        // who speaks up.
        val words = rng.split("headlines")

        league.teams.forEach { club ->
            out.roster(club.id).forEach { man ->
                if (man.demand != DemandState.NONE) return@forEach
                val contract = man.contract ?: return@forEach
                if (man.accruedSeasons < t.disputeAccruedSeasons) return@forEach
                val sch = scheme(out, man)
                if (overall(man, sch) < t.disputeVoice) return@forEach
                val market = pricer.annual(man, sch, out.year)
                val paid = contract.capHit(out.year).coerceAtLeast(1)
                if (market < paid * t.disputePayGap) return@forEach

                // Whether he says it. An ego speaks up; loyalty carries it quietly.
                val nerve = t.disputeWeeklyChance *
                    (1f + (man.traits.ego - 50) / 100f) *
                    (1f - (man.traits.loyalty - 50) / 150f)
                if (rng.nextFloat() >= nerve) return@forEach

                val asking = Ask(man, paid, market, years(man, out.year))
                val settle = { l: League -> extend(l, club.id, man.id, week, pricer) }
                out = out.copy(players = out.players.map {
                    if (it.id == man.id) it.copy(demand = DemandState.PENDING) else it
                })
                news += NewsEvent(
                    week, NewsKind.DISPUTE,
                    Headlines.write("dispute.raised", words, "player" to man.name,
                        "pos" to man.position.label, "club" to club.abbrev,
                        "paid" to money(paid), "market" to money(market)),
                    man.id.v, club.id.v,
                )

                // The league's own clubs answer the same week.
                if (club.id != userTeam) {
                    val answered = if (canAfford(out, club.id, asking)) {
                        settle(out)
                    } else {
                        refuse(out, club.id, man.id, week)
                    }
                    if (answered is Transactions.Outcome.Done) {
                        // Settling logs his new deal; telling him no logs nothing.
                        val signed = answered.league.transactions.drop(out.transactions.size).lastOrNull()
                        out = answered.league
                        val about = arrayOf("player" to man.name, "pos" to man.position.label, "club" to club.abbrev)
                        news += NewsEvent(
                            week, NewsKind.DISPUTE,
                            if (signed != null) Headlines.write("dispute.settled", words, *about,
                                "years" to signed.years, "annual" to money(signed.amount))
                            else Headlines.write("dispute.refused", words, *about),
                            man.id.v, club.id.v,
                        )
                    }
                }
            }
        }
        return Result(pressure(out, userTeam), news)
    }

    /** A demand nobody has answered wears on a man. */
    private fun pressure(league: League, userTeam: TeamId?): League {
        if (userTeam == null) return league
        val waiting = league.roster(userTeam).filter { it.demand == DemandState.PENDING }
            .map { it.id }.toSet()
        if (waiting.isEmpty()) return league
        val drop = league.tuning.ai.disputeWaitingMorale
        val floor = league.tuning.ai.disputeWaitingFloor
        return league.copy(players = league.players.map {
            // Waiting wears on him down to the floor and no further: what
            // sours him past that is being told no, not being kept waiting.
            if (it.id in waiting && it.morale > floor) {
                it.copy(morale = (it.morale - drop).coerceAtLeast(floor))
            } else it
        })
    }

    /**
     * The least he will take, as a share of the market (SPEC 8.3). His agent
     * does not publish it: a club learns it by offering less and being told
     * no. Two men with the same market read differently - an ego wants every
     * dollar, and a man who likes it where he is will take a discount to
     * stay - so haggling is a read on the player, not arithmetic.
     */
    fun reservation(player: Player, tuning: TuningTable): Float {
        val t = tuning.ai
        val ego = (player.traits.ego - 50) / 50f * t.disputeEgoWeight
        val loyal = (player.traits.loyalty - 50) / 50f * t.disputeLoyaltyWeight
        // Stable per man, so the same club gets the same answer twice.
        val quirk = ((player.id.v * 2654435761L) % 61) / 1000f - 0.03f
        return (t.disputeReservationBase + ego - loyal + quirk)
            .coerceIn(t.disputeReservationFloor, 1f)
    }

    /**
     * An offer at [share] of the market. Above what he will take he signs;
     * below it he says no, his agent names his floor, and the demand stays
     * on the club's desk.
     */
    fun offer(
        league: League,
        team: TeamId,
        playerId: PlayerId,
        share: Float,
        week: Int = 0,
        stats: Map<Int, StatLine> = emptyMap(),
    ): Transactions.Outcome {
        val man = league.playersById[playerId] ?: return Transactions.Outcome.Refused("There is no such player.")
        if (man.teamId != team) return Transactions.Outcome.Refused("${man.name} does not play for this club.")
        val pricer = pricer(league, Production.index(league.players, stats))
        val asking = ask(league, man, pricer)
        val annual = (asking.market * share).toInt().coerceAtLeast(Contract.MIN_BASE_SALARY)
        val contract = Contract.of(asking.years, annual * asking.years, league.year)
        val cost = contract.capHit(league.year) - asking.paid
        if (Transactions.spaceFor(league, team) < cost) {
            return Transactions.Outcome.Refused(
                "No room: that deal costs ${money(cost)} more against the cap this year.")
        }
        val floor = reservation(man, league.tuning)
        if (share + 0.001f < floor) {
            val wants = (asking.market * floor).toInt()
            return Transactions.Outcome.Done(
                league.copy(players = league.players.map {
                    if (it.id == playerId) it.copy(
                        morale = (it.morale - league.tuning.ai.disputeSnubMorale).coerceAtLeast(0)) else it
                }),
                "${man.lastName} turns down ${money(annual)} a year. His agent says " +
                    "he will not go below ${money(wants)}.",
            )
        }
        val settled = man.copy(
            contract = contract,
            demand = DemandState.SETTLED,
            // A man who took a discount is a little less delighted about it.
            morale = (man.morale + (league.tuning.ai.disputeSettledMorale * share).toInt()).coerceAtMost(100),
        )
        return done(
            Transaction.of(league.year, week, TransactionKind.SIGNED, team, man,
                amount = annual, years = asking.years),
            league.copy(players = league.players.map { if (it.id == playerId) settled else it }),
            "${man.position.label} ${man.name} signs for ${asking.years} years at " +
                "${money(annual)} a year" +
                if (share < 0.99f) ", ${(100 - share * 100).toInt()}% under the market." else ".",
        )
    }

    /** Every way to write the deal he asked for: his term and a year either side, three ways each. */
    fun deals(league: League, ask: Ask): List<com.nflsim.engine.offseason.ContractOptions.Deal> =
        com.nflsim.engine.offseason.ContractOptions.deals(ask.market, ask.years, league.year, league.tuning)

    /** What to do about a demand, and why. */
    data class Advice(
        val deal: com.nflsim.engine.offseason.ContractOptions.Deal?,
        val headline: String,
        val why: String,
    )

    fun advise(league: League, team: TeamId, ask: Ask): Advice {
        val space = Transactions.spaceFor(league, team)
        val cap = com.nflsim.engine.offseason.CapManagement.capFor(league.year)
        val all = deals(league, ask)
        val affordable = all.filter { it.capNow - ask.paid <= space }
        if (affordable.isEmpty()) {
            val cheapest = all.minOf { it.capNow - ask.paid }
            return Advice(null, "You cannot pay him yet",
                "The cheapest way to write it needs ${money(cheapest)} more room than your " +
                    "${money(space)}. Restructure a big contract to make the room, or tell him no.")
        }
        val best = com.nflsim.engine.offseason.ContractOptions.bestDeal(
            ask.player, affordable, ask.years, space + ask.paid, cap, league.year, league.tuning)
        val d = best.pick
        return Advice(d,
            "Pay him: ${d.years} ${if (d.years == 1) "year" else "years"} at ${money(d.annual)}, " +
                d.structure.label.lowercase(),
            "${best.why} Or offer 90% first: if he will not take it, his agent names his floor, " +
                "and it costs him five morale rather than eighteen.")
    }

    /**
     * The answer the league's own clubs give, for a user who would rather
     * not: pay the market rate if there is room, tell him no if there is not.
     */
    fun frontOfficeAnswer(
        league: League,
        team: TeamId,
        playerId: PlayerId,
        week: Int = 0,
        stats: Map<Int, StatLine> = emptyMap(),
    ): Transactions.Outcome {
        val man = league.playersById[playerId] ?: return Transactions.Outcome.Refused("There is no such player.")
        val asking = ask(league, man, stats) ?: return Transactions.Outcome.Refused("${man.name} has no contract to fix.")
        return if (canAfford(league, team, asking)) extend(league, team, playerId, week, stats)
        else refuse(league, team, playerId, week)
    }

    /** Pay him the market rate: as he asked, or as one of [deals] writes it. */
    fun extend(
        league: League,
        team: TeamId,
        playerId: PlayerId,
        week: Int = 0,
        stats: Map<Int, StatLine> = emptyMap(),
        years: Int? = null,
        structure: com.nflsim.engine.offseason.ContractOptions.Structure? = null,
    ): Transactions.Outcome = extend(
        league, team, playerId, week, pricer(league, Production.index(league.players, stats)), years, structure)

    private fun extend(
        league: League,
        team: TeamId,
        playerId: PlayerId,
        week: Int,
        pricer: MarketValue.Pricer,
        years: Int? = null,
        structure: com.nflsim.engine.offseason.ContractOptions.Structure? = null,
    ): Transactions.Outcome {
        val man = league.playersById[playerId] ?: return Transactions.Outcome.Refused("There is no such player.")
        if (man.teamId != team) return Transactions.Outcome.Refused("${man.name} does not play for this club.")
        if (man.contract == null) return Transactions.Outcome.Refused("${man.name} has no contract to fix.")
        val asking = ask(league, man, pricer)
        // The deal as chosen, or his own terms written the standard way.
        val deal = deals(league, asking).let { all ->
            all.firstOrNull {
                it.years == (years ?: asking.years) &&
                    it.structure == (structure ?: com.nflsim.engine.offseason.ContractOptions.Structure.STANDARD)
            } ?: all.first { it.years == asking.years }
        }
        if (Transactions.spaceFor(league, team) < deal.capNow - asking.paid) {
            return Transactions.Outcome.Refused(
                "No room: that deal costs ${money(deal.capNow - asking.paid)} more against the cap this year.")
        }
        val contract = deal.contract
        val settled = man.copy(
            contract = contract,
            demand = DemandState.SETTLED,
            morale = (man.morale + league.tuning.ai.disputeSettledMorale).coerceAtMost(100),
        )
        val note = "${man.position.label} ${man.name} signs a new deal: " +
            "${deal.years} years at ${money(deal.annual)} a year, ${deal.structure.label.lowercase()}."
        return Transactions.Outcome.Done(
            league.copy(players = league.players.map { if (it.id == playerId) settled else it })
                .logged(Transaction.of(
                    league.year, week, TransactionKind.SIGNED, team, man,
                    amount = deal.annual, years = deal.years)),
            note,
        )
    }

    /** Tell him no. He plays on, with his morale down and a memory. */
    fun refuse(league: League, team: TeamId, playerId: PlayerId, week: Int = 0): Transactions.Outcome {
        val man = league.playersById[playerId] ?: return Transactions.Outcome.Refused("There is no such player.")
        if (man.teamId != team) return Transactions.Outcome.Refused("${man.name} does not play for this club.")
        val refused = man.copy(
            demand = DemandState.REFUSED,
            morale = (man.morale - league.tuning.ai.disputeRefusedMorale).coerceAtLeast(0),
        )
        return Transactions.Outcome.Done(
            league.copy(players = league.players.map { if (it.id == playerId) refused else it }),
            "${man.position.label} ${man.name} is told to play out his deal.",
        )
    }

    private fun done(entry: Transaction, league: League, note: String) =
        Transactions.Outcome.Done(league.logged(entry), note)

    private fun canAfford(league: League, team: TeamId, asking: Ask): Boolean =
        Transactions.spaceFor(league, team) >= asking.capChange

    /** Years a club commits: it buys an older man a season at a time. */
    private fun years(player: Player, year: Int): Int = when {
        player.age(year) >= 31 -> 2
        player.age(year) >= 28 -> 3
        else -> 4
    }

    private fun scheme(league: League, player: Player) = SchemeCatalog.tuned(
        league.team(player.teamId!!).let {
            if (player.position.isOffense) it.offenseScheme else it.defenseScheme
        },
        league.tuning,
    )

    private fun pricer(league: League, production: Map<Int, Float>): MarketValue.Pricer {
        val rostered = league.players.filter { it.teamId != null }
        return MarketValue.pricer(
            rostered = rostered,
            scheme = { p -> scheme(league, p) },
            year = league.year,
            payroll = rostered.sumOf { it.capHit(league.year).toLong() }.coerceAtLeast(1L),
            cap = CapManagement.capFor(league.year),
            production = production,
        )
    }

    private fun money(thousands: Int): String =
        if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)
}
