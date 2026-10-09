package com.example.nflsimtext.ui

import com.nflsim.engine.sim.PlayKind
import com.nflsim.engine.sim.PlayLog
import com.nflsim.engine.sim.Side

/** Why the ball changed hands, in the words the field shows. */
enum class PossessionChange(val label: String) {
    OPENING_KICKOFF("Kickoff"),
    KICKOFF("Kickoff after the score"),
    INTERCEPTION("Interception"),
    FUMBLE("Fumble lost"),
    PUNT("Punt"),
    BLOCKED_PUNT("Blocked punt"),
    MISSED_FIELD_GOAL("Missed field goal"),
    BLOCKED_FIELD_GOAL("Blocked field goal"),
    DOWNS("Turnover on downs"),
    SECOND_HALF("Second-half kickoff"),
    OTHER("Change of possession"),
}

/**
 * Why the ball changed hands after the plays in [shown], when the next snap is
 * [nextOffense]'s: null when the club with the ball still has it, the opening
 * kickoff before the first snap. Read from the last snap or kick (a note such
 * as a timeout is passed over), its kind, its words and the score.
 */
internal fun possessionChange(
    shown: List<PlayLog>,
    nextOffense: Side,
    nextQuarter: Int,
    nextHomeScore: Int,
    nextAwayScore: Int,
): PossessionChange? {
    val last = shown.lastOrNull { it.kind != PlayKind.NOTE } ?: return PossessionChange.OPENING_KICKOFF
    if (nextOffense == last.offense) return null
    val text = last.text.lowercase()
    // A log saved before plays kept their kind calls every line a snap: its
    // kicks are known by their words.
    val punt = last.kind == PlayKind.PUNT || (last.kind == PlayKind.SNAP && "punt" in text)
    val kick = last.kind == PlayKind.FIELD_GOAL ||
        (last.kind == PlayKind.SNAP && (FIELD_GOAL_BLOCKS + FIELD_GOAL_MISSES).any { it in text })
    return when {
        nextHomeScore > last.homeScore || nextAwayScore > last.awayScore -> PossessionChange.KICKOFF
        // The half ending is what hands it over, whatever the last play was.
        last.quarter == 2 && nextQuarter == 3 -> PossessionChange.SECOND_HALF
        punt -> if ("block" in text) PossessionChange.BLOCKED_PUNT else PossessionChange.PUNT
        kick -> if (FIELD_GOAL_BLOCKS.any { it in text }) PossessionChange.BLOCKED_FIELD_GOAL else PossessionChange.MISSED_FIELD_GOAL
        "intercept" in text -> PossessionChange.INTERCEPTION
        "fumble" in text -> PossessionChange.FUMBLE
        last.down == 4 -> PossessionChange.DOWNS
        else -> PossessionChange.OTHER
    }
}

/** What the field says: why the ball changed hands, and whose it is now. */
internal fun possessionBanner(change: PossessionChange, newOffense: String): String = "${change.label} · $newOffense ball"

/**
 * Words every missed field goal's line uses one of (narratives fg.miss, fg.no_kicker) and no
 * other play's does: "misses" alone is an incomplete pass too.
 */
private val FIELD_GOAL_MISSES = listOf("no good", "misses from", "yarder", "yard try", "cannot convert", "no kicker")

/** Words every blocked field goal's line uses one of (narrative fg.blocked); a run "blocked well" is not one. */
private val FIELD_GOAL_BLOCKS = listOf("attempt is blocked", "blocked!", "try is blocked", "blocked at the line",
    "and it is blocked", "free and blocks", "yarder is blocked", "blocked kick")
