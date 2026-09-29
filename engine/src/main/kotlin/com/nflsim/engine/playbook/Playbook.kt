package com.nflsim.engine.playbook

import com.nflsim.engine.sim.Coverage
import com.nflsim.engine.sim.DefensiveFront
import com.nflsim.engine.sim.DefensivePlayCall
import com.nflsim.engine.sim.OffensivePlayCall
import com.nflsim.engine.sim.PassConcept
import com.nflsim.engine.sim.Personnel
import com.nflsim.engine.sim.RunConcept
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A scheme's playbook (SPEC 5.4): formations, and the plays run from each.
 * Every play is an exact call the engine plays - a name on the sheet for a
 * concept, a personnel group or front, and its details. Loaded from
 * `playbooks/<scheme id>.json`, so a book can be deepened without a
 * recompile.
 */
@Serializable
data class Playbook(val scheme: String, val formations: List<Formation>) {
    val plays: List<Pair<Formation, Play>> get() = formations.flatMap { f -> f.plays.map { f to it } }
}

/** An offensive formation (a personnel group) or a defensive one (a front). */
@Serializable
data class Formation(
    val name: String,
    val personnel: Personnel? = null,
    val front: DefensiveFront? = null,
    val plays: List<Play>,
)

/** One play on the sheet. Offence fills run or pass; defence fills coverage. */
@Serializable
data class Play(
    val name: String,
    val run: RunConcept? = null,
    val pass: PassConcept? = null,
    val playAction: Boolean = false,
    /** Backs and tight ends kept in to block. */
    val protect: Int = 0,
    /** The intended receiver: 0 is the first read. */
    val target: Int = 0,
    val coverage: Coverage? = null,
    /** Rushers sent past the standard four. */
    val rushers: Int = 0,
    /** Defenders added to the box. */
    val box: Int = 0,
    /** The receiver bracketed, if any. */
    val bracket: Int? = null,
) {
    val isRun: Boolean get() = run != null
}

object Playbooks {

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, Playbook>()

    /** The book for [schemeId]. */
    fun forScheme(schemeId: String): Playbook = synchronized(cache) {
        cache.getOrPut(schemeId) {
            val text = Playbooks::class.java.getResourceAsStream("/playbooks/$schemeId.json")
                ?.bufferedReader()?.use { it.readText() }
                ?: error("no playbook for $schemeId")
            json.decodeFromString(Playbook.serializer(), text)
        }
    }

    /**
     * The clock calls every book shares, as the live game offers them beside
     * its formations. Named, so a coordinator's kneel reads as one.
     */
    val CLOCK = Formation("Clock", plays = listOf(Play("Kneel"), Play("Spike")))

    /** The engine call a play on the sheet makes. */
    fun offense(formation: Formation, play: Play): OffensivePlayCall {
        val personnel = formation.personnel ?: error("${formation.name} is not an offensive formation")
        return play.run?.let { OffensivePlayCall.Run(it, personnel, play.playAction) }
            ?: OffensivePlayCall.Pass(play.pass ?: error("${play.name} has no concept"), personnel,
                play.playAction, play.protect, play.target)
    }

    fun defense(formation: Formation, play: Play): DefensivePlayCall = DefensivePlayCall(
        front = formation.front ?: error("${formation.name} is not a defensive formation"),
        coverage = play.coverage ?: error("${play.name} has no coverage"),
        extraRushers = play.rushers,
        boxAdd = play.box,
        doubledTarget = play.bracket,
    )

    /**
     * The play on the sheet a coordinator's call reads as: same formation and
     * concept, then the closest in its details. Null if the book has nothing
     * like it.
     */
    fun nameOf(book: Playbook, call: OffensivePlayCall): Pair<Formation, Play>? {
        // The clock calls are every club's, not a scheme's: kneel and spike.
        when (call) {
            is OffensivePlayCall.Kneel -> return CLOCK to CLOCK.plays[0]
            is OffensivePlayCall.Spike -> return CLOCK to CLOCK.plays[1]
            else -> Unit
        }
        val same = book.plays.filter { (f, p) ->
            f.personnel == call.personnel && when (call) {
                is OffensivePlayCall.Run -> p.run == call.concept
                is OffensivePlayCall.Pass -> p.pass == call.concept
                else -> false
            }
        }
        return same.minByOrNull { (_, p) ->
            when (call) {
                is OffensivePlayCall.Pass -> (if (p.playAction == call.playAction) 0 else 4) +
                    kotlin.math.abs(p.target - call.primaryTarget) + kotlin.math.abs(p.protect - call.extraProtectors)
                else -> if (p.playAction == call.playAction) 0 else 4
            }
        }
    }

    fun nameOf(book: Playbook, call: DefensivePlayCall): Pair<Formation, Play>? =
        book.plays.filter { (f, p) -> f.front == call.front && p.coverage == call.coverage }
            .minByOrNull { (_, p) ->
                kotlin.math.abs(p.rushers - call.extraRushers) * 3 + kotlin.math.abs(p.box - call.boxAdd) +
                    if ((p.bracket != null) == (call.doubledTarget != null)) 0 else 2
            }
}
