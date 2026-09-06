package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Conference
import com.nflsim.engine.model.Division
import com.nflsim.engine.model.League
import com.nflsim.engine.rng.SplitMixRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SeasonTest {

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }

    // ---- schedule ----------------------------------------------------

    @Test
    fun `every team plays seventeen games`() {
        val schedule = ScheduleGenerator.generate(league, 2026, SplitMixRng(1L))
        league.teams.forEach { team ->
            assertEquals(17, schedule.forTeam(team.id).size,
                "${team.abbrev} plays ${schedule.forTeam(team.id).size} games")
        }
        assertEquals(272, schedule.games.size)
    }

    @Test
    fun `nobody plays twice in a week`() {
        val schedule = ScheduleGenerator.generate(league, 2026, SplitMixRng(2L))
        (1..Schedule.WEEKS).forEach { week ->
            val playing = schedule.week(week).flatMap { listOf(it.home, it.away) }
            assertEquals(playing.size, playing.toSet().size, "a team plays twice in week $week")
        }
    }

    @Test
    fun `everyone gets exactly one bye and it is not week one`() {
        val schedule = ScheduleGenerator.generate(league, 2026, SplitMixRng(3L))
        league.teams.forEach { team ->
            val bye = schedule.byeWeek(team.id)
            assertNotNull(bye, "${team.abbrev} never has a bye")
            assertTrue(bye in 4..15, "${team.abbrev} has a bye in week $bye")
        }
    }

    @Test
    fun `division rivals are played home and away`() {
        val schedule = ScheduleGenerator.generate(league, 2026, SplitMixRng(4L))
        league.teams.forEach { team ->
            val rivals = league.teams.filter {
                it.conference == team.conference && it.division == team.division && it.id != team.id
            }
            rivals.forEach { rival ->
                val games = schedule.games.filter {
                    it.involves(team.id) && it.involves(rival.id)
                }
                assertEquals(2, games.size,
                    "${team.abbrev} plays ${rival.abbrev} ${games.size} times")
                assertEquals(1, games.count { it.home == team.id },
                    "${team.abbrev} vs ${rival.abbrev} is not home and away")
            }
        }
    }

    @Test
    fun `home and away games are close to balanced`() {
        val schedule = ScheduleGenerator.generate(league, 2026, SplitMixRng(5L))
        league.teams.forEach { team ->
            val home = schedule.games.count { it.home == team.id }
            assertTrue(home in 7..10, "${team.abbrev} has $home home games")
        }
    }

    @Test
    fun `the rotation changes from year to year`() {
        val a = ScheduleGenerator.generate(league, 2026, SplitMixRng(6L))
        val b = ScheduleGenerator.generate(league, 2027, SplitMixRng(6L))
        val teamId = league.teams.first().id
        val opponentsA = a.forTeam(teamId).mapNotNull { it.opponentOf(teamId) }.toSet()
        val opponentsB = b.forTeam(teamId).mapNotNull { it.opponentOf(teamId) }.toSet()
        assertTrue(opponentsA != opponentsB, "the same opponents came up two years running")
    }

    // ---- season ------------------------------------------------------

    private val season: SeasonResult by lazy { SeasonSimulator(league, 2026, 2026L).simulate() }

    @Test
    fun `a full season completes with a champion`() {
        assertNotNull(season.champion)
        assertEquals(272, season.results.size)
        assertEquals(13, season.playoffs.size) // 6 wild card, 4 divisional, 2 title, 1 final
    }

    @Test
    fun `every team plays seventeen and the records add up`() {
        league.teams.forEach { team ->
            val r = season.record(team.id)
            assertEquals(17, r.games, "${team.abbrev} record shows ${r.games} games")
        }
        val wins = league.teams.sumOf { season.record(it.id).wins }
        val losses = league.teams.sumOf { season.record(it.id).losses }
        assertEquals(wins, losses, "league wins and losses do not balance")
    }

    @Test
    fun `the standings look like a real league`() {
        val winTotals = league.teams.map { season.record(it.id).wins }
        val best = winTotals.max()
        val worst = winTotals.min()
        val mean = winTotals.average()
        val sd = kotlin.math.sqrt(winTotals.map { (it - mean) * (it - mean) }.average())

        assertTrue(best in 12..17, "best record in the league was $best wins")
        assertTrue(worst in 0..5, "worst record in the league was $worst wins")
        assertTrue(mean in 8.0..9.0, "mean wins was $mean")
        assertTrue(sd in 2.0..4.0, "standard deviation of wins was $sd")
    }

    @Test
    fun `seeding gives four division winners and three wild cards`() {
        Conference.entries.forEach { conference ->
            val seeds = season.seedsFor(conference)
            assertEquals(7, seeds.size)
            assertEquals(seeds.size, seeds.toSet().size, "a team is seeded twice")

            val standings = Standings(league, season.results, SplitMixRng(1L))
            val winners = standings.divisionWinners(conference).toSet()
            assertEquals(winners, seeds.take(4).toSet(),
                "the top four seeds are not the division winners")
        }
    }

    @Test
    fun `a division winner always makes the field`() {
        Conference.entries.forEach { conference ->
            Division.entries.forEach { division ->
                val standings = Standings(league, season.results, SplitMixRng(1L))
                val winner = standings.division(conference, division).first()
                assertTrue(winner in season.seedsFor(conference),
                    "${league.team(winner).abbrev} won its division and missed the playoffs")
            }
        }
    }

    @Test
    fun `higher seeds host and the bracket resolves`() {
        val wildCard = season.playoffs.filter { it.round == PlayoffRound.WILD_CARD }
        assertEquals(6, wildCard.size)
        wildCard.forEach {
            assertTrue(it.homeSeed < it.awaySeed,
                "seed ${it.homeSeed} hosted seed ${it.awaySeed}")
        }
        assertTrue(season.playoffs.none { it.homeScore == it.awayScore },
            "a playoff game ended in a tie")
    }

    @Test
    fun `the top seed does not play on wild card weekend`() {
        Conference.entries.forEach { conference ->
            val top = season.seedsFor(conference).first()
            val played = season.playoffs.any {
                it.round == PlayoffRound.WILD_CARD && (it.home == top || it.away == top)
            }
            assertTrue(!played, "the top seed played in the wild card round")
        }
    }

    @Test
    fun `statistical leaders are plausible`() {
        val passing = LeagueLeaders.passingYards(league, season.playerStats, 5)
        val rushing = LeagueLeaders.rushingYards(league, season.playerStats, 5)
        assertTrue(passing.isNotEmpty() && rushing.isNotEmpty())
        assertTrue(passing.first().value in 3200..5800,
            "passing leader threw for ${passing.first().value}")
        assertTrue(rushing.first().value in 900..2300,
            "rushing leader ran for ${rushing.first().value}")
    }

    @Test
    fun `awards go to somebody`() {
        val a = season.awards
        assertNotNull(a.mostValuablePlayer)
        assertNotNull(a.offensivePlayerOfTheYear)
        assertNotNull(a.defensivePlayerOfTheYear)
    }

    @Test
    fun `the same seed replays the same season`() {
        val a = SeasonSimulator(league, 2026, 99L).simulate()
        val b = SeasonSimulator(league, 2026, 99L).simulate()
        assertEquals(a.champion, b.champion)
        assertEquals(a.results, b.results)
        assertEquals(a.records, b.records)
    }

    @Test
    fun `different seeds produce different seasons`() {
        val a = SeasonSimulator(league, 2026, 1L).simulate()
        val b = SeasonSimulator(league, 2026, 2L).simulate()
        assertTrue(a.results != b.results, "two seeds produced identical seasons")
    }
}
