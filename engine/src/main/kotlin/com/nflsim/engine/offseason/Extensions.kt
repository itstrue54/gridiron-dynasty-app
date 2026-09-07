package com.nflsim.engine.offseason

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.rng.Rng
import kotlin.math.roundToInt

/**
 * Keeping your own players, before anyone else can bid.
 *
 * This is where the money actually goes in the NFL, and leaving it out was
 * why the cap never bit. Every expiring contract went to the open market, so
 * no team ever committed the kind of long, large deal that hurts three years
 * later - the roster turned over instead of the cap sheet filling up.
 *
 * A team pays slightly under market to keep its own, because there is no
 * bidding war, and the player takes it because the alternative is uncertainty.
 * The cost is that the team is committing before it knows what the market
 * would have charged - which is exactly the bet that produces a contract a
 * general manager regrets.
 */
object Extensions {

    data class Result(val players: List<Player>, val signings: List<Signing>)

    fun run(
        league: League,
        players: List<Player>,
        /** Who each expiring player was with last season. */
        previousTeam: Map<Int, TeamId>,
        year: Int,
        deadMoney: Map<Int, Int>,
        scheme: (TeamId?, Position) -> Scheme,
        pricer: MarketValue.Pricer,
        rng: Rng,
    ): Result {
        val roster = players.filter { it.teamId != null }
            .groupBy { it.teamId!! }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
        val free = players.filter { it.teamId == null }.toMutableList()
        val signings = mutableListOf<Signing>()

        league.teams.forEach { team ->
            val current = roster.getOrPut(team.id) { mutableListOf() }
            val space = CapManagement.spaceFor(current, year, deadMoney[team.id.v] ?: 0)
            if (space <= Contract.MIN_BASE_SALARY * 4) return@forEach

            // Only part of the space goes on your own players - the rest is
            // what a team takes into free agency and the draft.
            var budget = (space * team.gm.ownPlayerShare).roundToInt()
            val teamRng = rng.split("extend|${team.id.v}")
            val needs = TeamNeeds.assess(current, { pos -> scheme(team.id, pos) }, year)

            val mine = free
                .filter { previousTeam[it.id.v] == team.id }
                .map { p ->
                    p to rosterValue(p, scheme(team.id, p.position), year) +
                        (needs[p.position] ?: 0f) * NEED_WEIGHT +
                        p.traits.loyalty / 25f +
                        teamRng.gaussian(0f, 5f)
                }
                .sortedByDescending { it.second }
                .map { it.first }

            mine.forEach { p ->
                if (current.size >= ROSTER_TARGET) return@forEach
                val market = pricer.annual(p, scheme(team.id, p.position), year)
                // A loyal front office pays closer to market to avoid a
                // bidding war; a ruthless one lets him test it.
                val discount = HOMETOWN_DISCOUNT + team.gm.loyaltyToOwnPlayers * 0.10f
                val offer = (market * discount).roundToInt()
                    .coerceAtLeast(Contract.MIN_BASE_SALARY)
                if (offer > budget) return@forEach

                // Nobody re-signs a replacement level body in February. Those
                // go to the market with everyone else.
                if (market <= Contract.MIN_BASE_SALARY * KEEP_THRESHOLD) return@forEach

                val years = MarketValue.termFor(p.age(year), depth = 0)
                val kept = p.copy(
                    teamId = team.id,
                    status = PlayerStatus.ACTIVE,
                    contract = Contract.of(
                        years = years,
                        totalValue = offer * years,
                        signedYear = year,
                        guaranteedShare = 0.50f,
                    ),
                    yearsInSystem = p.yearsInSystem + 1,
                )
                current += kept
                free.remove(p)
                budget -= offer
                signings += Signing(
                    kept.id.v, kept.name, kept.position.label, team.id.v,
                    offer, years, market, suitors = 1,
                )
            }
        }

        return Result(roster.values.flatten() + free, signings)
    }

    /**
     * Leave room for the draft and the market.
     *
     * A 53 man roster is filled in that order: whoever is already under
     * contract, the players a team keeps, seven draft picks, then free
     * agency. Setting this at 46 filled rosters to 53 before the market ever
     * opened - the auction made 28 signings instead of 156 and teams finished
     * the offseason with more cap space than they started with.
     */
    private const val ROSTER_TARGET = 40

    /** No bidding war, so the price is a little under market. */
    private const val HOMETOWN_DISCOUNT = 0.93f

    /** Below this multiple of the minimum, let him hit the market. */
    private const val KEEP_THRESHOLD = 1.6f

    private const val NEED_WEIGHT = 10f
}
