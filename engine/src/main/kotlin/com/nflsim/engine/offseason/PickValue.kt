package com.nflsim.engine.offseason

import com.nflsim.engine.model.PickAsset
import com.nflsim.engine.model.TeamId

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
 * a rookie deal, across all 224 slots and in round one alike.
 */
object PickValue {

    /** Chart points per point of player value (rating above replacement). */
    const val POINTS_PER_VALUE = 59f

    /** The Johnson chart, points by overall pick 1-224. Past 224 a pick is worth 1. */
    private val CHART = floatArrayOf(
        3000f, 2600f, 2200f, 1800f, 1700f, 1600f, 1500f, 1400f,
        1350f, 1300f, 1250f, 1200f, 1150f, 1100f, 1050f, 1000f,
        950f, 900f, 875f, 850f, 800f, 780f, 760f, 740f,
        720f, 700f, 680f, 660f, 640f, 620f, 600f, 590f,
        580f, 560f, 550f, 540f, 530f, 520f, 510f, 500f,
        490f, 480f, 470f, 460f, 450f, 440f, 430f, 420f,
        410f, 400f, 390f, 380f, 370f, 360f, 350f, 340f,
        330f, 320f, 310f, 300f, 292f, 284f, 276f, 270f,
        265f, 260f, 255f, 250f, 245f, 240f, 235f, 230f,
        225f, 220f, 215f, 210f, 205f, 200f, 195f, 190f,
        185f, 180f, 175f, 170f, 165f, 160f, 155f, 150f,
        145f, 140f, 136f, 132f, 128f, 124f, 120f, 116f,
        112f, 108f, 104f, 100f, 96f, 92f, 88f, 86f,
        84f, 82f, 80f, 78f, 76f, 74f, 72f, 70f,
        68f, 66f, 64f, 62f, 60f, 58f, 56f, 54f,
        52f, 50f, 49f, 48f, 47f, 46f, 45f, 44f,
        43f, 42f, 41f, 40f, 39.5f, 39f, 38.5f, 38f,
        37.5f, 37f, 36.5f, 36f, 35.5f, 35f, 34.5f, 34f,
        33.5f, 33f, 32.6f, 32.3f, 31.8f, 31.4f, 31f, 30.6f,
        30.2f, 29.8f, 29.4f, 29f, 28.6f, 28.2f, 27.8f, 27.4f,
        27f, 26.6f, 26.2f, 25.8f, 25.4f, 25f, 24.6f, 24.2f,
        23.8f, 23.4f, 23f, 22.6f, 22.2f, 21.8f, 21.4f, 21f,
        20.6f, 20.2f, 19.8f, 19.4f, 19f, 18.6f, 18.2f, 17.8f,
        17.4f, 17f, 16.6f, 16.2f, 15.8f, 15.4f, 15f, 14.6f,
        14.2f, 13.8f, 13.4f, 13f, 12.6f, 12.2f, 11.8f, 11.4f,
        11f, 10.6f, 10.2f, 9.8f, 9.4f, 9f, 8.6f, 8.2f,
        7.8f, 7.4f, 7f, 6.6f, 6.2f, 5.8f, 5.4f, 5f,
        4.6f, 4.2f, 3.8f, 3.4f, 3f, 2.6f, 2.3f, 2f,
    )

    fun points(overallPick: Int): Float = CHART.getOrElse(overallPick - 1) { 1f }

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
    fun value(pick: PickAsset, draftYear: Int, order: List<TeamId>, winNow: Float): Float =
        points(slot(pick, draftYear, order)) / POINTS_PER_VALUE * (1f + (0.5f - winNow) * TIMELINE)

    private const val TEAMS = 32

    /** How far a club's timeline tilts what a pick is worth to it. */
    private const val TIMELINE = 0.5f
}
