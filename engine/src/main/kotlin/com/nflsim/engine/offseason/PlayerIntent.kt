package com.nflsim.engine.offseason

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.rng.Rng
import kotlinx.serialization.Serializable

/** What a player has told his team he wants. */
@Serializable
enum class Intent { CONTENT, WANTS_TO_WIN, WANTS_A_ROLE, WANTS_PAYING, TRADE_REQUEST }

@Serializable
data class Wish(
    val player: Int,
    val name: String,
    val position: String,
    val team: Int,
    val overall: Int,
    val intent: Intent,
    /** What he actually said, for the news screen. */
    val note: String,
)

@Serializable
data class TradeMove(
    val player: Int,
    val name: String,
    val position: String,
    val from: Int,
    val to: Int,
    val overall: Int,
    /** What the old team ate to move him. */
    val deadMoney: Int,
    val reason: String,
)

/**
 * Players who want something, and say so.
 *
 * A roster of men who go wherever the money is and never have a view about it
 * is the least realistic thing about a football simulation. Real players ask
 * out. A thirty-two year old on a four win team knows exactly how many
 * seasons he has left. A good player buried behind a better one wants to
 * play. An underpaid one has noticed.
 *
 * Three things make a player unhappy here, and which one bites hardest
 * decides what he asks for:
 *
 *  - **Losing**, weighted by how little time he has left. Winning matters
 *    more at thirty-three than at twenty-three, which is why veterans on bad
 *    teams are the ones who force moves.
 *  - **Not playing**, when he is good enough to start somewhere.
 *  - **Being underpaid** against what the market would give him.
 *
 * Loyalty decides whether he says it out loud. A high-loyalty player carries
 * the same grievance quietly and re-signs anyway.
 */
object PlayerIntent {

    data class Context(
        val winPct: (TeamId) -> Float,
        val scheme: (TeamId?, Position) -> Scheme,
        val pricer: MarketValue.Pricer,
        val depthRank: Map<Int, Int>,
        val year: Int,
    )

    fun assess(league: League, players: List<Player>, ctx: Context, rng: Rng): List<Wish> {
        val wishes = mutableListOf<Wish>()

        players.filter { it.teamId != null && it.contract != null }.forEach { p ->
            val team = p.teamId!!
            val sch = ctx.scheme(team, p.position)
            val ovr = overall(p, sch)
            val age = p.age(ctx.year)

            // Only players who matter push. A camp body has no leverage and
            // knows it.
            if (ovr < VOICE_THRESHOLD) return@forEach

            val losing = ((1f - ctx.winPct(team)) - 0.45f).coerceAtLeast(0f) * 2f *
                ((age - 26).coerceAtLeast(0) / 6f).coerceIn(0.25f, 1.4f)

            val rank = ctx.depthRank[p.id.v] ?: 0
            val buried = if (rank >= 1 && ovr >= STARTER_QUALITY) 0.35f + rank * 0.22f else 0f

            val worth = ctx.pricer.annual(p, sch, ctx.year)
            val paid = p.capHit(ctx.year).coerceAtLeast(1)
            val underpaid = ((worth.toFloat() / paid) - 1.35f).coerceIn(0f, 1.2f)

            val worst = maxOf(losing, buried, underpaid)
            if (worst < GRUMBLE) return@forEach

            val intent = when (worst) {
                losing -> Intent.WANTS_TO_WIN
                buried -> Intent.WANTS_A_ROLE
                else -> Intent.WANTS_PAYING
            }

            // Whether he says it out loud, and how far he goes. A loyal player
            // carries the same grievance quietly.
            //
            // A man who wants paying wants paying - he does not want a new
            // city, he wants his own club to fix it. Treating money the same
            // as the other grievances had 173 players a year demanding trades,
            // most of them rookies on slotted deals and minimum-salary players
            // who had got good. They are underpaid by construction; that is
            // what a rookie contract is.
            val pressure = if (intent == Intent.WANTS_PAYING) worst * MONEY_PATIENCE else worst
            val nerve = pressure - p.traits.loyalty / 160f + rng.gaussian(0f, 0.18f)
            val demands = nerve > DEMAND && p.contract!!.isActive(ctx.year + 1)

            wishes += Wish(
                p.id.v, p.name, p.position.label, team.v, ovr,
                if (demands) Intent.TRADE_REQUEST else intent,
                note = when {
                    demands && intent == Intent.WANTS_TO_WIN ->
                        "wants to be traded to a contender"
                    demands && intent == Intent.WANTS_A_ROLE ->
                        "wants a trade somewhere he can start"
                    demands -> "wants a trade or a new deal"
                    intent == Intent.WANTS_TO_WIN -> "has told the club he wants to win now"
                    intent == Intent.WANTS_A_ROLE -> "wants a bigger role"
                    else -> "wants his contract addressed"
                },
            )
        }

        return wishes
    }

    /**
     * Granting the requests that can be granted.
     *
     * No draft picks change hands yet - that is SPEC 8.4, and it needs a pick
     * asset model this engine does not have. What makes these trades cost
     * something in the meantime is the cap: the new team takes the contract
     * as it stands, and the old team eats the bonus money it already paid.
     * A team cannot get value by shipping out a player it simply cannot
     * afford, because the dead money follows it.
     */
    fun resolveTrades(
        league: League,
        players: List<Player>,
        wishes: List<Wish>,
        deadMoney: Map<Int, Int>,
        ctx: Context,
        rng: Rng,
    ): Triple<List<Player>, Map<Int, Int>, List<TradeMove>> {
        val roster = players.filter { it.teamId != null }
            .groupBy { it.teamId!! }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
        val dead = deadMoney.toMutableMap()
        val moves = mutableListOf<TradeMove>()

        val asked = wishes.filter { it.intent == Intent.TRADE_REQUEST }
            .sortedByDescending { it.overall }

        asked.forEach { wish ->
            val from = TeamId(wish.team)
            val player = roster[from]?.firstOrNull { it.id.v == wish.player } ?: return@forEach
            val contract = player.contract ?: return@forEach
            val hit = player.capHit(ctx.year)

            // A team only trades him if it is willing to let him go, and it is
            // more willing when he is old, expensive, or has said he is done.
            val keepRng = rng.split("trade|${wish.player}")
            if (keepRng.nextFloat() > GRANT_CHANCE) return@forEach

            val suitor = league.teams
                .filter { it.id != from }
                .mapNotNull { t ->
                    val theirs = roster.getOrPut(t.id) { mutableListOf() }
                    val space = CapManagement.spaceFor(theirs, ctx.year, dead[t.id.v] ?: 0)
                    if (space < hit) return@mapNotNull null

                    val sch = ctx.scheme(t.id, player.position)
                    val worth = ctx.pricer.annual(player, sch, ctx.year)
                    // Nobody trades for a contract that is already bad.
                    if (worth < hit * WORTH_IT) return@mapNotNull null

                    val winning = ctx.winPct(t.id)
                    // He asked for a reason; a team that does not fix it is
                    // not a destination.
                    val fixesIt = when (wish.intent) {
                        Intent.TRADE_REQUEST -> winning >= CONTENDER ||
                            theirs.count { it.position == player.position } <=
                                TeamNeeds.requiredStarters(player.position)
                        else -> true
                    }
                    if (!fixesIt) return@mapNotNull null

                    t.id to schemeFit(player, sch) * 6f + winning * 10f +
                        (worth - hit) / 1_500f + keepRng.gaussian(0f, 2f)
                }
                .maxByOrNull { it.second }?.first ?: return@forEach

            val deadCap = contract.deadCap(ctx.year).thisYear
            roster[from]?.remove(player)
            dead[from.v] = (dead[from.v] ?: 0) + deadCap
            roster.getOrPut(suitor) { mutableListOf() } +=
                player.copy(teamId = suitor, yearsInSystem = 0)

            moves += TradeMove(
                player.id.v, player.name, player.position.label,
                from.v, suitor.v, wish.overall, deadCap, wish.note,
            )
        }

        val free = players.filter { it.teamId == null }
        return Triple(roster.values.flatten() + free, dead, moves)
    }

    /** Below this a player has no leverage and knows it. */
    private const val VOICE_THRESHOLD = 68

    /** Good enough to start somewhere, if not here. */
    private const val STARTER_QUALITY = 72

    /** Unhappy enough to say something. */
    private const val GRUMBLE = 0.30f

    /** Unhappy enough to ask out. */
    private const val DEMAND = 0.62f

    /** How much less a money grievance pushes a player out the door. */
    private const val MONEY_PATIENCE = 0.42f

    /** Not every request gets granted. Most teams say no first. */
    private const val GRANT_CHANCE = 0.45f

    /** A suitor has to value him above what he costs. */
    private const val WORTH_IT = 1.15f

    /** Win rate that reads as a contender to a player who wants to win. */
    private const val CONTENDER = 0.55f
}
