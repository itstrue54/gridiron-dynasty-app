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
        /** A club whose own players are decided elsewhere - the user's. */
        skip: TeamId? = null,
    ): Result {
        val roster = players.filter { it.teamId != null }
            .groupBy { it.teamId!! }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
        val free = players.filter { it.teamId == null }.toMutableList()
        val signings = mutableListOf<Signing>()
        val needBar = TeamNeeds.bar(roster) { id, pos -> scheme(id, pos) }

        league.teams.forEach { team ->
            if (team.id == skip) return@forEach
            val current = roster.getOrPut(team.id) { mutableListOf() }
            val space = CapManagement.spaceFor(current, year, deadMoney[team.id.v] ?: 0)
            if (space <= Contract.MIN_BASE_SALARY * 4) return@forEach

            // Only part of the space goes on your own players - the rest is
            // what a team takes into free agency and the draft.
            var budget = (space * team.gm.ownPlayerShare).roundToInt()
            val teamRng = rng.split("extend|${team.id.v}")
            val needs = TeamNeeds.assess(current, { pos -> scheme(team.id, pos) }, year, needBar)

            val mine = free
                .filter { previousTeam[it.id.v] == team.id }
                .map { p ->
                    p to rosterValue(p, scheme(team.id, p.position), year, team.gm.winNowVsFuture) +
                        (needs[p.position] ?: 0f) * NEED_WEIGHT +
                        p.traits.loyalty / 25f +
                        teamRng.gaussian(0f, 5f)
                }
                .sortedByDescending { it.second }
                .map { it.first }

            mine.forEach { p ->
                if (current.size >= ROSTER_TARGET) return@forEach
                val market = pricer.annual(p, scheme(team.id, p.position), year)
                // Two sides to keeping a player. He names his price - a loyal
                // one takes less to stay, a mercenary wants what the market
                // would pay - and the club decides how far it will go before
                // it lets him test the market.
                val ask = asking(p, market).toFloat()
                val limit = market * (CLUB_LIMIT + team.gm.loyaltyToOwnPlayers * CLUB_LOYALTY +
                    keepPremium(p, team.gm, needs, free, scheme(team.id, p.position), year))
                if (ask > limit) return@forEach
                val offer = ask.roundToInt().coerceAtLeast(Contract.MIN_BASE_SALARY)
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
                    yearsInSystem = p.yearsInSystem + 1, yearsWithClub = p.clubYears + 1,
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

    /**
     * What a player asks to stay, as a share of market: 1.06 for the least
     * loyal down to 0.90 for the most. There is no bidding war, so on average
     * it comes in a little under market.
     */
    private const val PLAYER_ASK = 1.06f
    private const val PLAYER_LOYALTY = 0.16f

    /** How far a club goes to keep its own: 0.94 of market up to 1.08. */
    private const val CLUB_LIMIT = 0.94f
    private const val CLUB_LOYALTY = 0.14f

    /**
     * What else makes a club stretch for its own player besides loyalty: a
     * starter the market cannot replace, a player in his prime for a club
     * trying to win now, and an aggressive front office's habit of paying.
     * As a share of market, added to the club's limit.
     */
    private fun keepPremium(
        player: Player,
        gm: com.nflsim.engine.model.GmProfile,
        needs: Map<Position, Float>,
        market: List<Player>,
        scheme: Scheme,
        year: Int,
    ): Float {
        val value = rosterValue(player, scheme, year, gm.winNowVsFuture)
        val alternative = market
            .filter { it.position == player.position && it.id != player.id }
            .maxOfOrNull { rosterValue(it, scheme, year, gm.winNowVsFuture) }
        val gap = if (alternative == null) IRREPLACEABLE_GAP else value - alternative
        val irreplaceable = (needs[player.position] ?: 0f) *
            (gap / IRREPLACEABLE_GAP).coerceIn(0f, 1f) * IRREPLACEABLE
        val prime = if (player.age(year) >= PRIME_AGE) (2f * gm.winNowVsFuture - 1f) * WIN_NOW_KEEP else 0f
        val aggression = (2f * gm.aggression - 1f) * AGGRESSION_KEEP
        return irreplaceable + prime + aggression
    }

    /** Most that a starter the market cannot replace adds to the limit. */
    private const val IRREPLACEABLE = 0.12f

    /** Rating points over the best free agent at his position that make a player irreplaceable. */
    private const val IRREPLACEABLE_GAP = 10f

    /** From this age win now moves the limit: all-in clubs stretch for their prime players, rebuilds pull back. */
    private const val PRIME_AGE = 27
    private const val WIN_NOW_KEEP = 0.08f

    /** How far an aggressive front office stretches past a careful one, either side of the middle. */
    private const val AGGRESSION_KEEP = 0.06f

    /** Below this multiple of the minimum, let him hit the market. */
    private const val KEEP_THRESHOLD = 1.6f

    private const val NEED_WEIGHT = 10f

    /**
     * What a man asks his own club for to stay: a loyal one takes less, a
     * mercenary wants what the market would pay. The same figure the league's
     * clubs are asked, and the one the user is.
     */
    fun asking(p: Player, market: Int): Int =
        (market * (PLAYER_ASK - p.traits.loyalty / 100f * PLAYER_LOYALTY)).roundToInt()
            .coerceAtLeast(Contract.MIN_BASE_SALARY)

    /** The deal a man re-signs on: what he asked, for as long as his age says. */
    fun kept(p: Player, team: TeamId, annual: Int, year: Int): Player {
        val years = MarketValue.termFor(p.age(year), depth = 0)
        return p.copy(
            teamId = team,
            status = PlayerStatus.ACTIVE,
            contract = Contract.of(years = years, totalValue = annual * years, signedYear = year,
                guaranteedShare = 0.50f),
            yearsInSystem = p.yearsInSystem + 1, yearsWithClub = p.clubYears + 1,
            timesTagged = 0,
        )
    }
}
