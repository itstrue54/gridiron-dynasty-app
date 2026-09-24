package com.nflsim.engine.offseason

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.season.Awards

/**
 * The offseason stopped before free agency (SPEC 7 phase 7), so the user's
 * club makes its own offers instead of bidding by the league's logic.
 *
 * An offer stands for all ten days: the club bids it every day the man is
 * unsigned and it can still pay, against every other club's bids, and he
 * takes the most appealing package that clears what he is asking - which
 * falls a little each day he waits. So an offer at his market may lose him
 * to a contender on the first day, and one under it may land on the
 * seventh.
 */
class FreeAgencyPause internal constructor(
    internal val ctx: OffseasonContext,
    internal val state: OffseasonState,
    internal val rng: Rng,
    internal val carousel: CoachingCarousel.Result,
    internal val awards: Awards,
    internal val previousTeam: Map<Int, TeamId>,
    internal val deadMoney: Map<Int, Int>,
    internal val releases: List<Release>,
    internal val pricer: MarketValue.Pricer,
    internal val wishes: List<Wish>,
    internal val trades: List<TradeMove>,
    internal val valueCuts: List<Release>,
    /** Offers each man has heard before the market, by player id. */
    internal val talks: Map<Int, Int> = emptyMap(),
) {
    val year: Int get() = ctx.newYear
    val userTeam: TeamId get() = ctx.dynasty.userTeamId

    data class Candidate(
        val player: Player,
        /** What he is worth a year, what he opens asking, and the length a club offers a man his age. */
        val market: Int,
        val opening: Int,
        val years: Int,
        /** The club he played for last season, if any. */
        val from: TeamId?,
    )

    /** Everyone on the market, the most valuable first. */
    val candidates: List<Candidate> by lazy {
        state.players
            .filter { it.teamId == null && it.status != PlayerStatus.RETIRED && it.status != PlayerStatus.PRACTICE_SQUAD }
            .map { p ->
                val market = pricer.annual(p, ctx.scheme(null, p.position), year)
                Candidate(p, market, FreeAgency.openingAsk(market),
                    MarketValue.termFor(p.age(year), depth = 0), previousTeam[p.id.v])
            }
            .sortedByDescending { it.market }
    }

    /** The user's roster as free agency opens. */
    val roster: List<Player> get() = state.players.filter { it.teamId == userTeam }

    val capSpace: Int get() = CapManagement.spaceFor(roster, year, state.deadMoney[userTeam.v] ?: 0,
        carryover = ctx.league.team(userTeam).finances.carryover)

    /** What to offer, if anything, and why. */
    data class Advice(val offer: FreeAgency.Offer?, val headline: String, val why: String)

    /**
     * Worth a bid if he would start for this club and it has the room; a
     * starter is worth his market, since that is what the clubs he will
     * choose between are offering. Anyone who would sit is not worth money
     * the draft will buy cheaper.
     */
    fun advice(c: Candidate): Advice {
        val p = c.player
        val age = p.age(year)
        val scheme = ctx.scheme(userTeam, p.position)
        val rank = roster.count { it.position == p.position && overall(it, scheme) > overall(p, scheme) }
        val starter = rank < (TeamNeeds.STARTERS[p.position] ?: 1)
        val yours = state.letGo[p.id.v] == userTeam
        return when {
            yours && !Extensions.willingToReturn(p, ctx.league.tuning) -> Advice(null, "He will not come back",
                "You let him go this spring, and he is too proud to sign with you again.")
            !starter -> Advice(null, "Pass", "He would be a backup for you: the draft buys depth cheaper.")
            age > ctx.league.tuning.ai.payThroughAge ->
                Advice(FreeAgency.Offer(p.id.v, c.market, 1), "Offer one year at ${money(c.market)}",
                    "He would start for you, but at $age a club pays him a season at a time.")
            c.market > capSpace -> Advice(null, "Pass",
                "He would start for you, but he is worth ${money(c.market)} and you have ${money(capSpace)}.")
            else -> Advice(FreeAgency.Offer(p.id.v, c.market, c.years),
                "Offer ${c.years} years at ${money(c.market)}",
                "He would start for you. His market is what the clubs he chooses between will " +
                    "pay; he opens asking ${money(c.opening)} and comes down each day he waits.")
        }
    }

    /**
     * What a man will take to sign before the market opens, as a share of
     * his market. His agent does not say until an offer falls short.
     */
    fun reservation(c: Candidate): Float {
        val t = ctx.league.tuning.ai
        val p = c.player
        val ego = (p.traits.ego - 50) / 50f * t.faTalkEgoWeight
        // Loyalty only counts toward the club he played for.
        val home = if (c.from == userTeam) (p.traits.loyalty - 50) / 50f * t.faTalkLoyaltyWeight else 0f
        val star = if (c.market >= CapManagement.capFor(year) * t.faTalkStarShare) t.faTalkStarPremium else 0f
        val contender = if (ctx.winPct(userTeam) >= t.faTalkContenderWinPct) t.faTalkContenderDiscount else 0f
        val quirk = ((p.id.v * 2654435761L) % 61) / 1000f - 0.03f
        return (t.faTalkBase + ego - home + star - contender + quirk).coerceIn(t.faTalkFloor, t.faTalkCeiling)
    }

    /** Offers left before he stops talking and goes to market. */
    fun talksLeft(playerId: Int): Int =
        (ctx.league.tuning.ai.faTalkAttempts - (talks[playerId] ?: 0)).coerceAtLeast(0)

    /** What came of an offer to his agent: the market as it now stands, and what was said. */
    data class Talk(val pause: FreeAgencyPause, val signed: Boolean, val note: String)

    /**
     * An offer before the market opens. At or above what he will take he
     * signs now, before anyone else can bid; under it he says no and his
     * agent names his floor. Twice, and he stops talking.
     */
    fun negotiate(playerId: Int, annual: Int, years: Int): Talk {
        val c = candidates.firstOrNull { it.player.id.v == playerId }
            ?: return Talk(this, false, "He is not on the market.")
        val p = c.player
        if (state.letGo[p.id.v] == userTeam && !Extensions.willingToReturn(p, ctx.league.tuning)) {
            return Talk(this, false, "${p.lastName}'s agent will not take the call: you let him go.")
        }
        if (talksLeft(playerId) == 0) {
            return Talk(this, false, "${p.lastName} is done talking. He will take his chances on the market.")
        }
        val contract = Contract.of(years, annual * years, year, guaranteedShare = PRE_MARKET_GUARANTEE)
        if (contract.capHit(year) > capSpace) {
            return Talk(this, false, "No room: that deal costs ${money(contract.capHit(year))} this year " +
                "and you have ${money(capSpace)}.")
        }
        val floor = (c.market * reservation(c)).toInt()
        if (annual < floor) {
            val used = talks + (playerId to (talks[playerId] ?: 0) + 1)
            val next = copy(talks = used)
            val left = next.talksLeft(playerId)
            return Talk(next, false, "${p.lastName} turns down ${money(annual)} a year. His agent says he " +
                "will not go below ${money(floor)} before the market opens" +
                if (left == 0) ", and he is done talking." else ". One more offer and he is done talking.")
        }
        val signed = p.copy(
            teamId = userTeam, status = PlayerStatus.ACTIVE, contract = contract,
            yearsInSystem = 0, yearsWithClub = 0,
        )
        val next = copy(state = state.copy(
            players = state.players.map { if (it.id == p.id) signed else it },
            preMarketSignings = state.preMarketSignings + Signing(
                p.id.v, p.name, p.position.label, userTeam.v, annual, years, c.market, suitors = 1),
        ))
        return Talk(next, true, "${p.position.label} ${p.name} signs before the market opens: " +
            "$years ${if (years == 1) "year" else "years"} at ${money(annual)}.")
    }

    private fun copy(
        state: OffseasonState = this.state,
        talks: Map<Int, Int> = this.talks,
    ) = FreeAgencyPause(ctx, state, rng, carousel, awards, previousTeam, deadMoney, releases,
        pricer, wishes, trades, valueCuts, talks)

    /** Free agency, with these offers, or with null the front office bidding for the club. */
    fun decide(offers: List<FreeAgency.Offer>?): OffseasonEngine.DraftPause =
        OffseasonEngine.finishFreeAgency(this, offers)

    private fun money(thousands: Int): String =
        if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)

    companion object {
        /** The fewest dollars an offer can be: the league minimum. */
        const val MIN_OFFER = Contract.MIN_BASE_SALARY

        /** What a deal signed before the market guarantees. */
        private const val PRE_MARKET_GUARANTEE = 0.45f
    }
}
