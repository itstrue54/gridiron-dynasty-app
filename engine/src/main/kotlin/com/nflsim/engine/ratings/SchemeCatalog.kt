package com.nflsim.engine.ratings

import kotlinx.serialization.json.Json

/**
 * Loads the shipped schemes from resources. Lazy, parsed once.
 */
object SchemeCatalog {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val all: List<Scheme> by lazy {
        val stream = SchemeCatalog::class.java.getResourceAsStream(RESOURCE)
            ?: error("$RESOURCE not found on the classpath")
        val text = stream.bufferedReader().use { it.readText() }
        json.decodeFromString<List<Scheme>>(text)
    }

    val offensive: List<Scheme> by lazy { all.filter { it.side == SchemeSide.OFFENSE } }
    val defensive: List<Scheme> by lazy { all.filter { it.side == SchemeSide.DEFENSE } }

    private val byId: Map<String, Scheme> by lazy { all.associateBy { it.id } }

    operator fun get(id: String): Scheme =
        byId[id] ?: error("unknown scheme '$id'; known ids are ${byId.keys.sorted()}")

    fun find(id: String): Scheme? = byId[id]

    /** A scheme carrying a tuning table's scheme-fit group, which every rating read from it uses. */
    fun tuned(id: String, tuning: com.nflsim.engine.tuning.TuningTable): Scheme = get(id).copy(ratings = tuning.ratings)

    private const val RESOURCE = "/schemes.json"
}
