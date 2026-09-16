package com.nflsim.engine.ratings

import com.nflsim.engine.rng.SplitMixRng

/**
 * What a club believes a rating is (docs/SPEC.md 4.6).
 *
 * A club never sees a true rating. It sees a point estimate and a band, both
 * moving with how much the club has seen of the player: the band is about
 * 12 points wide at no confidence and closes to a point or two for a
 * well-known veteran, without ever reaching certainty.
 */
data class RatingView(val point: Int, val low: Int, val high: Int, val confidence: Float) {

    /** True once a club has seen enough that the estimate is the rating. */
    val exact: Boolean get() = confidence >= ScoutingLens.EXACT_AT

    /** "82" for a player a club knows, "78-86" for one it is still guessing at. */
    val text: String get() = if (exact || low == high) "$point" else "$low-$high"
}

/**
 * One club's read on one player.
 *
 * The miss is per club and per player and stable within a save: a scout who is
 * wrong about a man stays wrong until new evidence arrives. It is drawn from
 * the two ids rather than stored, the way trait grades already are, so it
 * needs no save field and two clubs are wrong about him differently.
 */
class ScoutingLens private constructor(
    val confidence: Float,
    private val bias: Float,
) {

    /** The club's estimate of [trueRating], with the band it would show. */
    fun view(trueRating: Int): RatingView {
        val half = (BAND * (1f - confidence)).coerceAtLeast(0f)
        if (confidence >= EXACT_AT) {
            return RatingView(trueRating, trueRating, trueRating, confidence)
        }
        // The band is the club's uncertainty about its own estimate, so the
        // miss is the size of the band: a club guessing within twelve points
        // is wrong by about that much. An extreme draw is still a scout's
        // opinion rather than a fantasy, so it stops at two bands out.
        val point = (trueRating + bias.coerceIn(-2f, 2f) * half).toInt().coerceIn(0, 99)
        return RatingView(
            point = point,
            low = (point - half).toInt().coerceIn(0, 99),
            high = (point + half).toInt().coerceIn(0, 99),
            confidence = confidence,
        )
    }

    companion object {
        /** Band half-width at no confidence at all (docs/SPEC.md 4.6). */
        const val BAND = 12f

        /** A trait shows a range from here, a grade from here, the truth from here. */
        const val RANGE_AT = 0.4f
        const val GRADE_AT = 0.7f
        const val EXACT_AT = 0.9f

        /** Confidence never reaches 1: a club is never quite certain. */
        const val CEILING = 0.95f

        /**
         * How well [viewer] reads [player]. The same pair always reads the same
         * way, and a different club reads him differently.
         */
        fun of(playerId: Int, viewerId: Int, confidence: Float): ScoutingLens {
            val seed = playerId.toLong() * 1_000_003L + viewerId.toLong() * 31L
            return ScoutingLens(
                confidence = confidence.coerceIn(0f, CEILING),
                bias = SplitMixRng(seed).gaussian(),
            )
        }

        /**
         * A club's confidence in its own player. A man on your own roster is
         * never a mystery - he practises in front of the staff every day - so
         * a new arrival reads 0.35-0.55 by scouting department, and each year
         * in the building adds 0.25: known by his second season, guesswork as
         * a rookie. One model serves ratings and traits alike; only the band
         * differs.
         */
        fun ownPlayer(yearsWithClub: Int, scoutingDept: Int): Float =
            (OWN_BASE + OWN_DEPT * ((scoutingDept - 40) / 40f).coerceIn(0f, 1f) +
                OWN_PER_YEAR * yearsWithClub).coerceAtMost(CEILING)

        /**
         * A club's confidence in a prospect before it spends anything: a man
         * from a big program has been watched for years, one from a small
         * school has barely been seen. Stable per prospect, like the miss.
         */
        fun prospectExposure(playerId: Int): Float {
            val draw = SplitMixRng(playerId.toLong() * 7_919L).nextFloat()
            return EXPOSURE_FLOOR + draw * (EXPOSURE_CEILING - EXPOSURE_FLOOR)
        }

        private const val OWN_BASE = 0.35f
        private const val OWN_DEPT = 0.2f
        private const val OWN_PER_YEAR = 0.25f
        private const val EXPOSURE_FLOOR = 0.10f
        private const val EXPOSURE_CEILING = 0.45f
    }
}
