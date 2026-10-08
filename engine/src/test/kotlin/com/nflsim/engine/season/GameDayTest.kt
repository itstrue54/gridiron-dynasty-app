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

    // ---- practice-squad call-ups ----

    private val squad = team.practiceSquad.map { league.player(it) }
    /** A practice-squad quarterback for the club, whether or not its squad has one. */
    private val squadQb = (squad.firstOrNull { it.position == Position.QB }
        ?: Transactions.freeAgents(league).first { it.position == Position.QB })
        .copy(status = com.nflsim.engine.model.PlayerStatus.PRACTICE_SQUAD)
    private val oneQb = roster.filter { it.position != Position.QB } + roster.filter { it.position == Position.QB }.take(1)

    @Test
    fun `a club down to one quarterback calls one up from its squad`() {
        val dressed = GameDay.actives(oneQb, offence, defence, squad = squad + squadQb)
        assertTrue(squadQb in dressed, "the squad quarterback dresses")
        assertEquals(2, dressed.count { it.position == Position.QB })
    }

    @Test
    fun `a man called up three times this season stays on the squad`() {
        val used = squadQb.copy(elevations = GameDay.CALL_UP_LIMIT)
        assertTrue(used !in GameDay.actives(oneQb, offence, defence, squad = listOf(used)))
    }

    @Test
    fun `a healthy club calls up only men who would make its kick coverage, and never more than two`() {
        val st = league.tuning.specialTeams
        val weakest = com.nflsim.engine.sim.SpecialTeamsUnits.coverageWorth(roster, offence, defence, st).values
            .sortedDescending()[com.nflsim.engine.sim.SpecialTeamsUnits.KICK_COVERAGE - 1]
        GameDay.callUps(roster, squad, offence, defence, st = st).forEach { man ->
            val cover = com.nflsim.engine.sim.SpecialTeamsUnits.value(
                man, com.nflsim.engine.sim.StRole.COVERAGE, if (man.position.isOffense) offence else defence, st)
            assertTrue(cover >= weakest + st.callUpCoverageMargin, "${man.position} called up without beating the coverage")
        }
        val thin = roster.take(30)
        assertEquals(GameDay.CALL_UPS, GameDay.callUps(thin, squad + squadQb, offence, defence).size)
    }

    @Test
    fun `a man the club names is called up, and its deepest man sits for him`() {
        val named = squad.first { it.position == Position.WR || it.position == Position.CB }
        val dressed = GameDay.actives(roster, offence, defence, squad = squad, callUp = listOf(named.id.v))
        assertTrue(named in dressed)
        assertEquals(GameDay.ACTIVES_WITH_LINE, dressed.size, "he dresses within the 48")
    }

    @Test
    fun `a called-up man plays for the club, counts the game, and goes back to the squad`() {
        val withQb = league.copy(
            players = league.players.map { p ->
                when {
                    p.teamId == team.id && p.position == Position.QB && p.id != oneQb.first { it.position == Position.QB }.id ->
                        p.copy(injuryWeeks = 2)
                    p.id == squadQb.id -> squadQb
                    else -> p
                }
            },
            teams = league.teams.map { if (it.id == team.id && squadQb.id !in it.practiceSquad) it.copy(practiceSquad = it.practiceSquad.drop(1) + squadQb.id) else it },
        )
        val teams = WeekRunner.teams(withQb, withQb.tuning)
        val ours = teams.getValue(team.id)
        val called = ours.roster.single { it.id == squadQb.id }
        assertEquals(team.id, called.teamId, "on the club for the game")
        val other = teams.values.first { it.team.id != team.id }
        val game = com.nflsim.engine.sim.GameSimulator(ours, other, withQb.tuning)
            .simulate(com.nflsim.engine.rng.SplitMixRng(5L))
        val after = WeekRunner.afterWeek(withQb, listOf(game), withQb.tuning)
        val back = after.player(squadQb.id)
        assertEquals(1, back.elevations)
        assertEquals(null, back.teamId, "the league keeps him on the squad")
        assertTrue(squadQb.id in after.team(team.id).practiceSquad)
    }

    @Test
    fun `a club down to three corners calls one up and sits a deeper man elsewhere`() {
        val corners = roster.filter { it.position == Position.CB }
        val thin = roster - corners.drop(3).toSet()
        val squadCb = (squad.firstOrNull { it.position == Position.CB }
            ?: Transactions.freeAgents(league).first { it.position == Position.CB })
        val dressed = GameDay.actives(thin, offence, defence, squad = squad + squadCb)
        assertEquals(4, dressed.count { it.position == Position.CB }, "four corners dress")
        assertTrue(dressed.size <= GameDay.ACTIVES_WITH_LINE)
    }
}
