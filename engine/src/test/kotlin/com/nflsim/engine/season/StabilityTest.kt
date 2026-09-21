package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.overall
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SPEC 14's M11 gate: thirty seasons, and the league is still a league.
 *
 * A dynasty game fails slowly. Nothing in a single season says whether the
 * talent pipeline keeps up with age, whether the cap binds, or whether one
 * club runs away with the thing - so this plays three decades and asserts the
 * shape of the league the whole way, not just at the end.
 */
class StabilityTest {

    private val years = 30

    private fun leagueMean(league: League): Double =
        league.teams.flatMap { t ->
            val off = SchemeCatalog.tuned(t.offenseScheme, league.tuning)
            val def = SchemeCatalog.tuned(t.defenseScheme, league.tuning)
            league.roster(t.id).map { overall(it, if (it.position.isOffense) off else def) }
        }.average()

    private fun elite(league: League): Int = league.players.count { p ->
        p.teamId != null && overall(p) >= 80
    }

    @Test
    fun `thirty seasons leave a league worth playing`() {
        val seed = 31L
        val league = LeagueGenerator.generate(2026, seed)
        var d = DynastyEngine.start(league, 2026, seed, league.teams.first().id)

        val means = mutableListOf<Double>()
        val elites = mutableListOf<Int>()
        val ages = mutableListOf<Double>()
        val champions = mutableListOf<Int>()
        val rosterSizes = mutableListOf<Int>()
        val userSizes = mutableListOf<Int>()

        repeat(years) {
            while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
            champions += d.champion ?: -1
            means += leagueMean(d.league)
            elites += elite(d.league)
            ages += d.league.players.filter { it.teamId != null }.map { it.age(d.year) }.average()
            // The 53 counts the active roster; injured reserve is on the books,
            // not the field. A club with no cap room cannot fill a place reserve
            // opens, which is legal, so the floor is the 46 a club dresses.
            // The test's own club has no one signing for it, so it only has a ceiling.
            rosterSizes += d.league.teams.filter { it.id != d.userTeamId }
                .map { RosterMoves.active(d.league, it.id).size }
            userSizes += RosterMoves.active(d.league, d.userTeamId).size
            d = DynastyEngine.advance(d)
        }

        // The league neither withers nor runs away.
        assertTrue(means.all { it in 65.0..73.0 }, "league mean wandered: ${means.map { "%.1f".format(it) }}")
        assertTrue(means.last() - means.first() in -2.0..3.0,
            "thirty years of drift: %.1f to %.1f".format(means.first(), means.last()))

        // The top of the league is replenished, not drained (ADR-005). Counted
        // on open ground rather than in scheme, so the figure runs higher than
        // the health check's column and does not move with a club's scheme.
        assertTrue(elites.all { it in 120..500 }, "players rated 80+ wandered: $elites")
        val early = elites.take(5).average()
        val late = elites.takeLast(5).average()
        assertTrue(late >= early * 0.6,
            "the top of the league drained: %.0f early against %.0f late".format(early, late))

        // Age settles rather than climbing forever.
        assertTrue(ages.all { it in 24.5..28.5 }, "ages wandered: ${ages.map { "%.1f".format(it) }}")
        assertTrue(ages.last() - ages.takeLast(10).average() in -1.0..1.0, "age still climbing at the end")

        // Somebody different wins it, and every club keeps a legal roster.
        val distinct = champions.filter { it > 0 }.distinct().size
        assertTrue(distinct >= 12, "only $distinct clubs won it in $years years")
        val most = champions.filter { it > 0 }.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        assertTrue(most <= 8, "one club won it $most times in $years years")
        assertTrue(rosterSizes.all { it in GAME_DAY..Transactions.ROSTER_LIMIT },
            "a club finished a season outside $GAME_DAY-${Transactions.ROSTER_LIMIT} active: " +
                rosterSizes.filter { it !in GAME_DAY..Transactions.ROSTER_LIMIT })
        assertTrue(userSizes.all { it <= Transactions.ROSTER_LIMIT }, "the user's club went past 53: $userSizes")
    }

    private companion object {
        /** The players an NFL club may dress on game day. */
        const val GAME_DAY = 46
    }
}
