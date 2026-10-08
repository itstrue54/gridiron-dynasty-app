package com.nflsim.engine.stats

import com.nflsim.engine.model.PlayerId
import kotlinx.serialization.Serializable

@Serializable
data class StatLine(
    val passAttempts: Int = 0,
    val completions: Int = 0,
    val passYards: Int = 0,
    val passTouchdowns: Int = 0,
    val interceptionsThrown: Int = 0,
    val timesSacked: Int = 0,
    val carries: Int = 0,
    val rushYards: Int = 0,
    val rushTouchdowns: Int = 0,
    val fumblesLost: Int = 0,
    val targets: Int = 0,
    val receptions: Int = 0,
    val receivingYards: Int = 0,
    val receivingTouchdowns: Int = 0,
    /** Tackles where he was the man who made the stop. */
    val tackles: Int = 0,
    /** Plays where he was the second man in. Combined tackles are the two together. */
    val assists: Int = 0,
    val sacks: Int = 0,
    val interceptions: Int = 0,
    /** Kickoffs and punts he brought back (fair catches and touchbacks are not returns), and the yards. */
    val kickReturns: Int = 0,
    val kickReturnYards: Int = 0,
    val puntReturns: Int = 0,
    val puntReturnYards: Int = 0,
    /** Returns he stopped in coverage. Kept apart from [tackles], which the defence's records and pricing read. */
    val specialTeamsTackles: Int = 0,
) {
    operator fun plus(other: StatLine) = StatLine(
        passAttempts + other.passAttempts, completions + other.completions,
        passYards + other.passYards, passTouchdowns + other.passTouchdowns,
        interceptionsThrown + other.interceptionsThrown, timesSacked + other.timesSacked,
        carries + other.carries, rushYards + other.rushYards,
        rushTouchdowns + other.rushTouchdowns, fumblesLost + other.fumblesLost,
        targets + other.targets, receptions + other.receptions,
        receivingYards + other.receivingYards, receivingTouchdowns + other.receivingTouchdowns,
        tackles + other.tackles, assists + other.assists,
        sacks + other.sacks, interceptions + other.interceptions,
        kickReturns + other.kickReturns, kickReturnYards + other.kickReturnYards,
        puntReturns + other.puntReturns, puntReturnYards + other.puntReturnYards,
        specialTeamsTackles + other.specialTeamsTackles,
    )

    /** What a tackle leaderboard counts: his own stops and the ones he helped on. */
    val combinedTackles: Int get() = tackles + assists

    val totalTouchdowns: Int get() = rushTouchdowns + receivingTouchdowns
    val touches: Int get() = carries + receptions
    val scrimmageYards: Int get() = rushYards + receivingYards
    val yardsPerCarry: Double get() = if (carries == 0) 0.0 else rushYards.toDouble() / carries
    val completionPct: Double get() = if (passAttempts == 0) 0.0 else completions.toDouble() / passAttempts

    /** Passer rating, the standard formula. */
    val passerRating: Double get() {
        if (passAttempts == 0) return 0.0
        val a = ((completions.toDouble() / passAttempts - 0.3) * 5).coerceIn(0.0, 2.375)
        val b = ((passYards.toDouble() / passAttempts - 3) * 0.25).coerceIn(0.0, 2.375)
        val c = ((passTouchdowns.toDouble() / passAttempts) * 20).coerceIn(0.0, 2.375)
        val d = (2.375 - (interceptionsThrown.toDouble() / passAttempts * 25)).coerceIn(0.0, 2.375)
        return (a + b + c + d) / 6 * 100
    }
}

@Serializable
data class TeamStats(
    val points: Int = 0,
    val firstDowns: Int = 0,
    val plays: Int = 0,
    val totalYards: Int = 0,
    val rushAttempts: Int = 0,
    val rushYards: Int = 0,
    val passAttempts: Int = 0,
    val completions: Int = 0,
    val passYards: Int = 0,
    val sacksAllowed: Int = 0,
    val sackYards: Int = 0,
    val turnovers: Int = 0,
    val passInterceptions: Int = 0,
    val fumblesLost: Int = 0,
    /** Of [fumblesLost], those on carries: the rest came off sacks and catches. */
    val rushFumblesLost: Int = 0,
    val rushesForLoss: Int = 0,
    val rushesOfTwentyPlus: Int = 0,
    val penalties: Int = 0,
    val penaltyYards: Int = 0,
    val thirdDownAttempts: Int = 0,
    val thirdDownConversions: Int = 0,
    val fourthDownAttempts: Int = 0,
    val fourthDownConversions: Int = 0,
    val redZoneTrips: Int = 0,
    val redZoneTouchdowns: Int = 0,
    val possessionSeconds: Int = 0,
    val drives: Int = 0,
) {
    val yardsPerPlay: Double get() = if (plays == 0) 0.0 else totalYards.toDouble() / plays
    val thirdDownPct: Double get() =
        if (thirdDownAttempts == 0) 0.0 else thirdDownConversions.toDouble() / thirdDownAttempts
    val redZonePct: Double get() =
        if (redZoneTrips == 0) 0.0 else redZoneTouchdowns.toDouble() / redZoneTrips
    val possessionText: String get() = "%d:%02d".format(possessionSeconds / 60, possessionSeconds % 60)
}

@Serializable
data class BoxScore(
    val home: TeamStats,
    val away: TeamStats,
    val players: Map<Int, StatLine> = emptyMap(),
) {
    fun forPlayer(id: PlayerId): StatLine = players[id.v] ?: StatLine()

    /**
     * Every yard has to come from somewhere. A box score that does not
     * reconcile means a stage of the sim is double counting or dropping.
     */
    fun reconciles(): Boolean {
        fun ok(t: TeamStats) = t.totalYards == t.rushYards + t.passYards + t.sackYards
        return ok(home) && ok(away)
    }
}

/** Mutable accumulator used while a game runs; frozen into a BoxScore at the end. */
class StatBuilder {
    private val lines = mutableMapOf<Int, StatLine>()

    fun update(id: PlayerId?, block: (StatLine) -> StatLine) {
        if (id == null) return
        lines[id.v] = block(lines[id.v] ?: StatLine())
    }

    fun snapshot(): Map<Int, StatLine> = lines.toMap()

    /** Carries so far this game. */
    fun carries(id: Int): Int = lines[id]?.carries ?: 0
}
