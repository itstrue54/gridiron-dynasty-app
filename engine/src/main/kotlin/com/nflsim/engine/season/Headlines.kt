package com.nflsim.engine.season

import com.nflsim.engine.narrative.TemplateBook
import com.nflsim.engine.rng.Rng

/** The words news is written in: `narrative/news.json` (SPEC 10.4). */
object Headlines {

    private val book = TemplateBook("/narrative/news.json")

    val templates: Map<String, List<String>> get() = book.templates

    /** One way of telling the [key] story, with [slots] filled in. */
    fun write(key: String, rng: Rng, vararg slots: Pair<String, Any>): String = book.write(key, rng, *slots)

    /** The slot names in a template. */
    fun slots(template: String): Set<String> = TemplateBook.slots(template)
}
