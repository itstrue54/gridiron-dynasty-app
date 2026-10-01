package com.nflsim.engine.offseason

import com.nflsim.engine.model.PickAsset
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.tuning.TuningTable

/**
 * What a draft pick is worth in a trade (SPEC 8.4): the Jimmy Johnson chart,
 * used as-is the way NFL front offices use it.
 *
 * The chart prices the first pick at five times the 32nd; this league's drafts
 * return about 1.4 times, the same overpricing of top picks Massey and Thaler
 * found in the NFL. Clubs trade by the chart anyway, because that is how the
 * market behaves - which leaves the same edge a real club has in trading
 * down. One exchange rate turns chart points into player value, measured from
 * this league's drafts: 59 points per point of rating above replacement over
 * a rookie deal, across all 224 slots and in round one alike. The chart and
 * the rate are tuning (TuningTable.Trades).
 */
object PickValue {

    /** Chart points for an overall pick (the chart is [TuningTable.Trades.pickChart]). Past its end a pick is worth 1. */
    fun points(overallPick: Int, t: TuningTable.Trades): Float = t.pickChart.getOrElse(overallPick - 1) { 1f }

    /**
     * Where a pick falls. This year's regular picks by the known draft order;
     * a compensatory pick at the end of its round; a future pick at mid-round,
     * one round later - the NFL's rule of thumb for a pick nobody can place yet.
     */
    fun slot(pick: PickAsset, draftYear: Int, order: List<TeamId>): Int {
        if (pick.year == draftYear) {
            if (pick.compensatory) return pick.round * TEAMS + pick.compOrder + 1
            val at = order.indexOfFirst { it.v == pick.original }.coerceAtLeast(0)
            return (pick.round - 1) * TEAMS + at + 1
        }
        val round = (pick.round + 1).coerceAtMost(DraftRunner.ROUNDS)
        return (round - 1) * TEAMS + TEAMS / 2
    }

    /**
     * A pick's worth to a club in player value. A rebuilding club prizes
     * picks and an all-in one spends them: at win now 0 a pick is worth a
     * quarter more, at 1 a quarter less.
     */
    fun value(pick: PickAsset, draftYear: Int, order: List<TeamId>, winNow: Float, t: TuningTable.Trades): Float =
        points(slot(pick, draftYear, order), t) / t.pointsPerValue * (1f + (0.5f - winNow) * t.pickTimeline)

    private const val TEAMS = 32
}
