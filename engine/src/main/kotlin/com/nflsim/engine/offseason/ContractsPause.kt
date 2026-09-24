package com.nflsim.engine.offseason

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.season.Awards

/** What the user's club does with a man whose deal has run out. */
enum class ContractChoice { RESIGN, FRANCHISE, TRANSITION, WALK }

/** A choice, with the deal's length and shape when it is a re-signing. */
data class ContractDecision(
    val choice: ContractChoice,
    val years: Int? = null,
    val structure: ContractOptions.Structure? = null,
)

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

    /** Every deal he will sign: his term and a year either side, each written three ways. */
    fun deals(e: Expiring): List<ContractOptions.Deal> =
        ContractOptions.deals(e.asking, e.years, year, ctx.league.tuning, guaranteedShare = RESIGN_GUARANTEE)

    /** The deal a [ContractDecision] describes, or his own terms written the standard way. */
    private fun deal(e: Expiring, d: ContractDecision): ContractOptions.Deal {
        val all = deals(e)
        return all.firstOrNull { it.years == (d.years ?: e.years) && it.structure == (d.structure ?: ContractOptions.Structure.STANDARD) }
            ?: all.first { it.years == e.years && it.structure == ContractOptions.Structure.STANDARD }
    }

    /** What the club should do with him, why, and what its own front office would have done. */
    data class Recommendation(
        val decision: ContractDecision,
        val headline: String,
        val why: String,
        val frontOffice: ContractChoice,
    )

    /**
     * The best call on each expiring man, judged on what he is to this club:
     * whether he starts, whether he asks about what he is worth, whether he
     * is young enough to pay through, and whether it fits. Made down the list
     * most valuable first, so the one tag goes to the man it suits best and
     * the room runs out where it would.
     *
     * Not the front office's call: that comes from one GM's habits - how much
     * of the cap he spends on his own, how loyal he is - and a club with a
     * hundred million of room was being told to let a 24-year-old starting
     * corner walk at his market rate.
     */
    val recommendations: Map<Int, Recommendation> by lazy {
        val t = ctx.league.tuning.ai
        val cap = CapManagement.capFor(year)
        val min = com.nflsim.engine.model.Contract.MIN_BASE_SALARY
        var room = capSpace
        var tagUsed = false
        val roster = state.players.filter { it.teamId == userTeam }
        val out = mutableMapOf<Int, Recommendation>()

        expiring.forEach { e ->
            val p = e.player
            val age = p.age(year)
            val scheme = ctx.scheme(userTeam, p.position)
            val rank = roster.count {
                it.position == p.position && com.nflsim.engine.ratings.overall(it, scheme) >
                    com.nflsim.engine.ratings.overall(p, scheme)
            }
            val starter = rank < (TeamNeeds.STARTERS[p.position] ?: 1)
            val role = if (starter) "he would start for you" else "he would be a backup"
            val fair = e.asking <= e.market * t.fairAskShare
            val old = age > t.payThroughAge
            val front = suggested[p.id.v] ?: ContractChoice.WALK

            fun walk(why: String) = Recommendation(ContractDecision(ContractChoice.WALK), "Let him go", why, front)
            fun keep(why: String): Recommendation? {
                val advice = ContractOptions.bestDeal(p, deals(e), e.years, room, cap, year, ctx.league.tuning)
                val d = advice.pick
                if (d.capNow > room) return null
                room -= d.capNow
                return Recommendation(
                    ContractDecision(ContractChoice.RESIGN, d.years, d.structure),
                    "Re-sign: ${d.years} ${if (d.years == 1) "year" else "years"} at ${money(d.annual)}, " +
                        d.structure.label.lowercase(),
                    "$why ${advice.why}", front)
            }
            fun tag(kind: ContractChoice, why: String): Recommendation? {
                if (tagUsed) return null
                val price = if (kind == ContractChoice.FRANCHISE) e.franchise else 0
                if (price > room) return null
                tagUsed = true
                room -= price
                return Recommendation(ContractDecision(kind),
                    if (kind == ContractChoice.FRANCHISE) "Franchise tag: ${money(e.franchise)} for one year"
                    else "Transition tag", why, front)
            }

            out[p.id.v] = when {
                e.market <= min * 2 -> walk("A man at his level is replaced for the minimum.")
                old && starter && e.franchise <= e.asking * t.veteranTagShare ->
                    tag(ContractChoice.FRANCHISE, "At $age $role, but not for ${e.years} years: one " +
                        "guaranteed year on the tender keeps him without the years he will not be worth.")
                        ?: keep("At $age $role.")
                        ?: walk("At $age, and with the room gone on the men above him.")
                old -> walk("At $age he is past the age a club pays through, and $role.")
                starter && fair -> keep("$role, and ${money(e.asking)} is about what he is worth.")
                    ?: walk("$role, but there is no room left after the men above him.")
                starter -> tag(ContractChoice.TRANSITION, "$role, but he asks ${money(e.asking)} against a " +
                    "market of ${money(e.market)}. Let the market price him and keep the right to match.")
                    ?: walk("$role, but he asks more than he is worth and the tag is spoken for.")
                e.asking <= min * t.cheapDepthMinimums -> keep("Cheap depth: $role at ${money(e.asking)}.")
                    ?: walk("There is no room for depth after the men above him.")
                else -> walk("$role, and ${money(e.asking)} a year is money better spent in free agency.")
            }
        }
        // Reasons are built from parts, some of which open with "he".
        out.mapValues { (_, r) -> r.copy(why = r.why.replaceFirstChar { it.uppercase() }) }
    }

    fun recommend(e: Expiring): Recommendation = recommendations.getValue(e.player.id.v)

    /** Cap room before any of it, against the dead money the club is carrying. */
    val capSpace: Int get() = CapManagement.spaceFor(
        state.players.filter { it.teamId == userTeam }, year, state.deadMoney[userTeam.v] ?: 0)

    /**
     * What a set of choices costs this year's cap. A transition tag costs
     * nothing now: he goes to market, and the club pays only if it matches.
     */
    fun cost(decisions: Map<Int, ContractDecision>): Int = expiring.sumOf { e ->
        val d = decisions[e.player.id.v] ?: return@sumOf 0
        when (d.choice) {
            ContractChoice.RESIGN -> deal(e, d).capNow
            ContractChoice.FRANCHISE -> e.franchise
            else -> 0
        }
    }

    /** The rest of the way to the draft. Null leaves the user's club to the AI. */
    fun decide(decisions: Map<Int, ContractDecision>?): OffseasonEngine.DraftPause =
        OffseasonEngine.continueToDraft(this, decisions)

    /** On to free agency, where the user's club makes its own offers. Null leaves these calls to the AI. */
    fun toFreeAgency(decisions: Map<Int, ContractDecision>?): FreeAgencyPause =
        OffseasonEngine.continueToFreeAgency(this, decisions)

    /** The same, from bare choices: each re-signing on his own terms, written the standard way. */
    fun decideChoices(choices: Map<Int, ContractChoice>): OffseasonEngine.DraftPause {
        val decisions: Map<Int, ContractDecision> = choices.mapValues { ContractDecision(it.value) }
        return OffseasonEngine.continueToDraft(this, decisions)
    }

    /**
     * The user's decisions, applied: one tag at most, as the CBA allows, and
     * only what fits under the cap, in the order the screen lists them.
     */
    internal fun apply(decisions: Map<Int, ContractDecision>): OffseasonState {
        val byId = state.players.associateBy { it.id.v }.toMutableMap()
        var space = capSpace
        val signings = mutableListOf<Signing>()
        val tags = mutableListOf<Tag>()
        val rightToMatch = mutableMapOf<Int, TeamId>()
        var tagged = false

        expiring.forEach { e ->
            val p = e.player
            val d = decisions[p.id.v]
            when (d?.choice) {
                ContractChoice.RESIGN -> {
                    val deal = deal(e, d)
                    if (deal.capNow > space) return@forEach
                    byId[p.id.v] = Extensions.kept(p, userTeam, deal.annual, year).copy(contract = deal.contract)
                    space -= deal.capNow
                    signings += Signing(p.id.v, p.name, p.position.label, userTeam.v,
                        deal.annual, deal.years, e.market, suitors = 1)
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

    private fun money(thousands: Int): String =
        if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)

    private companion object {
        /** What a club guarantees on a re-signing (Extensions). */
        const val RESIGN_GUARANTEE = 0.50f
    }
}
