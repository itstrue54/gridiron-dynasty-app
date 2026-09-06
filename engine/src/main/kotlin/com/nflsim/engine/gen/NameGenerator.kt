package com.nflsim.engine.gen

import com.nflsim.engine.rng.Rng
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class NamePools(val first: List<String>, val last: List<String>)

/**
 * Fictional player names and colleges, loaded from resources.
 *
 * The pools are big enough that a 30-season dynasty will not feel repetitive:
 * first x last gives well over 100,000 combinations.
 */
object NameGenerator {

    private val json = Json { ignoreUnknownKeys = true }

    private val pools: NamePools by lazy {
        val text = read("/names.json")
        json.decodeFromString<NamePools>(text)
    }

    val colleges: List<String> by lazy {
        json.decodeFromString<List<String>>(read("/colleges.json"))
    }

    val firstNames: List<String> get() = pools.first
    val lastNames: List<String> get() = pools.last

    val combinations: Int get() = firstNames.size * lastNames.size

    fun firstName(rng: Rng): String = firstNames[rng.nextInt(firstNames.size)]
    fun lastName(rng: Rng): String = lastNames[rng.nextInt(lastNames.size)]
    fun college(rng: Rng): String = colleges[rng.nextInt(colleges.size)]

    fun fullName(rng: Rng): Pair<String, String> = firstName(rng) to lastName(rng)

    private fun read(resource: String): String =
        NameGenerator::class.java.getResourceAsStream(resource)
            ?.bufferedReader()?.use { it.readText() }
            ?: error("$resource not found on the classpath")
}
