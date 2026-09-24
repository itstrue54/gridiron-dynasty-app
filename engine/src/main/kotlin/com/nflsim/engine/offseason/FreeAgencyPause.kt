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

    val capSpace: Int get() = CapManagement.spaceFor(roster, year, state.deadMoney[userTeam.v] ?: 0)

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
        return when {
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

    /** Free agency, with these offers, or with null the front office bidding for the club. */
    fun decide(offers: List<FreeAgency.Offer>?): OffseasonEngine.DraftPause =
        OffseasonEngine.finishFreeAgency(this, offers)

    private fun money(thousands: Int): String =
        if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)

    companion object {
        /** The fewest dollars an offer can be: the league minimum. */
        const val MIN_OFFER = Contract.MIN_BASE_SALARY
    }
}
