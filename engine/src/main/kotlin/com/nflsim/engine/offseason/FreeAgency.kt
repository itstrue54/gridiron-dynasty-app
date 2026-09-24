package com.nflsim.engine.offseason

import com.nflsim.engine.tuning.TuningTable
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

    /** A standing offer: this much a year, for this long. */
    data class Offer(val player: Int, val annual: Int, val years: Int)

    /** What a free agent opens asking, against what he is worth. */
    fun openingAsk(market: Int): Int = (market * OPENING_PREMIUM).roundToInt()

    /** How far his ask falls each day he goes unsigned, as a share. */
    val dailyCut: Float get() = 1f - DAILY_DECAY

    data class Result(
        val players: List<Player>,
        val signings: List<Signing>,
        /** Dead money by team, including whatever trading up left behind. */
        val deadMoney: Map<Int, Int>,
        /** Players a full roster released to make room for a better one. */
        val upgradeCuts: List<Release> = emptyList(),
    )

    private data class Bid(
        val team: TeamId,
        val annual: Int,
        val years: Int,
        /** What the player thinks of the whole package, not just the money. */
        val appeal: Float,
        /** The player this signing pushes off a full roster, if any. */
        val replaces: Int? = null,
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
        /** Who each free agent played for last season - his teamId is gone by now. */
        previousTeam: Map<Int, TeamId> = emptyMap(),
        /** Transition-tagged players, and the club that may match any offer for each. */
        rightToMatch: Map<Int, TeamId> = emptyMap(),
        /** A club making its own offers - the user's - instead of bidding by the league's logic. */
        manual: TeamId? = null,
        /** Its standing offers, bid every day the man is unsigned and the club can pay. */
        offers: List<Offer> = emptyList(),
        /** Men the user's club let walk this spring: they take its offer only if willing. */
        letGo: Map<Int, TeamId> = emptyMap(),
    ): Result {
        val roster = players.filter { it.teamId != null }
            .groupBy { it.teamId!! }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
        val pool = players.filter { it.teamId == null }.toMutableList()
        val signings = mutableListOf<Signing>()
        val dead = deadMoney.toMutableMap()
        val upgradeCuts = mutableListOf<Release>()
        val upgrades = mutableMapOf<Int, Int>()

        // What each player is asking, and what he is actually worth. The gap
        // between them is the negotiation.
        val market = pool.associate { p ->
            p.id.v to pricer.annual(p, scheme(null, p.position), year)
        }.toMutableMap()
        val asking = market.mapValues { (_, v) -> v * OPENING_PREMIUM }.toMutableMap()
        val needBar = TeamNeeds.bar(roster) { id, pos -> scheme(id, pos) }

        repeat(DAYS) { day ->
            val bids = mutableMapOf<Int, MutableList<Bid>>()

            league.teams.forEach { team ->
                val current = roster.getOrPut(team.id) { mutableListOf() }
                if (team.id == manual) {
                    // The user's club bids what he told it to, and only what
                    // it can pay: each offer on the table counts against the
                    // room for the others, the way a real cap sheet does.
                    var space = CapManagement.spaceFor(current, year, dead[team.id.v] ?: 0,
                        carryover = team.finances.carryover)
                    offers.forEach { o ->
                        val p = pool.firstOrNull { it.id.v == o.player } ?: return@forEach
                        val worth = market[o.player] ?: return@forEach
                        if (o.annual > space) return@forEach
                        space -= o.annual
                        bids.getOrPut(o.player) { mutableListOf() } += Bid(
                            team = team.id, annual = o.annual, years = o.years,
                            appeal = appealOf(p, team.id, o.annual, worth, scheme, winPct),
                        )
                    }
                    return@forEach
                }
                // Front offices are not interchangeable (SPEC 8.2). An
                // aggressive one puts a third of its cap on one player and
                // goes past market to win a bidding war; a careful one does
                // not. That difference is where bad contracts come from, and
                // bad contracts are what the cap is for.
                val front = team.gm
                val rawSpace = CapManagement.spaceFor(current, year, dead[team.id.v] ?: 0,
                    carryover = team.finances.carryover)

                // A full roster still has a use for cap room: sign the better
                // player and release the one he displaces. Without this a team
                // at its target could not turn money into ability at all, and
                // that stranded room was most of the league's unspent cap.
                if (current.size >= ROSTER_TARGET) {
                    if ((upgrades[team.id.v] ?: 0) < league.tuning.ai.faMaxUpgrades) {
                        upgradeBids(team, current, rawSpace, pool, market, year, scheme, pricer,
                            winPct, rng.split("upgrade|${team.id.v}|$day"), bids, league.tuning.ai)
                    }
                    return@forEach
                }

                val space = rawSpace - reserve(front.spendShare, year, league.tuning.ai.faReserveOfCap)
                if (space < Contract.MIN_BASE_SALARY * 3) return@forEach

                val needs = TeamNeeds.assess(current, { pos -> scheme(team.id, pos) }, year, needBar)
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
                        p to rosterValue(p, scheme(team.id, p.position), year, front.winNowVsFuture) +
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
                        (1f + need * league.tuning.ai.faNeedPremium) *
                        front.premium *
                        losingPremium(p, worth, winPct(team.id), year, pricer.maxAnnual, league.tuning.ai.faLosingPremium)).roundToInt()
                        .coerceAtMost((space * front.singleDealShare).toInt()
                            .coerceAtLeast(Contract.MIN_BASE_SALARY))
                        .coerceAtMost(pricer.maxAnnual)

                    if (willing < worth * league.tuning.ai.faLowballFloor) return@forEach

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
            val released = mutableListOf<Player>()
            bids.forEach { (id, offers) ->
                val player = pool.firstOrNull { it.id.v == id } ?: return@forEach
                // An upgrade offer only stands while the player it displaces is
                // still on the roster and the team has swaps left.
                val live = offers.filter { b ->
                    b.replaces == null || ((upgrades[b.team.v] ?: 0) < league.tuning.ai.faMaxUpgrades &&
                        roster[b.team]?.any { it.id.v == b.replaces } == true)
                }.filter { b ->
                    // The club that let him go gets him back only if he is willing.
                    letGo[id] != b.team || Extensions.willingToReturn(player, league.tuning)
                }
                // A loyal player gives his old club the benefit of the doubt.
                val best = live.maxByOrNull { b ->
                    b.appeal + if (previousTeam[id] == b.team) player.traits.loyalty / 400f else 0f
                } ?: return@forEach
                val ask = asking[id] ?: return@forEach

                // The best players let the market form before they sign. It is
                // also the only way a bidding war ever gets a second round.
                val holdout = (market[id] ?: 0) >= HOLDOUT_VALUE && day < HOLDOUT_DAYS
                if (holdout && best.annual < ask * HOLDOUT_OVERRIDE) return@forEach
                if (best.annual < ask) return@forEach

                // A transition tag: his old club may match the offer he takes (CBA).
                val matcher = rightToMatch[id]?.takeIf { club ->
                    club != best.team &&
                        CapManagement.spaceFor(roster[club] ?: emptyList(), year, dead[club.v] ?: 0,
                            carryover = league.team(club).finances.carryover) >= best.annual
                }
                val team = matcher ?: best.team

                val contract = Contract.of(
                    years = best.years,
                    totalValue = best.annual * best.years,
                    signedYear = year,
                    guaranteedShare = 0.40f + (live.size - 1).coerceAtMost(4) * 0.04f,
                )
                val hired = player.copy(
                    teamId = team,
                    contract = contract,
                    status = PlayerStatus.ACTIVE,
                    yearsInSystem = 0, yearsWithClub = 0,
                )
                roster.getOrPut(team) { mutableListOf() } += hired
                signings += Signing(
                    hired.id.v, hired.name, hired.position.label, team.v,
                    best.annual, best.years, market[id] ?: best.annual, live.size,
                )
                signed += id

                best.replaces?.takeIf { matcher == null }?.let { outId ->
                    val list = roster.getValue(best.team)
                    val out = list.first { it.id.v == outId }
                    list.remove(out)
                    val owed = out.contract?.deadCap(year)?.thisYear ?: 0
                    dead[best.team.v] = (dead[best.team.v] ?: 0) + owed
                    upgrades[best.team.v] = (upgrades[best.team.v] ?: 0) + 1
                    upgradeCuts += Release(
                        out.id.v, out.name, out.position.label, best.team.v,
                        com.nflsim.engine.ratings.overall(out, scheme(best.team, out.position)),
                        out.capHit(year) - owed, owed,
                    )
                    val freeAgent = out.copy(
                        teamId = null, contract = null, status = PlayerStatus.FREE_AGENT)
                    market[outId] = pricer.annual(freeAgent, scheme(null, out.position), year)
                    asking[outId] = market.getValue(outId).toFloat()
                    released += freeAgent
                }
            }

            pool.removeAll { it.id.v in signed }
            // Everyone still unsigned comes down a little.
            pool.forEach { p -> asking[p.id.v] = (asking[p.id.v] ?: 0f) * DAILY_DECAY }
            // Released today, on the market tomorrow at what he is worth.
            pool += released
        }

        val stillFree = pool.map {
            it.copy(teamId = null, contract = null, status = PlayerStatus.FREE_AGENT)
        }
        return Result(roster.values.flatten() + stillFree, signings, dead, upgradeCuts)
    }

    private class Upgrade(
        val player: Player,
        val out: Player,
        val worth: Int,
        val willing: Int,
        val score: Float,
    )

    /**
     * What a full roster bids on: players who clearly beat its weakest at the
     * same position, paid for out of its space plus whatever releasing that
     * player frees once his dead money is counted.
     */
    private fun upgradeBids(
        team: com.nflsim.engine.model.Team,
        current: List<Player>,
        rawSpace: Int,
        pool: List<Player>,
        market: Map<Int, Int>,
        year: Int,
        scheme: (TeamId?, Position) -> Scheme,
        pricer: MarketValue.Pricer,
        winPct: (TeamId) -> Float,
        rng: Rng,
        bids: MutableMap<Int, MutableList<Bid>>, ai: TuningTable.Ai
    ) {
        val front = team.gm
        val winNow = front.winNowVsFuture
        val weakest = current.groupBy { it.position }.mapValues { (pos, group) ->
            group.minByOrNull { rosterValue(it, scheme(team.id, pos), year, winNow) }
        }
        // Affordability is checked before ranking. Ranking by gain first put
        // the best players on the market - none within one deal's share of
        // the budget - in front of every upgrade the team could pay for.
        pool.mapNotNull { p ->
            val out = weakest[p.position] ?: return@mapNotNull null
            val sch = scheme(team.id, p.position)
            val gain = rosterValue(p, sch, year, winNow) - rosterValue(out, sch, year, winNow)
            if (gain < UPGRADE_MARGIN) return@mapNotNull null
            val worth = market[p.id.v] ?: return@mapNotNull null
            val freed = out.capHit(year) - (out.contract?.deadCap(year)?.thisYear ?: 0)
            val budget = rawSpace + freed - reserve(front.spendShare, year, ai.faReserveOfCap)
            if (budget < Contract.MIN_BASE_SALARY) return@mapNotNull null
            val willing = (worth * front.premium *
                losingPremium(p, worth, winPct(team.id), year, pricer.maxAnnual, ai.faLosingPremium)).roundToInt()
                .coerceAtMost((budget * front.singleDealShare).toInt()
                    .coerceAtLeast(Contract.MIN_BASE_SALARY))
                .coerceAtMost(pricer.maxAnnual)
            if (willing < worth * ai.faLowballFloor) return@mapNotNull null
            Upgrade(p, out, worth, willing, gain + rng.gaussian(0f, 2f))
        }
            .sortedByDescending { it.score }
            .take(TARGETS_PER_DAY)
            .forEach { u ->
                bids.getOrPut(u.player.id.v) { mutableListOf() } += Bid(
                    team = team.id,
                    annual = u.willing,
                    years = MarketValue.termFor(u.player.age(year), depth = 0),
                    appeal = appealOf(u.player, team.id, u.willing, u.worth, scheme, winPct),
                    replaces = u.out.id.v,
                )
            }
    }

    /** Rating points a free agent has to clear a full roster's weakest player by. */
    /**
     * The cap a front office keeps free through the market, in money. Spend
     * share used to scale whatever space was left each day, and ten days of
     * that spent nearly all of it whatever the GM - held as a sum set once,
     * a careful club finishes the market with room and a reckless one without.
     */
    private fun reserve(spendShare: Float, year: Int, reserveOfCap: Float): Int =
        (CapManagement.capFor(year) * (1f - spendShare) * reserveOfCap).toInt()


    private const val UPGRADE_MARGIN = 4f


    /**
     * What a losing club adds to win a key veteran. Free agents prefer a
     * winner - it is in how they rank offers - so a bad club that wants a
     * proven starter has to pay for being bad. A .500 club adds nothing.
     */
    internal fun losingPremium(player: Player, worth: Int, winPct: Float, year: Int, maxAnnual: Int, premium: Float = TuningTable.REALISTIC.ai.faLosingPremium): Float {
        val key = player.age(year) >= AGE_CLIFF && worth >= maxAnnual * KEY_VETERAN_SHARE
        if (!key) return 1f
        return 1f + ((0.5f - winPct) * 2f).coerceAtLeast(0f) * premium
    }

    /** A veteran worth this share of the biggest deal allowed is a key signing. */
    private const val KEY_VETERAN_SHARE = 0.10f


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
        // Players notice who wins, and the ones running out of seasons notice
        // hardest. It is not enough to outbid a contender for a thirty-three
        // year old - which is the whole reason a good team can sign anyone.
        val winning = winPct(team) * WINNING_APPEAL
        return money + fit * FIT_APPEAL + winning
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
