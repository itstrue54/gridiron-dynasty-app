package com.nflsim.engine.sim

import com.nflsim.engine.model.TeamId
import kotlinx.serialization.Serializable

@Serializable
enum class Side { HOME, AWAY;
    fun other(): Side = if (this == HOME) AWAY else HOME
}

@Serializable
enum class ScoringPlay(val label: String, val points: Int) {
    TOUCHDOWN("touchdown", 6),
    EXTRA_POINT("extra point", 1),
    TWO_POINT("two point conversion", 2),
    FIELD_GOAL("field goal", 3),
    SAFETY("safety", 2),
    DEFENSIVE_TOUCHDOWN("defensive touchdown", 6),
}

@Serializable
enum class DriveEnding(val label: String) {
    TOUCHDOWN("touchdown"),
    FIELD_GOAL("field goal"),
    MISSED_FIELD_GOAL("missed field goal"),
    PUNT("punt"),
    DOWNS("turnover on downs"),
    INTERCEPTION("interception"),
    FUMBLE("fumble"),
    SAFETY("safety"),
    END_OF_HALF("end of half"),
    END_OF_GAME("end of game"),
}

/** One possession, start to finish. */
@Serializable
data class Drive(
    val offense: Side,
    val startQuarter: Int,
    val startClock: Int,
    val startYardLine: Int,
    val ending: DriveEnding,
    val plays: Int,
    val yards: Int,
    val seconds: Int,
    val points: Int,
    val endYardLine: Int,
) {
    val isScore: Boolean get() = points > 0
}

/**
 * A defender's part in a snap the sim decided for him by name - good or bad -
 * so a coach can see who is winning and who is losing (SPEC 10).
 */
@Serializable
enum class DefenderPlay(val good: Boolean, val label: String) {
    SACK(true, "sack"),
    INTERCEPTION(true, "interception"),
    COVERED(true, "had him covered"),
    STUFF(true, "stopped it at the line"),
    BEATEN(false, "beaten in coverage"),
    FLAG(false, "flagged"),
}

/** What a line of the play-by-play is: a snap, a kick that ends a drive, or a note between plays. */
@Serializable
enum class PlayKind { SNAP, PUNT, FIELD_GOAL, NOTE }

/** A single line in the play-by-play feed. */
@Serializable
data class PlayLog(
    val quarter: Int,
    val clock: Int,
    val offense: Side,
    val down: Int,
    val distance: Int,
    val yardLine: Int,
    val homeScore: Int,
    val awayScore: Int,
    val text: String,
    /** What the line is. A log saved before kinds were kept reads every line as a snap. */
    val kind: PlayKind = PlayKind.SNAP,
    /** On a snap, what each side called, named from its club's playbook. */
    val offenseCall: String? = null,
    val defenseCall: String? = null,
    /** On a snap, the defender the play turned on, and how (DefenderPlay). */
    val defender: Int? = null,
    val defenderPlay: DefenderPlay? = null,
) {
    val clockText: String get() = "%d:%02d".format(clock / 60, clock % 60)

    /** Field position the way a broadcast says it. */
    val fieldText: String get() = when {
        yardLine == 50 -> "midfield"
        yardLine < 50 -> "own $yardLine"
        else -> "opp ${100 - yardLine}"
    }
}

@Serializable
data class GameState(
    val homeTeam: TeamId,
    val awayTeam: TeamId,
    val homeScore: Int = 0,
    val awayScore: Int = 0,
    val quarter: Int = 1,
    val secondsLeft: Int = QUARTER_SECONDS,
    val possession: Side = Side.AWAY,
    /** Yards from the possessing team's own goal line. */
    val yardLine: Int = 25,
    val down: Int = 1,
    val distance: Int = 10,
    val homeTimeouts: Int = 3,
    val awayTimeouts: Int = 3,
    /** Periods the game is scheduled for: the four quarters, and each overtime period it goes to (SPEC 5.10). */
    val periods: Int = 4,
) {
    fun scoreFor(side: Side): Int = if (side == Side.HOME) homeScore else awayScore
    fun timeoutsFor(side: Side): Int = if (side == Side.HOME) homeTimeouts else awayTimeouts

    /** Offense score minus defense score. */
    val scoreDiff: Int get() = scoreFor(possession) - scoreFor(possession.other())

    val isOver: Boolean get() = quarter > periods && secondsLeft <= 0

    /** In overtime: past the fourth quarter. */
    val inOvertime: Boolean get() = quarter > 4

    fun toPlayState(): PlayState = PlayState(
        down = down,
        distance = distance.coerceAtLeast(1),
        yardLine = yardLine.coerceIn(1, 99),
        quarter = quarter,
        secondsLeftInQuarter = secondsLeft,
        scoreDiff = scoreDiff,
        offenseTimeouts = timeoutsFor(possession),
        defenseTimeouts = timeoutsFor(possession.other()),
    )

    companion object {
        const val QUARTER_SECONDS = 900
        const val TOUCHBACK_YARD_LINE = 30
    }
}
