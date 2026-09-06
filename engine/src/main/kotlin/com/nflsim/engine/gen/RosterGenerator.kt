package com.nflsim.engine.gen

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
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
    val TEMPLATE: List<Pair<Position, List<Int>>> = listOf(
        Position.QB to listOf(84, 70, 61),
        Position.RB to listOf(80, 74, 66, 60),
        Position.FB to listOf(68),
        Position.WR to listOf(84, 79, 74, 68, 63, 58),
        Position.TE to listOf(79, 70, 63),
        Position.LT to listOf(82, 65),
        Position.LG to listOf(78, 64),
        Position.C to listOf(79),
        Position.RG to listOf(78, 64),
        Position.RT to listOf(80, 65),
        Position.EDGE to listOf(84, 79, 70, 63, 58),
        Position.DT to listOf(82, 76, 67, 60),
        Position.LB to listOf(82, 76, 70, 64, 59, 55),
        Position.CB to listOf(83, 78, 72, 65, 59),
        Position.S to listOf(81, 75, 66, 60),
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

                roster += PlayerGenerator.generate(
                    id = nextId(),
                    position = position,
                    targetOverall = target,
                    year = year,
                    rng = rng,
                    teamId = teamId,
                    ageBias = ageBias,
                )
            }
        }
        return roster
    }
}
