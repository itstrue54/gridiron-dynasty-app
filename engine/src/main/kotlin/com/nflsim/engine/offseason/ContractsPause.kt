package com.nflsim.engine.offseason

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.season.Awards

/** What the user's club does with a man whose deal has run out. */
enum class ContractChoice { RESIGN, FRANCHISE, TRANSITION, WALK }

/**
 * The offseason stopped before re-signing (SPEC 7 phases 5-6), so the
 * user's club decides its own expiring players instead of watching the
 * league's logic decide them.
 *
 * Every price here is the one the league's own clubs are quoted: a man asks
 * his club for what [Extensions.asking] says, and the tags cost what the
 * CBA's top-five and top-ten averages at his position say. The user makes
 * the call the AI would have made, on the same numbers.
 */
class ContractsPause internal constructor(
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

    data class Expiring(
        val player: Player,
        /** What the market will pay him a year. */
        val market: Int,
        /** What he asks his own club for to stay, and for how long. */
        val asking: Int,
        val years: Int,
        /** The one-year franchise tender, and the transition tag's price. */
        val franchise: Int,
        val transition: Int,
        /** What he said he wants, if he said anything (SPEC 7 phase 4). */
        val wish: Wish?,
    )

    /** The user's men whose deals have run out, the most valuable first. */
    val expiring: List<Expiring> by lazy {
        val (franchise, transition) = FranchiseTag.prices(state.players, year)
        val said = wishes.associateBy { it.player }
        state.players
            .filter { it.teamId == null && previousTeam[it.id.v] == userTeam && it.status != PlayerStatus.RETIRED }
            .map { p ->
                val market = pricer.annual(p, ctx.scheme(userTeam, p.position), year)
                Expiring(
                    player = p,
                    market = market,
                    asking = Extensions.asking(p, market),
                    years = MarketValue.termFor(p.age(year), depth = 0),
                    franchise = FranchiseTag.price(p, FranchiseTag.FRANCHISE, franchise, transition),
                    transition = FranchiseTag.price(p, FranchiseTag.TRANSITION, franchise, transition),
                    wish = said[p.id.v],
                )
            }
            .sortedByDescending { it.market }
    }

    /**
     * What the club's own front office would do, which is where the screen
     * starts: tapping straight through keeps the offseason the AI would have
     * run, and the user changes only what he disagrees with. The same logic
     * on the same random draws, for his club alone.
     */
    val suggested: Map<Int, ContractChoice> by lazy {
        val extended = Extensions.run(
            ctx.league, state.players, state.previousTeam, year, state.deadMoney,
            ctx.scheme, pricer, rng.split("extend|$year"),
        )
        val kept = extended.signings.filter { it.team == userTeam.v }.map { it.player }
        val tagged = FranchiseTag.run(
            ctx.league, extended.players, state.previousTeam, state.deadMoney, ctx.scheme, pricer, year,
        ).tags.filter { it.team == userTeam.v }
        kept.associateWith { ContractChoice.RESIGN } + tagged.associate {
            it.player to if (it.kind == FranchiseTag.FRANCHISE) ContractChoice.FRANCHISE else ContractChoice.TRANSITION
        }
    }

    /** Cap room before any of it, against the dead money the club is carrying. */
    val capSpace: Int get() = CapManagement.spaceFor(
        state.players.filter { it.teamId == userTeam }, year, state.deadMoney[userTeam.v] ?: 0)

    /**
     * What a set of choices costs this year's cap. A transition tag costs
     * nothing now: he goes to market, and the club pays only if it matches.
     */
    fun cost(choices: Map<Int, ContractChoice>): Int = expiring.sumOf { e ->
        when (choices[e.player.id.v]) {
            ContractChoice.RESIGN -> Extensions.kept(e.player, userTeam, e.asking, year).capHit(year)
            ContractChoice.FRANCHISE -> e.franchise
            else -> 0
        }
    }

    /** The rest of the way to the draft. Null choices leaves the user's club to the AI. */
    fun decide(choices: Map<Int, ContractChoice>?): OffseasonEngine.DraftPause =
        OffseasonEngine.continueToDraft(this, choices)

    /**
     * The user's decisions, applied: one tag at most, as the CBA allows, and
     * only what fits under the cap, in the order the screen lists them.
     */
    internal fun apply(choices: Map<Int, ContractChoice>): OffseasonState {
        val byId = state.players.associateBy { it.id.v }.toMutableMap()
        var space = capSpace
        val signings = mutableListOf<Signing>()
        val tags = mutableListOf<Tag>()
        val rightToMatch = mutableMapOf<Int, TeamId>()
        var tagged = false

        expiring.forEach { e ->
            val p = e.player
            when (choices[p.id.v]) {
                ContractChoice.RESIGN -> {
                    val kept = Extensions.kept(p, userTeam, e.asking, year)
                    val hit = kept.capHit(year)
                    if (hit > space) return@forEach
                    byId[p.id.v] = kept
                    space -= hit
                    signings += Signing(p.id.v, p.name, p.position.label, userTeam.v,
                        e.asking, kept.contract!!.years, e.market, suitors = 1)
                }
                ContractChoice.FRANCHISE -> {
                    if (tagged || e.franchise !in 1..space) return@forEach
                    tagged = true
                    space -= e.franchise
                    byId[p.id.v] = p.copy(
                        teamId = userTeam, status = PlayerStatus.ACTIVE, timesTagged = p.timesTagged + 1,
                        contract = FranchiseTag.tender(e.franchise, year),
                    )
                    tags += Tag(p.id.v, p.name, p.position.label, userTeam.v, e.franchise,
                        FranchiseTag.FRANCHISE, p.timesTagged + 1)
                }
                ContractChoice.TRANSITION -> {
                    if (tagged || e.transition <= 0) return@forEach
                    tagged = true
                    rightToMatch[p.id.v] = userTeam
                    byId[p.id.v] = p.copy(timesTagged = p.timesTagged + 1)
                    tags += Tag(p.id.v, p.name, p.position.label, userTeam.v, e.transition,
                        FranchiseTag.TRANSITION, p.timesTagged + 1)
                }
                ContractChoice.WALK, null -> {}
            }
        }
        return state.copy(
            players = state.players.map { byId.getValue(it.id.v) },
            extensionSignings = state.extensionSignings + signings,
            tags = state.tags + tags,
            transitionTags = state.transitionTags + rightToMatch,
        )
    }
}
