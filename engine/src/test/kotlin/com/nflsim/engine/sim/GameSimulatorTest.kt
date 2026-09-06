package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameSimulatorTest {

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }

    private fun team(abbrev: String, aggression: Float = 0.5f): GameTeam {
        val t = league.teams.first { it.abbrev == abbrev }
        return GameTeam(t, league.roster(t.id),
            SchemeCatalog[t.offenseScheme], SchemeCatalog[t.defenseScheme], aggression)
    }

    private fun play(seed: Long, home: String = "KC", away: String = "SEA"): GameResult =
        GameSimulator(team(home), team(away)).simulate(SplitMixRng(seed))

    @Test
    fun `a game finishes and produces a score`() {
        val g = play(1L)
        assertTrue(g.homeScore >= 0 && g.awayScore >= 0)
        assertTrue(g.playByPlay.isNotEmpty(), "no play by play was recorded")
        assertTrue(g.drives.size >= 14, "only ${g.drives.size} drives in a full game")
    }

    @Test
    fun `the box score reconciles`() {
        repeat(30) { seed ->
            val g = play(seed.toLong())
            assertTrue(g.boxScore.reconciles(),
                "seed $seed: total yards did not equal rush plus pass plus sack yardage")
        }
    }

    @Test
    fun `games are decided by football-shaped scores`() {
        val scores = (1..120).map { play(it.toLong()) }
        val mean = scores.flatMap { listOf(it.homeScore, it.awayScore) }.average()
        val shutouts = scores.count { it.homeScore == 0 || it.awayScore == 0 }
        val blowouts = scores.count { it.margin > 35 }

        assertTrue(mean in 16.0..30.0, "mean team score was $mean")
        assertTrue(shutouts <= 12, "$shutouts shutouts in 120 games")
        assertTrue(blowouts <= 18, "$blowouts games decided by more than 35")
    }

    @Test
    fun `a game runs a plausible number of plays`() {
        val plays = (1..60).map { play(it.toLong()).totalPlays }
        val mean = plays.average()
        assertTrue(mean in 105.0..145.0, "mean total plays per game was $mean")
        assertTrue(plays.all { it in 70..190 }, "play counts ranged ${plays.min()}..${plays.max()}")
    }

    @Test
    fun `no drive runs forever`() {
        repeat(40) { seed ->
            val g = play(seed.toLong())
            val longest = g.drives.maxByOrNull { it.plays }
            assertTrue(longest!!.plays <= 30,
                "seed $seed had a ${longest.plays} play drive")
        }
    }

    @Test
    fun `the clock always moves forward`() {
        val g = play(3L)
        var lastQuarter = 0
        var lastClock = Int.MAX_VALUE
        g.playByPlay.forEach { p ->
            if (p.quarter > lastQuarter) { lastQuarter = p.quarter; lastClock = Int.MAX_VALUE }
            assertTrue(p.quarter >= lastQuarter, "the quarter went backwards")
            assertTrue(p.clock <= lastClock, "the clock ran backwards in Q${p.quarter}")
            assertTrue(p.clock in 0..GameState.QUARTER_SECONDS, "clock read ${p.clock}")
            lastClock = p.clock
        }
        assertTrue(lastQuarter >= 4, "the game ended in quarter $lastQuarter")
    }

    @Test
    fun `individual stats add up to the team totals`() {
        val g = play(5L)
        val playerRushing = g.boxScore.players.values.sumOf { it.rushYards }
        val teamRushing = g.boxScore.home.rushYards + g.boxScore.away.rushYards
        assertEquals(teamRushing, playerRushing, "player rushing did not match team rushing")

        val playerReceiving = g.boxScore.players.values.sumOf { it.receivingYards }
        val teamPassing = g.boxScore.home.passYards + g.boxScore.away.passYards
        assertEquals(teamPassing, playerReceiving, "receiving did not match team passing")
    }

    @Test
    fun `the better team wins more often`() {
        var strongWins = 0
        val n = 200
        repeat(n) { seed ->
            // Green Bay against the league's weakest roster by top-22 talent.
            val g = GameSimulator(team("KC"), team("JAX")).simulate(SplitMixRng(seed.toLong()))
            if (g.winner == g.home) strongWins++
        }
        assertTrue(strongWins in 60..190, "home team won $strongWins of $n")
    }

    @Test
    fun `an aggressive coach goes for it more often`() {
        fun attempts(aggression: Float): Int =
            (1..40).sumOf { seed ->
                GameSimulator(team("KC", aggression), team("SEA"))
                    .simulate(SplitMixRng(seed.toLong()))
                    .boxScore.home.fourthDownAttempts
            }
        val bold = attempts(0.95f)
        val timid = attempts(0.05f)
        assertTrue(bold > timid, "bold coach went for it $bold times, timid $timid")
    }

    @Test
    fun `the same seed replays the same game`() {
        val a = play(42L)
        val b = play(42L)
        assertEquals(a.homeScore, b.homeScore)
        assertEquals(a.awayScore, b.awayScore)
        assertEquals(a.playByPlay.size, b.playByPlay.size)
        assertEquals(a.drives, b.drives)
    }

    @Test
    fun `field position never leaves the field`() {
        repeat(25) { seed ->
            val g = play(seed.toLong())
            g.playByPlay.forEach {
                assertTrue(it.yardLine in 1..99, "ball was spotted at ${it.yardLine}")
                assertTrue(it.down in 1..4, "it was ${it.down} down")
            }
        }
    }
}
