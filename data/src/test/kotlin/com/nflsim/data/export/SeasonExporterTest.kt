package com.nflsim.data.export

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.LeagueNames
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/SPEC.md 11: a season leaves the phone as Markdown to post and a CSV to keep. */
class SeasonExporterTest {

    private fun start(): Dynasty {
        val league = LeagueGenerator.generate(2026, 91L)
        return DynastyEngine.start(league, 2026, 91L, league.teams.first().id)
    }

    @Test
    fun `the season so far lists every game the user's club has played`() {
        var d = start()
        repeat(5) { d = DynastyEngine.advance(d) }
        val season = SeasonExporter.current(d)
        val md = SeasonExporter.markdown(season, d.league, d.userTeamId)
        assertTrue(md.startsWith("# 2026 season, through week 5"), md.lines().first())
        val rows = md.lines().dropWhile { !it.startsWith("## ${d.team.name}, game by game") }
            .drop(4).takeWhile { it.startsWith("| ") }.filterNot { "| Bye |" in it }
        val played = d.results.count { it.home == d.userTeamId || it.away == d.userTeamId }
        assertEquals(played, rows.size, "one row a game played: $rows")
        assertTrue(rows.all { Regex("""\| [WLT] \d+-\d+ \|$""").containsMatchIn(it) }, "$rows")
        // A club plays every week but its bye.
        assertTrue(md.lines().count { "| Bye |" in it } <= 1)
        assertEquals(8, season.divisions.size)
        assertEquals(32, season.divisions.sumOf { it.second.size })
        assertTrue("**${d.team.name}**" in md, "the user's club stands out in the standings")
    }

    @Test
    fun `a season not yet started says so`() {
        val d = start()
        val md = SeasonExporter.markdown(SeasonExporter.current(d), d.league, d.userTeamId)
        assertTrue(md.startsWith("# 2026 season, before week 1"), md.lines().first())
        assertTrue("game by game" !in md, "no games to list yet")
    }

    @Test
    fun `a filed season has its champion, awards and leaders`() {
        var d = start()
        while (d.year == 2026) d = DynastyEngine.advance(d)
        val record = d.league.history.seasons.single { it.year == 2026 }
        val season = SeasonExporter.of(record, d.league)
        val md = SeasonExporter.markdown(season, d.league, d.userTeamId)
        val champion = d.league.teams.first { it.id.v == record.champion }
        assertTrue("**Champion:** ${champion.name}" in md, md)
        assertTrue("## Awards" in md && "Most valuable player" in md, md)
        assertTrue("## League leaders" in md && "Passing yards" in md, md)
        assertTrue(season.soFar == null && md.startsWith("# 2026 season\n"))
        // Standings in order: nobody sits above a club with a better record.
        season.divisions.forEach { (_, rows) ->
            rows.zipWithNext().forEach { (a, b) -> assertTrue(a.wins + a.ties / 2.0 >= b.wins + b.ties / 2.0, "$a above $b") }
        }
    }

    @Test
    fun `the title game goes by the name the roster file gave it`() {
        var d = start()
        while (d.year == 2026) d = DynastyEngine.advance(d)
        val record = d.league.history.seasons.single { it.year == 2026 }
        val named = d.league.copy(names = LeagueNames(championship = "Example Bowl"))
        val champion = TeamId(record.champion!!)
        val md = SeasonExporter.markdown(SeasonExporter.of(record, named), named, champion)
        assertTrue(md.lines().any { it.startsWith("| Example Bowl | ") }, md)
        assertTrue(md.lines().none { it.startsWith("| Final | ") }, md)
        // Unnamed, it is the final it always was.
        val plain = SeasonExporter.markdown(SeasonExporter.of(record, d.league), d.league, champion)
        assertTrue(plain.lines().any { it.startsWith("| Final | ") }, plain)
    }

    @Test
    fun `the standings CSV has a row a club and marks the champion`() {
        var d = start()
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        val csv = SeasonExporter.standingsCsv(SeasonExporter.current(d), d.league).trim().lines()
        assertEquals("season,division,team,abbrev,wins,losses,ties,points_for,points_against,champion", csv.first())
        assertEquals(33, csv.size)
        assertEquals(1, csv.count { it.endsWith(",yes") }, "one champion")
        assertTrue(csv.drop(1).all { it.split(",").size == 10 }, "every row has every column")
        assertEquals("nflsimtext-2026-season.csv", SeasonExporter.fileName(SeasonExporter.current(d), "csv"))
    }
}
