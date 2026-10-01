package com.nflsim.engine.offseason

import com.nflsim.engine.tuning.TuningTable
import com.nflsim.engine.gen.PlayerGenerator
import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.Scouting
import com.nflsim.engine.ratings.ScoutingLens
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.rng.Rng
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class DraftPick(
    val round: Int,
    val overallPick: Int,
    val team: Int,
    val player: Int,
    /** Whose pick it was before any trade: the slot's original club. */
    val original: Int = 0,
)

/**
 * Where new players come from.
 *
 * The interface is the point: this is how a college football simulator plugs
 * in later without any of the rest changing (docs/SPEC.md 8.5). A synthetic
 * class today, real college production and three years of scouting history
 * whenever that project exists.
 */
interface DraftClassSource {
    fun generate(year: Int, firstId: Int, rng: Rng, tuning: TuningTable): List<Player>
}

object SyntheticDraftClass : DraftClassSource {

    /** Roughly how often each position comes off the board. */
    private val POSITION_MIX: List<Pair<Position, Int>> = listOf(
        Position.WR to 42, Position.CB to 38, Position.EDGE to 34,
        Position.LB to 30, Position.DT to 30, Position.S to 26,
        Position.RB to 26, Position.TE to 22, Position.LT to 18,
        Position.RT to 16, Position.LG to 16, Position.RG to 14,
        Position.C to 12, Position.QB to 18, Position.FB to 4,
        Position.K to 3, Position.P to 3, Position.LS to 3,
    )

    override fun generate(year: Int, firstId: Int, rng: Rng, tuning: TuningTable): List<Player> {
        val depth = tuning.ai.draftBoardDepth
        val pool = mutableListOf<Player>()
        var id = firstId
        val total = POSITION_MIX.sumOf { (it.second * depth).roundToInt() }

        POSITION_MIX.forEach { (position, baseCount) ->
            val count = (baseCount * depth).roundToInt()
            repeat(count) { i ->
                // A handful of generational players, a fat middle, a long tail.
                val percentile = (pool.size + i).toFloat() / total
                val target = talentCurve(rng)
                // Age is set explicitly rather than nudged. PlayerGenerator's
                // age model is built for a veteran roster, and shifting a wide
                // distribution downward still produced twenty-eight year old
                // rookies. A draft class is 21 to 24, full stop.
                val generated = PlayerGenerator.generate(
                    id = PlayerId(id++),
                    position = position,
                    targetOverall = target,
                    year = year,
                    rng = rng,
                )
                pool += generated.copy(
                    birthYear = year - prospectAge(rng),
                    accruedSeasons = 0,
                    yearsInSystem = 0, yearsWithClub = 0,
                )
            }
        }
        return pool
    }

    /** Most declare after three years; a few are fourth or fifth year seniors. */
    private fun prospectAge(rng: Rng): Int {
        val roll = rng.nextFloat()
        return when {
            roll < 0.22f -> 21
            roll < 0.62f -> 22
            roll < 0.90f -> 23
            else -> 24
        }
    }

    /**
     * Prospect quality. Most of a draft class never plays; the top of it is
     * where the franchise-altering players are, and there are not many.
     */
    private fun talentCurve(rng: Rng): Int {
        val roll = rng.nextFloat()
        val base = when {
            roll < 0.012f -> 78 + rng.nextInt(8)   // generational
            roll < 0.06f -> 71 + rng.nextInt(8)    // immediate starter
            roll < 0.20f -> 64 + rng.nextInt(8)    // year two starter
            roll < 0.48f -> 57 + rng.nextInt(8)    // rotational
            else -> 46 + rng.nextInt(11)           // camp body
        }
        return base.coerceIn(40, 88)
    }
}

/**
 * The draft itself.
 *
 * AI teams evaluate through their own scouting error, so they miss on players,
 * and they miss differently from each other and from you. A board everyone
 * agrees on is not a draft, it is a queue.
 */
object DraftRunner {

    const val ROUNDS = 7

    data class Result(
        val picks: List<DraftPick>,
        val drafted: Map<Int, Player>,
        val undrafted: List<Player>,
        /**
         * Who is left on the board. Empty once the draft has run to the end;
         * the rest of the class when it stopped early for a club's own pick.
         */
        val available: List<Player> = emptyList(),
        /** The slot the draft stopped on, if it stopped. */
        val stoppedAt: Int? = null,
    )

    fun run(
        /** Every pick in the draft, in order: its round and the club using it. */
        slots: List<Pair<Int, TeamId>>,
        prospects: List<Player>,
        schemeFor: (TeamId) -> Scheme,
        needsFor: (TeamId) -> Map<Position, Float>,
        year: Int,
        rng: Rng,
        /** How far each club's GM will go; only the aggressive trade up. */
        aggression: (TeamId) -> Float = { 0f },
        /**
         * Offered a round-one trade up: [buyer] moves from overall index [from]
         * to [to], ahead of [seller], and may pay with any of its [later] picks
         * in this draft. Returns the ones it gave up, or null if it cannot pay.
         */
        tradeUp: (buyer: TeamId, seller: TeamId, from: Int, to: Int, later: List<Int>) -> List<Int>? =
            { _, _, _, _, _ -> null },
        /** Each club's scouting department and where it pointed it (SPEC 4.6). */
        scouting: (TeamId) -> Pair<Int, Set<Position>> = { 50 to emptySet() },
        /**
         * A club making its own pick: given the overall number and the club on
         * the clock, the player it takes. Null leaves the pick to the AI.
         */
        userPick: (overall: Int, team: TeamId) -> Int? = { _, _ -> null },
        /**
         * Stop before this slot and hand back the board as it stands, for a
         * club that wants to look before it picks.
         */
        stopBefore: ((overall: Int, team: TeamId) -> Boolean)? = null,
        /** The league's AI tuning: need, fit and scouting error on the board, and trade-ups. */
        ai: TuningTable.Ai = TuningTable.REALISTIC.ai,
        /** How well its scouts read the board. */
        scoutingTuning: TuningTable.Scouting = TuningTable.REALISTIC.scouting,
    ): Result {
        val available = prospects.toMutableList()
        val picks = mutableListOf<DraftPick>()
        val drafted = mutableMapOf<Int, Player>()

        val board = slots.toMutableList()
        val movedUp = mutableSetOf<TeamId>()
        var overallPick = 1
        board.indices.forEach { i ->
            if (available.isEmpty()) return@forEach
            val round = board[i].first
            var team = board[i].second

            // Draft day in round one: the club on the clock does not need the
            // best player left, and an aggressive club a few picks later badly
            // does. They swap firsts and the club moving up pays the chart
            // difference in later picks, this year's or future ones, so the
            // club moving down comes away with more. One move up per club.
            if (round == 1) {
                val best = available.maxBy { overall(it) }
                if ((needsFor(team)[best.position] ?: ai.draftMissingNeed) <= ai.draftPassNeed) {
                    val needOf = { t: TeamId -> needsFor(t)[best.position] ?: 0f }
                    val buyer = (i + 1 until minOf(board.size, i + 1 + ai.draftTradeUpRange))
                        .filter { j ->
                            val t = board[j].second
                            board[j].first == 1 && t != team && t !in movedUp &&
                                aggression(t) >= ai.draftTradeUpAggression && needOf(t) >= ai.draftTradeUpNeed
                        }
                        .maxByOrNull { j -> needOf(board[j].second) + aggression(board[j].second) }
                    val up = buyer?.let { board[it].second }
                    val later = if (buyer == null) emptyList() else (buyer + 1 until board.size).filter { board[it].second == up }
                    val paid = if (buyer == null || up == null) null else tradeUp(up, team, buyer, i, later)
                    if (buyer != null && up != null && paid != null) {
                        board[buyer] = 1 to team
                        board[i] = 1 to up
                        paid.forEach { k -> board[k] = board[k].first to team }
                        movedUp += up
                        team = up
                    }
                }
            }

            val scheme = schemeFor(team)
            val needs = needsFor(team)

            // Every club reads the board through its own scouting (SPEC 4.6):
            // what it thinks a prospect is worth, not what he is. The miss is
            // the club's and the prospect's together and does not move while
            // the club sits on the clock, so a board is consistent rather than
            // noisy - a club that is high on a man stays high on him.
            if (stopBefore != null && stopBefore(overallPick, team)) {
                return Result(picks, drafted, emptyList(), available.toList(), overallPick)
            }

            val (dept, focus) = scouting(team)
            val chosen = userPick(overallPick, team)?.let { id -> available.firstOrNull { it.id.v == id } }
            val choice = chosen ?: available.maxByOrNull { p ->
                val lens = ScoutingLens.of(
                    playerId = p.id.v,
                    viewerId = team.v,
                    confidence = Scouting.prospect(p.id.v, p.position, dept, focus, scoutingTuning) *
                        ai.draftScoutingConfidence,
                    t = scoutingTuning,
                )
                val talent = lens.view(overall(p)).point.toFloat()
                val fit = schemeFit(p, scheme)
                val need = needs[p.position] ?: 0.4f
                talent + need * ai.draftNeedWeight + fit * ai.draftFitWeight
            } ?: return@forEach

            available.remove(choice)
            val signed = choice.copy(
                teamId = team,
                contract = rookieContract(overallPick, round, year),
                accruedSeasons = 0,
                yearsInSystem = 0, yearsWithClub = 0,
            )
            drafted[signed.id.v] = signed
            picks += DraftPick(round, overallPick, team.v, signed.id.v)
            overallPick++
        }
        return Result(picks, drafted, available.toList())
    }

    /**
     * The CBA's rookie wage scale: every drafted player signs a four-year deal
     * slotted by where he was picked. The league derives each slot from the
     * rookie pool, so this follows 2025's actual contracts as shares of that
     * year's cap - the first pick near 17.5% over four years, the last of
     * round one near 4.8%, everyone after the hundredth near the 1.5-2% floor -
     * and grows with the cap. A compensatory pick is slotted where it falls,
     * which is the CBA's midpoint of its neighbours. First-rounders are fully
     * guaranteed and carry the club's fifth-year option.
     */
    fun rookieContract(overallPick: Int, round: Int, year: Int): com.nflsim.engine.model.Contract {
        val total = (CapManagement.capFor(year) * interpolate(overallPick, SCALE)).roundToInt()
        val bonus = interpolate(overallPick, BONUS_SHARE)
        val guaranteedBase = when (round) {
            1 -> 1f
            2 -> 0.5f
            3 -> 0.2f
            else -> 0f
        }
        return com.nflsim.engine.model.Contract.of(
            years = 4, totalValue = total, signedYear = year,
            bonusShare = bonus,
            guaranteedShare = guaranteedBase * (1f - bonus),
        ).copy(fifthYearOption = round == 1)
    }

    private fun interpolate(pick: Int, points: List<Pair<Int, Float>>): Float {
        if (pick <= points.first().first) return points.first().second
        if (pick >= points.last().first) return points.last().second
        val (lo, hi) = points.zipWithNext().first { (a, b) -> pick in a.first..b.first }
        return lo.second + (hi.second - lo.second) * (pick - lo.first) / (hi.first - lo.first)
    }

    /**
     * Four-year rookie totals by overall pick, as shares of the cap: 2025's
     * contracts over its 279.2m cap - 48.8m for the first pick, 13.35m for the
     * 32nd, 6.6m by the 100th, 4.3m at the end.
     */
    private val SCALE = listOf(
        1 to 0.1748f, 5 to 0.1433f, 15 to 0.0716f, 32 to 0.0478f, 33 to 0.0394f,
        49 to 0.0358f, 64 to 0.0258f, 100 to 0.0236f, 101 to 0.0201f, 257 to 0.0154f,
    )

    /** How much of a rookie deal is signing bonus, by overall pick: two-thirds at the top, a sliver at the end. */
    private val BONUS_SHARE = listOf(1 to 0.66f, 32 to 0.50f, 64 to 0.30f, 100 to 0.15f, 257 to 0.05f)


    /**
     * Draft-day trades: round one only. The club on the clock needs the best
     * player left no more than ai.draftPassNeed, and a club within ai.draftTradeUpRange
     * picks needs him at least TRADE_UP_NEED and is aggressive enough to move.
     */
    const val TRADE_UP_REASON = "draft-day trade up"
}

/** What a roster is short of, 0 (set) to 1 (desperate). */
object TeamNeeds {

    /** How many start at each position. */
    val STARTERS: Map<Position, Int> = mapOf(
        Position.QB to 1, Position.RB to 1, Position.FB to 1, Position.WR to 3,
        Position.TE to 1, Position.LT to 1, Position.LG to 1, Position.C to 1,
        Position.RG to 1, Position.RT to 1, Position.EDGE to 2, Position.DT to 2,
        Position.LB to 3, Position.CB to 3, Position.S to 2,
        Position.K to 1, Position.P to 1, Position.LS to 1,
    )

    /**
     * What a typical starting unit looks like at each position in this
     * league: the mean, across clubs, of the starter average [assess] scores.
     * Needs are judged against it rather than one number for every position -
     * centers, fullbacks and specialists rate well below other starters by
     * construction, and a single bar of 74 made every club look short at all
     * of them. Relative, not absolute, for the same reason as ADR-006.
     */
    fun bar(rosters: Map<TeamId, List<Player>>, t: TuningTable.Needs, scheme: (TeamId, Position) -> Scheme): Map<Position, Float> =
        Position.entries.associateWith { position ->
            val required = STARTERS[position] ?: 1
            val units = rosters.mapNotNull { (id, roster) ->
                roster.filter { it.position == position }
                    .map { overall(it, scheme(id, position)) }
                    .sortedDescending()
                    .takeIf { it.size >= required }
                    ?.take(required)?.average()
            }
            if (units.isEmpty()) t.fallbackBar else units.average().toFloat()
        }

    fun assess(
        roster: List<Player>,
        scheme: (Position) -> Scheme,
        year: Int,
        bar: Map<Position, Float>,
        t: TuningTable.Needs,
    ): Map<Position, Float> =
        Position.entries.associateWith { position ->
            val group = roster.filter { it.position == position }
                .sortedByDescending { overall(it, scheme(position)) }
            val required = STARTERS[position] ?: 1

            if (group.size < required) return@associateWith 1f

            val starters = group.take(required)
            val quality = starters.map { overall(it, scheme(position)) }.average()
            val age = starters.map { it.age(year) }.average()

            // A starting unit short of this league's typical one at the
            // position is a need. So is a good one about to fall apart.
            val threshold = (bar[position] ?: t.fallbackBar) - t.slack
            val byQuality = ((threshold - quality) / t.qualityRange).coerceIn(0.0, 1.0)
            val byAge = ((age - t.ageFrom) / t.ageRange).coerceIn(0.0, t.ageMax)
            // No backup is a need only where the roster carries backups. The
            // template has one center, fullback, kicker, punter and snapper,
            // so flagging those left every club shopping for a second one.
            val carriesBackups = (ROSTER_TEMPLATE[position] ?: required) > required
            val byDepth = if (carriesBackups && group.size <= required) t.noBackup else 0.0
            // Where clubs rotate, the first player in behind the starters
            // plays a real share of snaps (SPEC 5.5), so one well behind them
            // is a need too.
            val rotation = group.getOrNull(required)?.let { overall(it, scheme(position)).toDouble() }
            val byRotation = if (position in ROTATES && rotation != null)
                ((quality - rotation - t.rotationSlack) / t.rotationRange).coerceIn(0.0, t.rotationMax) else 0.0
            ((byQuality * t.qualityWeight + byAge * t.ageWeight + byDepth + byRotation * t.rotationWeight) * t.scale)
                .coerceIn(0.0, 1.0).toFloat()
        }

    /** Positions that rotate (how far a rotation player may trail is TuningTable.Needs). */
    private val ROTATES = setOf(Position.RB, Position.WR, Position.TE, Position.EDGE, Position.DT,
        Position.LB, Position.CB, Position.S)

    fun requiredStarters(position: Position): Int = STARTERS[position] ?: 1

    /** Positional counts a 53 man roster has to hit. */
    val ROSTER_TEMPLATE: Map<Position, Int> = mapOf(
        Position.QB to 3, Position.RB to 4, Position.FB to 1, Position.WR to 6,
        Position.TE to 3, Position.LT to 2, Position.LG to 2, Position.C to 1,
        Position.RG to 2, Position.RT to 2, Position.EDGE to 5, Position.DT to 4,
        Position.LB to 6, Position.CB to 5, Position.S to 4,
        Position.K to 1, Position.P to 1, Position.LS to 1,
    )

    fun archetypeFor(position: Position, rng: Rng): Archetype {
        val options = Archetype.forPosition(position)
        return options[rng.nextInt(options.size)]
    }

}
