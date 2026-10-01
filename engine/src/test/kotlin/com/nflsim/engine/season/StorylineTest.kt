package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.model.NewsKind
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.sim.GameResult
import com.nflsim.engine.sim.GameSimulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 10.4: a rookie QB the town wants, when the veteran in front of him has a day to forget. */
class StorylineTest {

    private val league = LeagueGenerator.generate(2026, 8L)

    /** A game in which the home club's starter threw two or more interceptions. */
    private val rough: Pair<GameResult, com.nflsim.engine.model.Player> by lazy {
        val teams = WeekRunner.teams(league, league.tuning).values.toList()
        (0 until 400).asSequence().map { i ->
            val home = teams[i % teams.size]
            val away = teams[(i + 5) % teams.size]
            GameSimulator(home, away, league.tuning).simulate(SplitMixRng(i.toLong()))
        }.mapNotNull { g ->
            val starter = league.roster(g.home).filter { it.position == Position.QB }.maxByOrNull { g.snaps[it.id.v] ?: 0 }
            starter?.takeIf { (g.boxScore.players[it.id.v]?.interceptionsThrown ?: 0) >= 2 }?.let { g to it }
        }.first()
    }

    /** The league with the starter a veteran and his backup a rookie as good as he is. */
    private fun withRookie(): Pair<League, com.nflsim.engine.model.Player> {
        val (game, starter) = rough
        val backup = league.roster(game.home).first { it.position == Position.QB && it.id != starter.id }
        val l = league.copy(players = league.players.map {
            when (it.id) {
                starter.id -> it.copy(accruedSeasons = 4)
                backup.id -> it.copy(accruedSeasons = 0, ratings = starter.ratings, injuryWeeks = 0)
                else -> it
            }
        })
        return l to l.player(backup.id)
    }

    /** A record of [losses] losses for [club], each to the next club along. */
    private fun standings(l: League, club: TeamId, losses: Int): Standings {
        val others = l.teams.map { it.id }.filter { it != club }
        return Standings(l, (1..losses).map { w -> GameOutcome(w, club, others[w], 10, 24) }, SplitMixRng(0L))
    }

    private fun file(l: League, s: Standings, said: List<com.nflsim.engine.model.NewsEvent> = emptyList()) =
        NewsDesk.forWeek(l, 5, listOf(rough.first), emptyMap(), emptyMap(), s, said, SplitMixRng(1L))
            .filter { it.kind == NewsKind.STORY }

    @Test
    fun `a losing club whose veteran throws it away has a rookie the town wants`() {
        val (l, rookie) = withRookie()
        val club = rough.first.home
        val story = file(l, standings(l, club, 3)).single()
        assertEquals(rookie.id.v, story.player)
        assertEquals(club.v, story.team)
        assertTrue(rookie.name in story.headline && rough.second.name in story.headline, story.headline)
        assertTrue("0-3" in story.headline || l.team(club).nickname in story.headline, story.headline)
    }

    @Test
    fun `not for a club that is winning, a rookie far behind, or a second time`() {
        val (l, rookie) = withRookie()
        val club = rough.first.home
        assertEquals(emptyList(), file(l, standings(l, club, 1)), "one loss is no crisis")
        // A rookie rated well below the starter is nobody's answer.
        val weak = l.copy(players = l.players.map {
            if (it.id == rookie.id) it.copy(ratings = com.nflsim.engine.model.Ratings(IntArray(rookie.ratings.values.size) { 20 })) else it
        })
        assertEquals(emptyList(), file(weak, standings(weak, club, 3)))
        val once = file(l, standings(l, club, 3))
        assertEquals(emptyList(), file(l, standings(l, club, 3), once), "said once a season")
    }
}
