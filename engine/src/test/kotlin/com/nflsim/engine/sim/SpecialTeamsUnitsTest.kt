package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.GameDay
import com.nflsim.engine.season.Transactions
import com.nflsim.engine.season.WeekRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Special teams as units read from their men's ratings (SPEC 5.10). */
class SpecialTeamsUnitsTest {

    private val league = LeagueGenerator.generate(2026, 41L)
    private val st = league.tuning.specialTeams
    private val teams = WeekRunner.teams(league, league.tuning)
    private val team = teams.values.first()
    private val units = SpecialTeamsUnits.of(team, st)
    private val specialists = setOf(Position.QB, Position.K, Position.P, Position.LS)

    @Test
    fun `each unit has its men, none of them specialists, none twice`() {
        assertEquals(SpecialTeamsUnits.KICK_COVERAGE, units.kickCoverage.size)
        assertEquals(SpecialTeamsUnits.KICK_RETURN, units.kickReturn.size)
        assertEquals(SpecialTeamsUnits.GUNNERS, units.gunners.size)
        assertEquals(SpecialTeamsUnits.JAMMERS, units.jammers.size)
        listOf(units.kickCoverage, units.kickReturn, units.puntCoverage, units.puntReturn, units.gunners, units.jammers).forEach { unit ->
            assertTrue(unit.none { it.position in specialists })
            assertEquals(unit.size, unit.distinctBy { it.id }.size)
        }
        assertTrue(units.gunners.none { it in units.puntCoverage }, "a gunner is not also inside the coverage")
        assertTrue(units.jammers.none { it in units.puntReturn })
        assertEquals(Position.LS, units.snapper?.position)
        assertEquals(Position.P, units.holder?.position)
    }

    @Test
    fun `a returner fields the ball and doesn't block for himself`() {
        val kick = SpecialTeams.returnerFor(team.offDepth, team.offScheme)
        val punt = SpecialTeams.returnerFor(team.offDepth, team.offScheme, punt = true)
        assertTrue(kick != null && kick !in units.kickReturn)
        assertTrue(punt != null && punt !in units.puntReturn && punt !in units.jammers)
    }

    @Test
    fun `a club's first receiver returns kicks only when far quicker than its backups`() {
        val receivers = team.offDepth.at(Position.WR)
        val first = receivers.first(); val backup = receivers.last()
        fun withSpeeds(top: Int, spare: Int): DepthChart {
            val roster = team.roster.map {
                when (it.id) {
                    first.id -> it.copy(ratings = it.ratings.with(RatingId.SPEED to top))
                    backup.id -> it.copy(ratings = it.ratings.with(RatingId.SPEED to spare))
                    else -> it.copy(ratings = it.ratings.with(RatingId.SPEED to minOf(it.ratings[RatingId.SPEED], 60)))
                }
            }
            return DepthChart.auto(roster, team.offScheme)
        }
        // Ten points quicker isn't enough to risk him; thirty is.
        assertEquals(backup.id, SpecialTeams.returnerFor(withSpeeds(95, 85), team.offScheme, st = st)?.id)
        assertEquals(first.id, SpecialTeams.returnerFor(withSpeeds(99, 70), team.offScheme, st = st)?.id)
    }

    @Test
    fun `return units are mostly backups`() {
        val starters = (team.offDepth.at(Position.WR).take(3) + team.offDepth.at(Position.RB).take(1) +
            team.defDepth.at(Position.CB).take(3) + team.defDepth.at(Position.LB).take(2) + team.defDepth.at(Position.S).take(2)).toSet()
        assertTrue(units.kickReturn.count { it in starters } <= 3, "${units.kickReturn.count { it in starters }} starters return kicks")
    }

    @Test
    fun `a core special teamer the club pins plays on every coverage and return unit`() {
        val starter = team.offDepth.at(Position.WR).first()
        val pinned = GameTeam(
            team.team.copy(depthPins = team.team.depthPins.copy(specialTeams = listOf(starter.id.v))),
            team.roster, team.offScheme, team.defScheme,
        )
        val u = SpecialTeamsUnits.of(pinned, st)
        assertTrue(starter in u.kickCoverage && starter in u.kickReturn)
        assertTrue(starter in u.gunners + u.puntCoverage && starter in u.jammers + u.puntReturn)
    }

    @Test
    fun `better return blocking against weaker coverage brings kicks back further`() {
        fun average(edge: Float): Double {
            val rng = SplitMixRng(9L)
            return (1..4000).map {
                SpecialTeams.kickoff(team.roster.first { it.position == Position.WR }, team.offScheme, rng, st,
                    matchup = KickMatchup(returnEdge = edge)).spot
            }.filter { it != GameState.TOUCHBACK_YARD_LINE }.average()
        }
        assertTrue(average(8f) > average(-8f) + 8, "16 points of matchup is worth about 16 yards")
    }

    @Test
    fun `a stronger leg puts more kickoffs through the end zone`() {
        val kicker = team.roster.first { it.position == Position.K }
        fun touchbacks(power: Int): Int {
            val rng = SplitMixRng(3L)
            val leg = kicker.copy(ratings = kicker.ratings.with(RatingId.KICK_POWER to power))
            return (1..4000).count {
                SpecialTeams.kickoff(null, team.offScheme, rng, st, kicker = leg, kickScheme = team.offScheme).touchback
            }
        }
        assertTrue(touchbacks(90) > touchbacks(50) + 500)
    }

    @Test
    fun `a rush that beats the protection blocks more kicks, and a bad snap costs accuracy`() {
        val kicker = team.roster.first { it.position == Position.K }
        fun kicks(m: KickMatchup): List<KickResult> {
            val rng = SplitMixRng(5L)
            return (1..6000).map { SpecialTeams.fieldGoal(kicker, 25, team.offScheme, 0, rng, st = st, matchup = m) }
        }
        assertTrue(kicks(KickMatchup(blockEdge = 10f)).count { it.blocked } > kicks(KickMatchup(blockEdge = -10f)).count { it.blocked })
        assertTrue(kicks(KickMatchup(snapEdge = 15f)).count { it.good } > kicks(KickMatchup(snapEdge = -15f)).count { it.good })
    }

    @Test
    fun `good gunners force more fair catches`() {
        val punter = team.roster.first { it.position == Position.P }
        val returner = team.roster.first { it.position == Position.WR }
        fun returned(gunners: Float): Int {
            val rng = SplitMixRng(7L)
            return (1..4000).count {
                SpecialTeams.punt(punter, returner, 30, team.offScheme, team.offScheme, rng, st,
                    matchup = KickMatchup(gunnerEdge = gunners)).returnYards > 0
            }
        }
        assertTrue(returned(10f) < returned(-10f))
    }

    @Test
    fun `a club calls up a squad man who covers kicks clearly better than its weakest`() {
        val off = team.offScheme; val def = team.defScheme
        val dressed = WeekRunner.eligible(league.roster(team.id))
        // A squad safety faster and surer than anyone on the 53.
        val ace = Transactions.freeAgents(league).first { it.position == Position.S }.let { p ->
            p.copy(ratings = RatingId.entries.fold(p.ratings) { r, id -> r.with(id to 95) })
        }
        assertTrue(ace in GameDay.callUps(dressed, listOf(ace), off, def, st = st))
    }
}
