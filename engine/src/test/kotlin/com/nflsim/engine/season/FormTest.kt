package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.NewsKind
import com.nflsim.engine.model.Position
import com.nflsim.engine.stats.StatLine
import com.nflsim.engine.tuning.TuningTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-season form: what a week does to it, and what it does on Sunday. */
class FormTest {

    private val league = LeagueGenerator.generate(2026, 12L)
    private val tuning = TuningTable.REALISTIC
    private val back = league.players.first { it.position == Position.RB }

    @Test
    fun `a good day lifts him and a bad one drops him`() {
        val big = Form.next(back, StatLine(carries = 20, rushYards = 160), tuning)
        val poor = Form.next(back, StatLine(carries = 20, rushYards = 30), tuning)
        assertTrue(big > 20, "a 8.0 a carry day should read hot, got $big")
        assertTrue(poor < -20, "a 1.5 a carry day should read cold, got $poor")
    }

    @Test
    fun `two carries move him barely, and a week off decays what he had`() {
        val light = Form.next(back, StatLine(carries = 2, rushYards = 16), tuning)
        val full = Form.next(back, StatLine(carries = 12, rushYards = 96), tuning)
        assertTrue(light in 1..(full / 3), "a two-carry day moved him to $light against $full")

        val hot = back.copy(form = 80)
        val idle = Form.next(hot, null, tuning)
        assertTrue(idle in 50..70, "a week off should decay 80 toward nothing, got $idle")
    }

    @Test
    fun `form is worth a few points on game day and nothing on his record`() {
        val hot = back.copy(form = 100)
        assertEquals(tuning.form.swing.toInt(), Form.points(hot, tuning))
        val dressed = Form.dressed(hot, tuning)
        assertTrue(dressed.ratings[com.nflsim.engine.model.RatingId.SPEED] >
            hot.ratings[com.nflsim.engine.model.RatingId.SPEED])
        // His own record is untouched: valuations and scouting read talent.
        assertEquals(back.ratings[com.nflsim.engine.model.RatingId.SPEED],
            hot.ratings[com.nflsim.engine.model.RatingId.SPEED])
    }

    @Test
    fun `a season runs hot and cold, benches somebody, and starts level again`() {
        var d = DynastyEngine.start(league, 2026, 12L, league.teams.first().id)
        while (d.phase == DynastyPhase.PRESEASON || d.phase == DynastyPhase.REGULAR_SEASON) {
            d = DynastyEngine.advance(d)
        }
        val forms = d.league.players.filter { it.teamId != null }.map { it.form }
        assertTrue(forms.any { it > 30 } && forms.any { it < -30 }, "somebody should be hot and somebody cold")
        assertTrue(forms.all { it in -100..100 })
        assertTrue(forms.average() in -12.0..12.0, "form should centre near nothing: ${forms.average()}")
        assertTrue(d.news.any { it.kind == NewsKind.BENCHING }, "somebody should have lost his place")

        // Into the next season, everyone starts level.
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d = DynastyEngine.advance(d)
        assertTrue(d.league.players.all { it.form == 0 }, "a new season starts level")
    }
}
