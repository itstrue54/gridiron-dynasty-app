package com.nflsim.engine.sim

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall

/**
 * A roster sorted into who plays where, best first.
 *
 * Ordering uses scheme-adjusted overall, not the raw sheet - so the guy who
 * fits what the coordinator runs climbs the depth chart over the guy who is
 * better on paper. That is the scheme pillar showing up in personnel, which is
 * where it should be felt first.
 */
class DepthChart private constructor(
    private val byPosition: Map<Position, List<Player>>,
) {
    fun at(position: Position): List<Player> = byPosition[position] ?: emptyList()

    fun starter(position: Position): Player? = at(position).firstOrNull()

    fun group(group: PositionGroup): List<Player> =
        Position.entries.filter { it.group == group }.flatMap { at(it) }
            .distinctBy { it.id }

    companion object {
        fun auto(roster: List<Player>, scheme: Scheme): DepthChart {
            val sorted = roster.groupBy { it.position }
                .mapValues { (_, players) -> players.sortedByDescending { overall(it, scheme) } }
            return DepthChart(sorted)
        }
    }
}

/** The eleven on the field for the offense, already chosen for the package. */
data class OffenseUnit(
    val quarterback: Player,
    val backs: List<Player>,
    val tightEnds: List<Player>,
    val receivers: List<Player>,
    val line: List<Player>,
    val scheme: Scheme,
    /**
     * The whole backfield depth chart, not just who lines up. Carries rotate
     * through this - without it the starter takes every handoff and finishes
     * the year with 649 attempts.
     */
    val backfield: List<Player> = backs,
) {
    /** Everyone eligible to catch a pass, ordered by how good they are. */
    val skillPlayers: List<Player> get() = receivers + tightEnds + backs

    val onField: List<Player> get() = listOf(quarterback) + line + backs + tightEnds + receivers

    companion object {
        fun from(depth: DepthChart, personnel: Personnel, scheme: Scheme): OffenseUnit {
            val backs = (depth.at(Position.RB) + depth.at(Position.FB)).take(personnel.backs)
            return OffenseUnit(
                quarterback = depth.starter(Position.QB)
                    ?: error("no quarterback on the roster"),
                backs = backs.ifEmpty { depth.at(Position.RB).take(1) },
                tightEnds = depth.at(Position.TE).take(personnel.tightEnds),
                receivers = depth.at(Position.WR).take(personnel.receivers),
                line = listOf(Position.LT, Position.LG, Position.C, Position.RG, Position.RT)
                    .mapNotNull { depth.starter(it) },
                scheme = scheme,
                backfield = depth.at(Position.RB).take(3)
                    .ifEmpty { depth.at(Position.FB).take(1) },
            )
        }
    }
}

/** The eleven on the field for the defense. */
data class DefenseUnit(
    val edges: List<Player>,
    val interior: List<Player>,
    val linebackers: List<Player>,
    val corners: List<Player>,
    val safeties: List<Player>,
    val scheme: Scheme,
) {
    val frontSeven: List<Player> get() = edges + interior + linebackers
    val secondary: List<Player> get() = corners + safeties
    val passRushers: List<Player> get() = edges + interior

    companion object {
        fun from(depth: DepthChart, front: DefensiveFront, scheme: Scheme): DefenseUnit {
            val (edgeCount, interiorCount, lbCount, cbCount, sCount) = when (front) {
                DefensiveFront.THREE_FOUR_TWO_GAP,
                DefensiveFront.THREE_FOUR_ONE_GAP -> Counts(2, 3, 2, 3, 2)
                DefensiveFront.NICKEL_FOUR_TWO -> Counts(2, 2, 2, 3, 2)
                DefensiveFront.DIME_FOUR_ONE -> Counts(2, 2, 1, 4, 2)
                DefensiveFront.THREE_THREE_FIVE -> Counts(2, 1, 3, 3, 2)
                DefensiveFront.GOAL_LINE -> Counts(2, 3, 4, 1, 1)
                else -> Counts(2, 2, 3, 2, 2)
            }
            return DefenseUnit(
                edges = depth.at(Position.EDGE).take(edgeCount),
                interior = depth.at(Position.DT).take(interiorCount),
                linebackers = depth.at(Position.LB).take(lbCount),
                corners = depth.at(Position.CB).take(cbCount),
                safeties = depth.at(Position.S).take(sCount),
                scheme = scheme,
            )
        }

        private data class Counts(
            val edges: Int, val interior: Int, val linebackers: Int,
            val corners: Int, val safeties: Int,
        )
    }
}
