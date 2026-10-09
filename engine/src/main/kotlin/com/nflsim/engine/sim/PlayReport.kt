package com.nflsim.engine.sim

import com.nflsim.engine.playbook.Playbooks
import com.nflsim.engine.tuning.TuningTable

/**
 * What the play-by-play says about a snap beyond its line (SPEC 10): what
 * each side called, named from its club's playbook as the play-calling
 * screen names it, and the defender the play turned on.
 */
object PlayReport {

    /** An offensive call by its playbook name - "Shotgun Doubles - Double Slants" - or what it was. */
    fun offenseCall(call: OffensivePlayCall, schemeId: String): String =
        runCatching { Playbooks.nameOf(Playbooks.forScheme(schemeId), call) }.getOrNull()
            ?.let { (formation, play) -> "${formation.name} - ${play.name}" }
            ?: when (call) {
                is OffensivePlayCall.Run -> "${call.concept.label} run, ${call.personnel.label} personnel"
                is OffensivePlayCall.Pass -> (if (call.playAction) "play-action " else "") + "${call.concept.label}, ${call.personnel.label} personnel"
                is OffensivePlayCall.Punt -> "punt"
                is OffensivePlayCall.FieldGoal -> "field goal"
                is OffensivePlayCall.Kneel -> "kneel"
                is OffensivePlayCall.Spike -> "spike"
            }

    /** A defensive call by its playbook name - "Nickel 4-2-5 - Cover 2" - or what it was. */
    fun defenseCall(call: DefensivePlayCall, schemeId: String): String =
        runCatching { Playbooks.nameOf(Playbooks.forScheme(schemeId), call) }.getOrNull()
            ?.let { (formation, play) -> "${formation.name} - ${play.name}" }
            ?: ("${call.front.label}, ${call.coverage.label}" + if (call.extraRushers > 0) ", ${call.extraRushers} extra rushing" else "")

    /**
     * The defender the snap turned on, by what the sim decided for him by
     * name: a flag on him, a sack, an interception, a throw he had covered,
     * a run he stopped at the line - or a long catch he was beaten on. A
     * snap that turned on no one man (most runs) names nobody.
     */
    fun standout(result: PlayResult, defenseFlagged: Boolean, t: TuningTable.Passing): Pair<Int, DefenderPlay>? {
        val routeWin = result.log["routeWin"]
        return when {
            defenseFlagged && result.penalty?.playerId != null -> result.penalty.playerId.v to DefenderPlay.FLAG
            result.outcome == PlayOutcome.SACK -> result.tackler?.let { it.v to DefenderPlay.SACK }
            result.outcome == PlayOutcome.INTERCEPTION -> result.tackler?.let { it.v to DefenderPlay.INTERCEPTION }
            result.outcome == PlayOutcome.INCOMPLETE && routeWin != null && routeWin <= 0f ->
                result.coverage?.let { it.v to DefenderPlay.COVERED }
            result.outcome == PlayOutcome.COMPLETION && routeWin != null && routeWin > 0f && result.yards >= t.beatenYards ->
                result.coverage?.let { it.v to DefenderPlay.BEATEN }
            (result.outcome == PlayOutcome.RUN || result.outcome == PlayOutcome.SCRAMBLE) && result.yards <= 0 ->
                result.tackler?.let { it.v to DefenderPlay.STUFF }
            else -> null
        }
    }
}
