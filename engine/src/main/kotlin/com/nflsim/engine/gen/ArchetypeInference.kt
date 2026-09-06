package com.nflsim.engine.gen

import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.Ratings
import com.nflsim.engine.ratings.OverallWeights

/**
 * Works out what kind of player a rating sheet describes.
 *
 * Imported rosters almost never carry an archetype - real ratings dumps have
 * numbers, not labels. But archetype drives scheme fit, which is the heart of
 * this game, so every imported player needs one. This reads the sheet the way
 * a scout would: which attributes stand out relative to the rest of it?
 */
object ArchetypeInference {

    /**
     * @return the archetype whose profile best matches how this player's
     *   ratings deviate from his own average, plus a 0..1 confidence.
     */
    fun infer(position: Position, ratings: Ratings): Inference {
        val candidates = Archetype.forPosition(position)
        if (candidates.size == 1) return Inference(candidates.first(), 1f)

        val relevant = OverallWeights.forPosition(position).keys
        val mean = relevant.map { ratings[it] }.average().toFloat()

        // How far each attribute sits from this player's own baseline. Using his
        // own mean rather than the league's is what makes it work for a 95 and a
        // 62 alike - we care about shape, not level.
        val deviation: Map<RatingId, Float> =
            RatingId.entries.associateWith { ratings[it] - mean }

        val scores = candidates.associateWith { archetype ->
            val profile = ArchetypeProfile.offsetsFor(archetype)
            if (profile.isEmpty()) 0f
            else profile.entries.sumOf { (rating, offset) ->
                (offset * (deviation[rating] ?: 0f)).toDouble()
            }.toFloat() / profile.size
        }

        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.first()
        val runnerUp = ranked.getOrNull(1)

        // Confidence is how clearly the winner separated from second place.
        val gap = if (runnerUp == null) 1f else (best.value - runnerUp.value)
        val spread = (ranked.first().value - ranked.last().value).takeIf { it > 0.001f } ?: 1f
        val confidence = (gap / spread).coerceIn(0f, 1f)

        return Inference(best.key, confidence)
    }

    data class Inference(val archetype: Archetype, val confidence: Float)
}
