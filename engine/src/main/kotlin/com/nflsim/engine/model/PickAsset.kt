package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/**
 * A draft pick as an asset a club owns (SPEC 8.4): the draft it is for, the
 * round, the club whose record decides where it falls, and the club that
 * uses it. Trading a pick changes only the owner.
 */
@Serializable
data class PickAsset(
    val year: Int,
    val round: Int,
    /** The club whose record sets where the pick falls. */
    val original: Int,
    /** The club that uses it. */
    val owner: Int,
    /** Awarded for free agents lost; sits at the end of its round (NFL rules). */
    val compensatory: Boolean = false,
)
