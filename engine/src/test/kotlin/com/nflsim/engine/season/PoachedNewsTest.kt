package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.NewsKind
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TransactionKind
import com.nflsim.engine.ratings.overall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The user hears when another club raids his practice squad. */
class PoachedNewsTest {

    @Test
    fun `a man signed off the user's squad is news to the user`() {
        val league = LeagueGenerator.generate(2026, 23L)
        val user = league.teams.first().id
        var d = DynastyEngine.start(league, 2026, 23L, user)

        // The best receiver in the league is parked on the user's squad, and
        // every other club loses its receivers for the year.
        val star = d.league.players.filter { it.position == Position.WR && it.teamId != user }
            .maxByOrNull { overall(it) }!!
        val l = d.league
        val parked = l.copy(
            players = l.players.map {
                when {
                    it.id == star.id -> it.copy(teamId = null, contract = null, status = PlayerStatus.PRACTICE_SQUAD)
                    it.position == Position.WR && it.teamId != null && it.teamId != user ->
                        it.copy(injuryWeeks = RosterMoves.IR_WEEKS + 6)
                    else -> it
                }
            },
            teams = l.teams.map {
                when (it.id) {
                    star.teamId -> it.copy(roster = it.roster - star.id)
                    user -> it.copy(practiceSquad = it.practiceSquad.drop(1) + star.id)
                    else -> it
                }
            },
        )
        d = DynastyEngine.advance(d.copy(league = parked))

        val taken = d.league.transactions.filter {
            it.kind == TransactionKind.SIGNED_OFF_SQUAD && it.other == user.v
        }
        assertTrue(taken.any { it.player == star.id.v }, "somebody should have signed him away")
        val news = d.news.filter { it.kind == NewsKind.POACHED }
        assertEquals(taken.size, news.size, "one line of news for each man taken")
        assertTrue(news.any { it.player == star.id.v && it.headline.contains("off your practice squad") },
            news.joinToString { it.headline })
    }
}
