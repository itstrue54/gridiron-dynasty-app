package com.nflsim.engine.rng

/**
 * Deterministic random source for the simulation.
 *
 * Every random number in the engine comes from one of these. Nothing in
 * :engine may call Math.random(), java.util.Random, System.currentTimeMillis()
 * or UUID.randomUUID() - see docs/SPEC.md section 5.11. CI fails the build if
 * it finds them.
 *
 * The point: a save file stores one seed, and that seed reproduces every game
 * of a 30-season dynasty exactly. Bugs become reproducible.
 */
interface Rng {
    fun nextLong(): Long
    fun nextInt(bound: Int): Int
    fun nextFloat(): Float
    fun nextDouble(): Double
    fun nextBoolean(): Boolean

    /** Normally distributed value. */
    fun gaussian(mean: Float = 0f, sd: Float = 1f): Float

    /**
     * Derives a child stream. The same parent seed and the same label always
     * produce the same child, which is what lets a single game be re-simmed
     * in isolation and come out identical.
     */
    fun split(label: String): Rng
}

/**
 * SplitMix64. Small, fast, good statistical quality, and fully portable -
 * no JDK randomness involved, so results are identical on any machine.
 */
class SplitMixRng(private val seed: Long) : Rng {

    private var state: Long = seed
    private var spareGaussian: Float? = null

    private fun next(): Long {
        state += GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    override fun nextLong(): Long = next()

    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, was $bound" }
        return ((next() ushr 1) % bound).toInt()
    }

    override fun nextFloat(): Float = (next() ushr 40).toFloat() / FLOAT_DIVISOR

    override fun nextDouble(): Double = (next() ushr 11).toDouble() / DOUBLE_DIVISOR

    override fun nextBoolean(): Boolean = (next() ushr 63) == 1L

    override fun gaussian(mean: Float, sd: Float): Float {
        spareGaussian?.let {
            spareGaussian = null
            return mean + sd * it
        }
        // Marsaglia polar method
        var u: Float
        var v: Float
        var s: Float
        do {
            u = nextFloat() * 2f - 1f
            v = nextFloat() * 2f - 1f
            s = u * u + v * v
        } while (s >= 1f || s == 0f)
        val factor = kotlin.math.sqrt(-2f * kotlin.math.ln(s) / s)
        spareGaussian = v * factor
        return mean + sd * (u * factor)
    }

    override fun split(label: String): Rng {
        var h = seed xor FNV_OFFSET
        for (c in label) {
            h = (h xor c.code.toLong()) * FNV_PRIME
        }
        // run it through the mixer once so nearby labels give distant streams
        var z = h
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return SplitMixRng(z xor (z ushr 31))
    }

    private companion object {
        val GAMMA = 0x9E3779B97F4A7C15uL.toLong()
        val MIX_1 = 0xBF58476D1CE4E5B9uL.toLong()
        val MIX_2 = 0x94D049BB133111EBuL.toLong()
        val FNV_OFFSET = 0xCBF29CE484222325uL.toLong()
        val FNV_PRIME = 0x100000001B3uL.toLong()
        const val FLOAT_DIVISOR = 16_777_216f
        const val DOUBLE_DIVISOR = 9_007_199_254_740_992.0
    }
}
