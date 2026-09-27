package com.nflsim.data.roster

import com.nflsim.data.SaveFile
import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.season.DynastyEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SPEC 9.4: a roster file's general managers, coaching staffs and schemes. */
class StaffImportTest {

    private val players = """[{"name":"Q One","position":"QB","overall":80},{"name":"W Two","position":"WR","overall":75}]"""

    private val staffed = """
        {"teams":[{"abbrev":"KC","conference":"AFC","division":"West","players":$players,
          "gm": {"name":"Front Office Person","aggression":0.9,"winNow":1.0,"loyalty":0.2,"risk":0.7},
          "staff": {
            "headCoach": {"name":"Head Person","age":68,"scheme":"OFF_WEST_COAST",
                          "ratings":{"development":90,"gameplan":95,"adjustments":88,"discipline":80,"motivation":92,"evaluation":85},
                          "contractYears":4,"hotSeat":35,"tendencies":{"fourthDownAggression":0.7}},
            "offensiveCoordinator": {"name":"Oc Person","scheme":"West Coast","tendencies":{"passRate":0.61,"playActionRate":0.22}},
            "defensiveCoordinator": {"name":"Dc Person","age":66,"scheme":"DEF_MAN_BLITZ","tendencies":{"blitzRate":0.4}},
            "specialTeamsCoordinator": "St Person",
            "positionCoaches": {"QB":"Qb Person","RB":"Rb Person","WR":"Wr Person","TE":"Te Person","OL":"Ol Person",
                                "DT":"Dl Person","EDGE":"Dl Person","LB":"Lb Person","CB":"Db Person","S":"Db Person","ST":"St Person"}
          }}]}
    """.trimIndent()

    @Test
    fun `his staff takes its slots, and the club runs its coordinators' schemes`() {
        val r = LeagueImport.build(staffed, 2026, 7L)
        val league = assertNotNull(r.league, "${r.errors}")
        assertTrue(r.errors.isEmpty(), "${r.errors}")
        val kc = league.teams.single { it.abbrev == "KC" }
        fun coach(id: com.nflsim.engine.model.CoachId) = league.coaches.getValue(id)
        val head = coach(kc.staff.headCoach)
        assertEquals("Head Person", head.name)
        assertEquals(68, head.age)
        assertEquals(95, head.ratings.gameplan)
        assertEquals(4, head.contractYearsLeft)
        assertEquals(35, head.hotSeat)
        assertEquals(0.7f, head.tendencies.fourthDownAggression)
        assertEquals("Oc Person", coach(kc.staff.offCoordinator).name)
        assertEquals(0.61f, coach(kc.staff.offCoordinator).tendencies.passRate)
        assertEquals("OFF_WEST_COAST", kc.offenseScheme, "a scheme by its name reads as its id")
        assertEquals("DEF_MAN_BLITZ", kc.defenseScheme)
        assertEquals(0.4f, coach(kc.staff.defCoordinator).tendencies.blitzRate)
        // Every position group has one of his coaches; one man may coach two.
        assertTrue(PositionGroup.entries.all { coach(kc.staff.positionCoaches.getValue(it)).name.endsWith("Person") })
        assertEquals(kc.staff.positionCoaches[PositionGroup.DT], kc.staff.positionCoaches[PositionGroup.EDGE])
        assertEquals(kc.staff.stCoordinator, kc.staff.positionCoaches[PositionGroup.ST], "the coordinator who is also the ST coach is one man")
        // The generated men he replaced are gone, not waiting for the carousel.
        val ids = listOf(kc.staff.headCoach, kc.staff.offCoordinator, kc.staff.defCoordinator, kc.staff.stCoordinator) +
            kc.staff.positionCoaches.values
        val generatedKc = LeagueGenerator.generate(2026, 7L).teams.single { it.abbrev == "KC" }.staff
        assertTrue(generatedKc.positionCoaches.values.none { it in league.coaches.keys && it !in ids })
        // The front office.
        assertEquals("Front Office Person", kc.gm.name)
        assertEquals(0.9f, kc.gm.aggression)
        assertEquals(1.0f, kc.gm.winNowVsFuture)
        // Clubs he did not name keep generated staffs, and generated GMs have names.
        assertTrue(league.teams.all { it.gm.name.isNotBlank() })
    }

    @Test
    fun `a slot the file leaves empty keeps its generated coach, and says so`() {
        val partial = """{"teams":[{"abbrev":"KC","players":$players,"staff":{"headCoach":"Only Name"}}]}"""
        val r = LeagueImport.build(partial, 2026, 7L)
        val league = assertNotNull(r.league)
        val kc = league.teams.single { it.abbrev == "KC" }
        val head = league.coaches.getValue(kc.staff.headCoach)
        assertEquals("Only Name", head.name)
        val generated = LeagueGenerator.generate(2026, 7L)
        val was = generated.coaches.getValue(generated.teams.single { it.abbrev == "KC" }.staff.headCoach)
        assertEquals(was.ratings, head.ratings, "a coach given only a name rates as the man he replaced")
        assertTrue(r.notes.any { "no offensive coordinator in the file, kept a generated one" in it }, "${r.notes}")
    }

    @Test
    fun `a bad scheme or rating is said, not guessed`() {
        val bad = """{"teams":[{"abbrev":"KC","players":$players,"offenseScheme":"Wishbone",
            "staff":{"defensiveCoordinator":{"name":"X","scheme":"OFF_AIR_RAID","ratings":{"gameplan":140,"charisma":50}}}}]}"""
        val r = LeagueImport.build(bad, 2026, 7L)
        assertTrue(r.errors.any { "unknown scheme 'Wishbone'" in it }, "${r.errors}")
        assertTrue(r.errors.any { "OFF_AIR_RAID is not a defensive scheme" in it }, "${r.errors}")
        assertTrue(r.errors.any { "gameplan 140 is outside 0-100" in it }, "${r.errors}")
        assertTrue(r.errors.any { "unknown coach rating 'charisma'" in it }, "${r.errors}")
        val kc = r.league!!.teams.single { it.abbrev == "KC" }
        assertEquals(100, r.league!!.coaches.getValue(kc.staff.defCoordinator).ratings.gameplan)
    }

    @Test
    fun `an age, contract or hot seat out of range is said, not dropped quietly`() {
        val odd = """{"teams":[{"abbrev":"KC","players":$players,"staff":{
            "headCoach":{"name":"Old Typo","age":250,"contractYears":14,"hotSeat":"lukewarm"}}}]}"""
        val r = LeagueImport.build(odd, 2026, 7L)
        assertTrue(r.errors.any { "age 250 is outside 20-95, left to the game" in it }, "${r.errors}")
        assertTrue(r.errors.any { "contractYears 14 is outside 0-10, clamped" in it }, "${r.errors}")
        assertTrue(r.errors.any { "hotSeat 'lukewarm' is not a number" in it }, "${r.errors}")
        val league = assertNotNull(r.league)
        val head = league.coaches.getValue(league.teams.single { it.abbrev == "KC" }.staff.headCoach)
        val generated = LeagueGenerator.generate(2026, 7L)
        val was = generated.coaches.getValue(generated.teams.single { it.abbrev == "KC" }.staff.headCoach)
        assertEquals(was.age, head.age, "an age the game cannot use is the replaced man's")
        assertEquals(10, head.contractYearsLeft)
        assertEquals(was.hotSeat, head.hotSeat)
    }

    @Test
    fun `the file names the league, its conferences and its title game`() {
        val named = """{"league":{"name":"Example Football League","short":"EFL","championship":"Example Bowl",
            "conferences":{"AFC":{"name":"Eastern Football Conference","short":"EFC"},"Continental":"WFC"}},
            "teams":[{"abbrev":"KC","conference":"AFC","division":"West","players":$players}]}"""
        val r = LeagueImport.build(named, 2026, 7L)
        val league = assertNotNull(r.league, "${r.errors}")
        assertTrue(r.errors.isEmpty(), "${r.errors}")
        assertEquals("EFL", league.names.leagueShort)
        assertEquals("Example Bowl champions", league.names.championsTitle)
        assertEquals("EFC West", league.divisionName(league.teams.single { it.abbrev == "KC" }))
        assertEquals("WFC", league.names.conference(com.nflsim.engine.model.Conference.CONTINENTAL))
        // Saved and loaded, the names stay.
        val d = DynastyEngine.start(league, 2026, 7L, league.teams.single { it.abbrev == "KC" }.id)
        assertEquals(league.names, SaveFile.decode(SaveFile.encode(d)).league.names)
        // A file with no league block keeps the game's names.
        assertEquals("American West", LeagueImport.build(staffed, 2026, 7L).league!!.let { l -> l.divisionName(l.teams.single { it.abbrev == "KC" }) })
    }

    @Test
    fun `an unknown conference in the league block is said`() {
        val bad = """{"league":{"conferences":{"Big Ten":"B1G"}},"teams":[{"abbrev":"KC","players":$players}]}"""
        assertTrue(LeagueImport.build(bad, 2026, 7L).errors.any { "conference 'Big Ten'" in it })
    }

    @Test
    fun `a league with his staffs plays, and saves with its GMs`() {
        val league = LeagueImport.build(staffed, 2026, 7L).league!!
        var d = DynastyEngine.start(league, 2026, 7L, league.teams.single { it.abbrev == "KC" }.id)
        repeat(2) { d = DynastyEngine.advance(d) }
        assertEquals(3, d.week)
        val back = SaveFile.decode(SaveFile.encode(d))
        assertEquals("Front Office Person", back.league.teams.single { it.abbrev == "KC" }.gm.name)
        assertEquals("Head Person", back.league.coaches.getValue(back.team.staff.headCoach).name)
    }
}
