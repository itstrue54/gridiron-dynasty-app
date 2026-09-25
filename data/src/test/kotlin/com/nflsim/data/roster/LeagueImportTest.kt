package com.nflsim.data.roster

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Conference
import com.nflsim.engine.model.Division
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.Transactions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC 9.4: the user's own rosters, from a JSON file, as a league. */
class LeagueImportTest {

    /** A roster file shaped like a real one: [count] players for a club, from a generated team's men. */
    private fun players(count: Int, from: Int): String {
        val donor = LeagueGenerator.generate(2026, 500L + from).players.filter { it.teamId != null }.take(count)
        return donor.mapIndexed { i, p ->
            """{"name":"Test Player$from-$i","position":"${p.position.label}","overall":${60 + i % 30},"age":${23 + i % 10},"number":${i + 1}}"""
        }.joinToString(",")
    }

    private val file = """
        {"teams":[
          {"abbrev":"KC","city":"Kansas City","nickname":"Chiefs","conference":"AFC","division":"West","players":[${players(60, 1)}]},
          {"abbrev":"PHI","city":"Philadelphia","nickname":"Eagles","conference":"NFC","division":"East","players":[${players(20, 2)}]}
        ]}
    """.trimIndent()

    @Test
    fun `his clubs take their places, named as he wrote them`() {
        val r = LeagueImport.build(file, 2026, 7L)
        val league = assertNotNull(r.league, "${r.errors}")
        assertTrue(r.errors.isEmpty(), "${r.errors}")
        assertEquals(32, league.teams.size)
        assertEquals(32, league.teams.map { it.abbrev }.distinct().size, "abbreviations stay unique")
        val kc = league.teams.single { it.abbrev == "KC" }
        assertEquals("Kansas City Chiefs", kc.name)
        assertEquals(Conference.AMERICAN, kc.conference)
        assertEquals(Division.WEST, kc.division)
        val phi = league.teams.single { it.abbrev == "PHI" }
        assertEquals(Conference.CONTINENTAL to Division.EAST, phi.conference to phi.division)
        // Every division still has four clubs.
        assertTrue(league.teams.groupBy { it.conference to it.division }.values.all { it.size == 4 })
    }

    @Test
    fun `his players are on his clubs, under contract, and the extras on the squad`() {
        val league = LeagueImport.build(file, 2026, 7L).league!!
        val byId = league.playersById
        val kc = league.teams.single { it.abbrev == "KC" }
        val roster = kc.roster.map { byId.getValue(it) }
        assertEquals(Transactions.ROSTER_LIMIT, roster.size, "60 in the file, 53 on the roster")
        assertTrue(roster.all { it.contract != null && it.teamId == kc.id }, "all under contract")
        val squad = kc.practiceSquad.map { byId.getValue(it) }
        assertTrue(squad.isNotEmpty() && squad.all { it.status == PlayerStatus.PRACTICE_SQUAD })
        assertTrue((roster + squad).count { it.name.startsWith("Test Player1-") } == 60, "all 60 kept, roster or squad")
        // A club with 20 men still fields a team: the generated men fill the rest.
        val phi = league.teams.single { it.abbrev == "PHI" }
        val phiRoster = phi.roster.map { byId.getValue(it) }
        assertEquals(20, phiRoster.count { it.name.startsWith("Test Player2-") })
        assertTrue(phiRoster.size >= 46, "enough to dress: ${phiRoster.size}")
        // No roster points at a player who is not in the league.
        assertTrue(league.teams.all { t -> (t.roster + t.practiceSquad).all { it in byId } })
    }

    @Test
    fun `the same file makes the same league`() {
        assertEquals(LeagueImport.build(file, 2026, 7L).league, LeagueImport.build(file, 2026, 7L).league)
    }

    @Test
    fun `a flat list of players names its clubs by team`() {
        val flat = """[{"name":"A One","position":"QB","team":"DAL","overall":85},
                       {"name":"B Two","position":"WR","team":"DAL"},
                       {"name":"C Three","position":"CB","team":"NYG","ratings":{"spd":92,"mcv":88}}]"""
        val league = assertNotNull(LeagueImport.build(flat, 2026, 3L).league)
        val dal = league.teams.single { it.abbrev == "DAL" }
        assertTrue(dal.roster.any { league.playersById.getValue(it).name == "A One" })
        val c = league.players.single { it.name == "C Three" }
        assertEquals(92, c.ratings[com.nflsim.engine.model.RatingId.SPEED])
    }

    @Test
    fun `a CSV file with a team column works the same way`() {
        val csv = "name,position,team,ovr\nA One,QB,SEA,88\nB Two,HB,SEA,75\n"
        val league = assertNotNull(LeagueImport.build(csv, 2026, 3L).league)
        assertTrue(league.teams.single { it.abbrev == "SEA" }.roster.any { league.playersById.getValue(it).name == "A One" })
    }

    @Test
    fun `bad input says what is wrong`() {
        assertNull(LeagueImport.build("{ not json", 2026, 1L).league)
        assertTrue(LeagueImport.build("{ not json", 2026, 1L).errors.single().startsWith("not valid JSON"))
        val oddPosition = """[{"name":"X","position":"QB","team":"GB"},{"name":"Y","position":"Wizard","team":"GB"}]"""
        val r = LeagueImport.build(oddPosition, 2026, 1L)
        assertNotNull(r.league, "one bad player does not stop the rest")
        assertTrue(r.errors.any { "Wizard" in it }, "${r.errors}")
        val crowded = (1..5).joinToString(",") { """{"abbrev":"T$it","conference":"AFC","division":"East","players":[{"name":"P$it","position":"QB"}]}""" }
        val placed = LeagueImport.build("""{"teams":[$crowded]}""", 2026, 1L)
        assertTrue(placed.notes.any { "division was full" in it }, "${placed.notes}")
    }

    @Test
    fun `the template imports cleanly as it stands`() {
        val r = LeagueImport.build(RosterJson.template(), 2026, 1L)
        val league = assertNotNull(r.league, "${r.errors}")
        assertTrue(r.errors.isEmpty(), "${r.errors}")
        assertTrue(r.report!!.unknownColumns.isEmpty(), "every field the template uses is one the importer knows: ${r.report!!.unknownColumns}")
        assertEquals("Example City Examples", league.teams.single { it.abbrev == "EXA" }.name)
        assertEquals(5, r.report!!.imported)
    }

    @Test
    fun `an imported league plays`() {
        val league = LeagueImport.build(file, 2026, 7L).league!!
        var d = DynastyEngine.start(league, 2026, 7L, league.teams.single { it.abbrev == "KC" }.id)
        repeat(2) { d = DynastyEngine.advance(d) }
        assertEquals(3, d.week)
        assertTrue(d.lastGame!!.playByPlay.isNotEmpty())
    }
}
