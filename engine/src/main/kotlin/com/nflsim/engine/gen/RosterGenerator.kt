package com.nflsim.engine.gen

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamFinances
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.rng.Rng
import kotlin.math.roundToInt

/**
 * Builds a 53-man roster with a believable talent curve.
 *
 * The template below is the shape of an NFL roster: one very good starter at
 * the premium spots, a real backup, then depth that falls away quickly. Team
 * strength shifts the whole curve up or down, which is what makes a 4-win
 * roster feel different from a 13-win one rather than just unlucky.
 */
object RosterGenerator {

    /** Position, then the target overall for each depth slot. Sums to 53. */
    // Rotational players - the next ones in behind the starters at positions
    // that rotate - sit a few points behind them rather than a dozen: they
    // play a real share of snaps (SPEC 5.5).
    val TEMPLATE: List<Pair<Position, List<Int>>> = listOf(
        Position.QB to listOf(84, 70, 61),
        Position.RB to listOf(80, 75, 66, 60),
        Position.FB to listOf(68),
        Position.WR to listOf(84, 79, 77, 70, 63, 58),
        Position.TE to listOf(79, 73, 63),
        Position.LT to listOf(82, 65),
        Position.LG to listOf(78, 64),
        Position.C to listOf(79),
        Position.RG to listOf(78, 64),
        Position.RT to listOf(80, 65),
        Position.EDGE to listOf(84, 79, 76, 68, 58),
        Position.DT to listOf(82, 76, 74, 64),
        Position.LB to listOf(82, 76, 70, 68, 59, 55),
        Position.CB to listOf(83, 78, 72, 70, 59),
        Position.S to listOf(81, 75, 71, 60),
        Position.K to listOf(76),
        Position.P to listOf(74),
        Position.LS to listOf(62),
    )

    val SIZE: Int = TEMPLATE.sumOf { it.second.size }

    /**
     * @param strength team quality offset in overall points, roughly -7..+7.
     * @param nextId supplies player ids so the caller controls numbering.
     */
    fun generate(
        teamId: TeamId,
        strength: Float,
        year: Int,
        rng: Rng,
        nextId: () -> PlayerId,
    ): List<Player> {
        val roster = mutableListOf<Player>()
        val depths = mutableListOf<Int>()
        val quotes = mutableListOf<Float>()
        for ((position, slots) in TEMPLATE) {
            slots.forEachIndexed { depth, slotTarget ->
                // Strength matters most at the top of the depth chart: a good
                // team's difference is its starters, not its third-string guard.
                val strengthWeight = when (depth) {
                    0 -> 1.0f
                    1 -> 0.7f
                    else -> 0.35f
                }
                val target = (slotTarget + strength * strengthWeight +
                    rng.gaussian(0f, 3.0f)).roundToInt().coerceIn(40, 99)

                // Starters skew older, depth skews younger.
                val ageBias = when (depth) {
                    0 -> 1
                    1 -> 0
                    else -> -2
                }

                val player = PlayerGenerator.generate(
                    id = nextId(),
                    position = position,
                    targetOverall = target,
                    year = year,
                    rng = rng,
                    teamId = teamId,
                    ageBias = ageBias,
                )
                roster += player
                depths += depth
                quotes += MarketValue.score(position, target, player.age(year)) *
                    careerDiscount(depth, player.age(year))
            }
        }
        return sign(roster, depths, quotes, year, rng)
    }

    /**
     * Puts every generated player on a contract.
     *
     * Without this the whole league is out of contract after one season and
     * the offseason turns into an annual redraft. Deals are staggered - each
     * player is somewhere in the middle of his - so roughly a quarter of a
     * roster reaches free agency each year, which is what gives the cap
     * something to bite on.
     */
    private fun sign(
        roster: List<Player>,
        depths: List<Int>,
        quotes: List<Float>,
        year: Int,
        rng: Rng,
    ): List<Player> {
        // A child stream, so adding contracts does not consume draws from the
        // parent and silently regenerate a different league. The first version
        // took its numbers from the parent rng and moved every downstream
        // random draw, which surfaced as a calibration regression three
        // modules away.
        val money = rng.split("contracts")

        val terms = roster.indices.map { MarketValue.termFor(roster[it].age(year), depths[it]) }
        // How far into his deal each player already is. Drawn once and reused,
        // so the two pricing passes below produce the same league.
        val elapsed = terms.map { money.nextInt(it) }

        fun build(scale: Float): List<Player> = roster.mapIndexed { i, p ->
            val annual = (quotes[i] * scale).roundToInt().coerceAtLeast(Contract.MIN_BASE_SALARY)
            p.copy(
                contract = Contract.of(
                    years = terms[i],
                    totalValue = annual * terms[i],
                    signedYear = year - elapsed[i],
                ),
                // Deliberately not yearsInSystem: a new league's players are
                // all learning their scheme, and familiarity is earned in the
                // sim. Backdating it here raised every effective rating and
                // pushed yards per attempt out of band.
                accruedSeasons = (p.age(year) - 22).coerceIn(0, 12),
            )
        }

        // Priced at market a full roster of starters costs far more than the
        // cap allows. Real books balance because half a roster is on cheap
        // deals signed years ago; scaling to the budget reproduces that while
        // keeping the prices in the right order relative to each other.
        //
        // Scale against the actual cap hits rather than the annual averages:
        // deals are back-loaded and the minimum salary is a floor, so what a
        // roster costs this year is not what it averages. Pricing off the
        // average left teams 15% over the cap on day one.
        val budget = (TeamFinances.LEAGUE_CAP * CAP_TARGET).roundToInt()
        // Scores are relative, so the first pass converts them to money at a
        // rate that spends the budget; the second corrects for what the deals
        // actually cost once back-loading and the salary floor are applied.
        val opening = if (quotes.sum() <= 0f) 1f else budget / quotes.sum()
        val trial = build(opening)
        val committed = trial.sumOf { it.capHit(year) }
        return if (committed <= budget) trial else build(opening * budget / committed)
    }

    /**
     * What fraction of market a player is actually being paid. Starters have
     * been paid; backups and anyone young enough to still be on a first
     * contract have not.
     */
    private fun careerDiscount(depth: Int, age: Int): Float {
        val byDepth = when (depth) {
            0 -> 0.85f
            1 -> 0.60f
            else -> 0.40f
        }
        return if (age <= 24) byDepth * 0.5f else byDepth
    }

    /** Where a generated team sits against the cap. Leaves room to sign. */
    private const val CAP_TARGET = 0.88f
}
