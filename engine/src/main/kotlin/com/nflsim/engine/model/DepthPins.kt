package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/**
 * A club's own say in its depth chart (SPEC 5.5), kept as pins over the
 * automatic order rather than as a whole chart. The pinned players at a
 * position come first, in the order given, and everyone else follows in the
 * automatic order. A pinned player who leaves simply drops out and a
 * newcomer slots in where the automatic order puts him, so a chart nobody
 * has touched is exactly the automatic one.
 */
@Serializable
data class DepthPins(
    /** Per position, the player ids pinned to the top of the chart, in order. */
    val order: Map<Position, List<Int>> = emptyMap(),
    /**
     * Per package (see sim.packageKey), who fills a position in that package,
     * in order, ahead of the position's own chart.
     */
    val packages: Map<String, Map<Position, List<Int>>> = emptyMap(),
    val kickReturner: Int? = null,
    val puntReturner: Int? = null,
) {
    /** Pins only for players still in [ids]. */
    fun keepOnly(ids: Set<Int>): DepthPins = DepthPins(
        order = order.mapValues { (_, l) -> l.filter { it in ids } }.filterValues { it.isNotEmpty() },
        packages = packages
            .mapValues { (_, m) -> m.mapValues { (_, l) -> l.filter { it in ids } }.filterValues { it.isNotEmpty() } }
            .filterValues { it.isNotEmpty() },
        kickReturner = kickReturner?.takeIf { it in ids },
        puntReturner = puntReturner?.takeIf { it in ids },
    )

    companion object {
        /** [players] with the [pinned] ids first, in that order, then the rest by [rank], highest first. */
        fun <R : Comparable<R>> ordered(players: List<Player>, pinned: List<Int>, rank: (Player) -> R): List<Player> {
            if (pinned.isEmpty()) return players.sortedByDescending(rank)
            val byId = players.associateBy { it.id.v }
            val top = pinned.distinct().mapNotNull { byId[it] }
            val topIds = top.map { it.id.v }.toSet()
            return top + players.filter { it.id.v !in topIds }.sortedByDescending(rank)
        }
    }
}
