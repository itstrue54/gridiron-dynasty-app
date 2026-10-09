package com.example.nflsimtext.ui

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.ratings.Scouting
import com.nflsim.engine.ratings.ScoutingLens
import com.nflsim.engine.season.DynastyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A man's card from the trade screen: whoever's he is, read as the user's club would. */
class TradeCardTest {

    private val league = LeagueGenerator.generate(2026, 43L)
    private val dynasty = DynastyEngine.start(league, 2026, 43L, league.teams[3].id)
    private val newcomer = ScoutingLens.ownPlayer(0, Scouting.department(dynasty.team, league), league.tuning.scouting)

    @Test
    fun `another club's man reads as a newcomer would, however long he has been there`() {
        val theirs = league.players.filter { it.teamId != null && it.teamId != dynasty.userTeamId }
        val veteran = theirs.maxBy { it.clubYears }
        assertTrue("a man with years at his club", veteran.clubYears > 0)
        assertEquals(newcomer, lensOf(dynasty, veteran).confidence, 0f)
    }

    @Test
    fun `the user's own man reads as his staff knows him`() {
        val ours = league.roster(dynasty.userTeamId).maxBy { it.clubYears }
        assertEquals(lensFor(dynasty, ours).confidence, lensOf(dynasty, ours).confidence, 0f)
        if (ours.clubYears > 0) assertTrue(lensOf(dynasty, ours).confidence > newcomer)
    }
}
