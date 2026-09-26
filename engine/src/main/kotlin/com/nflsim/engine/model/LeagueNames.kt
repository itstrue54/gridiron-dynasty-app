package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/** A conference's name in full and as it is written in a division's name ("AFC"). */
@Serializable
data class ConferenceName(val name: String, val short: String)

/**
 * What the league and its conferences are called (SPEC 9.4). A generated
 * league is unnamed and its conferences are the American and the Continental;
 * a roster file can name them all - its own league, the user's to bring, never
 * shipped with the game. Blank fields mean "as the game has always called it".
 */
@Serializable
data class LeagueNames(
    /** In full, "Example Football League". Blank: the league goes unnamed. */
    val league: String = "",
    /** As written in a line of text, "EFL". Blank: [league]. */
    val short: String = "",
    val conferences: Map<Conference, ConferenceName> = emptyMap(),
    /** The title game, "The Example Bowl". Blank: "the championship". */
    val championship: String = "",
) {
    /** The conference as it leads a division's name: "AFC", or the game's "American". */
    fun conference(c: Conference): String = conferences[c]?.short?.takeIf { it.isNotBlank() } ?: c.label

    /** The conference in full: "American Football Conference", or "American Conference". */
    fun conferenceFull(c: Conference): String =
        conferences[c]?.name?.takeIf { it.isNotBlank() } ?: "${conference(c)} Conference"

    fun division(c: Conference, d: Division): String =
        "${conference(c)} ${d.name.lowercase().replaceFirstChar { it.uppercase() }}"

    /** The league as a line of text calls it; blank when it has no name. */
    val leagueShort: String get() = short.ifBlank { league }

    /** "Super Bowl champions", or "Champions" for an unnamed title game. */
    val championsTitle: String get() = if (championship.isBlank()) "Champions" else "$championship champions"
}
