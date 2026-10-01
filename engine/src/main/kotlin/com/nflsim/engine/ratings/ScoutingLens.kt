package com.nflsim.engine.ratings

import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable

/**
 * What a club believes a rating is (docs/SPEC.md 4.6).
 *
 * A club never sees a true rating. It sees a point estimate and a band, both
 * moving with how much the club has seen of the player: the band is about
 * 12 points wide at no confidence and closes to a point or two for a
 * well-known veteran, without ever reaching certainty.
 */
data class RatingView(
    val point: Int,
    val low: Int,
    val high: Int,
    val confidence: Float,
    /** True once a club has seen enough that the estimate is the rating. */
    val exact: Boolean,
) {

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
    private val t: TuningTable.Scouting,
) {

    /** The club's estimate of [trueRating], with the band it would show. */
    fun view(trueRating: Int): RatingView {
        val half = (t.band * (1f - confidence)).coerceAtLeast(0f)
        if (confidence >= t.exactAt) {
            return RatingView(trueRating, trueRating, trueRating, confidence, exact = true)
        }
        // The band has to be worth reading: the miss is half of it, so the
        // rating sits inside the band the club is shown about nineteen times
        // in twenty. A club can still be badly wrong about a man - that is
        // the whole point of a draft - but not so wrong that the band it
        // published meant nothing.
        val point = (trueRating + bias.coerceIn(-t.biasClamp, t.biasClamp) * half * t.missShare).toInt().coerceIn(0, 99)
        return RatingView(
            point = point,
            low = (point - half).toInt().coerceIn(0, 99),
            high = (point + half).toInt().coerceIn(0, 99),
            confidence = confidence,
            exact = false,
        )
    }

    companion object {
        // The band, the thresholds and the ceiling are tuning: TuningTable.Scouting.

        /**
         * How well [viewer] reads [player]. The same pair always reads the same
         * way, and a different club reads him differently.
         */
        fun of(playerId: Int, viewerId: Int, confidence: Float, t: TuningTable.Scouting): ScoutingLens {
            val seed = playerId.toLong() * 1_000_003L + viewerId.toLong() * 31L
            return ScoutingLens(
                confidence = confidence.coerceIn(0f, t.ceiling),
                bias = SplitMixRng(seed).gaussian(),
                t = t,
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
        fun ownPlayer(yearsWithClub: Int, scoutingDept: Int, t: TuningTable.Scouting): Float =
            (t.ownBase + t.ownDept * ((scoutingDept - t.ownDeptFrom) / t.ownDeptRange).coerceIn(0f, 1f) +
                t.ownPerYear * yearsWithClub).coerceAtMost(t.ceiling)

        /**
         * A club's confidence in a prospect before it spends anything: a man
         * from a big program has been watched for years, one from a small
         * school has barely been seen. Stable per prospect, like the miss.
         */
        fun prospectExposure(playerId: Int, t: TuningTable.Scouting): Float {
            val draw = SplitMixRng(playerId.toLong() * 7_919L).nextFloat()
            return t.exposureFloor + draw * (t.exposureCeiling - t.exposureFloor)
        }
    }
}
