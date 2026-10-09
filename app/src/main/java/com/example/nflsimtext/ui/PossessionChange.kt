package com.example.nflsimtext.ui

import com.nflsim.engine.sim.PlayKind
import com.nflsim.engine.sim.PlayLines
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
 * kickoff before the first snap. After a kickoff, until the next snap, why
 * it was kicked. Read from the last snap or kick (a note such
 * as a timeout is passed over), its kind and the score; a kick's line says
 * which kind of kick it was.
 */
internal fun possessionChange(
    shown: List<PlayLog>,
    nextOffense: Side,
    nextQuarter: Int,
    nextHomeScore: Int,
    nextAwayScore: Int,
): PossessionChange? {
    val i = shown.indexOfLast { it.kind != PlayKind.NOTE }
    if (i < 0) return PossessionChange.OPENING_KICKOFF
    val last = shown[i]
    // A kickoff is logged as the receiving club's, so the ball is already
    // theirs: why it was kicked is the play before it.
    if (last.kind == PlayKind.KICKOFF) {
        val before = shown.subList(0, i).lastOrNull { it.kind != PlayKind.NOTE }
        return when {
            before == null -> PossessionChange.OPENING_KICKOFF
            last.homeScore > before.homeScore || last.awayScore > before.awayScore -> PossessionChange.KICKOFF
            before.quarter == 2 && last.quarter == 3 -> PossessionChange.SECOND_HALF
            else -> PossessionChange.KICKOFF
        }
    }
    if (nextOffense == last.offense) return null
    val text = last.text.lowercase()
    // A log saved before plays kept their kind calls every line a snap: its
    // kicks are known by the lines the game writes for them.
    val legacy = last.kind == PlayKind.SNAP
    val blockedPunt = isLine(last.text, "punt.blocked")
    val punt = last.kind == PlayKind.PUNT || (legacy && (blockedPunt || isLine(last.text, "punt", "punt.touchback", "punt.no_punter")))
    val blockedKick = isLine(last.text, "fg.blocked")
    val kick = last.kind == PlayKind.FIELD_GOAL || (legacy && (blockedKick || isLine(last.text, "fg.miss", "fg.no_kicker")))
    return when {
        nextHomeScore > last.homeScore || nextAwayScore > last.awayScore -> PossessionChange.KICKOFF
        // The half ending is what hands it over, whatever the last play was.
        last.quarter == 2 && nextQuarter == 3 -> PossessionChange.SECOND_HALF
        punt -> if (blockedPunt) PossessionChange.BLOCKED_PUNT else PossessionChange.PUNT
        kick -> if (blockedKick) PossessionChange.BLOCKED_FIELD_GOAL else PossessionChange.MISSED_FIELD_GOAL
        "intercept" in text -> PossessionChange.INTERCEPTION
        "fumble" in text -> PossessionChange.FUMBLE
        last.down == 4 -> PossessionChange.DOWNS
        else -> PossessionChange.OTHER
    }
}

/** What the field says: why the ball changed hands, and whose it is now. */
internal fun possessionBanner(change: PossessionChange, newOffense: String): String = "${change.label} · $newOffense ball"

/** Whether [text] is one of the game's lines for any of [keys], its blanks filled with anything. */
private fun isLine(text: String, vararg keys: String): Boolean =
    keys.any { key -> LINES.getValue(key).any { it.matches(text) } }

/** The kicking lines the game writes, each as a pattern with its blanks open (narrative plays.json). */
private val LINES: Map<String, List<Regex>> by lazy {
    listOf("punt", "punt.touchback", "punt.no_punter", "punt.blocked", "fg.miss", "fg.blocked", "fg.no_kicker")
        .associateWith { key ->
            PlayLines.templates.getValue(key).map { template ->
                Regex(template.split(Regex("\\{[a-z]+\\}")).joinToString(".*") { Regex.escape(it) })
            }
        }
}
