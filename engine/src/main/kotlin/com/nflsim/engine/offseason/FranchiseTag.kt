package com.nflsim.engine.offseason

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import kotlinx.serialization.Serializable

/** A tag a club put on a player it could not re-sign. */
@Serializable
data class Tag(
    val player: Int,
    val name: String,
    val position: String,
    val team: Int,
    /** The one-year tender, in thousands. */
    val price: Int,
    val kind: String,
    /** How many times in a row this club has tagged him, this one included. */
    val times: Int = 1,
)

/**
 * The franchise and transition tags (2020 CBA, Article 10), SPEC 7 phase 5.
 *
 * Each club may tag one player whose contract has run out and who would not
 * re-sign. The franchise tag is a fully guaranteed one-year tender at the
 * average of the five biggest cap hits at his CBA position; he stays. The
 * transition tag is the top ten's average, and he goes to market, but his
 * club may match the offer he takes - and if nobody offers, he plays on the
 * tender. A second consecutive tag costs 120%, a third 144% or the
 * quarterback tag, whichever is more.
 *
 * Simplified: prices use this year's cap hits, not the CBA's five-year cap
 * shares, and there are no offer sheets on a franchise tag - AI clubs almost
 * never give up two firsts for one, and neither do real ones.
 */
object FranchiseTag {

    const val FRANCHISE = "franchise"
    const val TRANSITION = "transition"

    data class Result(val players: List<Player>, val tags: List<Tag>, val rightToMatch: Map<Int, TeamId>)

    /** The CBA's tag positions. */
    fun group(pos: Position): String = when (pos) {
        Position.QB -> "QB"
        Position.RB, Position.FB -> "RB"
        Position.WR -> "WR"
        Position.TE -> "TE"
        Position.LT, Position.LG, Position.C, Position.RG, Position.RT -> "OL"
        Position.EDGE -> "DE"
        Position.DT -> "DT"
        Position.LB -> "LB"
        Position.CB -> "CB"
        Position.S -> "S"
        Position.K, Position.P, Position.LS -> "K/P"
    }

    /** The tag price at every tag position: the mean of the [top] biggest cap hits there. */
    fun prices(players: List<Player>, year: Int, top: Int): Map<String, Int> =
        players.filter { it.teamId != null && it.contract != null }
            .groupBy { group(it.position) }
            .mapValues { (_, g) -> g.map { it.capHit(year) }.sortedDescending().take(top).average().toInt() }

    fun price(p: Player, kind: String, franchise: Map<String, Int>, transition: Map<String, Int>): Int {
        val base = (if (kind == FRANCHISE) franchise else transition)[group(p.position)] ?: 0
        return when (p.timesTagged) {
            0 -> base
            1 -> (base * SECOND).toInt()
            else -> maxOf((base * THIRD).toInt(), franchise["QB"] ?: 0)
        }
    }

    fun run(
        league: League,
        players: List<Player>,
        previousTeam: Map<Int, TeamId>,
        deadMoney: Map<Int, Int>,
        scheme: (TeamId?, Position) -> Scheme,
        pricer: MarketValue.Pricer,
        year: Int,
        /** A club whose tag is decided elsewhere - the user's. */
        skip: TeamId? = null,
    ): Result {
        val franchise = prices(players, year, FRANCHISE_TOP)
        val transition = prices(players, year, TRANSITION_TOP)
        val byId = players.associateBy { it.id.v }.toMutableMap()
        val tags = mutableListOf<Tag>()
        val rightToMatch = mutableMapOf<Int, TeamId>()

        league.teams.forEach { team ->
            if (team.id == skip) return@forEach
            val expiring = players.filter { it.teamId == null && previousTeam[it.id.v] == team.id }
            if (expiring.isEmpty()) return@forEach
            val space = CapManagement.spaceFor(
                players.filter { it.teamId == team.id }, year, deadMoney[team.id.v] ?: 0,
                carryover = team.finances.carryover)
            // The best player it could not keep, if he is worth near what the
            // tag costs and it fits - the franchise tag first, then the cheaper one.
            val (p, kind, cost) = expiring
                .map { it to pricer.annual(it, scheme(team.id, it.position), year) }
                .sortedByDescending { it.second }
                .firstNotNullOfOrNull { (p, worth) ->
                    listOf(FRANCHISE, TRANSITION)
                        .map { kind -> kind to price(p, kind, franchise, transition) }
                        .firstOrNull { (kind, cost) ->
                            cost in 1..space && worth >= cost * if (kind == FRANCHISE) league.tuning.ai.tagWorth else league.tuning.ai.tagTransitionWorth
                        }
                        ?.let { (kind, cost) -> Triple(p, kind, cost) }
                } ?: return@forEach

            tags += Tag(p.id.v, p.name, p.position.label, team.id.v, cost, kind, p.timesTagged + 1)
            byId[p.id.v] = if (kind == FRANCHISE) {
                p.copy(teamId = team.id, status = PlayerStatus.ACTIVE, timesTagged = p.timesTagged + 1,
                    contract = tender(cost, year))
            } else {
                rightToMatch[p.id.v] = team.id
                p.copy(timesTagged = p.timesTagged + 1)
            }
        }
        return Result(players.map { byId.getValue(it.id.v) }, tags, rightToMatch)
    }

    /** Both tags' prices by CBA position this year: franchise, then transition. */
    fun prices(players: List<Player>, year: Int): Pair<Map<String, Int>, Map<String, Int>> =
        prices(players, year, FRANCHISE_TOP) to prices(players, year, TRANSITION_TOP)

    /** A one-year tender, all of it guaranteed. */
    fun tender(price: Int, year: Int): Contract =
        Contract.of(years = 1, totalValue = price, signedYear = year, bonusShare = 0f, guaranteedShare = 1f)

    /** How many of the biggest cap hits set each tag. */
    const val FRANCHISE_TOP = 5
    const val TRANSITION_TOP = 10

    /** Consecutive tags. */
    private const val SECOND = 1.2f
    private const val THIRD = 1.44f

    // How much a man must be worth to be tagged is tuning: ai.tagWorth and
    // ai.tagTransitionWorth, with the sweep that set them.
}
