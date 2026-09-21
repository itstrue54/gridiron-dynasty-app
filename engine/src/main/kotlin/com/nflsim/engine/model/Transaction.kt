package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/**
 * One line on the league's transactions wire (SPEC 4.7), kept forever
 * (SPEC 9.2).
 *
 * The name and position are copied rather than looked up: a man who
 * retires leaves the league's player list, and the wire still has to say
 * who he was.
 */
@Serializable
data class Transaction(
    val year: Int,
    /** The game week the move came before; 0 for the offseason, 19 once the regular season is over. */
    val week: Int,
    val kind: TransactionKind,
    /** The club that made the move; 0 for a retirement. */
    val team: Int,
    val player: Int,
    val name: String,
    val position: String,
    /** Thousands: a year's value for a signing, dead money for a release. */
    val amount: Int = 0,
    /** Contract length for a signing. */
    val years: Int = 0,
    /** The club he came from, for a trade or a man signed off another's squad. */
    val other: Int = 0,
) {
    companion object {
        fun of(
            year: Int, week: Int, kind: TransactionKind, team: TeamId?, player: Player,
            amount: Int = 0, years: Int = 0, other: TeamId? = null,
        ) = Transaction(
            year, week, kind, team?.v ?: 0, player.id.v, player.name, player.position.label,
            amount, years, other?.v ?: 0,
        )
    }
}

@Serializable
enum class TransactionKind(val verb: String) {
    SIGNED("signed"),
    RELEASED("released"),
    DRAFTED("drafted"),
    TRADED("acquired by trade"),
    PROMOTED("promoted from the practice squad"),
    SIGNED_OFF_SQUAD("signed off a practice squad"),
    TO_SQUAD("signed to the practice squad"),
    OFF_SQUAD("released from the practice squad"),
    INJURED_RESERVE("placed on injured reserve"),
    ACTIVATED("activated from injured reserve"),
    RETIRED("retired"),
}
