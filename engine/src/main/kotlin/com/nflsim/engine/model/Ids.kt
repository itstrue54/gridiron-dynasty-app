package com.nflsim.engine.model

import kotlinx.serialization.Serializable

// Integer IDs, not UUIDs: they serialize small, compare fast, and can index
// into arrays. See docs/SPEC.md section 4.1.

@JvmInline @Serializable value class PlayerId(val v: Int)
@JvmInline @Serializable value class TeamId(val v: Int)
@JvmInline @Serializable value class CoachId(val v: Int)
@JvmInline @Serializable value class GameId(val v: Int)
