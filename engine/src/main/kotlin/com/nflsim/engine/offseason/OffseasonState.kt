package com.nflsim.engine.offseason

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.RetiredCareer
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.Standings

/** What every offseason step reads and none of them change. */
class OffseasonContext(
    val dynasty: Dynasty,
    val oldYear: Int,
    val newYear: Int,
    /** Offence or defence scheme for a player's side of the ball. */
    val scheme: (TeamId?, Position) -> Scheme,
    val schemePair: (TeamId) -> Pair<Scheme, Scheme>,
    val standings: Standings,
    val winPct: (TeamId) -> Float,
    val production: Map<Int, Float>,
) {
    val league: League get() = dynasty.league
}

/**
 * What one step hands the next.
 *
 * The nullable fields are ordering constraints made explicit: [pricer] does
 * not exist until the cap has been enforced, [draft] until the draft has run.
 * A step that reads one before it is set is a step running out of order —
 * which is the same thing SPEC 7 calls a blocking condition.
 */
data class OffseasonState(
    val players: List<Player>,
    /** Chosen after the cut to 53 (SPEC 7 phase 11). */
    val practiceSquads: Map<com.nflsim.engine.model.TeamId, List<com.nflsim.engine.model.PlayerId>> = emptyMap(),
    val deadMoney: Map<Int, Int> = emptyMap(),
    /** Who each expiring player was with, for the re-signing window. */
    val previousTeam: Map<Int, TeamId> = emptyMap(),
    /** Depth-chart position, which is how playing time is inferred. */
    val depthRank: Map<Int, Int> = emptyMap(),
    val pricer: MarketValue.Pricer? = null,
    /** Mean roster size once the cap was enforced, before signings. */
    val underContract: Int = 0,

    // ---- accumulated for the report ----
    val retirements: List<Retirement> = emptyList(),
    /** The careers of the men who just left, for the league's history. */
    val retiredCareers: List<RetiredCareer> = emptyList(),
    val developments: List<Development> = emptyList(),
    val releases: List<Release> = emptyList(),
    val valueCuts: List<Release> = emptyList(),
    val wishes: List<Wish> = emptyList(),
    val trades: List<TradeMove> = emptyList(),
    val extensionSignings: List<Signing> = emptyList(),
    /** Free agents the user signed by talking to their agents before the market opened. */
    val preMarketSignings: List<Signing> = emptyList(),
    val gapSignings: List<Signing> = emptyList(),
    val draft: DraftRunner.Result? = null,
    val auction: FreeAgency.Result? = null,

    // ---- development aggregates ----
    val deltaSum: Int = 0,
    val deltaCount: Int = 0,
    val ageSum: Map<String, Int> = emptyMap(),
    val ageCount: Map<String, Int> = emptyMap(),
    val teamDeltaSum: Map<Int, Int> = emptyMap(),
    val teamDeltaCount: Map<Int, Int> = emptyMap(),
    val teamYoungSum: Map<Int, Int> = emptyMap(),
    val teamYoungCount: Map<Int, Int> = emptyMap(),

    // ---- the 53-man cut ----
    val cutdownCount: Int = 0,
    val cutdownDeadMoney: Int = 0,
    /** Cut in the same offseason they were signed or drafted. */
    val cutdownFresh: Int = 0,

    // ---- fifth-year options ----
    val optionsExercised: Int = 0,
    val optionsDeclined: Int = 0,

    // ---- draft picks, which trades move before the draft uses them ----
    val picks: List<com.nflsim.engine.model.PickAsset> = emptyList(),
    val pickTrades: List<PickTrade> = emptyList(),

    // ---- tags ----
    val tags: List<Tag> = emptyList(),
    /** Transition-tagged players, and the club that may match an offer for each. */
    val transitionTags: Map<Int, TeamId> = emptyMap(),
    /** Men the user's club let walk this spring, and that club: they return only if willing (SPEC 7). */
    val letGo: Map<Int, TeamId> = emptyMap(),
    /** Transition-tagged players who stayed, matched or on the tender. */
    val transitionKept: Int = 0,
) {
    fun requirePricer(): MarketValue.Pricer =
        pricer ?: error("pricer is not built until the cap is enforced")
}
