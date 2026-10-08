package com.nflsim.engine.gen

import com.nflsim.engine.model.League
import com.nflsim.engine.model.Position
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.overall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeagueGeneratorTest {

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }

    @Test
    fun `the league has 32 teams in 8 divisions of 4`() {
        assertEquals(32, league.teams.size)
        val divisions = league.divisions()
        assertEquals(8, divisions.size)
        divisions.forEach { (key, teams) ->
            assertEquals(4, teams.size, "$key has ${teams.size} teams")
        }
    }

    @Test
    fun `every team carries a full roster`() {
        assertEquals(53, RosterGenerator.SIZE)
        league.teams.forEach {
            assertEquals(53, it.roster.size, "${it.abbrev} has ${it.roster.size} players")
        }
        league.teams.forEach {
            assertEquals(com.nflsim.engine.season.PracticeSquads.SIZE, it.practiceSquad.size,
                "${it.abbrev} has ${it.practiceSquad.size} on its practice squad")
        }
        // Everyone else is on the street, waiting for a call (CampCutGenerator).
        assertEquals(
            32 * (53 + com.nflsim.engine.season.PracticeSquads.SIZE) + league.tuning.ai.freeAgentPool,
            league.players.size,
        )
    }

    @Test
    fun `player ids are unique and no player is on two rosters`() {
        val ids = league.players.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate player ids")

        val assignments = league.teams.flatMap { it.roster }
        assertEquals(assignments.size, assignments.toSet().size, "a player is on two rosters")
    }

    @Test
    fun `every roster has the positions a football team needs`() {
        league.teams.forEach { team ->
            val byPosition = league.roster(team.id).groupBy { it.position }
            assertTrue(byPosition[Position.QB]!!.size >= 2, "${team.abbrev} short at QB")
            assertTrue(byPosition[Position.K]!!.isNotEmpty(), "${team.abbrev} has no kicker")
            assertTrue(byPosition[Position.P]!!.isNotEmpty(), "${team.abbrev} has no punter")
            assertTrue(byPosition[Position.LS]!!.isNotEmpty(), "${team.abbrev} has no long snapper")
            val olCount = listOf(Position.LT, Position.LG, Position.C, Position.RG, Position.RT)
                .sumOf { byPosition[it]?.size ?: 0 }
            assertEquals(9, olCount, "${team.abbrev} has $olCount offensive linemen")
        }
    }

    @Test
    fun `every team runs a real scheme`() {
        val offensive = SchemeCatalog.offensive.map { it.id }.toSet()
        val defensive = SchemeCatalog.defensive.map { it.id }.toSet()
        league.teams.forEach {
            assertTrue(it.offenseScheme in offensive, "${it.abbrev}: ${it.offenseScheme}")
            assertTrue(it.defenseScheme in defensive, "${it.abbrev}: ${it.defenseScheme}")
        }
    }

    @Test
    fun `talent is distributed like a football league`() {
        val overalls = league.players.map { overall(it) }
        val mean = overalls.average()
        assertTrue(mean in 66.0..76.0, "league mean overall was $mean")

        val elite = overalls.count { it >= 90 }
        assertTrue(elite in 5..120, "league had $elite players at 90+")

        val best = overalls.max()
        assertTrue(best >= 90, "best player in the league was only $best")
    }

    @Test
    fun `teams are not all the same strength`() {
        val strengths = league.teams.map { team ->
            league.roster(team.id).map { overall(it) }.sortedDescending().take(22).average()
        }
        val spread = strengths.max() - strengths.min()
        assertTrue(spread >= 4.0, "top-22 talent spread across the league was only $spread")
        assertTrue(spread <= 25.0, "talent spread of $spread is implausibly wide")
    }

    @Test
    fun `names do not repeat much`() {
        val names = league.players.map { it.name }
        val unique = names.toSet().size
        val duplicateRate = 1.0 - unique.toDouble() / names.size
        assertTrue(duplicateRate < 0.03, "duplicate name rate was $duplicateRate")
    }

    @Test
    fun `the same seed produces the same league`() {
        val a = LeagueGenerator.generate(2026, 7L)
        val b = LeagueGenerator.generate(2026, 7L)
        assertEquals(a.players.map { it.name }, b.players.map { it.name })
        assertEquals(a.players.map { overall(it) }, b.players.map { overall(it) })
        assertEquals(a.teams.map { it.offenseScheme }, b.teams.map { it.offenseScheme })
    }

    @Test
    fun `different seeds produce different leagues`() {
        val a = LeagueGenerator.generate(2026, 7L)
        val b = LeagueGenerator.generate(2026, 8L)
        assertTrue(a.players.map { it.name } != b.players.map { it.name })
    }

    @Test
    fun `every player belongs to the team that rostered him`() {
        league.teams.forEach { team ->
            league.roster(team.id).forEach {
                assertEquals(team.id, it.teamId, "${it.name} is rostered by ${team.abbrev} but tagged ${it.teamId}")
            }
        }
    }
}
