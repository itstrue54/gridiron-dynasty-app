package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.overall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Game-day inactives: 47 dress, or 48 with eight linemen (CBA Article 25). */
class GameDayTest {

    private val league = LeagueGenerator.generate(2026, 31L)
    private val team = league.teams[4]
    private val roster = league.roster(team.id)
    private val offence = SchemeCatalog.tuned(team.offenseScheme, league.tuning)
    private val defence = SchemeCatalog.tuned(team.defenseScheme, league.tuning)

    private fun actives(eligible: List<Player>, named: List<Int> = emptyList()) =
        GameDay.actives(eligible, offence, defence, named)

    private fun line(players: List<Player>) = players.count { it.position.group == PositionGroup.OL }

    @Test
    fun `a healthy 53 with nine linemen dresses 48`() {
        assertTrue(line(roster) >= GameDay.LINEMEN)
        val dressed = actives(roster)
        assertEquals(GameDay.ACTIVES_WITH_LINE, dressed.size)
        assertTrue(line(dressed) >= GameDay.LINEMEN, "it keeps eight linemen to earn the 48th place")
    }

    @Test
    fun `the scratches are the deepest men, never a position a game needs`() {
        val dressed = actives(roster)
        assertTrue(dressed.count { it.position == Position.QB } >= 2)
        listOf(Position.K, Position.P, Position.LS).forEach { pos -> assertTrue(dressed.any { it.position == pos }, "$pos dresses") }
        // Every starter dresses: the best man at each position is never the one scratched.
        roster.groupBy { it.position }.forEach { (pos, men) ->
            val best = men.maxBy { overall(it, if (pos.isOffense) offence else defence) }
            assertTrue(best in dressed, "the best $pos dresses")
        }
    }

    @Test
    fun `with only seven linemen a club dresses 47`() {
        val lines = roster.filter { it.position.group == PositionGroup.OL }
        val short = roster - lines.drop(7).toSet()
        assertEquals(7, line(short))
        assertEquals(GameDay.ACTIVES, actives(short).size)
    }

    @Test
    fun `a club with eight linemen keeps all eight`() {
        val lines = roster.filter { it.position.group == PositionGroup.OL }
        val eight = roster - lines.drop(8).toSet()
        val dressed = actives(eight)
        assertEquals(8, line(dressed))
        assertEquals(GameDay.ACTIVES_WITH_LINE, dressed.size)
    }

    @Test
    fun `a club with no more than it may dress dresses everyone`() {
        val few = roster.take(45)
        assertEquals(few, actives(few))
    }

    @Test
    fun `the club's named inactives come first, and only as many as it has to scratch`() {
        val receivers = roster.filter { it.position == Position.WR }.sortedByDescending { overall(it, offence) }
        val named = receivers.take(2).map { it.id.v } +
            roster.filter { it.position == Position.CB }.take(4).map { it.id.v }
        val dressed = actives(roster, named)
        assertEquals(GameDay.ACTIVES_WITH_LINE, dressed.size)
        assertTrue(receivers.take(2).none { it in dressed }, "the two receivers he named sit, starters or not")
        // Five scratches and six names: the last name dresses.
        assertTrue(roster.first { it.id.v == named.last() } in dressed)
    }

    @Test
    fun `a name that would leave the club without a kicker is ignored`() {
        val kicker = roster.single { it.position == Position.K }
        val dressed = actives(roster, listOf(kicker.id.v))
        assertTrue(kicker in dressed)
        assertEquals(GameDay.ACTIVES_WITH_LINE, dressed.size)
    }

    @Test
    fun `every club takes the field with no more than 48`() {
        val teams = WeekRunner.teams(league, league.tuning)
        teams.values.forEach { assertTrue(it.roster.size <= GameDay.ACTIVES_WITH_LINE, "${it.team.abbrev} dresses ${it.roster.size}") }
    }
}
