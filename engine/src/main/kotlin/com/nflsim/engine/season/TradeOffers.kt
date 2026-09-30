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
 * Which clubs call is drawn from the dynasty seed split by the week, from a
 * stream nothing else reads: asking for the week's calls again gets the same
 * calls, and no call changes the sim unless the user takes it. Nothing is
 * saved. Accepting one is making it at the trade desk.
 */
object TradeOffers {

    /** A club's call: the deal as the user would make it, and what its GM said. */
    data class Offer(val proposal: TradeDesk.Proposal, val target: Int, val pitch: Banter.Quote)

    /** The week's calls, best for the calling club first. Empty outside the trade window. */
    fun thisWeek(dynasty: Dynasty): List<Offer> {
        if (!TradeDesk.open(dynasty)) return emptyList()
        return calls(TradeDesk.inSeason(dynasty), dynasty, dynasty.userTeamId.let { dynasty.league.team(it) })
    }

    private fun calls(book: TradeDesk.Book, dynasty: Dynasty, user: Team): List<Offer> {
        val t = book.league.tuning.ai
        val rng = SplitMixRng(dynasty.seed).split("trade-calls|${dynasty.league.year}|${dynasty.week}")
        // Every club rolls, in league order, so who calls doesn't depend on who else could.
        val calling = book.league.teams.filter { it.id != user.id }.filter { rng.nextFloat() < t.tradeOfferCallChance }
        val mine = active(book, user)
        return calling.mapNotNull { club -> best(book, user, club, mine) }
            .sortedByDescending { it.second }
            .take(t.tradeOffersMax)
            .map { it.first }
    }

    /** The club's best call on the user's roster, and how much it gains: null if it has none worth making. */
    private fun best(book: TradeDesk.Book, user: Team, club: Team, mine: List<Player>): Pair<Offer, Float>? {
        val t = book.league.tuning.ai
        val theirs = active(book, club)
        val bestAt = theirs.groupBy { it.position }.mapValues { (_, g) -> g.maxOf { TradeDesk.value(book, it, club) } }
        val targets = mine.filter { it.position !in NOT_A_TARGET }
            .map { it to TradeDesk.value(book, it, club) - (bestAt[it.position] ?: 0f) }
            .filter { (_, gain) -> gain >= t.tradeClearUpgrade }
            .sortedByDescending { it.second }
        if (targets.isEmpty()) return null

        // What the club can part with: anyone but its best at a position, and its picks.
        val bestIds = theirs.groupBy { it.position }.values.mapNotNull { g -> g.maxByOrNull { TradeDesk.value(book, it, club) }?.id }.toSet()
        val spare = theirs.filter { it.id !in bestIds }
        val picks = book.picks.filter { it.owner == club.id.v }
        // The pieces the user's club would want most, singly and in pairs.
        val pieces = (spare.map { Piece(it, null, TradeDesk.value(book, it, user)) } +
            picks.map { Piece(null, it, TradeDesk.value(book, it, user)) })
            .filter { it.toUser > 0f }
            .sortedByDescending { it.toUser }
            .take(t.tradeOfferPool)
        val packages = pieces.map { listOf(it) } +
            pieces.indices.flatMap { i -> (i + 1 until pieces.size).map { j -> listOf(pieces[i], pieces[j]) } }

        for ((star, gain) in targets) {
            val worth = TradeDesk.value(book, star, user)
            val deal = packages
                .filter { pkg -> pkg.sumOf { it.toUser.toDouble() } >= worth }
                .sortedByDescending { pkg -> pkg.sumOf { it.toUser.toDouble() } }
                .map { pkg ->
                    TradeDesk.Proposal(
                        club.id,
                        give = setOf(star.id.v),
                        get = pkg.mapNotNull { it.player?.id?.v }.toSet(),
                        getPicks = pkg.mapNotNull { it.pick },
                    )
                }
                // The most it would give that it would still take.
                .firstOrNull { TradeDesk.evaluate(book, user.id, it).accepted }
                ?: continue
            val pitch = Banter.gm(book.league.seed, club, "gm.offer.${Banter.tone(club)}", "call|${book.year}|${star.id.v}",
                "player" to star.lastName, "club" to user.nickname)
            return Offer(deal, star.id.v, pitch) to gain
        }
        return null
    }

    private data class Piece(val player: Player?, val pick: PickAsset?, val toUser: Float)

    private fun active(book: TradeDesk.Book, team: Team) =
        book.players.filter { it.teamId == team.id && it.status == PlayerStatus.ACTIVE }

    /** Positions nobody calls about: the same the league's contenders never trade for. */
    private val NOT_A_TARGET = setOf(Position.FB, Position.K, Position.P, Position.LS)
}
