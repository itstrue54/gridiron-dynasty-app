package com.nflsim.engine.sim

import com.nflsim.engine.model.DepthPins
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
    private val pins: DepthPins = DepthPins(),
    private val roster: Map<Int, Player> = emptyMap(),
) {
    fun at(position: Position): List<Player> = byPosition[position] ?: emptyList()

    /** Who fills [position] in the package [key]: that package's pins first, then the position's chart. */
    fun forPackage(key: String, position: Position): List<Player> {
        val pinned = pins.packages[key]?.get(position).orEmpty()
        if (pinned.isEmpty()) return at(position)
        val chart = at(position)
        val top = pinned.distinct().mapNotNull { id -> chart.firstOrNull { it.id.v == id } }
        val topIds = top.map { it.id }.toSet()
        return top + chart.filter { it.id !in topIds }
    }

    /** Returners the club pinned, if they are still on the roster. */
    val kickReturner: Player? get() = pins.kickReturner?.let { roster[it] }
    val puntReturner: Player? get() = pins.puntReturner?.let { roster[it] }

    /**
     * This chart for the next snap (SPEC 5.5): players being rested drop
     * behind their teammates at the position, and everyone carries his
     * current fatigue into his effective ratings.
     */
    fun rested(resting: Set<Int>, fatigue: Map<Int, Float>, out: Set<Int> = emptySet()): DepthChart {
        if (resting.isEmpty() && fatigue.isEmpty() && out.isEmpty()) return this
        fun tired(p: Player) = p.copy(fatigue = (fatigue[p.id.v] ?: 0f).toInt().coerceIn(0, 100))
        val order = byPosition.mapValues { (_, list) ->
            // Players hurt this game are gone - unless nobody is left at the
            // position, when the last of them plays through it.
            val healthy = list.filter { it.id.v !in out }.ifEmpty { list }
            val (sitting, playing) = healthy.partition { it.id.v in resting }
            (playing + sitting).map(::tired)
        }
        return DepthChart(order, pins, roster.filterKeys { it !in out }.mapValues { (_, p) -> tired(p) })
    }

    fun starter(position: Position): Player? = at(position).firstOrNull()

    fun group(group: PositionGroup): List<Player> =
        Position.entries.filter { it.group == group }.flatMap { at(it) }
            .distinctBy { it.id }

    companion object {
        /** The automatic order - scheme-adjusted overall - with the club's [pins] over it. */
        fun auto(roster: List<Player>, scheme: Scheme, pins: DepthPins = DepthPins()): DepthChart {
            val sorted = roster.groupBy { it.position }
                .mapValues { (position, players) ->
                    DepthPins.ordered(players, pins.order[position].orEmpty()) { overall(it, scheme) }
                }
            return DepthChart(sorted, pins, roster.associateBy { it.id.v })
        }
    }
}

/** How a package is keyed in a club's depth pins. */
fun packageKey(personnel: Personnel): String = "off:${personnel.name}"
fun packageKey(front: DefensiveFront): String = "def:${front.name}"

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
            val key = packageKey(personnel)
            val backs = (depth.forPackage(key, Position.RB) + depth.forPackage(key, Position.FB)).take(personnel.backs)
            return OffenseUnit(
                quarterback = depth.starter(Position.QB)
                    ?: error("no quarterback on the roster"),
                backs = backs.ifEmpty { depth.at(Position.RB).take(1) },
                tightEnds = depth.forPackage(key, Position.TE).take(personnel.tightEnds),
                receivers = depth.forPackage(key, Position.WR).take(personnel.receivers),
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
            val key = packageKey(front)
            return DefenseUnit(
                edges = depth.forPackage(key, Position.EDGE).take(edgeCount),
                interior = depth.forPackage(key, Position.DT).take(interiorCount),
                linebackers = depth.forPackage(key, Position.LB).take(lbCount),
                corners = depth.forPackage(key, Position.CB).take(cbCount),
                safeties = depth.forPackage(key, Position.S).take(sCount),
                scheme = scheme,
            )
        }

        private data class Counts(
            val edges: Int, val interior: Int, val linebackers: Int,
            val corners: Int, val safeties: Int,
        )
    }
}
