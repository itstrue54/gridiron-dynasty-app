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
 * Free agency as an auction, run over ten days (docs/SPEC.md 8.3).
 *
 * What this replaces mattered more than it looked. Filling rosters by simply
 * handing each team the best available player at each position produced a
 * league where nobody ever overpaid, so nobody was ever cap-strapped, so the
 * salary cap never forced a single release in ten simulated seasons. The cap
 * is supposed to be the strategy game; a cap that never binds is a number on
 * a screen.
 *
 * An auction binds it, because of two things that only exist when teams bid
 * against each other:
 *
 *  - **The winner's curse.** A player signs with the team that values him
 *    most, and the team that values him most is usually the one that is
 *    wrong about him. Overpaying is not a bug here, it is the mechanism.
 *  - **Need premiums.** A team without a quarterback pays more for a
 *    quarterback than a team with one. That is what turns a hole on the
 *    roster into a hole on the cap sheet two years later.
 *
 * Asking prices start above market and decay each day, so patience is a real
 * strategy and the bargains are on days seven through ten - which is also
 * what gives a disciplined team a way back from a bad cap year.
 */
object FreeAgency {

    /** How long the market runs before the leftovers go to camp bodies. */
    const val DAYS = 10

    data class Result(
        val players: List<Player>,
        val signings: List<Signing>,
    )

    private data class Bid(
        val team: TeamId,
        val annual: Int,
        val years: Int,
        /** What the player thinks of the whole package, not just the money. */
        val appeal: Float,
    )

    fun run(
        league: League,
        players: List<Player>,
        year: Int,
        deadMoney: Map<Int, Int>,
        scheme: (TeamId?, Position) -> Scheme,
        pricer: MarketValue.Pricer,
        /** Last season's record. Players notice who wins. */
        winPct: (TeamId) -> Float,
        rng: Rng,
    ): Result {
        val roster = players.filter { it.teamId != null }
            .groupBy { it.teamId!! }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
        val pool = players.filter { it.teamId == null }.toMutableList()
        val signings = mutableListOf<Signing>()

        // What each player is asking, and what he is actually worth. The gap
        // between them is the negotiation.
        val market = pool.associate { p ->
            p.id.v to pricer.annual(p, scheme(null, p.position), year)
        }
        val asking = market.mapValues { (_, v) -> v * OPENING_PREMIUM }.toMutableMap()

        repeat(DAYS) { day ->
            val bids = mutableMapOf<Int, MutableList<Bid>>()

            league.teams.forEach { team ->
                val current = roster.getOrPut(team.id) { mutableListOf() }
                if (current.size >= ROSTER_TARGET) return@forEach
                // Front offices are not interchangeable (SPEC 8.2). An
                // aggressive one puts a third of its cap on one player and
                // goes past market to win a bidding war; a careful one does
                // not. That difference is where bad contracts come from, and
                // bad contracts are what the cap is for.
                val front = team.gm

                val space = (CapManagement.spaceFor(current, year, deadMoney[team.id.v] ?: 0) *
                    front.spendShare).toInt()
                if (space < Contract.MIN_BASE_SALARY * 3) return@forEach

                val needs = TeamNeeds.assess(current, { pos -> scheme(team.id, pos) }, year)
                val dayRng = rng.split("bid|${team.id.v}|$day")

                // A team looks at a handful of players a day, not the whole
                // board - which is why good players occasionally go unnoticed
                // for a week and then sign for less than they are worth.
                //
                // Score every player once and sort the scores. Scoring inside
                // the comparator re-rolls the noise on every comparison, which
                // is not a stable ordering: TimSort notices and throws
                // "comparison method violates its general contract".
                val board = pool
                    .filter { (needs[it.position] ?: 0f) > NEED_FLOOR }
                    .map { p ->
                        p to rosterValue(p, scheme(team.id, p.position), year) +
                            (needs[p.position] ?: 0f) * NEED_WEIGHT +
                            dayRng.gaussian(0f, 4f)
                    }
                    .sortedByDescending { it.second }
                    .take(TARGETS_PER_DAY)
                    .map { it.first }

                board.forEach { p ->
                    val worth = market[p.id.v] ?: return@forEach
                    val need = needs[p.position] ?: 0f
                    val willing = (worth *
                        (1f + need * NEED_PREMIUM) *
                        front.premium).roundToInt()
                        .coerceAtMost((space * front.singleDealShare).toInt()
                            .coerceAtLeast(Contract.MIN_BASE_SALARY))
                        .coerceAtMost(pricer.maxAnnual)

                    if (willing < worth * LOWBALL_FLOOR) return@forEach

                    bids.getOrPut(p.id.v) { mutableListOf() } += Bid(
                        team = team.id,
                        annual = willing,
                        years = MarketValue.termFor(p.age(year), depth = 0),
                        appeal = appealOf(p, team.id, willing, worth, scheme, winPct),
                    )
                }
            }

            // Resolve the day. A player takes the best package on the table if
            // it clears what he is asking; otherwise he waits and asks for a
            // little less tomorrow.
            val signed = mutableSetOf<Int>()
            bids.forEach { (id, offers) ->
                val player = pool.firstOrNull { it.id.v == id } ?: return@forEach
                val best = offers.maxByOrNull { it.appeal } ?: return@forEach
                val ask = asking[id] ?: return@forEach

                // The best players let the market form before they sign. It is
                // also the only way a bidding war ever gets a second round.
                val holdout = (market[id] ?: 0) >= HOLDOUT_VALUE && day < HOLDOUT_DAYS
                if (holdout && best.annual < ask * HOLDOUT_OVERRIDE) return@forEach
                if (best.annual < ask) return@forEach

                val contract = Contract.of(
                    years = best.years,
                    totalValue = best.annual * best.years,
                    signedYear = year,
                    guaranteedShare = 0.40f + (offers.size - 1).coerceAtMost(4) * 0.04f,
                )
                val hired = player.copy(
                    teamId = best.team,
                    contract = contract,
                    status = PlayerStatus.ACTIVE,
                    yearsInSystem = 0,
                )
                roster.getOrPut(best.team) { mutableListOf() } += hired
                signings += Signing(
                    hired.id.v, hired.name, hired.position.label, best.team.v,
                    best.annual, best.years, market[id] ?: best.annual, offers.size,
                )
                signed += id
            }

            pool.removeAll { it.id.v in signed }
            // Everyone still unsigned comes down a little.
            pool.forEach { p -> asking[p.id.v] = (asking[p.id.v] ?: 0f) * DAILY_DECAY }
        }

        val stillFree = pool.map {
            it.copy(teamId = null, contract = null, status = PlayerStatus.FREE_AGENT)
        }
        return Result(roster.values.flatten() + stillFree, signings)
    }

    /**
     * What a player thinks of an offer. Money leads by a distance, but not so
     * far that a bad team can simply buy every free agent - which is what
     * makes a winning roster worth building rather than purchasing.
     */
    private fun appealOf(
        player: Player,
        team: TeamId,
        annual: Int,
        worth: Int,
        scheme: (TeamId?, Position) -> Scheme,
        winPct: (TeamId) -> Float,
    ): Float {
        val money = annual.toFloat() / worth.coerceAtLeast(1)
        val fit = com.nflsim.engine.ratings.schemeFit(player, scheme(team, player.position))
        val loyalty = if (player.teamId == team) player.traits.loyalty / 400f else 0f
        // Players notice who wins, and the ones running out of seasons notice
        // hardest. It is not enough to outbid a contender for a thirty-three
        // year old - which is the whole reason a good team can sign anyone.
        val winning = winPct(team) * WINNING_APPEAL
        return money + fit * FIT_APPEAL + loyalty + winning
    }

    /**
     * Leave room for the draft class. Free agency runs before the draft (SPEC
     * 7), so filling to 51 and then drafting seven meant cutting straight back
     * to 53 - buying players in March to release them in August.
     */
    private const val ROSTER_TARGET = League.ROSTER_SIZE - DraftRunner.ROUNDS

    /** Opening ask, as a multiple of market. */
    private const val OPENING_PREMIUM = 1.20f

    /** How fast an unsigned player's price falls, per day. */
    private const val DAILY_DECAY = 0.955f

    /** A team only looks at positions it actually needs. */
    private const val NEED_FLOOR = 0.20f

    /** Rating points a need is worth when ranking the board. */
    private const val NEED_WEIGHT = 14f

    /** How much more a team pays for a position it must fill. */
    private const val NEED_PREMIUM = 0.55f

    /** Below this fraction of market a team does not bother bidding. */
    private const val LOWBALL_FLOOR = 0.72f

    private const val TARGETS_PER_DAY = 4

    /** Players worth this much let the market form before signing. */
    private const val HOLDOUT_VALUE = 12_000

    private const val HOLDOUT_DAYS = 3

    /** Unless somebody blows them away on day one. */
    private const val HOLDOUT_OVERRIDE = 1.25f

    private const val FIT_APPEAL = 0.30f

    /** How much a winning team is worth against money. */
    private const val WINNING_APPEAL = 0.35f
}
