package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Position
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kickoffs and punt returns as the play-by-play tells them. */
class KickLinesTest {

    private val league = LeagueGenerator.generate(2026, 43L)
    private val teams = WeekRunner.teams(league, league.tuning).values.toList()
    private fun game(i: Int) = GameSimulator(teams[i], teams[i + 13], league.tuning).simulate(SplitMixRng(i.toLong()))

    @Test
    fun `every kickoff is a line, the receiving club's, and the game opens with one`() {
        repeat(12) { i ->
            val plays = game(i).playByPlay
            val kicks = plays.withIndex().filter { it.value.kind == PlayKind.KICKOFF }
            assertEquals(PlayKind.KICKOFF, plays.first { it.kind != PlayKind.NOTE }.kind, "the game opens with the kickoff")
            assertTrue(kicks.any { it.value.quarter == 3 }, "the second half opens with one too")
            kicks.forEach { (at, kick) ->
                assertEquals(1 to 10, kick.down to kick.distance, "it leaves first and ten")
                // The next snap or kick is the club that received it.
                plays.drop(at + 1).firstOrNull { it.kind == PlayKind.SNAP || it.kind == PlayKind.PUNT || it.kind == PlayKind.FIELD_GOAL }
                    ?.let { assertEquals(kick.offense, it.offense, kick.text) }
            }
        }
    }

    @Test
    fun `no kickoff is said after the half or the game has run out`() {
        repeat(40) { i ->
            val plays = GameSimulator(teams[i % 30], teams[(i + 7) % 30], league.tuning).simulate(SplitMixRng(100L + i)).playByPlay
            // One kickoff opens the second half, and nothing is kicked once the game is over.
            val third = plays.filter { it.quarter == 3 }
            assertEquals(PlayKind.KICKOFF, third.first { it.kind != PlayKind.NOTE }.kind, "the half opens with its kickoff")
            assertTrue(third.zipWithNext().none { (a, b) -> a.kind == PlayKind.KICKOFF && b.kind == PlayKind.KICKOFF }, "two kickoffs running")
            assertTrue(plays.last { it.kind != PlayKind.NOTE }.kind != PlayKind.KICKOFF, "a kickoff after the final whistle")
        }
    }

    @Test
    fun `each kickoff run back is a return in the box score, and says who ran it`() {
        repeat(12) { i ->
            val g = game(i)
            val returned = g.playByPlay.filter { it.kind == PlayKind.KICKOFF && "ouchback" !in it.text }
            assertEquals(g.boxScore.players.values.sumOf { it.kickReturns }, returned.size)
            val returners = g.boxScore.players.filterValues { it.kickReturns > 0 }.keys
                .map { id -> league.players.first { it.id.v == id }.lastName }
            returned.forEach { line -> assertTrue(returners.any { it in line.text }, line.text) }
        }
    }

    @Test
    fun `a punt names the man who fielded it, returned or fair caught`() {
        val team = teams[0]
        val punter = team.offDepth.starter(Position.P)
        val returner = teams[1].offDepth.at(Position.WR)[0]
        val rng = SplitMixRng(11L)
        var returned = 0
        var caught = 0
        repeat(400) {
            val p = SpecialTeams.punt(punter, returner, 30, team.offScheme, teams[1].offScheme, rng)
            if (p.touchback || p.blocked) return@repeat
            assertTrue(returner.lastName in p.narrative, p.narrative)
            if (p.returnYards > 0) returned++ else caught++
        }
        assertTrue(returned > 0 && caught > 0, "both happen: $returned returned, $caught fair caught")
    }
}
