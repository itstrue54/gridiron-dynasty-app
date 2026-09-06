package com.nflsim.data.roster

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.DevCurve
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.overall
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RosterImportTest {

    @Test
    fun `the barest possible file still produces players`() {
        val csv = """
            name,position
            Jordan Vance,QB
            Miles Ackerman,WR
            Trey Bollinger,EDGE
        """.trimIndent()

        val result = RosterImporter.import(csv, year = 2026)
        assertEquals(3, result.players.size, result.report.summary())
        assertTrue(result.report.ok, result.report.summary())
        assertEquals("Jordan", result.players[0].firstName)
        assertEquals("Vance", result.players[0].lastName)
        assertEquals(Position.QB, result.players[0].position)
    }

    @Test
    fun `an overall column is honoured`() {
        val csv = """
            name,pos,ovr
            Elite Guy,CB,94
            Depth Guy,CB,58
        """.trimIndent()
        val result = RosterImporter.import(csv, year = 2026)
        val elite = overall(result.players[0])
        val depth = overall(result.players[1])
        assertTrue(abs(elite - 94) <= 2, "elite came out $elite")
        assertTrue(abs(depth - 58) <= 2, "depth came out $depth")
    }

    @Test
    fun `supplied ratings are used exactly as given`() {
        val csv = """
            name,pos,spd,acc,mcv,zcv,prs,awr,prc,agi,cth,jmp,tak
            Press Corner,CB,93,91,95,70,94,80,79,88,60,85,70
        """.trimIndent()
        val result = RosterImporter.import(csv, year = 2026)
        val p = result.players.single()
        assertEquals(93, p[RatingId.SPEED])
        assertEquals(95, p[RatingId.MAN_COVERAGE])
        assertEquals(70, p[RatingId.ZONE_COVERAGE])
        assertEquals(ImportFidelity.FULL, result.report.fidelity.keys.first())
    }

    @Test
    fun `archetype is inferred from the rating sheet when absent`() {
        val press = """
            name,pos,spd,acc,mcv,zcv,prs,awr,prc,agi,cth,jmp,tak
            Press Corner,CB,92,90,96,66,95,78,74,88,58,84,72
        """.trimIndent()
        val zone = """
            name,pos,spd,acc,mcv,zcv,prs,awr,prc,agi,cth,jmp,tak
            Zone Corner,CB,84,84,68,95,62,90,94,80,70,78,76
        """.trimIndent()

        val pressCb = RosterImporter.import(press, 2026).players.single()
        val zoneCb = RosterImporter.import(zone, 2026).players.single()

        assertEquals(Archetype.MAN_PRESS, pressCb.archetype, "press sheet read as ${pressCb.archetype}")
        assertEquals(Archetype.ZONE_CB, zoneCb.archetype, "zone sheet read as ${zoneCb.archetype}")
    }

    @Test
    fun `an explicit archetype wins over inference`() {
        val csv = """
            name,pos,archetype,ovr
            Somebody,RB,POWER_BACK,80
        """.trimIndent()
        assertEquals(Archetype.POWER_BACK, RosterImporter.import(csv, 2026).players.single().archetype)
    }

    @Test
    fun `an archetype from the wrong position is rejected with a warning`() {
        val csv = """
            name,pos,archetype,ovr
            Somebody,RB,MAN_PRESS,80
        """.trimIndent()
        val result = RosterImporter.import(csv, 2026)
        val p = result.players.single()
        assertTrue(p.archetype.group == Position.RB.group, "got ${p.archetype}")
        assertTrue(result.report.warnings.any { it.message.contains("does not belong") },
            result.report.summary())
    }

    @Test
    fun `position spellings from the wild are understood`() {
        val csv = """
            name,pos
            A,Quarterback
            B,HB
            C,WO
            D,DE
            E,NT
            F,MLB
            G,FS
            H,PK
            I,Long Snapper
        """.trimIndent()
        val result = RosterImporter.import(csv, 2026)
        assertEquals(9, result.players.size, result.report.summary())
        assertEquals(
            listOf(Position.QB, Position.RB, Position.WR, Position.EDGE, Position.DT,
                   Position.LB, Position.S, Position.K, Position.LS),
            result.players.map { it.position }
        )
    }

    @Test
    fun `ambiguous positions import but are flagged`() {
        val csv = """
            name,pos
            Generic Tackle,T
            Generic Guard,G
        """.trimIndent()
        val result = RosterImporter.import(csv, 2026)
        assertEquals(listOf(Position.LT, Position.LG), result.players.map { it.position })

        // Assert on what the warnings say, not how many there are - a minimal
        // file also warns that the ratings were invented, and that is correct.
        val ambiguity = result.report.warnings.filter { it.message.contains("ambiguous") }
        assertEquals(2, ambiguity.size, result.report.summary())
        assertTrue(ambiguity.any { it.message.contains("read as LT") }, result.report.summary())
        assertTrue(ambiguity.any { it.message.contains("read as LG") }, result.report.summary())
    }

    @Test
    fun `an unknown position is an error not a guess`() {
        val csv = """
            name,pos
            Good Player,QB
            Mystery Player,ZZ
        """.trimIndent()
        val result = RosterImporter.import(csv, 2026)
        assertEquals(1, result.players.size)
        assertEquals(1, result.report.skipped)
        assertTrue(result.report.errors.single().message.contains("ZZ"))
    }

    @Test
    fun `names in last comma first order are handled`() {
        val csv = """
            name,pos
            "Bollinger, Trey",EDGE
        """.trimIndent()
        val p = RosterImporter.import(csv, 2026).players.single()
        assertEquals("Trey", p.firstName)
        assertEquals("Bollinger", p.lastName)
    }

    @Test
    fun `height accepts feet and inches`() {
        val csv = """
            name,pos,height,weight
            Tall Guy,LT,6-7,318
            Numeric Guy,LT,79,320
        """.trimIndent()
        val players = RosterImporter.import(csv, 2026).players
        assertEquals(79, players[0].heightIn)
        assertEquals(79, players[1].heightIn)
        assertEquals(318, players[0].weightLb)
    }

    @Test
    fun `dev trait is read when supplied`() {
        val csv = """
            name,pos,ovr,dev
            Star,WR,88,superstar
            Plodder,WR,70,slow
        """.trimIndent()
        val players = RosterImporter.import(csv, 2026).players
        assertEquals(DevCurve.SUPERSTAR, players[0].traits.developmentCurve)
        assertEquals(DevCurve.SLOW, players[1].traits.developmentCurve)
    }

    @Test
    fun `unrecognised columns are reported and ignored`() {
        val csv = """
            name,pos,ovr,fantasy_points,twitter_handle
            Somebody,QB,85,312.4,@somebody
        """.trimIndent()
        val result = RosterImporter.import(csv, 2026)
        assertEquals(1, result.players.size)
        assertTrue(result.report.unknownColumns.containsAll(listOf("fantasypoints", "twitterhandle")),
            "unknown columns were ${result.report.unknownColumns}")
    }

    @Test
    fun `teams are matched by abbreviation when a lookup is supplied`() {
        val league = LeagueGenerator.generate(2026, 1L)
        val byAbbrev = league.teams.associate { it.abbrev to it.id }
        val csv = """
            team,name,pos,ovr
            KC,Somebody,QB,88
            NOPE,Nobody,QB,70
        """.trimIndent()
        val players = RosterImporter.import(csv, 2026, teamIdFor = { byAbbrev[it] }).players
        assertEquals(byAbbrev["KC"], players[0].teamId)
        assertEquals(null, players[1].teamId)
    }

    @Test
    fun `importing is deterministic`() {
        val csv = """
            name,pos
            Somebody,LB
            Someone Else,S
        """.trimIndent()
        val a = RosterImporter.import(csv, 2026, seed = 5L).players
        val b = RosterImporter.import(csv, 2026, seed = 5L).players
        assertEquals(a, b)
    }

    @Test
    fun `an empty file fails cleanly`() {
        val result = RosterImporter.import("", 2026)
        assertTrue(result.players.isEmpty())
        assertTrue(!result.report.ok)
    }

    @Test
    fun `the shipped template imports without errors`() {
        val result = RosterImporter.import(RosterExporter.template(), 2026)
        assertEquals(3, result.players.size, result.report.summary())
        assertTrue(result.report.ok, result.report.summary())
    }

    @Test
    fun `a generated roster survives an export and reimport unchanged`() {
        val league = LeagueGenerator.generate(2026, 3L)
        val team = league.teams.first { it.abbrev == "KC" }
        val original = league.roster(team.id)

        val csv = assertNotNull(RosterExporter.teamToCsv(league, "KC"))
        val reimported = RosterImporter.import(csv, league.year).players

        assertEquals(original.size, reimported.size)
        original.zip(reimported).forEach { (before, after) ->
            assertEquals(before.name, after.name)
            assertEquals(before.position, after.position)
            assertEquals(before.archetype, after.archetype, "${before.name} changed archetype")
            assertEquals(before.ratings, after.ratings, "${before.name} changed ratings")
            assertEquals(before.birthYear, after.birthYear)
            assertEquals(before.heightIn, after.heightIn)
            assertEquals(before.weightLb, after.weightLb)
            assertEquals(before.college, after.college)
            assertEquals(overall(before), overall(after))
        }
    }
}
