package com.nflsim.engine.season

import com.nflsim.engine.model.CareerSeason
import com.nflsim.engine.model.CareerStats
import com.nflsim.engine.model.LeagueHistory
import com.nflsim.engine.model.RetiredCareer
import com.nflsim.engine.model.SeasonRecord
import com.nflsim.engine.stats.StatLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/SPEC.md 10: the hall of fame, voted from the careers that have ended. */
class HallOfFameTest {

    private fun career(years: Int, from: Int = 2020, line: StatLine) = CareerStats(
        (0 until years).map { CareerSeason(from + it, team = 1, stats = line) },
    )

    private fun man(
        id: Int,
        position: String,
        career: CareerStats,
        retiredIn: Int = 2030,
        proBowls: Int = 0,
    ) = RetiredCareer(
        player = id, name = "Player $id", position = position, age = 34,
        overall = 80, proBowls = proBowls, lastTeam = 1, career = career, year = retiredIn,
    )

    // Twelve seasons of leading the league, and the Pro Bowls to match: nobody
    // has to argue about this one.
    private val greatQb = man(
        1, "QB",
        career(12, line = StatLine(passYards = 5_000, passTouchdowns = 40)),
        proBowls = 8,
    )

    private val journeyman = man(
        2, "QB",
        career(6, line = StatLine(passYards = 2_100, passTouchdowns = 11)),
    )

    @Test
    fun `a career the league will not forget goes in`() {
        val history = LeagueHistory(retired = listOf(greatQb))
        val class2033 = HallOfFame.induct(history, 2033, com.nflsim.engine.tuning.TuningTable.REALISTIC.honours)
        assertEquals(listOf(1), class2033.map { it.player })
        assertEquals(12, class2033.first().seasons)
        assertTrue(class2033.first().headline > 50_000, "his yards should follow him in")
    }

    @Test
    fun `a good player is not a hall of famer`() {
        val history = LeagueHistory(retired = listOf(journeyman))
        assertTrue(HallOfFame.induct(history, 2033, com.nflsim.engine.tuning.TuningTable.REALISTIC.honours).isEmpty(),
            "six ordinary seasons should not be enough")
    }

    @Test
    fun `nobody goes in the year he finishes`() {
        val history = LeagueHistory(retired = listOf(greatQb))
        assertTrue(HallOfFame.induct(history, 2030, com.nflsim.engine.tuning.TuningTable.REALISTIC.honours).isEmpty(), "he retired this year")
        assertTrue(HallOfFame.induct(history, 2032, com.nflsim.engine.tuning.TuningTable.REALISTIC.honours).isEmpty(), "still inside the wait")
        assertTrue(HallOfFame.induct(history, 2033, com.nflsim.engine.tuning.TuningTable.REALISTIC.honours).isNotEmpty(), "eligible by now")
    }

    @Test
    fun `a man already in is not voted in twice`() {
        var history = LeagueHistory(retired = listOf(greatQb))
        history = history.copy(hallOfFame = HallOfFame.induct(history, 2033, com.nflsim.engine.tuning.TuningTable.REALISTIC.honours))
        assertTrue(HallOfFame.induct(history, 2034, com.nflsim.engine.tuning.TuningTable.REALISTIC.honours).isEmpty(), "he is already in")
    }

    @Test
    fun `a class has room for only so many`() {
        val crowd = (1..8).map {
            man(it, "QB", career(12, line = StatLine(passYards = 5_000, passTouchdowns = 40)),
                proBowls = it)
        }
        val inducted = HallOfFame.induct(LeagueHistory(retired = crowd), 2033, com.nflsim.engine.tuning.TuningTable.REALISTIC.honours)
        assertEquals(com.nflsim.engine.tuning.TuningTable.REALISTIC.honours.hofClassSize, inducted.size)
        assertTrue(inducted.map { it.score }.zipWithNext().all { (a, b) -> a >= b },
            "the best careers should go first")
    }

    @Test
    fun `a lineman's case is the hardware he won`() {
        val guard = man(9, "RG", career(13, line = StatLine()), proBowls = 9)
        // Eight first-team selections, which is the only record of what he was.
        val honours = (2021..2028).map { year ->
            SeasonRecord(
                year = year,
                awards = Awards(honours = listOf(Honour(9, "Player 9", "RG", 1, tier = 1))),
            )
        }
        val history = LeagueHistory(seasons = honours, retired = listOf(guard))
        assertTrue(HallOfFame.induct(history, 2033, com.nflsim.engine.tuning.TuningTable.REALISTIC.honours).any { it.player == 9 },
            "a great lineman with no stat line should still get in")
    }
}
