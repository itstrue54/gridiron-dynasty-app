package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.Scouting
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.Form
import com.nflsim.engine.tuning.TuningTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 4.7: what a coach's game plan, motivation and evaluation do, each from the league's coaching mean. */
class CoachRatingsTest {

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }
    private val staff = TuningTable.REALISTIC.staff

    private fun context(): PlayContext {
        val offTeam = league.teams.first { it.abbrev == "KC" }
        val defTeam = league.teams.first { it.abbrev == "SEA" }
        val offScheme = SchemeCatalog[offTeam.offenseScheme]
        val defScheme = SchemeCatalog[defTeam.defenseScheme]
        return PlayContext(
            offense = OffenseUnit.from(DepthChart.auto(league.roster(offTeam.id), offScheme), Personnel.P_11, offScheme),
            defense = DefenseUnit.from(DepthChart.auto(league.roster(defTeam.id), defScheme), DefensiveFront.FOUR_THREE_OVER, defScheme),
            state = PlayState(),
        )
    }

    @Test
    fun `an average coach or an empty chair is worth nothing, and the rest are centred`() {
        assertEquals(0f, coachEdge(null, 6f, staff))
        assertEquals(0f, coachEdge(staff.coachMean.toInt(), 6f, staff))
        assertTrue(coachEdge(100, 6f, staff) > 0f && coachEdge(30, 6f, staff) < 0f)
        assertEquals(0f, coachEdge(45, 6f, staff) + coachEdge(85, 6f, staff), 1e-6f)
    }

    @Test
    fun `a coordinator's game plan gains his side yards, and the other coordinator's takes them back`() {
        fun yardsPerPlay(offEdge: Float, defEdge: Float): Double {
            val ctx = context().copy(offEdge = offEdge, defEdge = defEdge)
            val rng = SplitMixRng(21L)
            var yards = 0L
            val n = 20_000
            repeat(n) {
                yards += PlaySimulator.simPlay(ctx, PlayCaller.offense(ctx, rng), PlayCaller.defense(ctx, rng), rng).yards
            }
            return yards.toDouble() / n
        }
        val sharp = coachEdge(100, staff.gameplanPoints, staff)
        val dull = coachEdge(30, staff.gameplanPoints, staff)
        val even = yardsPerPlay(0f, 0f)
        assertTrue(yardsPerPlay(sharp, 0f) > even, "a sharp offensive coordinator gains yards")
        assertTrue(yardsPerPlay(dull, 0f) < even, "a dull one loses them")
        assertTrue(yardsPerPlay(0f, sharp) < even, "a sharp defensive coordinator takes them away")
        assertEquals(even, yardsPerPlay(sharp, sharp), 1e-9, "two equal coordinators cancel")
    }

    @Test
    fun `a special teams coordinator's game plan is worth yards on a return`() {
        val team = league.teams.first()
        val scheme = SchemeCatalog[team.offenseScheme]
        val returner = SpecialTeams.returnerFor(DepthChart.auto(league.roster(team.id), scheme), scheme)
        // Returned kicks only: a touchback has no return to coach.
        fun meanSpot(edge: Float): Double {
            val rng = SplitMixRng(5L)
            return (1..4000).map { SpecialTeams.kickoff(returner, scheme, rng, edge = edge) }
                .filter { it.second != "Touchback." }.map { it.first }.average()
        }
        assertTrue(meanSpot(4f) > meanSpot(0f) + 2.0)
        assertTrue(meanSpot(-4f) < meanSpot(0f) - 2.0)
    }

    @Test
    fun `a motivator shortens a slump, and leaves a hot streak alone`() {
        val t = TuningTable.REALISTIC
        val player = league.players.first { it.position == com.nflsim.engine.model.Position.RB }
        val slumping = player.copy(form = -60)
        val hot = player.copy(form = 60)
        val good = Form.next(slumping, null, t, motivation = 100)
        val poor = Form.next(slumping, null, t, motivation = 30)
        val average = Form.next(slumping, null, t, motivation = staff.coachMean.toInt())
        assertTrue(good > average && average > poor, "slump after a week off: good $good, average $average, poor $poor")
        assertEquals(Form.next(slumping, null, t), average, "an average coach is the old decay")
        assertEquals(Form.next(hot, null, t, motivation = 100), Form.next(hot, null, t, motivation = 30))
        assertTrue(Form.next(slumping, null, t, motivation = 0) <= 0, "a slump never turns into a streak by itself")
    }

    @Test
    fun `a head coach's eye for talent adds to the scouting department`() {
        val team = league.teams.first()
        val head = league.coach(team.staff.headCoach)
        fun with(evaluation: Int) = league.copy(coaches = league.coaches + (head.id to head.copy(
            ratings = head.ratings.copy(evaluation = evaluation))))
        val mean = staff.coachMean.toInt()
        assertEquals(team.staff.scoutingDept, Scouting.department(team, with(mean)))
        assertTrue(Scouting.department(team, with(100)) > team.staff.scoutingDept)
        assertTrue(Scouting.department(team, with(30)) < team.staff.scoutingDept)
        val nobody = team.copy(staff = team.staff.copy(headCoach = com.nflsim.engine.model.CoachId(0)))
        assertEquals(team.staff.scoutingDept, Scouting.department(nobody, league))
    }
}
