package com.nflsim.engine.narrative

import com.nflsim.engine.gen.LeagueGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SPEC 10.4: what general managers and agents say, from narrative/banter.json. */
class BanterTest {

    /** What each line may be written with. A slot outside this is a typo in the file. */
    private val allowed = mapOf(
        "gm.trade.yes.blunt" to setOf("club"),
        "gm.trade.yes.warm" to setOf("club"),
        "gm.trade.close.blunt" to setOf("club"),
        "gm.trade.close.warm" to setOf("club"),
        "gm.trade.far.blunt" to setOf("club"),
        "gm.trade.far.warm" to setOf("club"),
        "gm.trade.nothing" to setOf("club"),
        "gm.trade.roster" to setOf("club"),
        "gm.trade.cap" to setOf("club"),
        "gm.trade.done.blunt" to setOf("club"),
        "gm.trade.done.warm" to setOf("club"),
        "gm.offer.blunt" to setOf("player", "club"),
        "gm.offer.warm" to setOf("player", "club"),
        "gm.offer.block" to setOf("player", "club"),
        "gm.lost_man" to setOf("player", "club"),
        "agent.counter" to setOf("player", "figure"),
        "agent.walk" to setOf("player", "figure"),
        "agent.done_talking" to setOf("player"),
        "agent.cold" to setOf("player", "club"),
        "agent.signed" to setOf("player", "club"),
        "agent.signed.discount" to setOf("player", "club"),
        "agent.refused" to setOf("player", "club"),
    )

    @Test
    fun `every line has between eight and fifteen ways to say it`() {
        assertEquals(allowed.keys, Banter.templates.keys)
        Banter.templates.forEach { (key, ways) ->
            assertTrue(ways.size in 8..15, "$key has ${ways.size} variants")
            assertEquals(ways.size, ways.distinct().size, "$key repeats itself")
        }
    }

    @Test
    fun `templates only use the slots their line fills and read as sentences`() {
        Banter.templates.forEach { (key, ways) ->
            ways.forEach { way ->
                assertTrue(TemplateBook.slots(way).all { it in allowed.getValue(key) }, "$key: $way")
                assertTrue(way.last() in ".?!", "$key: $way")
            }
        }
    }

    @Test
    fun `the counter names his figure every time`() {
        listOf("agent.counter").forEach { key ->
            Banter.templates.getValue(key).forEach { assertTrue("{figure}" in it, "$key: $it") }
        }
    }

    private val league by lazy { LeagueGenerator.generate(2026, 5L) }

    @Test
    fun `a man's agent is one of a pool, the same every time`() {
        val names = league.players.map { Banter.agentName(it.id.v) }
        assertEquals(names, league.players.map { Banter.agentName(it.id.v) }, "the same man, the same agent")
        val distinct = names.toSet()
        assertTrue(distinct.size in (Banter.AGENTS / 2)..Banter.AGENTS, "${distinct.size} agents for the league")
    }

    @Test
    fun `the same conversation reads the same, and a different one can read differently`() {
        val man = league.players.first()
        fun say(context: String) = Banter.agent(league.seed, man, "agent.counter", context,
            "player" to man.lastName, "figure" to "$4.0M")
        assertEquals(say("a"), say("a"))
        assertTrue((0 until 40).map { say("offer $it").line }.toSet().size > 1, "one line for every offer")
        assertTrue(say("a").speaker.endsWith("${man.lastName}'s agent"), say("a").speaker)
        assertTrue(say("a").text.contains(say("a").line))
    }

    @Test
    fun `a GM talks as his club trades`() {
        val blunt = league.teams.first().let { it.copy(gm = it.gm.copy(aggression = 0.9f)) }
        val warm = blunt.copy(gm = blunt.gm.copy(aggression = 0.1f))
        assertEquals("blunt", Banter.tone(blunt))
        assertEquals("warm", Banter.tone(warm))
        val q = Banter.gm(league.seed, blunt, "gm.trade.yes.blunt", "x", "club" to "Bears")
        assertTrue(q.speaker.contains(blunt.abbrev), q.speaker)
        assertNotEquals("", q.line)
    }
}
