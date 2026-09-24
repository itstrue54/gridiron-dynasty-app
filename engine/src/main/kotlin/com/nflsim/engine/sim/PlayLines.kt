package com.nflsim.engine.sim

import com.nflsim.engine.narrative.TemplateBook
import com.nflsim.engine.rng.Rng

/**
 * The words the play-by-play is written in: `narrative/plays.json`
 * (SPEC 10.4). The [Rng] a line is written with is a game's narration
 * stream and nothing else, so how a snap is told never moves how it plays.
 */
object PlayLines {

    private val book = TemplateBook("/narrative/plays.json")

    val templates: Map<String, List<String>> get() = book.templates

    fun write(key: String, words: Rng, vararg slots: Pair<String, Any>): String = book.write(key, words, *slots)

    /** "1 yard", "7 yards". */
    fun yardage(yards: Int): String = if (yards == 1) "1 yard" else "$yards yards"
}
