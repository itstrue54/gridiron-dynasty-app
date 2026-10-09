package com.nflsim.engine.offseason

import com.nflsim.engine.gen.PlayerGenerator
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.season.Dynasty

/**
 * The offseason stopped after the draft and training camp (SPEC 7 phases
 * 10-11), so the user's club fills its own roster and makes its own cut to
 * 53 instead of the league's logic doing both. Camp has happened: its
 * development and its injuries are in, and [campBefore] is his roster as it
 * reported, for what camp did to each man.
 *
 * He releases whoever he wants - each at the dead money his contract says -
 * and signs undrafted men and leftover veterans off the street at the
 * minimum. The suggestions are the league's own fill and cut, run on his
 * roster alone, so taking them is what his front office would have done.
 */
class CutdownPause internal constructor(
    internal val draft: OffseasonEngine.DraftPause,
    internal val state: OffseasonState,
    /** The user's roster as it reported to camp, before camp developed it. */
    val campBefore: List<Player> = emptyList(),
    /** Every man hurt in camp, the league over. */
    val campInjuries: List<TrainingCamp.CampInjury> = emptyList(),
    /** Everyone as he reported to camp, for how far camp moved him. */
    internal val reported: Map<Int, Player> = emptyMap(),
    /** The camp cuts and trades made so far: every other club's, and any the user has taken. */
    internal val campReleases: List<Release> = emptyList(),
    internal val campTrades: List<TradeMove> = emptyList(),
) {
    val year: Int get() = draft.ctx.newYear
    val userTeam: TeamId get() = draft.ctx.dynasty.userTeamId
    private val league: League get() = draft.ctx.league

    /** The user's camp roster, as the draft left it. */
    val roster: List<Player> get() = state.players.filter { it.teamId == userTeam }

    /** Undrafted men and leftover veterans, the best first by what they are to this club. */
    val pool: List<Player> by lazy {
        state.players
            .filter { it.teamId == null && it.status != PlayerStatus.RETIRED && it.status != PlayerStatus.PRACTICE_SQUAD }
            .sortedByDescending { value(it) }
    }

    /** What he is to this club: the same judgement its front office cuts by. */
    fun value(p: Player): Float = rosterValue(
        p, draft.ctx.scheme(userTeam, p.position), year, league.team(userTeam).gm.winNowVsFuture, league.tuning.ai)

    /** Dead money this year if he is released. */
    fun deadIfCut(p: Player): Int = p.contract?.deadCap(year)?.thisYear ?: 0

    val capSpace: Int get() = CapManagement.spaceFor(roster, year, state.deadMoney[userTeam.v] ?: 0,
        carryover = league.team(userTeam).finances.carryover)

    /** The cut the user makes: who goes, and who is signed off the street. */
    data class Cut(val release: Set<Int> = emptySet(), val sign: Set<Int> = emptySet())

    /**
     * What the front office would do: sign the best man available at every
     * position the club is short of, then cut to 53 by the league's own
     * logic - protecting positional minimums, and counting a man's dead
     * money in his favour, since the club pays it either way.
     */
    val suggested: Cut by lazy {
        val have = roster.groupingBy { it.position }.eachCount().toMutableMap()
        val signs = mutableListOf<Player>()
        val street = pool.toMutableList()
        TeamNeeds.ROSTER_TEMPLATE.forEach { (position, required) ->
            while ((have[position] ?: 0) < required) {
                val best = street.firstOrNull { it.position == position } ?: break
                street.remove(best)
                signs += best
                have[position] = (have[position] ?: 0) + 1
            }
        }
        // The camp moves first: whoever camp says should go is not counted on.
        val planned = campPlan.map { it.player.id.v }.toSet()
        val camp = roster.filter { it.id.v !in planned } + signs.map { it.copy(teamId = userTeam, contract = streetDeal()) }
        val kept = OffseasonEngine.enforceRosterLimit(league, camp, year, draft.ctx.scheme)
            .filter { it.teamId == userTeam }.map { it.id.v }.toSet()
        Cut(
            release = roster.map { it.id.v }.filter { it !in kept }.toSet() + planned,
            sign = signs.map { it.id.v }.filter { it in kept }.toSet(),
        )
    }

    /**
     * The moves the user's club would make on what camp showed (CampDecisions):
     * each a cut, or a trade another club has offered for him. Nothing is
     * made until the user takes it.
     */
    val campPlan: List<CampDecisions.Move> by lazy {
        CampDecisions.plan(OffseasonEngine.campContext(draft, state, reported), league.team(userTeam),
            league.teams.filter { it.id != userTeam })
    }

    /** The clubs that would trade for one of the user's men rather than see him cut. */
    val campOffers: List<CampDecisions.Move> get() = campPlan.filter { it.to != null }

    /** Camp with [offer] taken: the man gone to his new club, its pick the user's, the rest as it was. */
    fun takeCampTrade(offer: CampDecisions.Move): CutdownPause {
        require(offer in campOffers) { "no such offer" }
        val ctx = OffseasonEngine.campContext(draft, state, reported)
        val after = CampDecisions.apply(ctx, CampDecisions.Result(state.players, state.deadMoney, state.picks,
            emptyList(), emptyList(), emptyList()), offer)
        return CutdownPause(draft,
            state.copy(players = after.players, deadMoney = after.deadMoney, picks = after.picks,
                pickTrades = state.pickTrades + after.pickTrades),
            campBefore, campInjuries, reported, campReleases, campTrades + after.trades)
    }

    /** Camp with the user's club making every move its front office would: its trades, then its cuts. */
    internal fun withCampPlan(): CutdownPause {
        val ctx = OffseasonEngine.campContext(draft, state, reported)
        var r = CampDecisions.Result(state.players, state.deadMoney, state.picks, emptyList(), emptyList(), emptyList())
        campPlan.forEach { r = CampDecisions.apply(ctx, r, it) }
        return CutdownPause(draft,
            state.copy(players = r.players, deadMoney = r.deadMoney, picks = r.picks, pickTrades = state.pickTrades + r.pickTrades),
            campBefore, campInjuries, reported, campReleases + r.releases, campTrades + r.trades)
    }

    /** Why a suggested cut is one: what camp showed, or where he sits among his club's men at his position. */
    fun whyCut(p: Player): String {
        campPlan.firstOrNull { it.player.id == p.id }?.let { return campReason(it) }
        val scheme = draft.ctx.scheme(userTeam, p.position)
        val ahead = roster.count { it.position == p.position && overall(it, scheme) > overall(p, scheme) }
        val dead = deadIfCut(p)
        return "${ordinal(ahead + 1)} of ${roster.count { it.position == p.position }} at ${p.position.label}" +
            if (dead > 0) ", and cutting him leaves ${money(dead)} of dead money." else ", and nothing is owed."
    }

    /** Positions every club has to field, which the front office fills if the user leaves one empty. */
    val mustField: List<Position> = listOf(Position.QB, Position.K, Position.P, Position.LS)

    /** Camp, the cut, and the turn of the year. Null leaves the fill and the cut to the front office. */
    fun decide(cut: Cut?): Pair<Dynasty, OffseasonReport> = OffseasonEngine.finishFromCutdown(this, cut)

    /**
     * The user's cut, applied: his releases at their dead money, his
     * signings at the minimum, and a camp body wherever he left a position
     * every club has to field empty.
     */
    internal fun apply(cut: Cut): OffseasonState {
        val byId = state.players.associateBy { it.id.v }.toMutableMap()
        val dead = state.deadMoney.toMutableMap()
        val releases = mutableListOf<Release>()
        val signings = mutableListOf<Signing>()

        roster.filter { it.id.v in cut.release }.forEach { p ->
            val owed = deadIfCut(p)
            dead[userTeam.v] = (dead[userTeam.v] ?: 0) + owed
            releases += Release(p.id.v, p.name, p.position.label, userTeam.v,
                overall(p, draft.ctx.scheme(userTeam, p.position)), p.capHit(year) - owed, owed)
            byId[p.id.v] = p.copy(teamId = null, contract = null, status = PlayerStatus.FREE_AGENT)
        }
        pool.filter { it.id.v in cut.sign }.forEach { p ->
            byId[p.id.v] = signed(p)
            signings += Signing(p.id.v, p.name, p.position.label, userTeam.v,
                Contract.MIN_BASE_SALARY, 1, Contract.MIN_BASE_SALARY, suitors = 1)
        }

        // Nobody plays a season without a quarterback, a kicker, a punter and
        // a snapper. Whatever the user left empty is filled off the street.
        var added = emptyList<Player>()
        mustField.forEach { position ->
            if (byId.values.any { it.teamId == userTeam && it.position == position }) return@forEach
            val body = byId.values
                .filter { it.teamId == null && it.position == position && it.status != PlayerStatus.RETIRED }
                .maxByOrNull { value(it) }
                ?: PlayerGenerator.generate(
                    id = PlayerId((byId.keys.maxOrNull() ?: 0) + 1),
                    position = position,
                    targetOverall = league.tuning.ai.campBody,
                    year = year,
                    rng = draft.rng.split("camp|${userTeam.v}|${position.name}|$year"),
                ).also { added = added + it }
            byId[body.id.v] = signed(body)
            signings += Signing(body.id.v, body.name, body.position.label, userTeam.v,
                Contract.MIN_BASE_SALARY, 1, Contract.MIN_BASE_SALARY, suitors = 1)
        }

        val players = state.players.map { byId.getValue(it.id.v) } + added.map { byId.getValue(it.id.v) }
        return state.copy(
            players = players,
            deadMoney = dead,
            releases = state.releases + releases,
            gapSignings = state.gapSignings + signings,
        )
    }

    private fun signed(p: Player) = p.copy(
        teamId = userTeam, status = PlayerStatus.ACTIVE, contract = streetDeal(),
        yearsInSystem = 0, yearsWithClub = 0,
    )

    /** A camp body's deal: a year at the minimum, nothing guaranteed. */
    private fun streetDeal() = Contract(years = 1, baseSalary = listOf(Contract.MIN_BASE_SALARY), signedYear = year)

    /** A camp move in words: why, and what letting him go saves this year and next. */
    fun campReason(m: CampDecisions.Move): String {
        val why = when (m.why) {
            CampDecisions.Why.REGRESSED -> "went backwards in camp (${m.campChange}) and is paid ${money(m.capHit)} for less than that now"
            CampDecisions.Why.PASSED_BY -> "camp passed him by: he is no longer a starter, at ${money(m.capHit)}"
            CampDecisions.Why.NEXT_YEARS_CAP -> "next year's cap is over as it stands, and at ${money(m.capHit)} he is the dearest for what he gives"
        }
        val next = if (m.savingNext > 0) " and ${money(m.savingNext)} next year" else ""
        val dead = if (m.deadNow > 0) ", leaving ${money(m.deadNow)} of dead money" else ""
        return "$why. Letting him go frees ${money(m.savingNow)} this year$next$dead."
    }

    private fun ordinal(n: Int) = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }

    private fun money(thousands: Int): String =
        if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)

    companion object {
        const val ROSTER_LIMIT = com.nflsim.engine.season.Transactions.ROSTER_LIMIT
        /** The fewest a club can dress, and so the fewest it can carry out of camp. */
        const val GAME_DAY = com.nflsim.engine.season.RosterMoves.GAME_DAY
    }
}
