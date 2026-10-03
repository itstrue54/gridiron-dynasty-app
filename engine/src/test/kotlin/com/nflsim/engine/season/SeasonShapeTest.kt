package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SPEC 13.2's season shape, on the first season of eight new leagues: the
 * one every new dynasty plays. A single season is noisy, so the bands are
 * held by the mean.
 */
class SeasonShapeTest {

    private class Shape(val best: Double, val atFourOrFewer: Int, val sd: Double)

    private val firstSeasons: List<Shape> by lazy {
        (41L..48L).map { seed ->
            val league = LeagueGenerator.generate(2026, seed)
            var d = DynastyEngine.start(league, 2026, seed, league.teams.first().id)
            while (d.phase != DynastyPhase.PLAYOFFS) d = DynastyEngine.advance(d)
            val standings = d.standings()
            val wins = d.league.teams.map { standings.record(it.id).let { r -> r.wins + r.ties * 0.5 } }
            val mean = wins.average()
            Shape(wins.max(), wins.count { it <= 4.0 }, sqrt(wins.sumOf { (it - mean) * (it - mean) } / wins.size))
        }
    }

    @Test
    fun `a first season has a best record of 13 to 17 wins`() {
        val best = firstSeasons.map { it.best }.average()
        assertTrue(best in 13.0..17.0, "best record averaged $best")
    }

    @Test
    fun `a first season has two to five clubs at four wins or fewer`() {
        val low = firstSeasons.map { it.atFourOrFewer }.average()
        assertTrue(low in 2.0..5.0, "clubs at four wins or fewer averaged $low")
    }

    @Test
    fun `a first season's wins spread 2_6 to 3_4`() {
        val sd = firstSeasons.map { it.sd }.average()
        assertTrue(sd in 2.6..3.4, "the spread of wins averaged $sd")
    }
}
