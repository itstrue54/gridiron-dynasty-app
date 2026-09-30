package com.nflsim.engine.season

import com.nflsim.engine.model.PickAsset
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.Team
import com.nflsim.engine.narrative.Banter
import com.nflsim.engine.rng.SplitMixRng

/**
 * Clubs that call the user (SPEC 8.4): in the season, up to the deadline,
 * a few of the league's clubs each week come asking for one of his men.
 *
 * A club calls about a man who would be a clear upgrade on its best at his
 * position - the same bar a contender sets for a star
 * (`ai.tradeClearUpgrade`). It offers the most it would give that it would
 * still take at the trade desk, and it only calls when the offer is worth
 * at least the man to the user's own club, on the user's club's timeline.
 * So a contender and a rebuilder can both come out ahead: they don't count
 * a pick or a veteran the same way.
 *
 * A man the user has put on his trade block needs only to be an upgrade
 * (`ai.tradeBlockUpgrade`), and a club he would help calls more often
 * (`ai.tradeBlockCallChance`). Calls about the block come first.
 *
 * Which clubs call is drawn from the dynasty seed split by the week, from a
 * stream nothing else reads: asking for the week's calls again gets the same
 * calls, and no call changes the sim unless the user takes it. Nothing is
 * saved. Accepting one is making it at the trade desk.
 */
object TradeOffers {

    /** A club's call: the deal as the user would make it, and what its GM said. */
    data class Offer(val proposal: TradeDesk.Proposal, val target: Int, val pitch: Banter.Quote, val onBlock: Boolean = false)

    /** The men on the user's trade block who are still his. */
    fun block(dynasty: Dynasty): Set<Int> =
        dynasty.tradeBlock.filter { dynasty.league.playersById[com.nflsim.engine.model.PlayerId(it)]?.teamId == dynasty.userTeamId }.toSet()

    /** [dynasty] with the user's man [playerId] put on the block, or taken off it. Only his own men go on. */
    fun setOnBlock(dynasty: Dynasty, playerId: Int, on: Boolean): Dynasty {
        val mine = dynasty.league.playersById[com.nflsim.engine.model.PlayerId(playerId)]?.teamId == dynasty.userTeamId
        val now = block(dynasty)
        return dynasty.copy(tradeBlock = if (on && mine) now + playerId else now - playerId)
    }

    /** The week's calls, best for the calling club first. Empty outside the trade window. */
    fun thisWeek(dynasty: Dynasty): List<Offer> {
        if (!TradeDesk.open(dynasty)) return emptyList()
        return calls(TradeDesk.inSeason(dynasty), dynasty, dynasty.userTeamId.let { dynasty.league.team(it) })
    }

    private fun calls(book: TradeDesk.Book, dynasty: Dynasty, user: Team): List<Offer> {
        val t = book.league.tuning.ai
        val rng = SplitMixRng(dynasty.seed).split("trade-calls|${dynasty.league.year}|${dynasty.week}")
        val mine = active(book, user)
        val block = block(dynasty)
        // Every club rolls once, in league order, so who calls doesn't depend
        // on who else could; one a man on the block would help rolls against
        // the block's chance.
        val calling = book.league.teams.filter { it.id != user.id }.filter { club ->
            val roll = rng.nextFloat()
            val wantsBlock = block.isNotEmpty() && targets(book, club, mine.filter { it.id.v in block }, t.tradeBlockUpgrade).isNotEmpty()
            roll < if (wantsBlock) t.tradeBlockCallChance else t.tradeOfferCallChance
        }
        return calling.mapNotNull { club -> best(book, user, club, mine, block) }
            .sortedWith(compareByDescending<Pair<Offer, Float>> { it.first.onBlock }.thenByDescending { it.second })
            .take(t.tradeOffersMax)
            .map { it.first }
    }

    /** The men [club] would call about, with how much each would add over its best at his position, best first. */
    private fun targets(book: TradeDesk.Book, club: Team, men: List<Player>, bar: Float): List<Pair<Player, Float>> {
        val bestAt = active(book, club).groupBy { it.position }.mapValues { (_, g) -> g.maxOf { TradeDesk.value(book, it, club) } }
        return men.filter { it.position !in NOT_A_TARGET }
            .map { it to TradeDesk.value(book, it, club) - (bestAt[it.position] ?: 0f) }
            .filter { (_, gain) -> gain >= bar && gain > 0f }
            .sortedByDescending { it.second }
    }

    /** The club's best call on the user's roster, and how much it gains: null if it has none worth making. */
    private fun best(book: TradeDesk.Book, user: Team, club: Team, mine: List<Player>, block: Set<Int>): Pair<Offer, Float>? {
        val t = book.league.tuning.ai
        val theirs = active(book, club)
        // The block first, then anyone else it would clearly upgrade on.
        val targets = targets(book, club, mine.filter { it.id.v in block }, t.tradeBlockUpgrade) +
            targets(book, club, mine.filter { it.id.v !in block }, t.tradeClearUpgrade)
        if (targets.isEmpty()) return null

        // What the club can part with: anyone but its best at a position, and its picks.
        val bestIds = theirs.groupBy { it.position }.values.mapNotNull { g -> g.maxByOrNull { TradeDesk.value(book, it, club) }?.id }.toSet()
        val spare = theirs.filter { it.id !in bestIds }
        val picks = book.picks.filter { it.owner == club.id.v }
        val all = spare.map { Piece(it, null, TradeDesk.value(book, it, user), TradeDesk.value(book, it, club)) } +
            picks.map { Piece(null, it, TradeDesk.value(book, it, user), TradeDesk.value(book, it, club)) }

        for ((star, gain) in targets) {
            val worth = TradeDesk.value(book, star, user)
            // The most the club would give for him and still take the deal.
            val budget = TradeDesk.value(book, star, club) / (1f + t.tradeSellerMargin)
            // Every piece it could part with for him, alone or with one more:
            // the packages worth his price to the user's club that it would
            // still take, the most for the user first.
            val affordable = all.filter { it.toUser > 0f && it.toClub <= budget }
            val packages = affordable.map { listOf(it) } +
                affordable.indices.flatMap { i -> (i + 1 until affordable.size).map { j -> listOf(affordable[i], affordable[j]) } }
            val deal = packages
                .filter { pkg -> pkg.sumOf { it.toUser.toDouble() } >= worth && pkg.sumOf { it.toClub.toDouble() } <= budget }
                // Men for men within both rosters' limits: one comes, the package's men go.
                .filter { pkg -> pkg.count { it.player != null }.let { n ->
                    mine.size - 1 + n <= book.rosterLimit && theirs.size + 1 - n <= book.rosterLimit } }
                .sortedByDescending { pkg -> pkg.sumOf { it.toUser.toDouble() } }
                // The desk checks the roster and the cap as well; the club tries its best few there.
                .take(t.tradeOfferPool)
                .map { pkg ->
                    TradeDesk.Proposal(
                        club.id,
                        give = setOf(star.id.v),
                        get = pkg.mapNotNull { it.player?.id?.v }.toSet(),
                        getPicks = pkg.mapNotNull { it.pick },
                    )
                }
                .firstOrNull { TradeDesk.evaluate(book, user.id, it).accepted }
                ?: continue
            val onBlock = star.id.v in block
            val pitch = Banter.gm(book.league.seed, club, if (onBlock) "gm.offer.block" else "gm.offer.${Banter.tone(club)}",
                "call|${book.year}|${star.id.v}", "player" to star.lastName, "club" to user.nickname)
            return Offer(deal, star.id.v, pitch, onBlock) to gain
        }
        return null
    }

    private data class Piece(val player: Player?, val pick: PickAsset?, val toUser: Float, val toClub: Float)

    private fun active(book: TradeDesk.Book, team: Team) =
        book.players.filter { it.teamId == team.id && it.status == PlayerStatus.ACTIVE }

    /** Positions nobody calls about: the same the league's contenders never trade for. */
    private val NOT_A_TARGET = setOf(Position.FB, Position.K, Position.P, Position.LS)
}
