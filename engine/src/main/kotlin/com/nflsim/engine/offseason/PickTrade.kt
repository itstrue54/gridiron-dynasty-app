package com.nflsim.engine.offseason

import kotlinx.serialization.Serializable

/** A draft pick changing hands, for the news screen and the health check. */
@Serializable
data class PickTrade(
    val year: Int,
    val round: Int,
    /** The club whose record sets where the pick falls. */
    val original: Int,
    val from: Int,
    val to: Int,
    val reason: String,
)
