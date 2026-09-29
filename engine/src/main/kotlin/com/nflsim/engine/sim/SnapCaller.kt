package com.nflsim.engine.sim

/**
 * Who calls a club's snaps: its coordinators, or the user (SPEC 5.4).
 *
 * Before each snap the coordinators' calls are made as they always are - from
 * the game's own stream - and handed here as the suggestion. Whatever comes
 * back is played. Returning the suggestion every time plays the game exactly
 * as the coordinators would have, draw for draw; a different call changes
 * what happens from that snap on, and nothing before it.
 *
 * A caller may block while a person decides: the game waits on it.
 */
interface SnapCaller {
    /** The club's call with the ball. */
    fun offense(snap: Snap, suggested: OffensivePlayCall): OffensivePlayCall = suggested

    /** The club's call without it. */
    fun defense(snap: Snap, suggested: DefensivePlayCall): DefensivePlayCall = suggested

    /** Fourth down: go for it, punt or kick. */
    fun fourthDown(snap: Snap, suggested: FourthDownChoice): FourthDownChoice = suggested

    /**
     * After a snap that leaves the clock running, with a timeout left that
     * would save time: stop the clock? [suggested] is whether the
     * coordinators would; [snap] is the game as the snap began, its play in
     * the log (SPEC 5.10).
     */
    fun timeout(snap: Snap, suggested: Boolean): Boolean = suggested

    /**
     * A playoff game this caller speaks in is about to kick off (SPEC 5.4):
     * [title] names it ("AFC championship"). It may wait here while the user
     * gets ready. A regular-season game, the only one of its week, does not
     * call it.
     */
    fun kickoff(title: String, home: com.nflsim.engine.model.TeamId, away: com.nflsim.engine.model.TeamId) {}

    /** That playoff game is over. */
    fun final(homeScore: Int, awayScore: Int) {}
}

/** A snap as the caller sees it: the game as it stands, and every play so far. */
data class Snap(
    val state: GameState,
    /** The side the caller speaks for. */
    val side: Side,
    val plays: List<PlayLog>,
) {
    val onOffense: Boolean get() = state.possession == side
}
