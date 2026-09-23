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
                    "${man.name} (${man.position.label}, ${club.abbrev}) wants his contract " +
                        "addressed: ${money(paid)} against a market of ${money(market)} a year.",
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
                        out = answered.league
                        news += NewsEvent(
                            week, NewsKind.DISPUTE, "${club.abbrev}: ${answered.note}",
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

    /** Pay him: the market rate, for as long as his age says. */
    fun extend(
        league: League,
        team: TeamId,
        playerId: PlayerId,
        week: Int = 0,
        stats: Map<Int, StatLine> = emptyMap(),
    ): Transactions.Outcome = extend(
        league, team, playerId, week, pricer(league, Production.index(league.players, stats)))

    private fun extend(
        league: League,
        team: TeamId,
        playerId: PlayerId,
        week: Int,
        pricer: MarketValue.Pricer,
    ): Transactions.Outcome {
        val man = league.playersById[playerId] ?: return Transactions.Outcome.Refused("There is no such player.")
        if (man.teamId != team) return Transactions.Outcome.Refused("${man.name} does not play for this club.")
        if (man.contract == null) return Transactions.Outcome.Refused("${man.name} has no contract to fix.")
        val asking = ask(league, man, pricer)
        if (!canAfford(league, team, asking)) {
            return Transactions.Outcome.Refused(
                "No room: the new deal costs ${money(asking.capChange)} more against the cap this year.")
        }
        val contract = Contract.of(asking.years, asking.total, league.year)
        val settled = man.copy(
            contract = contract,
            demand = DemandState.SETTLED,
            morale = (man.morale + league.tuning.ai.disputeSettledMorale).coerceAtMost(100),
        )
        val note = "${man.position.label} ${man.name} signs a new deal: " +
            "${asking.years} years at ${money(asking.market)} a year."
        return Transactions.Outcome.Done(
            league.copy(players = league.players.map { if (it.id == playerId) settled else it })
                .logged(Transaction.of(
                    league.year, week, TransactionKind.SIGNED, team, man,
                    amount = asking.market, years = asking.years)),
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
