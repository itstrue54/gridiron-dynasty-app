package com.nflsim.engine.offseason

import com.nflsim.engine.gen.PlayerGenerator
import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
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
    fun generate(year: Int, firstId: Int, rng: Rng): List<Player>
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

    /**
     * How many prospects exist for every one that gets picked.
     *
     * This is the draft's whole point. At 350 prospects for 224 picks teams
     * took 64% of the board - everybody who was drafted played, and the class
     * that arrived was barely better than the class that was generated. A real
     * draft chooses a few hundred players out of thousands, and that selection
     * is where a league's talent actually comes from. Widening the board
     * raises what arrives without touching what a prospect is worth.
     */
    private const val BOARD_DEPTH = 1.45f

    override fun generate(year: Int, firstId: Int, rng: Rng): List<Player> {
        val pool = mutableListOf<Player>()
        var id = firstId
        val total = POSITION_MIX.sumOf { (it.second * BOARD_DEPTH).roundToInt() }

        POSITION_MIX.forEach { (position, baseCount) ->
            val count = (baseCount * BOARD_DEPTH).roundToInt()
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
                    yearsInSystem = 0,
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
    )

    fun run(
        order: List<TeamId>,
        prospects: List<Player>,
        schemeFor: (TeamId) -> Scheme,
        needsFor: (TeamId) -> Map<Position, Float>,
        year: Int,
        rng: Rng,
    ): Result {
        val available = prospects.toMutableList()
        val picks = mutableListOf<DraftPick>()
        val drafted = mutableMapOf<Int, Player>()

        var overallPick = 1
        for (round in 1..ROUNDS) {
            order.forEach { team ->
                if (available.isEmpty()) return@forEach
                val scheme = schemeFor(team)
                val needs = needsFor(team)

                // Every team sees a slightly different board.
                val choice = available.maxByOrNull { p ->
                    val talent = overall(p).toFloat()
                    val fit = schemeFit(p, scheme)
                    val need = needs[p.position] ?: 0.4f
                    val error = rng.gaussian(0f, SCOUTING_ERROR)
                    talent + need * NEED_WEIGHT + fit * FIT_WEIGHT + error
                } ?: return@forEach

                available.remove(choice)
                val signed = choice.copy(
                    teamId = team,
                    contract = rookieContract(round, year),
                    accruedSeasons = 0,
                    yearsInSystem = 0,
                )
                drafted[signed.id.v] = signed
                picks += DraftPick(round, overallPick, team.v, signed.id.v)
                overallPick++
            }
        }
        return Result(picks, drafted, available.toList())
    }

    /** Slotted rookie deals, four years, cheap and getting cheaper by round. */
    fun rookieContract(round: Int, year: Int): com.nflsim.engine.model.Contract {
        val total = when (round) {
            1 -> 18_000
            2 -> 8_500
            3 -> 5_400
            4 -> 4_300
            5 -> 3_900
            6 -> 3_700
            else -> 3_500
        }
        return com.nflsim.engine.model.Contract.of(
            years = 4, totalValue = total, signedYear = year,
            bonusShare = if (round == 1) 0.55f else 0.25f,
            guaranteedShare = if (round <= 2) 0.85f else 0.35f,
        )
    }

    /** Scouting error in overall points. Bigger than most people expect. */
    private const val SCOUTING_ERROR = 7.5f
    private const val NEED_WEIGHT = 9f
    private const val FIT_WEIGHT = 6f
}

/** What a roster is short of, 0 (set) to 1 (desperate). */
object TeamNeeds {

    private val STARTERS: Map<Position, Int> = mapOf(
        Position.QB to 1, Position.RB to 1, Position.FB to 1, Position.WR to 3,
        Position.TE to 1, Position.LT to 1, Position.LG to 1, Position.C to 1,
        Position.RG to 1, Position.RT to 1, Position.EDGE to 2, Position.DT to 2,
        Position.LB to 3, Position.CB to 3, Position.S to 2,
        Position.K to 1, Position.P to 1, Position.LS to 1,
    )

    fun assess(roster: List<Player>, scheme: (Position) -> Scheme, year: Int): Map<Position, Float> =
        Position.entries.associateWith { position ->
            val group = roster.filter { it.position == position }
                .sortedByDescending { overall(it, scheme(position)) }
            val required = STARTERS[position] ?: 1

            if (group.size < required) return@associateWith 1f

            val starters = group.take(required)
            val quality = starters.map { overall(it, scheme(position)) }.average()
            val age = starters.map { it.age(year) }.average()

            // A weak starter is a need. So is a good one about to fall apart.
            val byQuality = ((74 - quality) / 26.0).coerceIn(0.0, 1.0)
            val byAge = ((age - 30) / 7.0).coerceIn(0.0, 0.6)
            val byDepth = if (group.size <= required) 0.25 else 0.0
            ((byQuality * 0.7 + byAge * 0.2 + byDepth) * 1.15).coerceIn(0.0, 1.0).toFloat()
        }

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

    /** Delegates to the one price curve the whole league uses (econ/MarketValue). */
    fun marketValue(player: Player, scheme: Scheme, year: Int): Int =
        MarketValue.annual(player, scheme, year)
}
