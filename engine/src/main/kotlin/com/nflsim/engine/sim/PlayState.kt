package com.nflsim.engine.sim

import com.nflsim.engine.model.PlayerId
import kotlinx.serialization.Serializable

/** Everything about the situation a play starts in. */
@Serializable
data class PlayState(
    val down: Int = 1,
    val distance: Int = 10,
    /** Yards from the offense's own goal line. 75 means the opponent's 25. */
    val yardLine: Int = 25,
    val quarter: Int = 1,
    val secondsLeftInQuarter: Int = 900,
    /** Offense score minus defense score. */
    val scoreDiff: Int = 0,
    val offenseTimeouts: Int = 3,
    val defenseTimeouts: Int = 3,
) {
    val yardsToGoal: Int get() = 100 - yardLine
    val inRedZone: Boolean get() = yardsToGoal <= 20
    val goalToGo: Boolean get() = distance >= yardsToGoal
    val twoMinuteDrill: Boolean get() = quarter in setOf(2, 4) && secondsLeftInQuarter <= 120

    init {
        require(down in 1..4) { "down was $down" }
        require(yardLine in 1..99) { "yardLine was $yardLine" }
    }
}

@Serializable
enum class PlayOutcome(val label: String, val isPass: Boolean, val stopsClock: Boolean) {
    RUN("run", false, false),
    SCRAMBLE("scramble", true, false),
    COMPLETION("completion", true, false),
    INCOMPLETE("incomplete", true, true),
    THROWAWAY("throwaway", true, true),
    SACK("sack", true, false),
    INTERCEPTION("interception", true, true),
    FUMBLE_LOST("fumble", false, true),
    KNEEL("kneel", false, false),
    SPIKE("spike", true, true),
}

/**
 * Why the play came out the way it did.
 *
 * Design pillar from docs/SPEC.md section 1.4: the player can always ask "why
 * did that happen" and get an answer. Every intermediate value the resolution
 * computed is kept here, so a bad statistic can be traced to the stage that
 * produced it instead of being guessed at.
 */
@Serializable
data class SimLog(
    val values: Map<String, Float> = emptyMap(),
    val narrative: String = "",
) {
    operator fun get(key: String): Float? = values[key]

    fun explain(): String = buildString {
        appendLine(narrative)
        values.entries.sortedBy { it.key }.forEach { (k, v) ->
            appendLine("  %-24s %8.2f".format(k, v))
        }
    }
}

@Serializable
enum class PenaltyType(
    val label: String,
    val yards: Int,
    val onOffense: Boolean,
    /** True if the play does not count when the flag is accepted. */
    val negatesPlay: Boolean,
    val preSnap: Boolean = false,
) {
    FALSE_START("False start", -5, true, true, preSnap = true),
    OFFSIDE("Offside", 5, false, true, preSnap = true),
    DELAY_OF_GAME("Delay of game", -5, true, true, preSnap = true),
    OFFENSIVE_HOLDING("Offensive holding", -10, true, true),
    ILLEGAL_BLOCK("Illegal block in the back", -10, true, true),
    DEFENSIVE_HOLDING("Defensive holding", 5, false, true),
    PASS_INTERFERENCE("Defensive pass interference", 0, false, true),
    ROUGHING_PASSER("Roughing the passer", 15, false, false),
    FACE_MASK("Face mask", 15, false, false),
}

@Serializable
data class Penalty(val type: PenaltyType, val yards: Int, val playerId: PlayerId? = null) {
    val description: String get() = "${type.label}, ${if (yards < 0) "${-yards}" else "$yards"} yards"
}

@Serializable
data class PlayResult(
    val outcome: PlayOutcome,
    val yards: Int,
    val clockRunoff: Int,
    val passer: PlayerId? = null,
    val target: PlayerId? = null,
    val ballCarrier: PlayerId? = null,
    val tackler: PlayerId? = null,
    /** The second man in, when there was one. */
    val assister: PlayerId? = null,
    val turnover: Boolean = false,
    val penalty: Penalty? = null,
    val log: SimLog = SimLog(),
) {
    val stopsClock: Boolean get() = outcome.stopsClock || penalty != null

    /** Net yardage after any accepted flag. */
    val netYards: Int get() = when {
        penalty == null -> yards
        penalty.type.negatesPlay -> penalty.yards
        else -> yards + penalty.yards
    }
}
