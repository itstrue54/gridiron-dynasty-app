package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/** What kind of story it is, which decides how it reads on screen. */
enum class NewsKind { INJURY, PERFORMANCE, MILESTONE, HOT_SEAT, BENCHING, DISPUTE, POACHED, TRADE, STORY }

/**
 * One line of the week's news (SPEC 10.1). Written from the week that was
 * played and kept for the season, not forever: a league remembers its
 * standings and its records, not its headlines.
 */
@Serializable
data class NewsEvent(
    val week: Int,
    val kind: NewsKind,
    val headline: String,
    val player: Int? = null,
    val team: Int? = null,
)
