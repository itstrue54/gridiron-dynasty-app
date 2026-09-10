package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Position
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import com.nflsim.engine.season.Schedule
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OffseasonTest {

    private val league: League by lazy { LeagueGenerator.generate(2026, 2026L) }

    private fun freshDynasty(seed: Long = 2026L): Dynasty =
        DynastyEngine.start(league, 2026, seed, league.teams.first { it.abbrev == "KC" }.id)

    /** Plays a whole year and rolls it over. */
    private fun playYear(start: Dynasty): Dynasty {
        var d = start
        repeat(Schedule.WEEKS) { d = DynastyEngine.advance(d) }
        d = DynastyEngine.advance(d)   // playoffs
        return DynastyEngine.advance(d) // offseason
    }

    // ---- contracts ---------------------------------------------------

    @Test
    fun `signing bonus prorates over at most five years`() {
        val short = Contract.of(years = 3, totalValue = 30_000, signedYear = 2026, bonusShare = 0.5f)
        assertEquals(15_000 / 3, short.proratedBonus)

        val long = Contract.of(years = 7, totalValue = 70_000, signedYear = 2026, bonusShare = 0.5f)
        assertEquals(35_000 / 5, long.proratedBonus, "proration must cap at five years")
    }

    @Test
    fun `cutting a player accelerates the unamortised bonus`() {
        val c = Contract(
            years = 5, baseSalary = List(5) { 5_000 },
            signingBonus = 25_000, guaranteed = 0, signedYear = 2026,
        )
        // Two years in, three years of bonus have not hit the books.
        val dead = c.deadCap(2028, postJune1 = false)
        assertEquals(15_000, dead.thisYear)
        assertEquals(0, dead.nextYear)
    }

    @Test
    fun `a post June 1 designation splits the damage across two years`() {
        val c = Contract(
            years = 5, baseSalary = List(5) { 5_000 },
            signingBonus = 25_000, guaranteed = 0, signedYear = 2026,
        )
        val dead = c.deadCap(2028, postJune1 = true)
        assertEquals(5_000, dead.thisYear)
        assertEquals(10_000, dead.nextYear)
        assertEquals(15_000, dead.total, "the total cost is the same either way")
    }

    @Test
    fun `guarantees are consumed as they are paid, so the back of a deal is cuttable`() {
        val c = Contract.of(years = 5, totalValue = 60_000, signedYear = 2026)

        // Year one: fully protected. Cutting him costs more than keeping him.
        val early = c.capHit(2026) - c.deadCap(2026).thisYear
        assertTrue(early <= 0, "a team should not be able to profit from an early cut")

        // Year five: the guarantee is spent and the bonus nearly amortised,
        // so releasing him finally frees money. This is the year real cap
        // casualties happen, and without it no team ever cuts anyone.
        val late = c.capHit(2030) - c.deadCap(2030).thisYear
        assertTrue(late > 0, "the last year of a deal must be escapable, saved $late")
    }

    @Test
    fun `restructuring lowers this year and raises the dead money`() {
        val c = Contract.of(years = 4, totalValue = 40_000, signedYear = 2026)
        val before = c.capHit(2026)
        val after = c.restructure(2026, 4_000)
        assertTrue(after.capHit(2026) < before, "restructure did not create room")
        assertTrue(after.deadCap(2027).total > c.deadCap(2027).total,
            "restructure did not push money into the future")
    }

    // ---- progression -------------------------------------------------

    @Test
    fun `young players improve and old players decline`() {
        val rng = SplitMixRng(1L)
        val ctx = Progression.Context(year = 2026, coaching = 70, snaps = 700)

        fun meanDelta(age: Int, position: Position): Double {
            val template = league.players.first { it.position == position }
            var total = 0.0
            repeat(300) {
                val p = template.copy(birthYear = 2026 - age)
                total += Progression.progress(p, ctx, rng).delta
            }
            return total / 300
        }

        assertTrue(meanDelta(22, Position.WR) > 1.0, "22 year olds should improve")
        assertTrue(meanDelta(34, Position.WR) < -1.0, "34 year olds should decline")
        assertTrue(meanDelta(24, Position.RB) > meanDelta(30, Position.RB),
            "a back should be past it sooner than that")
    }

    @Test
    fun `quarterbacks age better than running backs`() {
        val rng = SplitMixRng(2L)
        val ctx = Progression.Context(year = 2026, snaps = 700)
        fun delta(position: Position, age: Int): Double {
            val template = league.players.first { it.position == position }
            var total = 0.0
            repeat(300) { total += Progression.progress(
                template.copy(birthYear = 2026 - age), ctx, rng).delta }
            return total / 300
        }
        assertTrue(delta(Position.QB, 31) > delta(Position.RB, 31),
            "a 31 year old quarterback should hold up better than a 31 year old back")
    }

    @Test
    fun `playing time drives development`() {
        val rng = SplitMixRng(3L)
        val young = league.players.first { it.position == Position.WR }.copy(birthYear = 2003)
        fun mean(snaps: Int): Double {
            var total = 0.0
            repeat(400) {
                total += Progression.progress(
                    young, Progression.Context(2026, 70, snaps), rng).delta
            }
            return total / 400
        }
        assertTrue(mean(900) > mean(50), "a rookie who sits developed as fast as one who played")
    }

    @Test
    fun `a good position coach is worth most to a coachable young player`() {
        val rng = SplitMixRng(4L)
        val template = league.players.first { it.position == Position.WR }.copy(birthYear = 2004)
        fun mean(coaching: Int, coachability: Int): Double {
            val p = template.copy(traits = template.traits.copy(coachability = coachability))
            var total = 0.0
            repeat(1000) {
                total += Progression.progress(p, Progression.Context(2026, coaching, 700), rng).delta
            }
            return total / 1000
        }
        val coachable = mean(85, 80) - mean(45, 80)
        val stubborn = mean(85, 20) - mean(45, 20)
        assertTrue(coachable > 0.5, "a great coach should be worth half a point a year, got $coachable")
        assertTrue(coachable > stubborn * 2,
            "coaching should matter most to the coachable: $coachable vs $stubborn")
    }

    // ---- free agency --------------------------------------------------

    @Test
    fun `a full roster trades up and the player it drops leaves dead money`() {
        val year = 2027
        val team = league.teams.first()
        val offense = SchemeCatalog[team.offenseScheme]
        val defense = SchemeCatalog[team.defenseScheme]
        val scheme = { _: com.nflsim.engine.model.TeamId?, pos: Position ->
            if (pos.isOffense) offense else defense
        }

        // Exactly at the free agency target, receivers kept so there is one to replace.
        val target = League.ROSTER_SIZE - DraftRunner.ROUNDS
        val own = league.roster(team.id)
        val receivers = own.filter { it.position == Position.WR }
        val roster = receivers + own.filter { it.position != Position.WR }.take(target - receivers.size)
        val weakest = receivers.minBy { rosterValue(it, offense, year) }

        val star = league.players
            .filter { it.position == Position.WR && it.teamId != team.id }
            .maxBy { overall(it, offense) }
            .copy(teamId = null, contract = null,
                status = com.nflsim.engine.model.PlayerStatus.FREE_AGENT)
        val pricer = com.nflsim.engine.econ.MarketValue.pricer(
            rostered = listOf(star), scheme = { offense }, year = year,
            payroll = 8_000L, cap = CapManagement.capFor(year))

        val result = FreeAgency.run(
            league.copy(teams = listOf(team)), roster + star, year, emptyMap(),
            scheme, pricer, { 0.5f }, SplitMixRng(5L))

        val after = result.players.filter { it.teamId == team.id }
        assertEquals(target, after.size, "trading up should not change the roster size")
        assertTrue(after.any { it.id == star.id }, "the better receiver was not signed")
        assertTrue(after.none { it.id == weakest.id }, "the weakest receiver was not released")
        assertEquals(weakest.contract?.deadCap(year)?.thisYear ?: 0, result.deadMoney[team.id.v] ?: 0,
            "the released receiver's dead money did not land on the books")
        assertEquals(1, result.upgradeCuts.size)
    }

    @Test
    fun `retirement rates climb with age`() {
        val rng = SplitMixRng(4L)
        fun rate(age: Int): Double {
            val p = league.players.first().copy(birthYear = 2026 - age)
            return (1..2000).count { Progression.retires(p, 2026, 72, rng) } / 2000.0
        }
        assertTrue(rate(25) < 0.01, "25 year olds retired at ${rate(25)}")
        assertTrue(rate(31) in 0.02..0.20, "31 year olds retired at ${rate(31)}")
        assertTrue(rate(36) > 0.30, "36 year olds retired at ${rate(36)}")
    }

    // ---- draft -------------------------------------------------------

    @Test
    fun `a draft class has a believable talent curve`() {
        val prospects = SyntheticDraftClass.generate(2027, 100_000, SplitMixRng(5L))
        // The board is deliberately far deeper than the 224 picks that come
        // off it - the selection is where the league's talent comes from.
        assertTrue(prospects.size in 450..620, "class size was ${prospects.size}")
        val overalls = prospects.map { overall(it) }
        val elite = overalls.count { it >= 78 }
        assertTrue(elite in 3..45, "$elite prospects graded 78 or better")
        assertTrue(overalls.average() in 52.0..64.0, "class average was ${overalls.average()}")
        assertTrue(prospects.all { it.age(2027) in 20..24 }, "prospects were the wrong age")
    }

    @Test
    fun `the draft assigns every pick to a different player`() {
        val dynasty = playYear(freshDynasty())
        val picks = dynasty.lastOffseason!!.draftPicks
        assertTrue(picks.isNotEmpty())
        assertEquals(picks.size, picks.map { it.player }.toSet().size, "a player was drafted twice")
    }

    // ---- the year turning over ---------------------------------------

    @Test
    fun `a season rolls into the next year`() {
        val after = playYear(freshDynasty())
        assertEquals(2027, after.year)
        assertEquals(1, after.week)
        assertEquals(DynastyPhase.REGULAR_SEASON, after.phase)
        assertTrue(after.results.isEmpty(), "last season's results carried over")
        assertTrue(after.lastOffseason != null, "no offseason report was produced")
    }

    @Test
    fun `every roster is still legal after the offseason`() {
        val after = playYear(freshDynasty())
        after.league.teams.forEach { team ->
            val roster = after.league.roster(team.id)
            assertTrue(roster.size >= 46,
                "${team.abbrev} came out of the offseason with ${roster.size} players")
            assertTrue(roster.any { it.position == Position.QB }, "${team.abbrev} has no quarterback")
            assertTrue(roster.any { it.position == Position.K }, "${team.abbrev} has no kicker")
        }
    }

    @Test
    fun `club money adds up to the league totals`() {
        val report = playYear(freshDynasty()).lastOffseason!!
        val clubs = report.moneyByTeam.values
        assertEquals(League.TEAM_COUNT, report.moneyByTeam.size)
        assertEquals(report.auctionSpend, clubs.sumOf { it.faPaid })
        assertEquals(report.extensionSpend, clubs.sumOf { it.keptPaid })
        assertEquals(report.overpaidBy50, clubs.sumOf { it.overpaid })
        assertEquals(report.capCasualties, clubs.sumOf { it.casualties })
        assertEquals(report.extensionCount, clubs.sumOf { it.keptCount })
    }

    @Test
    fun `nobody is on two rosters and nobody is lost`() {
        val after = playYear(freshDynasty())
        val assigned = after.league.teams.flatMap { it.roster }
        assertEquals(assigned.size, assigned.toSet().size, "a player is on two rosters")
        val ids = after.league.players.map { it.id.v }
        assertEquals(ids.size, ids.toSet().size, "duplicate player ids after the draft")
    }

    @Test
    fun `players who retired are gone`() {
        val after = playYear(freshDynasty())
        val retiredIds = after.lastOffseason!!.retirements.map { it.player }.toSet()
        val stillHere = after.league.players.map { it.id.v }.toSet()
        assertTrue(retiredIds.none { it in stillHere }, "a retired player is still on a roster")
    }

    @Test
    fun `veterans who go unsigned retire and young ones hang around`() {
        var d = freshDynasty(11L)
        repeat(3) { d = playYear(d) }
        val report = d.lastOffseason!!

        // The report itself only keeps the twenty most notable.
        assertTrue(report.retirementCount > 30,
            "only ${report.retirementCount} players left the league in a year")

        val freeAgents = d.league.players.filter { it.teamId == null }
        assertTrue(freeAgents.isNotEmpty(), "nobody is available on the wire")
        val oldOnes = freeAgents.count { it.age(d.year) >= 32 }
        assertTrue(oldOnes < freeAgents.size / 4,
            "$oldOnes of ${freeAgents.size} unsigned players are 32 or older - " +
                "veterans should take the hint")
    }

    @Test
    fun `the league does not inflate or deflate over five years`() {
        var d = freshDynasty(7L)
        val start = leagueMean(d)
        repeat(5) { d = playYear(d) }
        val end = leagueMean(d)
        assertTrue(abs(end - start) < 4.0,
            "league mean overall drifted from %.1f to %.1f over five years".format(start, end))
    }

    @Test
    fun `five seasons run end to end without breaking`() {
        var d = freshDynasty(9L)
        repeat(5) {
            d = playYear(d)
            assertTrue(d.league.players.size in 1_400..3_200,
                "league had ${d.league.players.size} players in ${d.year}")
        }
        assertEquals(2031, d.year)
    }

    @Test
    fun `the league does not get older every year`() {
        var d = freshDynasty(13L)
        val start = meanAge(d)
        repeat(5) { d = playYear(d) }
        val end = meanAge(d)

        // Rosters turn over. A league that ages steadily is one where nobody
        // is ever displaced by a younger player, and its mean overall slides
        // for years afterwards because the decline curve does the rest.
        assertTrue(
            end - start < 1.0,
            "league aged from %.1f to %.1f over five years".format(start, end),
        )
        assertTrue(end > 24.0, "league is implausibly young at %.1f".format(end))
    }

    @Test
    fun `the top of the league is replaced as fast as it ages out`() {
        var d = freshDynasty(17L)
        val startStarters = starterMean(d)
        val startElite = eliteCount(d)

        repeat(8) { d = playYear(d) }

        // The failure this guards against is specific and was invisible in the
        // league average: every team starts with an 84 at quarterback, that
        // founding cohort peaks around year four and is gone by year eight,
        // and if the draft cannot replace it the top of the league quietly
        // drains while the mean holds up on depth players.
        val endStarters = starterMean(d)
        assertTrue(
            endStarters > startStarters - 3.0,
            "starter quality fell from %.1f to %.1f over eight years".format(
                startStarters, endStarters),
        )

        val endElite = eliteCount(d)
        assertTrue(
            endElite > startElite * 0.7,
            "players rated 80+ fell from $startElite to $endElite",
        )
    }

    /** The best player at each position on each roster - who actually plays. */
    private fun starterMean(d: Dynasty): Double =
        d.league.teams.flatMap { t ->
            d.league.roster(t.id).groupBy { it.position }.values.map { group ->
                group.maxOf { overall(it, schemeFor(t, it)) }
            }
        }.average()

    private fun eliteCount(d: Dynasty): Int =
        d.league.teams.sumOf { t ->
            d.league.roster(t.id).count { overall(it, schemeFor(t, it)) >= 80 }
        }

    /** Rate a player against the scheme he actually plays in, not his team's offence. */
    private fun schemeFor(team: com.nflsim.engine.model.Team, p: com.nflsim.engine.model.Player) =
        if (p.position.isOffense) SchemeCatalog[team.offenseScheme]
        else SchemeCatalog[team.defenseScheme]

    private fun meanAge(d: Dynasty): Double =
        d.league.players.filter { it.teamId != null }.map { it.age(d.year).toDouble() }.average()

    private fun leagueMean(d: Dynasty): Double =
        d.league.teams.flatMap { t ->
            d.league.roster(t.id).map { overall(it, schemeFor(t, it)) }
        }.average()
}
