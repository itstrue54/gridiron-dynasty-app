package com.nflsim.engine.ratings

import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import kotlinx.serialization.Serializable

@Serializable
enum class SchemeSide { OFFENSE, DEFENSE }

/**
 * A scheme is data, not code. Adding one must never require a recompile -
 * see docs/SPEC.md 4.8. These load from engine/src/main/resources/schemes.json.
 *
 * Keys in archetypeFit are either a Position name ("RB", "LT") or a
 * PositionGroup name ("OL", "CB"). Position wins if both are present, so a
 * scheme can say "OL generally" and still special-case left tackle.
 */
@Serializable
data class Scheme(
    val id: String,
    val name: String,
    val side: SchemeSide,
    /** position-or-group -> archetype -> fit, 0.0 (wrong guy) to 1.0 (ideal). */
    val archetypeFit: Map<String, Map<String, Float>> = emptyMap(),
    /** position-or-group -> rating names this scheme leans on especially hard. */
    val ratingEmphasis: Map<String, List<String>> = emptyMap(),
    val basePassRate: Float = 0.55f,
    val playActionRate: Float = 0.22f,
    val blitzRate: Float = 0.25f,
    val manZoneSplit: Float = 0.45f,
    val tempo: Float = 0.50f,
    val notes: String = "",
    /**
     * The scheme-fit tuning every rating read from this scheme uses. Never in
     * schemes.json: a league's lookups attach its own (SchemeCatalog.tuned).
     */
    @kotlinx.serialization.Transient
    val ratings: com.nflsim.engine.tuning.TuningTable.Ratings = com.nflsim.engine.tuning.TuningTable.REALISTIC.ratings,
) {
    /** How well this archetype suits the scheme at this position. */
    fun fitFor(position: Position, archetype: Archetype): Float {
        val byPosition = archetypeFit[position.name]?.get(archetype.name)
        if (byPosition != null) return byPosition.coerceIn(0f, 1f)
        val byGroup = archetypeFit[position.group.name]?.get(archetype.name)
        if (byGroup != null) return byGroup.coerceIn(0f, 1f)
        return NEUTRAL_FIT
    }

    fun emphasizes(position: Position, rating: RatingId): Boolean {
        val direct = ratingEmphasis[position.name]
        if (direct != null) return rating.name in direct
        return ratingEmphasis[position.group.name]?.contains(rating.name) == true
    }

    companion object {
        /** Used when a scheme says nothing about an archetype: neither help nor harm. */
        const val NEUTRAL_FIT = 0.65f
    }
}
