package com.nflsim.engine.narrative

import com.nflsim.engine.gen.NameGenerator
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Team
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.rng.SplitMixRng

/**
 * What the other side of a deal says (SPEC 10.4): a general manager
 * answering a trade, an agent answering an offer. From
 * `narrative/banter.json`.
 *
 * Words only. A line is chosen from a stream of its own, drawn from the
 * league seed split by the deal, so the same offer draws the same answer
 * however often the screen redraws it, and no answer moves anything in the
 * sim. A GM talks as his club trades: an aggressive one is blunt, the rest
 * are warm.
 *
 * Agents are a pool of generated names, as a league's agents are a few
 * agencies with many clients: a man's agent is fixed by his id, and two
 * men can share one.
 */
object Banter {

    /** How many agents represent the league's players. */
    const val AGENTS = 40

    /** Where a GM's aggression (0..1) turns his answers blunt: half the scale. */
    const val BLUNT_FROM = 0.5f

    private val book = TemplateBook("/narrative/banter.json")

    val templates: Map<String, List<String>> get() = book.templates

    /** A line and who said it. */
    data class Quote(val line: String, val speaker: String) {
        val text: String get() = "“$line” — $speaker"
    }

    private val agents: List<String> by lazy {
        (0 until AGENTS).map { i ->
            val (first, last) = NameGenerator.fullName(SplitMixRng(AGENT_SEED).split("agent|$i"))
            "$first $last"
        }
    }

    /** Who represents the man with [playerId]. */
    fun agentName(playerId: Int): String = agents[SplitMixRng(playerId.toLong()).split("agency").nextInt(AGENTS)]

    /** "blunt" or "warm", by the GM's aggression. */
    fun tone(team: Team): String = if (team.gm.aggression >= BLUNT_FROM) "blunt" else "warm"

    /** [player]'s agent saying [key]. [context] tells this conversation from another. */
    fun agent(seed: Long, player: Player, key: String, context: String, vararg slots: Pair<String, Any>): Quote =
        Quote(book.write(key, words(seed, "$key|${player.id.v}|$context"), *slots),
            "${agentName(player.id.v)}, ${player.lastName}'s agent")

    /** [team]'s general manager saying [key]. */
    fun gm(seed: Long, team: Team, key: String, context: String, vararg slots: Pair<String, Any>): Quote =
        Quote(book.write(key, words(seed, "$key|${team.id.v}|$context"), *slots), gmName(team))

    private fun gmName(team: Team): String =
        if (team.gm.name.isBlank()) "${team.abbrev} general manager" else "${team.gm.name}, ${team.abbrev} GM"

    private fun words(seed: Long, label: String): Rng = SplitMixRng(seed).split("banter|$label")

    /** The agents' names are the same in every league. */
    private const val AGENT_SEED = 0x4A6E7473L
}
