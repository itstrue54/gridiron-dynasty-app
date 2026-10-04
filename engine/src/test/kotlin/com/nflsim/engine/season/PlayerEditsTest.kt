package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.DepthPins
import com.nflsim.engine.model.DevCurve
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.overall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** SPEC 10.5: with editing on, any player's position, ratings and hidden traits are the user's to rewrite. */
class PlayerEditsTest {

    private val league = LeagueGenerator.generate(2026, 8L)
    private val on = DynastyEngine.start(league, 2026, 8L, league.teams.first().id).copy(editPlayers = true)

    /** A player on another club: editing reaches the whole league, not just the user's. */
    private val theirs = league.roster(league.teams[5].id).first { it.position == Position.WR }

    @Test
    fun `nothing is editable with editing off`() {
        val off = on.copy(editPlayers = false)
        assertFailsWith<IllegalArgumentException> { PlayerEdits.apply(off, theirs.id.v, PlayerEdits.current(off, theirs.id.v)) }
    }

    @Test
    fun `an edit that changes nothing changes nothing`() {
        assertEquals(on, PlayerEdits.apply(on, theirs.id.v, PlayerEdits.current(on, theirs.id.v)))
    }

    @Test
    fun `ratings and traits change, held to their ranges, and overall follows`() {
        val now = PlayerEdits.current(on, theirs.id.v)
        val edit = now.copy(
            ratings = now.ratings + mapOf(RatingId.SPEED to 99, RatingId.CATCHING to 140, RatingId.ROUTE_DEEP to -5),
            traits = now.traits.copy(developmentCurve = DevCurve.SUPERSTAR, workEthic = 130, peakAgeOffset = 9),
        )
        val after = PlayerEdits.apply(on, theirs.id.v, edit).league.playersById.getValue(theirs.id)
        assertEquals(99, after.ratings[RatingId.SPEED])
        assertEquals(99, after.ratings[RatingId.CATCHING])
        assertEquals(1, after.ratings[RatingId.ROUTE_DEEP])
        assertEquals(DevCurve.SUPERSTAR, after.traits.developmentCurve)
        assertEquals(100, after.traits.workEthic)
        assertEquals(3, after.traits.peakAgeOffset)
        assertTrue(overall(after) != overall(theirs), "overall is derived from the ratings")
        // Nothing he was not edited in moves.
        assertEquals(theirs.copy(ratings = after.ratings, traits = after.traits), after)
    }

    @Test
    fun `a move to another group takes that group's archetype and comes off pins elsewhere`() {
        val club = league.teams[5]
        val pinned = on.copy(league = on.league.copy(teams = on.league.teams.map {
            if (it.id == club.id) it.copy(depthPins = DepthPins(order = mapOf(Position.WR to listOf(theirs.id.v)))) else it
        }))
        val edit = PlayerEdits.current(pinned, theirs.id.v).copy(position = Position.TE)
        val after = PlayerEdits.apply(pinned, theirs.id.v, edit)
        val man = after.league.playersById.getValue(theirs.id)
        assertEquals(Position.TE, man.position)
        assertEquals(PositionGroup.TE, man.archetype.group)
        assertTrue(after.league.team(club.id).depthPins.order[Position.WR].orEmpty().none { it == theirs.id.v })
    }

    @Test
    fun `a move inside the group keeps his archetype`() {
        val tackle = league.roster(league.teams[5].id).first { it.position == Position.LT }
        val after = PlayerEdits.apply(on, tackle.id.v, PlayerEdits.current(on, tackle.id.v).copy(position = Position.RT))
        assertEquals(tackle.archetype, after.league.playersById.getValue(tackle.id).archetype)
    }
}
