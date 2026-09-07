package com.nflsim.engine.model

import com.nflsim.engine.rng.Rng
import kotlinx.serialization.Serializable

/**
 * The front office (docs/SPEC.md 8.2).
 *
 * Thirty-two identical general managers produce a league with no bad
 * contracts in it. Every team bid the same fraction of its space, kept the
 * same share of its own players, and restructured only when forced, so no
 * team ever got into cap trouble and the salary cap forced zero releases in a
 * decade. Real cap trouble is not an accident of arithmetic - it is a
 * decision somebody made in March, and somebody makes it every March.
 *
 * All values run 0..1 and are drawn once when the league is built, so a club's
 * reputation is consistent across a career.
 */
@Serializable
data class GmProfile(
    /** How much of the cap he will put on one player. */
    val aggression: Float = 0.5f,
    /** 0 is a full rebuild, 1 is all-in. Drives restructuring and old players. */
    val winNowVsFuture: Float = 0.5f,
    /** How hard he works to keep his own before they reach the market. */
    val loyaltyToOwnPlayers: Float = 0.5f,
    /** Willingness to carry dead money and bet on a bounce-back. */
    val riskTolerance: Float = 0.5f,
) {
    /** Share of a team's space it will commit in one offseason. */
    val spendShare: Float get() = 0.62f + winNowVsFuture * 0.36f

    /** Most it will put into a single contract, as a share of space. */
    val singleDealShare: Float get() = 0.22f + aggression * 0.34f

    /** Share of space reserved for keeping its own players. */
    val ownPlayerShare: Float get() = 0.35f + loyaltyToOwnPlayers * 0.35f

    /** How far past market it will go to win a bidding war. */
    val premium: Float get() = 0.86f + aggression * 0.42f

    /** How readily it pushes money into future years to stay competitive. */
    val restructures: Int get() = (winNowVsFuture * 6f).toInt()

    /** How overpriced a contract has to be before it cuts the player. */
    val patience: Float get() = 1.28f + riskTolerance * 0.5f

    companion object {
        /**
         * A spread wide enough that some clubs are genuinely reckless. A
         * league of moderates has no cap casualties, which is the whole
         * reason this type exists.
         */
        fun generate(rng: Rng): GmProfile = GmProfile(
            aggression = rng.nextFloat(),
            winNowVsFuture = rng.nextFloat(),
            loyaltyToOwnPlayers = rng.nextFloat(),
            riskTolerance = rng.nextFloat(),
        )
    }
}
