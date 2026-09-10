package com.nflsim.engine.offseason

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.League
import com.nflsim.engine.model.PickAsset
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall

/**
 * Contenders buying a star (SPEC 8.4).
 *
 * A good club a player or two from a title trades young players or draft
 * picks for a proven one, and a club going the other way takes them. Each
 * side values everything on its own timeline - players by rosterValue at its
 * own win now, picks by the Johnson chart tilted the same way - so the
 * contender rates the star above what it gives up and the rebuilding club
 * rates the youth and the picks above the star it loses. A deal happens only
 * when both come out ahead.
 *
 * The GM's personality decides how far a contender goes: win now makes it a
 * buyer at all, aggression lets it give up more than it gets and go back for
 * a second player, and risk tolerance decides how old a star it will take on.
 *
 * Contracts move as they stand, and each club eats the unamortised bonus of
 * the players it sends away, as request trades do (ADR-010). Picks can be
 * traded for the coming draft and the two after it (NFL rules).
 */
object ContenderTrades {

    data class Result(
        val players: List<Player>,
        val deadMoney: Map<Int, Int>,
        val moves: List<TradeMove>,
        val picks: List<PickAsset> = emptyList(),
        val pickTrades: List<PickTrade> = emptyList(),
    )

    const val STAR_REASON = "traded to a contender"
    const val YOUNG_REASON = "sent to a rebuilding club for a star"
    const val PICK_REASON = "sent to a rebuilding club for a star"

    /** One piece of a package - a young player or a pick - and what each side thinks it is worth. */
    private class Piece(val player: Player?, val pick: PickAsset?, val toSeller: Float, val toBuyer: Float)

    private class Deal(val seller: Team, val star: Player, val pkg: List<Piece>, val gain: Float)

    fun run(
        league: League,
        players: List<Player>,
        deadMoney: Map<Int, Int>,
        scheme: (TeamId?, Position) -> Scheme,
        winPct: (TeamId) -> Float,
        year: Int,
        /** Every club's picks; this year's are placed by [order]. */
        picks: List<PickAsset> = emptyList(),
        order: List<TeamId> = emptyList(),
    ): Result {
        val roster = players.filter { it.teamId != null }
            .groupBy { it.teamId!! }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
        val dead = deadMoney.toMutableMap()
        val moves = mutableListOf<TradeMove>()
        val held = picks.toMutableList()
        val pickTrades = mutableListOf<PickTrade>()
        val sold = mutableSetOf<TeamId>()
        val needBar = TeamNeeds.bar(roster) { id, pos -> scheme(id, pos) }

        fun value(p: Player, club: Team): Float =
            (rosterValue(p, scheme(club.id, p.position), year, club.gm.winNowVsFuture) -
                MarketValue.REPLACEMENT).coerceAtLeast(0f)

        fun value(pick: PickAsset, club: Team): Float =
            PickValue.value(pick, year, order, club.gm.winNowVsFuture)

        val buyers = league.teams
            .filter { winPct(it.id) >= CONTENDER && it.gm.winNowVsFuture >= BUYER_WIN_NOW }
            .sortedByDescending { winPct(it.id) }
        val sellers = league.teams
            .filter { winPct(it.id) < 0.5f || it.gm.winNowVsFuture <= SELLER_WIN_NOW }

        buyers.forEach { buyer ->
            val deals = if (buyer.gm.aggression >= SECOND_DEAL_AGGRESSION) 2 else 1
            for (attempt in 1..deals) {
                val mine = roster.getOrPut(buyer.id) { mutableListOf() }
                // A player or two away: one or two real holes at positions a
                // star can play. A club with more than that is not one trade
                // from anything.
                val holes = TeamNeeds.assess(mine, { pos -> scheme(buyer.id, pos) }, year, needBar)
                    .filter { (pos, need) -> need >= HOLE && pos !in NOT_A_HOLE }.keys
                if (holes.isEmpty() || holes.size > MAX_HOLES) break

                val starMaxAge = STAR_MIN_AGE + 3 + (buyer.gm.riskTolerance * 4f).toInt()
                val bestAt = mine.groupBy { it.position }
                    .mapValues { (_, group) -> group.maxByOrNull { value(it, buyer) } }
                // What the contender can spare: young, not at a position it is
                // short at, and not its best player at his own - and its picks.
                val spare = mine.filter { p ->
                    p.age(year) <= YOUNG_AGE && p.position !in holes && bestAt[p.position]?.id != p.id
                }
                val ownPicks = held.filter { it.owner == buyer.id.v }

                var chosen: Deal? = null
                for (seller in sellers) {
                    if (seller.id == buyer.id || seller.id in sold) continue
                    val theirs = roster.getOrPut(seller.id) { mutableListOf() }
                    // Every single piece and every pair from what the seller
                    // wants most. The first two alone are too coarse: one is too
                    // little for the seller and both too much for the buyer,
                    // when a different pair suits both.
                    val pieces = spare.map { Piece(it, null, value(it, seller), value(it, buyer)) } +
                        ownPicks.map { Piece(null, it, value(it, seller), value(it, buyer)) }
                    val wanted = pieces.sortedByDescending { it.toSeller }.take(PACKAGE_POOL)
                    val packages = wanted.map { listOf(it) } +
                        wanted.indices.flatMap { i -> (i + 1 until wanted.size).map { j -> listOf(wanted[i], wanted[j]) } }

                    for (star in theirs) {
                        if (star.position !in holes) continue
                        val age = star.age(year)
                        if (age < STAR_MIN_AGE || age > starMaxAge) continue
                        if (overall(star, scheme(buyer.id, star.position)) < STAR_OVERALL) continue
                        val gain = value(star, buyer) - (bestAt[star.position]?.let { value(it, buyer) } ?: 0f)
                        if (gain < CLEAR_UPGRADE) continue
                        if (chosen != null && gain <= chosen.gain) continue

                        val sellerWants = value(star, seller) * (1f + SELLER_MARGIN)
                        val buyerPays = value(star, buyer) * (1f + buyer.gm.aggression * AGGRESSION_OVERPAY)
                        // The package that costs the contender least and still
                        // satisfies the seller. Picks carry no cap hit.
                        val pkg = packages
                            .filter { pkg ->
                                pkg.sumOf { it.toSeller.toDouble() } >= sellerWants &&
                                    pkg.sumOf { it.toBuyer.toDouble() } <= buyerPays &&
                                    fits(mine, theirs, star, pkg.mapNotNull { it.player }, year,
                                        dead[buyer.id.v] ?: 0, dead[seller.id.v] ?: 0)
                            }
                            .minByOrNull { pkg -> pkg.sumOf { it.toBuyer.toDouble() } }
                            ?: continue
                        chosen = Deal(seller, star, pkg, gain)
                    }
                }

                val deal = chosen ?: break
                val theirs = roster.getValue(deal.seller.id)
                val sentPlayers = deal.pkg.mapNotNull { it.player }
                theirs.remove(deal.star)
                mine.removeAll(sentPlayers.toSet())

                val starDead = deal.star.contract?.deadCap(year)?.thisYear ?: 0
                dead[deal.seller.id.v] = (dead[deal.seller.id.v] ?: 0) + starDead
                mine += deal.star.copy(teamId = buyer.id, yearsInSystem = 0)
                moves += TradeMove(
                    deal.star.id.v, deal.star.name, deal.star.position.label,
                    deal.seller.id.v, buyer.id.v,
                    overall(deal.star, scheme(buyer.id, deal.star.position)), starDead, STAR_REASON,
                )
                sentPlayers.forEach { young ->
                    val owed = young.contract?.deadCap(year)?.thisYear ?: 0
                    dead[buyer.id.v] = (dead[buyer.id.v] ?: 0) + owed
                    theirs += young.copy(teamId = deal.seller.id, yearsInSystem = 0)
                    moves += TradeMove(
                        young.id.v, young.name, young.position.label,
                        buyer.id.v, deal.seller.id.v,
                        overall(young, scheme(deal.seller.id, young.position)), owed, YOUNG_REASON,
                    )
                }
                deal.pkg.mapNotNull { it.pick }.forEach { pick ->
                    held[held.indexOf(pick)] = pick.copy(owner = deal.seller.id.v)
                    pickTrades += PickTrade(pick.year, pick.round, pick.original,
                        buyer.id.v, deal.seller.id.v, PICK_REASON)
                }
                sold += deal.seller.id
            }
        }

        return Result(
            roster.values.flatten() + players.filter { it.teamId == null },
            dead, moves, held, pickTrades,
        )
    }

    /** Both clubs still under the cap once the contracts and the dead money have moved. */
    private fun fits(
        buyerRoster: List<Player>,
        sellerRoster: List<Player>,
        star: Player,
        pkg: List<Player>,
        year: Int,
        buyerDead: Int,
        sellerDead: Int,
    ): Boolean {
        val buyerOwes = buyerDead + pkg.sumOf { it.contract?.deadCap(year)?.thisYear ?: 0 }
        val sellerOwes = sellerDead + (star.contract?.deadCap(year)?.thisYear ?: 0)
        return CapManagement.spaceFor(buyerRoster - pkg.toSet() + star, year, buyerOwes) >= 0 &&
            CapManagement.spaceFor(sellerRoster - star + pkg, year, sellerOwes) >= 0
    }

    /** A winning record that makes a club think it is close. */
    private const val CONTENDER = 0.55f

    /** Win now from which a good club goes looking; at or below SELLER_WIN_NOW a club will sell. */
    private const val BUYER_WIN_NOW = 0.6f
    private const val SELLER_WIN_NOW = 0.4f

    /** Need at a position that counts as a real hole, and how many a club can have and still be close. */
    private const val HOLE = 0.35f
    private const val MAX_HOLES = 2

    /** Positions nobody trades for a star at. */
    private val NOT_A_HOLE = setOf(Position.FB, Position.K, Position.P, Position.LS)

    /** A star: this good, and old enough to be proven. Risk tolerance sets the upper age, 30 to 34. */
    private const val STAR_OVERALL = 78
    private const val STAR_MIN_AGE = 27

    /** Young enough to be the future a rebuilding club is buying. */
    private const val YOUNG_AGE = 25

    /** How many of the pieces a seller wants most it will build a package from. */
    private const val PACKAGE_POOL = 6

    /** Rating points a star has to add over the contender's best at the position. */
    private const val CLEAR_UPGRADE = 6f

    /** How much more a seller wants back than it gives, on its own valuation. */
    private const val SELLER_MARGIN = 0.05f

    /** How far past break-even the most aggressive contender will go. */
    private const val AGGRESSION_OVERPAY = 0.25f

    /** Aggression from which a contender goes back for a second player. */
    private const val SECOND_DEAL_AGGRESSION = 0.75f
}
