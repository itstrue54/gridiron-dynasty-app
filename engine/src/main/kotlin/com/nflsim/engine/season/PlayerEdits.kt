package com.nflsim.engine.season

import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.HiddenTraits
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.Ratings

/**
 * The commissioner's pen (SPEC 10.5): with editing on, the user rewrites any
 * player in the league - his position, his true ratings and his hidden
 * traits. Nothing else about him moves: not his age, his contract or his
 * club. Overall is derived, so it follows the ratings on its own.
 */
object PlayerEdits {

    /** What the editor hands back: the whole of what may change. */
    data class Edit(
        val position: Position,
        val ratings: Map<RatingId, Int>,
        val traits: HiddenTraits,
    )

    /** The bounds the editor offers and an edit is held to. */
    val RATING_RANGE = Ratings.MIN..Ratings.MAX
    val TRAIT_RANGE = 0..100
    val PEAK_AGE_RANGE = -3..3

    /** Where [playerId] stands now, as an edit that changes nothing. */
    fun current(dynasty: Dynasty, playerId: Int): Edit {
        val p = dynasty.league.playersById.getValue(com.nflsim.engine.model.PlayerId(playerId))
        return Edit(p.position, RatingId.entries.associateWith { p.ratings[it] }, p.traits)
    }

    /**
     * [edit] applied to [playerId]. Ratings and traits are held to their
     * ranges. A move to another position group takes that group's first
     * archetype - an archetype belongs to its group - and takes him off any
     * depth chart pin at another position, on whichever club has him.
     */
    fun apply(dynasty: Dynasty, playerId: Int, edit: Edit): Dynasty {
        require(dynasty.editPlayers) { "Player editing is off for this dynasty." }
        val league = dynasty.league
        val id = com.nflsim.engine.model.PlayerId(playerId)
        val player = league.playersById[id] ?: return dynasty

        val ratings = player.ratings.with(*edit.ratings.map { (r, v) -> r to v.coerceIn(RATING_RANGE) }.toTypedArray())
        val t = edit.traits
        val traits = t.copy(
            peakAgeOffset = t.peakAgeOffset.coerceIn(PEAK_AGE_RANGE),
            workEthic = t.workEthic.coerceIn(TRAIT_RANGE),
            footballIq = t.footballIq.coerceIn(TRAIT_RANGE),
            coachability = t.coachability.coerceIn(TRAIT_RANGE),
            schemeVersatility = t.schemeVersatility.coerceIn(TRAIT_RANGE),
            injuryProneness = t.injuryProneness.coerceIn(TRAIT_RANGE),
            clutch = t.clutch.coerceIn(TRAIT_RANGE),
            bigGame = t.bigGame.coerceIn(TRAIT_RANGE),
            consistency = t.consistency.coerceIn(TRAIT_RANGE),
            ego = t.ego.coerceIn(TRAIT_RANGE),
            loyalty = t.loyalty.coerceIn(TRAIT_RANGE),
            penaltyProne = t.penaltyProne.coerceIn(TRAIT_RANGE),
            durabilityUnderLoad = t.durabilityUnderLoad.coerceIn(TRAIT_RANGE),
        )
        val moved = edit.position != player.position
        val archetype = if (edit.position.group == player.archetype.group) player.archetype
            else Archetype.entries.first { it.group == edit.position.group }
        val edited = player.copy(position = edit.position, archetype = archetype, ratings = ratings, traits = traits)

        val teams = if (!moved) league.teams else league.teams.map { team ->
            if (player.teamId != team.id) team
            else team.copy(depthPins = team.depthPins.let { pins ->
                pins.copy(
                    order = pins.order.mapValues { (pos, ids) -> if (pos == edit.position) ids else ids - playerId }
                        .filterValues { it.isNotEmpty() },
                    packages = pins.packages.mapValues { (_, m) ->
                        m.mapValues { (pos, ids) -> if (pos == edit.position) ids else ids - playerId }.filterValues { it.isNotEmpty() }
                    },
                )
            })
        }
        return dynasty.copy(league = league.copy(
            players = league.players.map { if (it.id == id) edited else it },
            teams = teams,
        ))
    }
}
