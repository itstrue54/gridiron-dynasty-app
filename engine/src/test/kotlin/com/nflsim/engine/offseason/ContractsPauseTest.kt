package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The user's club decides its own expiring players. */
class ContractsPauseTest {

    private val season: Dynasty by lazy {
        val league = LeagueGenerator.generate(2026, 33L)
        var d = DynastyEngine.start(league, 2026, 33L, league.teams.first().id)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d
    }

    private fun roster(pause: OffseasonEngine.DraftPause, team: com.nflsim.engine.model.TeamId) =
        pause.finish().first.league.let { l -> l.players.filter { it.teamId == team }.map { it.id.v }.toSet() }

    @Test
    fun `the user's expiring men are listed with what they cost`() {
        val pause = OffseasonEngine.runToContracts(season)
        assertTrue(pause.expiring.isNotEmpty(), "somebody's deal should have run out")
        pause.expiring.forEach {
            assertTrue(it.market > 0 && it.asking > 0 && it.years >= 1, "$it")
            assertTrue(it.franchise >= it.transition, "a franchise tag costs more than a transition tag: $it")
        }
    }

    @Test
    fun `whoever the user keeps stays, and whoever he lets go returns only if willing`() {
        val pause = OffseasonEngine.runToContracts(season)
        val user = pause.userTeam
        val best = pause.expiring.first().player.id.v
        val rest = pause.expiring.drop(1).map { it.player.id.v }
        val choices = mapOf(best to ContractChoice.RESIGN) + rest.associateWith { ContractChoice.WALK }
        assertTrue(pause.cost(choices.mapValues { ContractDecision(it.value) }) > 0)

        val kept = roster(pause.decideChoices(choices), user)
        assertTrue(best in kept, "the man the user re-signed should be on the roster")
        // The front office may bring one back later in the spring, but only
        // a man who wants to come (SPEC 7).
        val stayed = rest.filter { it in kept }
        val byId = season.league.playersById
        assertTrue(stayed.all { Extensions.willingToReturn(byId.getValue(com.nflsim.engine.model.PlayerId(it)), season.league.tuning) },
            "let go and brought back against his will: $stayed")
    }

    /** The same season, with every man on the user's club too proud to come back. */
    private val proud: Dynasty by lazy {
        val user = season.userTeamId
        season.copy(league = season.league.copy(players = season.league.players.map {
            if (it.teamId == user) it.copy(traits = it.traits.copy(ego = 95, loyalty = 5)) else it
        }))
    }

    @Test
    fun `a proud man the user lets go never comes back`() {
        val pause = OffseasonEngine.runToContracts(proud)
        val user = pause.userTeam
        val walked = pause.expiring.map { it.player.id.v }
        val kept = roster(pause.decideChoices(walked.associateWith { ContractChoice.WALK }), user)
        assertTrue(walked.none { it in kept }, "back after being let go: ${walked.filter { it in kept }}")
    }

    @Test
    fun `free agency tells the user who will not come back`() {
        val pause = OffseasonEngine.runToContracts(proud)
        val walked = pause.expiring.map { it.player.id.v }
        val fa = pause.toFreeAgency(walked.associateWith { ContractDecision(ContractChoice.WALK) })
        val him = fa.candidates.first { it.player.id.v in walked }
        assertEquals("He will not come back", fa.advice(him).headline)
        val talk = fa.negotiate(him.player.id.v, him.market * 2, him.years)
        assertTrue(!talk.signed && "let him go" in talk.note, talk.note)
    }

    @Test
    fun `one tag at most, and the franchise tag keeps him`() {
        val pause = OffseasonEngine.runToContracts(season)
        val user = pause.userTeam
        val two = pause.expiring.take(2).map { it.player.id.v }
        val choices = two.associateWith { ContractChoice.FRANCHISE }
        val draft = pause.decideChoices(choices)
        val tags = draft.state.tags.filter { it.team == user.v }
        assertEquals(1, tags.size, "the CBA allows one tag: $tags")
        assertTrue(tags.single().player in roster(draft, user), "a franchise-tagged man stays")
    }

    @Test
    fun `leaving it to the league changes nothing`() {
        val auto = OffseasonEngine.runToDraft(season).finish().first
        val viaPause = OffseasonEngine.runToContracts(season).decide(null).finish().first
        assertEquals(auto.league.players.map { it.teamId }, viaPause.league.players.map { it.teamId })
    }

    @Test
    fun `taking the front office's advice is the offseason the AI would have run`() {
        val pause = OffseasonEngine.runToContracts(season)
        val user = pause.userTeam
        val advised = roster(pause.decideChoices(pause.suggested), user)
        val auto = roster(OffseasonEngine.runToContracts(season).decide(null), user)
        assertEquals(auto, advised, "the suggested choices should keep the same men")
    }

    @Test
    fun `every man gets advice, one tag at most, and the advice fits the cap`() {
        val pause = OffseasonEngine.runToContracts(season)
        val recs = pause.expiring.map { pause.recommend(it) }
        recs.forEach { assertTrue(it.headline.isNotBlank() && it.why.isNotBlank(), "$it") }
        val tags = recs.count {
            it.decision.choice == ContractChoice.FRANCHISE || it.decision.choice == ContractChoice.TRANSITION
        }
        assertTrue(tags <= 1, "the CBA allows one tag: $tags recommended")
        val cost = pause.cost(pause.expiring.associate { it.player.id.v to pause.recommend(it).decision })
        assertTrue(cost <= pause.capSpace, "the advice spends $cost of ${pause.capSpace}")
    }

    @Test
    fun `a young starter asking what he is worth is kept`() {
        // The case a phone turned up: a 24-year-old starting corner at his
        // market rate, with a hundred million of room, was advised to walk.
        val user = season.userTeamId
        val star = season.league.roster(user).filter { it.age(season.year) <= 27 }
            .maxByOrNull { com.nflsim.engine.ratings.overall(it) }!!
        val expiring = season.copy(league = season.league.copy(players = season.league.players.map {
            if (it.id == star.id) it.copy(
                contract = com.nflsim.engine.model.Contract(years = 1, baseSalary = listOf(5_000), signedYear = season.year),
                traits = it.traits.copy(loyalty = 70),
            ) else it
        }))
        val pause = OffseasonEngine.runToContracts(expiring)
        val e = pause.expiring.first { it.player.id == star.id }
        val rec = pause.recommend(e)
        assertEquals(ContractChoice.RESIGN, rec.decision.choice, "${rec.headline}: ${rec.why}")
        assertTrue(rec.why.contains("start"), rec.why)
    }

    @Test
    fun `a re-signing is written the way it was chosen`() {
        val pause = OffseasonEngine.runToContracts(season)
        val e = pause.expiring.first()
        val deals = pause.deals(e)
        // His term and a year either side, three ways each.
        assertTrue(deals.map { it.years }.distinct().size >= 2)
        assertEquals(ContractOptions.Structure.entries.toSet(), deals.map { it.structure }.toSet())
        val light = deals.first { it.structure == ContractOptions.Structure.CAP_LIGHT }
        val payGo = deals.first { it.structure == ContractOptions.Structure.PAY_AS_YOU_GO && it.years == light.years }
        assertTrue(light.capNow < payGo.capNow, "cap-light should cost less now")
        if (light.years > 1) assertTrue(light.deadIfCutNextYear > payGo.deadIfCutNextYear,
            "and leave more dead money")

        val draft = pause.decide(mapOf(e.player.id.v to ContractDecision(ContractChoice.RESIGN, light.years, light.structure)))
        val signed = draft.state.players.first { it.id == e.player.id }
        assertEquals(pause.userTeam, signed.teamId)
        assertEquals(light.contract, signed.contract)
    }

    @Test
    fun `a shorter deal costs more a year and a longer one less`() {
        val pause = OffseasonEngine.runToContracts(season)
        val e = pause.expiring.first { it.years in 2..4 }
        val standard = pause.deals(e).filter { it.structure == ContractOptions.Structure.STANDARD }
            .associateBy { it.years }
        assertTrue(standard.getValue(e.years - 1).annual > standard.getValue(e.years).annual)
        assertTrue(standard.getValue(e.years + 1).annual < standard.getValue(e.years).annual)
    }
}
