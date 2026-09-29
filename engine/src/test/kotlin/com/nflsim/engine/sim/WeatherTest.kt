package com.nflsim.engine.sim

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Stadium
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.WeekRunner
import com.nflsim.engine.tuning.TuningTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 5.10: weather by region and month; a dome plays indoors. */
class WeatherTest {

    private val t = TuningTable.REALISTIC.weather
    private fun sample(stadium: Stadium, week: Int) = (0 until 400).map { Weather.draw(stadium, week, SplitMixRng(it.toLong())) }

    @Test
    fun `every stadium is in a region the climate table knows`() {
        LeagueGenerator.teamSeeds.forEach { assertTrue(it.stadium.climate in Weather.regions, "${it.abbrev}: ${it.stadium.climate}") }
    }

    @Test
    fun `a dome plays indoors, and the weather follows region and month`() {
        val dome = LeagueGenerator.teamSeeds.first { it.stadium.domed }.stadium
        assertTrue(sample(dome, 16).all { it == Weather.INDOORS })
        val greenBay = LeagueGenerator.teamSeeds.first { it.abbrev == "GB" }.stadium
        val miami = LeagueGenerator.teamSeeds.first { it.abbrev == "MIA" }.stadium
        val gbDecember = sample(greenBay, 16)
        assertTrue(gbDecember.any { it.precipitation == Precipitation.SNOW }, "snow in Green Bay in December")
        assertTrue(sample(miami, 16).none { it.precipitation == Precipitation.SNOW }, "never in Miami")
        assertTrue(gbDecember.map { it.tempF }.average() < sample(miami, 16).map { it.tempF }.average() - 20)
        assertTrue(sample(greenBay, 2).map { it.tempF }.average() > gbDecember.map { it.tempF }.average() + 20, "September is warmer")
        assertEquals(Weather.draw(greenBay, 16, SplitMixRng(9L)), Weather.draw(greenBay, 16, SplitMixRng(9L)), "the same draw from the same stream")
    }

    @Test
    fun `weather costs the deep ball, wet hands and kickers, and nothing indoors`() {
        val calm = Weather(60, 5, Precipitation.NONE, indoors = false)
        val windy = Weather(60, 25, Precipitation.NONE, indoors = false)
        val snow = Weather(25, 5, Precipitation.SNOW, indoors = false)
        assertEquals(0f, Weather.INDOORS.passPenalty(30, t)); assertEquals(1f, Weather.INDOORS.fumbleFactor(t))
        assertEquals(0f, Weather.INDOORS.kickRangeLoss(t)); assertEquals(0f, Weather.INDOORS.kickAccuracyPenalty(t))
        assertEquals(0f, calm.passPenalty(30, t), "calm and dry: nothing")
        assertTrue(windy.passPenalty(30, t) > 0f && windy.passPenalty(5, t) == 0f, "wind costs the deep ball, not the short one")
        assertTrue(snow.passPenalty(5, t) > 0f && snow.fumbleFactor(t) > 1f)
        assertTrue(snow.kickRangeLoss(t) > 0f, "cold shortens a kick")
        assertTrue(windy.kickAccuracyPenalty(t) > 0f && windy.kickRangeLoss(t) > 0f)
    }

    @Test
    fun `an outdoor game opens with the conditions, a dome game does not`() {
        val league = LeagueGenerator.generate(2026, 2026L)
        val teams = WeekRunner.teams(league, league.tuning)
        val outdoor = teams.values.first { !it.team.stadium.domed }
        val indoor = teams.values.first { it.team.stadium.domed }
        val away = teams.values.first { it != outdoor && it != indoor }
        val w = Weather.draw(outdoor.team.stadium, 16, SplitMixRng(3L))
        val out = GameSimulator(outdoor, away, league.tuning, weather = w).simulate(SplitMixRng(1L))
        assertTrue(w.description in out.playByPlay.first().text, out.playByPlay.first().text)
        assertEquals(w, out.weather)
        val inside = GameSimulator(indoor, away, league.tuning, weather = Weather.INDOORS).simulate(SplitMixRng(1L))
        assertEquals(inside, GameSimulator(indoor, away, league.tuning).simulate(SplitMixRng(1L)), "indoors is the game without weather")
    }
}
