package com.nflsim.engine.offseason

import com.nflsim.engine.gen.PlayerGenerator
import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.econ.Production
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.rng.shuffled
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyPhase
import com.nflsim.engine.season.ScheduleGenerator
import com.nflsim.engine.season.Standings
import kotlinx.serialization.Serializable

@Serializable
data class Retirement(
    val player: Int,
    val name: String,
    val position: String,
    val age: Int,
    val overall: Int,
    /** Why he went. Walking away on your own terms reads differently. */
    val reason: String = "retired",
)

@Serializable
data class Development(val player: Int, val name: String, val position: String, val delta: Int, val note: String?)

@Serializable
data class Signing(
    val player: Int,
    val name: String,
    val position: String,
    val team: Int,
    /** Annual value, in thousands. */
    val value: Int,
    val years: Int = 1,
    /** What he was worth. value over market is what the auction cost. */
    val market: Int = 0,
    /** How many teams were bidding. One is a bargain; four is a problem. */
    val suitors: Int = 1,
)

/**
 * One club's offseason in money, in thousands - enough to tell a reckless
 * front office from a careful one by its books rather than its profile.
 */
@Serializable
data class TeamMoney(
    val faPaid: Int = 0,
    val faMarket: Int = 0,
    val keptPaid: Int = 0,
    val keptMarket: Int = 0,
    /** Own players kept before the market opened. */
    val keptCount: Int = 0,
    /** Contracts above 6m on the books once the offseason is done. */
    val bigContracts: Int = 0,
    /** Of those, how many run past 1.5x what the player is worth now. */
    val overpaid: Int = 0,
    /** Dead money on the books this offseason, carried and new. */
    val deadMoney: Int = 0,
    val casualties: Int = 0,
)

/** What happened between seasons, for the news screen. */
@Serializable
data class OffseasonReport(
    val year: Int,
    val retirementCount: Int = 0,
    val retirements: List<Retirement> = emptyList(),
    val risers: List<Development> = emptyList(),
    val fallers: List<Development> = emptyList(),
    val draftPicks: List<DraftPick> = emptyList(),
    val signings: List<Signing> = emptyList(),
    val releases: List<Release> = emptyList(),
    val yourPicks: List<DraftPick> = emptyList(),
    val capCasualties: Int = 0,
    /** Signings a full roster made by releasing a worse player at the position. */
    val upgradeCount: Int = 0,
    /** Every club's money, keyed by team id. The lists above are cut to twenty. */
    val moneyByTeam: Map<Int, TeamMoney> = emptyMap(),
    /** Players cut to reach 53, how many had only just arrived, and their dead money. */
    val cutdownCount: Int = 0,
    val cutdownFresh: Int = 0,
    val cutdownDeadMoney: Int = 0,
    /** Every free agent signed, not just the twenty the news screen lists. */
    val signingCount: Int = 0,
    /** What players told their clubs they wanted. */
    val wishes: List<Wish> = emptyList(),
    val tradeRequests: Int = 0,
    val trades: List<TradeMove> = emptyList(),
    /** Players a team kept before the market opened. */
    val extensionCount: Int = 0,
    /** Where the offseason's money went, in thousands per year committed. */
    val extensionSpend: Int = 0,
    val auctionSpend: Int = 0,
    val fillSpend: Int = 0,
    /** Mean roster size once the cap was enforced, before anyone was signed. */
    val underContract: Int = 0,
    /**
     * Contracts worth cutting, by how far past the player's value they run.
     * Zero cap casualties is either a market with no bad contracts in it or a
     * threshold set where nothing can reach; this is how to tell.
     */
    val overpaidBy30: Int = 0,
    val overpaidBy50: Int = 0,
    val overpaidBy70: Int = 0,
    /** Auction signings only, and what they cost against market. */
    val auctionCount: Int = 0,
    val auctionOverpay: Float = 0f,
    val auctionContested: Int = 0,
    /** Cap space left league-wide once the market closed, per team. */
    val meanCapSpace: Int = 0,
    /** Teams with less than ten million to their name. */
    val teamsTightOnCap: Int = 0,
    /** Cap space per team, keyed by team id, once the market has closed. */
    val capSpaceByTeam: Map<Int, Int> = emptyMap(),
    // ---- talent flow, for the health check ----
    /** Mean overall of everyone who left the league this offseason. */
    val retiredMean: Float = 0f,
    val draftedCount: Int = 0,
    /** Rookies who arrive already able to start. The elite pipeline. */
    val draftedStarters: Int = 0,
    /** Mean overall of the incoming draft class, as drafted. */
    val draftedMean: Float = 0f,
    /** Average rating points gained or lost per player in development. */
    val developmentNet: Float = 0f,
    /**
     * The same figure split by age bracket. A league that slides is either
     * failing to develop its young players or punishing its old ones too
     * hard, and the aggregate cannot tell those apart.
     */
    val developmentByAge: Map<String, Float> = emptyMap(),
    /**
     * The same figure per team, keyed by team id. Coaching is only worth a
     * hiring screen if a good staff visibly out-develops a bad one, and a
     * league-wide average is exactly the number that cannot show it.
     */
    val developmentByTeam: Map<Int, Float> = emptyMap(),
    /**
     * Per team again, but only players 24 and under - the ones coaching can
     * actually reach. SPEC 7.1 applies the coach multiplier to the growth
     * branch and not to decline, so a whole-roster average measures a team's
     * age profile far more than it measures its staff.
     */
    val youngDevelopmentByTeam: Map<Int, Float> = emptyMap(),
)

/**
 * The year turning over.
 *
 * Order matters and is not arbitrary: players retire before anyone develops
 * (a 38 year old should not gain a point on his way out), contracts expire
 * before the draft so needs are honest, the cap is enforced before the draft
 * so team needs reflect the roster a team can afford, and free agency runs
 * after the draft so a team that took a corner in round one is not still
 * shopping for one. Whatever the market leaves unfilled is filled last, at
 * the minimum, by players nobody bid on.
 */
object OffseasonEngine {

    fun run(dynasty: Dynasty, rng: Rng = SplitMixRng(dynasty.seed + dynasty.year)): Pair<Dynasty, OffseasonReport> {
        val league = dynasty.league
        val oldYear = dynasty.year
        val newYear = oldYear + 1

        val schemeFor: (TeamId) -> Pair<Scheme, Scheme> = { id ->
            val t = league.team(id)
            SchemeCatalog[t.offenseScheme] to SchemeCatalog[t.defenseScheme]
        }
        fun sideScheme(teamId: TeamId?, position: Position): Scheme {
            val id = teamId ?: league.teams.first().id
            val (off, def) = schemeFor(id)
            return if (position.isOffense) off else def
        }

        // ---- context, fixed for the whole run -----------------------
        // Standings and production both read the league as it finished the
        // season, and standings takes a named rng split rather than drawing
        // from the caller's stream, so building them here changes nothing.
        val standings = Standings(league, dynasty.results, rng.split("order|$newYear"))
        val winPct: (TeamId) -> Float = { id -> standings.record(id).winPct.toFloat() }
        val production = Production.index(league.players, dynasty.playerStats)

        val ctx = OffseasonContext(
            dynasty = dynasty,
            oldYear = oldYear,
            newYear = newYear,
            scheme = ::sideScheme,
            schemePair = schemeFor,
            standings = standings,
            winPct = winPct,
            production = production,
        )

        var state = OffseasonState(players = league.players)

        // ---- 1. retirements -----------------------------------------
        state = stepRetirements(ctx, state, rng)

        // ---- 2. development -----------------------------------------
        state = stepDepthChart(ctx, state)

        // Bridge back to the names the rest of this function still uses.
        // Every further extraction shortens this list; when it is empty the
        // run function is a phase loop.
        val depthRank = state.depthRank

        // ---- 3. contracts expire ------------------------------------
        state = stepContractsExpire(ctx, state)

        // ---- 4. get under the cap -----------------------------------
        state = stepCapCompliance(ctx, state, rng)

        val previousTeam = state.previousTeam
        val afterCap = state.players
        val deadMoney = state.deadMoney
        val releases = state.releases

        // ---- 5. what the market can pay -----------------------------
        // Prices are set by the money actually chasing players, not by a
        // fixed curve (ADR-006). Total cap space is what teams have to spend;
        // the free agents who will fill the league's open roster spots are
        // what they are spending it on. A cap-rich offseason with a thin
        // market is expensive, which is exactly how it works in reality.
        //
        // What everyone did last season is what teams actually pay for
        // (ADR-007). Stats are season-scoped and still in hand here; they are
        // cleared when the year rolls over at the end of this function.
        // ---- 5. what the market can pay -----------------------------
        state = stepBuildPricer(ctx, state)

        // ---- 6. what players want -----------------------------------
        state = stepPlayerIntent(ctx, state, rng)

        // ---- 7. cut the contracts that are not worth it --------------
        state = stepPruneBadValue(ctx, state)

        val pricer = state.requirePricer()
        val wishes = state.wishes
        val trades = state.trades
        val valueCuts = state.valueCuts
        val afterPrune = state.players

        // ---- phase 6: re-signing ------------------------------------
        state = stepReSigning(ctx, state, rng)
        // ---- phase 7: free agency -----------------------------------
        state = stepFreeAgency(ctx, state, rng)
        // ---- phase 9: draft -----------------------------------------
        state = stepDraft(ctx, state, rng)
        val extendedSignings = state.extensionSignings
        val draft = state.draft!!
        val auction = state.auction!!

        // ---- phase 10: undrafted free agents ------------------------
        state = stepFillRosters(ctx, state, rng)
        // ---- phase 11: OTAs and camp --------------------------------
        state = stepRosterLimit(ctx, state)
        state = stepResolveUnsigned(ctx, state, rng)
        state = stepDevelopment(ctx, state, rng)
        val developments = state.developments
        val deltaSum = state.deltaSum
        val deltaCount = state.deltaCount
        val ageSum = state.ageSum
        val ageCount = state.ageCount
        val retirements = state.retirements
        val gapSignings = state.gapSignings
        val signings = extendedSignings + auction.signings + gapSignings
        val survivors = state.players

        // ---- 14. rebuild --------------------------------------------
        val byTeam = survivors.filter { it.teamId != null }.groupBy { it.teamId!! }
        val teams: List<Team> = league.teams.map { t ->
            t.copy(
                roster = (byTeam[t.id] ?: emptyList()).map { it.id },
                finances = t.finances.copy(
                    salaryCap = CapManagement.capFor(newYear),
                    // Dead money is carried forward: a cut you make this year
                    // is still on the books next year, which is what makes a
                    // bad contract hurt for seasons rather than one afternoon.
                    deadMoney = (state.deadMoney[t.id.v] ?: 0) / 2,
                ),
            )
        }
        val newLeague = league.copy(year = newYear, teams = teams, players = survivors)

        val schedule = ScheduleGenerator.generate(
            newLeague, newYear, rng.split("schedule|$newYear"))

        // How much room the league has left. A cap that never binds is a
        // number on a screen, and the only way to know is to look.
        val finalRosters = survivors.filter { it.teamId != null }.groupBy { it.teamId!! }
        val capSpace = league.teams.map { t ->
            CapManagement.spaceFor(
                finalRosters[t.id] ?: emptyList(), newYear, (state.deadMoney[t.id.v] ?: 0) / 2)
        }

        // Every contract big enough to be worth cutting, against what the
        // player is actually worth now.
        val bigContracts = survivors
            .filter { it.teamId != null && it.capHit(newYear) > 6_000 }
            .map { p ->
                val worth = pricer.annual(p, sideScheme(p.teamId, p.position), newYear)
                p.teamId!!.v to p.capHit(newYear).toFloat() / worth.coerceAtLeast(1)
            }
        val overpayRatios = bigContracts.map { it.second }

        // The same books per club, so a front office's habits can be read off
        // what it signed rather than guessed from a league-wide average.
        val bigByTeam = bigContracts.groupBy({ it.first }, { it.second })
        val moneyByTeam = league.teams.associate { t ->
            val id = t.id.v
            val fa = auction.signings.filter { it.team == id }
            val kept = extendedSignings.filter { it.team == id }
            val big = bigByTeam[id] ?: emptyList()
            id to TeamMoney(
                faPaid = fa.sumOf { it.value },
                faMarket = fa.sumOf { it.market },
                keptPaid = kept.sumOf { it.value },
                keptMarket = kept.sumOf { it.market },
                keptCount = kept.size,
                bigContracts = big.size,
                overpaid = big.count { it > 1.5f },
                deadMoney = state.deadMoney[id] ?: 0,
                casualties = (releases + valueCuts).count { it.team == id },
            )
        }

        val report = OffseasonReport(
            year = newYear,
            retirementCount = retirements.size,
            retirements = retirements.sortedByDescending { it.overall }.take(20),
            risers = developments.filter { it.delta > 0 }.sortedByDescending { it.delta }.take(15),
            fallers = developments.filter { it.delta < 0 }.sortedBy { it.delta }.take(15),
            draftPicks = draft.picks.take(32),
            signings = signings.sortedByDescending { it.value }.take(20),
            signingCount = signings.size,
            auctionCount = auction.signings.size,
            // Measured over every auction signing, not the twenty the news
            // screen keeps. Computing a statistic from a list truncated for
            // display is how "free agents signed" read exactly 20 every year.
            auctionOverpay = auction.signings
                .filter { it.market > 0 }
                .let { all ->
                    if (all.isEmpty()) 0f
                    else all.sumOf { it.value.toDouble() }
                        .div(all.sumOf { it.market.toDouble() }).toFloat()
                },
            auctionContested = auction.signings.count { it.suitors > 1 },
            meanCapSpace = capSpace.average().toInt(),
            teamsTightOnCap = capSpace.count { it < 10_000 },
            capSpaceByTeam = league.teams.zip(capSpace).associate { (t, s) -> t.id.v to s },
            releases = (releases + valueCuts + auction.upgradeCuts)
                .sortedByDescending { it.overall }.take(20),
            capCasualties = releases.size + valueCuts.size,
            upgradeCount = auction.upgradeCuts.size,
            moneyByTeam = moneyByTeam,
            cutdownCount = state.cutdownCount,
            cutdownFresh = state.cutdownFresh,
            cutdownDeadMoney = state.cutdownDeadMoney,
            wishes = wishes.sortedByDescending { it.overall }.take(25),
            tradeRequests = wishes.count { it.intent == Intent.TRADE_REQUEST },
            trades = trades.sortedByDescending { it.overall }.take(15),
            extensionCount = extendedSignings.size,
            extensionSpend = extendedSignings.sumOf { it.value },
            auctionSpend = auction.signings.sumOf { it.value },
            fillSpend = gapSignings.sumOf { it.value },
            underContract = state.underContract / League.TEAM_COUNT,
            overpaidBy30 = overpayRatios.count { it > 1.3f },
            overpaidBy50 = overpayRatios.count { it > 1.5f },
            overpaidBy70 = overpayRatios.count { it > 1.7f },
            retiredMean = retirements.map { it.overall }.averageOrZero(),
            draftedCount = draft.drafted.size,
            draftedStarters = draft.drafted.values.count {
                overall(it, sideScheme(it.teamId, it.position)) >= 70
            },
            draftedMean = draft.drafted.values
                .map { overall(it, sideScheme(it.teamId, it.position)) }.averageOrZero(),
            developmentNet = if (deltaCount == 0) 0f else deltaSum.toFloat() / deltaCount,
            developmentByAge = ageSum.mapValues { (k, v) -> v.toFloat() / (ageCount[k] ?: 1) },
            developmentByTeam = state.teamDeltaSum.mapValues { (k, v) ->
                v.toFloat() / (state.teamDeltaCount[k] ?: 1)
            },
            youngDevelopmentByTeam = state.teamYoungSum.mapValues { (k, v) ->
                v.toFloat() / (state.teamYoungCount[k] ?: 1)
            },
            yourPicks = draft.picks.filter { it.team == dynasty.userTeam },
        )

        val next = dynasty.copy(
            league = newLeague,
            year = newYear,
            schedule = schedule,
            week = 1,
            phase = DynastyPhase.REGULAR_SEASON,
            results = emptyList(),
            playerStats = emptyMap(),
            playoffs = emptyList(),
            champion = null,
            lastGame = null,
        )
        return next to report
    }

    // ---- extracted steps ---------------------------------------------

    /**
     * Where everyone sits on the depth chart, which is how playing time is
     * inferred.
     *
     * Depth rank, not statistics. Inferring playing time from a stat line
     * gives every offensive lineman zero snaps, so linemen never developed
     * and the whole league's average slid a third of a point a year.
     *
     * Ranked before anyone moves, so it describes the roster as the season
     * ended - which is the roster the snaps were actually taken on.
     */
    private fun stepDepthChart(
        ctx: OffseasonContext,
        state: OffseasonState,
    ): OffseasonState {
        val depthRank: Map<Int, Int> = state.players
            .filter { it.teamId != null }
            .groupBy { it.teamId!! to it.position }
            .flatMap { (key, group) ->
                group.sortedByDescending { overall(it, ctx.scheme(key.first, key.second)) }
                    .mapIndexed { rank, p -> p.id.v to rank }
            }
            .toMap()
        return state.copy(depthRank = depthRank)
    }

    /** SPEC 7 phase 10. Whatever the market left unfilled, at the minimum. */
    private fun stepFillRosters(
        ctx: OffseasonContext,
        state: OffseasonState,
        rng: Rng,
    ): OffseasonState {
        val (players, signings) = fillRosters(
            league = ctx.league,
            players = state.players,
            year = ctx.newYear,
            deadMoney = state.deadMoney,
            scheme = ctx.scheme,
            pricer = state.requirePricer(),
            rng = rng.split("fa|${ctx.newYear}"),
        )
        return state.copy(
            players = players,
            gapSignings = state.gapSignings + signings,
        )
    }

    /** Part of SPEC 7 phase 11. Every roster down to 53. */
    private fun stepRosterLimit(
        ctx: OffseasonContext,
        state: OffseasonState,
    ): OffseasonState {
        val after = enforceRosterLimit(ctx.league, state.players, ctx.newYear, ctx.scheme)
        // A cut at the 53 is still a release: the unamortised bonus and the
        // guaranteed base follow the player onto the books (SPEC 8.1).
        val before = state.players.filter { it.teamId != null }.associateBy { it.id.v }
        val cut = after.filter { it.teamId == null && it.id.v in before }.map { before.getValue(it.id.v) }
        val dead = state.deadMoney.toMutableMap()
        cut.forEach { p ->
            val team = p.teamId!!.v
            dead[team] = (dead[team] ?: 0) + (p.contract?.deadCap(ctx.newYear)?.thisYear ?: 0)
        }
        return state.copy(
            players = after,
            deadMoney = dead,
            cutdownCount = cut.size,
            cutdownDeadMoney = cut.sumOf { it.contract?.deadCap(ctx.newYear)?.thisYear ?: 0 },
            cutdownFresh = cut.count { it.contract?.signedYear == ctx.newYear },
        )
    }

    /** Part of SPEC 7 phase 11. A phone that stops ringing in August. */
    private fun stepResolveUnsigned(
        ctx: OffseasonContext,
        state: OffseasonState,
        rng: Rng,
    ): OffseasonState {
        val (survivors, washedOut) = resolveUnsigned(
            state.players, ctx.newYear, ctx.scheme,
            rng.split("waiver|${ctx.newYear}"))
        return state.copy(
            players = survivors,
            retirements = state.retirements + washedOut,
        )
    }

    /** SPEC 7 phase 6. First call on your own pending free agents. */
    private fun stepReSigning(
        ctx: OffseasonContext,
        state: OffseasonState,
        rng: Rng,
    ): OffseasonState {
        val result = Extensions.run(
            league = ctx.league,
            players = state.players,
            previousTeam = state.previousTeam,
            year = ctx.newYear,
            deadMoney = state.deadMoney,
            scheme = ctx.scheme,
            pricer = state.requirePricer(),
            rng = rng.split("extend|${ctx.newYear}"),
        )
        return state.copy(
            players = result.players,
            extensionSignings = state.extensionSignings + result.signings,
        )
    }

    /** SPEC 7 phase 9. Seven rounds, worst record picking first. */
    private fun stepDraft(
        ctx: OffseasonContext,
        state: OffseasonState,
        rng: Rng,
    ): OffseasonState {
        val nextId = (state.players.maxOfOrNull { it.id.v } ?: 0) + 1
        val prospects = SyntheticDraftClass.generate(
            ctx.newYear, nextId, rng.split("draft|${ctx.newYear}"))
        val order = ctx.league.teams
            .sortedWith(compareBy(
                { ctx.standings.record(it.id).winPct },
                { ctx.standings.record(it.id).pointsFor }))
            .map { it.id }
        val rosterNow = state.players.filter { it.teamId != null }.groupBy { it.teamId!! }
        val draft = DraftRunner.run(
            order = order,
            prospects = prospects,
            schemeFor = { id -> ctx.schemePair(id).first },
            needsFor = { id ->
                TeamNeeds.assess(
                    rosterNow[id] ?: emptyList(),
                    { pos -> ctx.scheme(id, pos) },
                    ctx.newYear)
            },
            year = ctx.newYear,
            rng = rng.split("picks|${ctx.newYear}"),
        )
        val undrafted = draft.undrafted.map {
            it.copy(teamId = null, contract = null, status = PlayerStatus.FREE_AGENT)
        }
        return state.copy(
            players = state.players + draft.drafted.values + undrafted,
            draft = draft,
        )
    }

    /** SPEC 7 phase 7. Ten days of bidding; teams overpay, and that is the point. */
    private fun stepFreeAgency(
        ctx: OffseasonContext,
        state: OffseasonState,
        rng: Rng,
    ): OffseasonState {
        val auction = FreeAgency.run(
            league = ctx.league,
            players = state.players,
            year = ctx.newYear,
            deadMoney = state.deadMoney,
            scheme = ctx.scheme,
            pricer = state.requirePricer(),
            winPct = ctx.winPct,
            rng = rng.split("auction|${ctx.newYear}"),
            previousTeam = state.previousTeam,
        )
        return state.copy(players = auction.players, deadMoney = auction.deadMoney, auction = auction)
    }

    /** Not a SPEC 7 phase - setup the phases after it depend on. */
    private fun stepBuildPricer(
        ctx: OffseasonContext,
        state: OffseasonState,
    ): OffseasonState {
        val rostered = state.players.filter { it.teamId != null }
        val capRosters = rostered.groupBy { it.teamId!! }
        val leagueSpace = ctx.league.teams.sumOf { t ->
            CapManagement.spaceFor(
                capRosters[t.id] ?: emptyList(), ctx.newYear,
                state.deadMoney[t.id.v] ?: 0).toLong()
        }.coerceAtLeast(1L)
        val openSpots = (League.TEAM_COUNT * League.ROSTER_SIZE -
            rostered.size - DraftRunner.ROUNDS * League.TEAM_COUNT).coerceAtLeast(1)
        val marketPool = state.players
            .filter { it.teamId == null }
            .sortedByDescending {
                MarketValue.score(it, ctx.scheme(null, it.position), ctx.newYear) *
                    (ctx.production[it.id.v] ?: 1f)
            }
            .take(openSpots)
        val pricer = MarketValue.pricer(
            rostered = marketPool,
            scheme = { p -> ctx.scheme(p.teamId, p.position) },
            year = ctx.newYear,
            payroll = (leagueSpace * SPEND_SHARE).toLong(),
            cap = CapManagement.capFor(ctx.newYear),
            production = ctx.production,
        )
        return state.copy(pricer = pricer, underContract = rostered.size)
    }

    /** Not a SPEC 7 phase in itself - what players want feeds several. */
    private fun stepPlayerIntent(
        ctx: OffseasonContext,
        state: OffseasonState,
        rng: Rng,
    ): OffseasonState {
        val intentCtx = PlayerIntent.Context(
            winPct = ctx.winPct,
            scheme = ctx.scheme,
            pricer = state.requirePricer(),
            depthRank = state.depthRank,
            year = ctx.newYear,
        )
        val wishes = PlayerIntent.assess(
            ctx.league, state.players, intentCtx, rng.split("wishes|${ctx.newYear}"))
        val (players, deadMoney, trades) = PlayerIntent.resolveTrades(
            ctx.league, state.players, wishes, state.deadMoney, intentCtx,
            rng.split("trades|${ctx.newYear}"))
        return state.copy(
            players = players,
            deadMoney = deadMoney,
            wishes = state.wishes + wishes,
            trades = state.trades + trades,
        )
    }

    /** Part of SPEC 7 phase 4. Judgement, not compliance. */
    private fun stepPruneBadValue(
        ctx: OffseasonContext,
        state: OffseasonState,
    ): OffseasonState {
        val pricer = state.requirePricer()
        val (players, deadMoney, cuts) = CapManagement.pruneBadValue(
            league = ctx.league,
            players = state.players,
            year = ctx.newYear,
            scheme = ctx.scheme,
            price = { p, sch -> pricer.annual(p, sch, ctx.newYear) },
            deadMoney = state.deadMoney,
        )
        return state.copy(
            players = players,
            deadMoney = deadMoney,
            valueCuts = state.valueCuts + cuts,
        )
    }

    /**
     * Part of SPEC 7 phase 4. Deals that ran out do exactly that.
     *
     * Who each expiring player was with is remembered: a team gets first
     * call on its own before the market opens, and that is where most of
     * the money in a real offseason goes.
     */
    private fun stepContractsExpire(
        ctx: OffseasonContext,
        state: OffseasonState,
    ): OffseasonState {
        val previousTeam = mutableMapOf<Int, TeamId>()
        val players = state.players.map { p ->
            val stillUnder = p.contract?.isActive(ctx.newYear) == true
            if (stillUnder) p else {
                p.teamId?.let { previousTeam[p.id.v] = it }
                p.copy(teamId = null, contract = null,
                    status = PlayerStatus.FREE_AGENT, yearsInSystem = 0)
            }
        }
        return state.copy(
            players = players,
            previousTeam = state.previousTeam + previousTeam,
        )
    }

    /**
     * Part of SPEC 7 phase 4, the compliance deadline.
     *
     * Runs before the draft so team needs reflect the roster a team can
     * actually afford rather than the one it wishes it had.
     */
    private fun stepCapCompliance(
        ctx: OffseasonContext,
        state: OffseasonState,
        rng: Rng,
    ): OffseasonState {
        val (players, deadMoney, releases) = CapManagement.enforce(
            ctx.league, state.players, ctx.newYear, ctx.scheme,
            rng.split("cap|${ctx.newYear}"))
        return state.copy(
            players = players,
            deadMoney = deadMoney,
            releases = state.releases + releases,
        )
    }

    /** SPEC 7 phase 3. Age, decline and contract decide who walks away. */
    private fun stepRetirements(
        ctx: OffseasonContext,
        state: OffseasonState,
        rng: Rng,
    ): OffseasonState {
        val retired = mutableListOf<Retirement>()
        val survivors = state.players.filter { p ->
            val ovr = overall(p, ctx.scheme(p.teamId, p.position))
            val retiring = Progression.retires(p, ctx.oldYear, ovr, rng)
            if (retiring) {
                retired += Retirement(p.id.v, p.name, p.position.label, p.age(ctx.oldYear), ovr,
                    reason = "retired")
            }
            !retiring
        }
        return state.copy(
            players = survivors,
            retirements = state.retirements + retired,
        )
    }

    /**
     * Part of SPEC 7 phase 11, though it runs first today.
     *
     * Depth rank, not statistics. Inferring playing time from a stat line
     * gives every offensive lineman zero snaps, so linemen never developed
     * and the whole league's average slid a third of a point a year.
     */
    private fun stepDevelopment(
        ctx: OffseasonContext,
        state: OffseasonState,
        rng: Rng,
    ): OffseasonState {

        val developments = mutableListOf<Development>()
        var deltaSum = 0
        var deltaCount = 0
        val ageSum = mutableMapOf<String, Int>()
        val ageCount = mutableMapOf<String, Int>()
        val teamSum = mutableMapOf<Int, Int>()
        val teamCount = mutableMapOf<Int, Int>()
        val youngSum = mutableMapOf<Int, Int>()
        val youngCount = mutableMapOf<Int, Int>()
        val developed = state.players.map { p ->
            val progCtx = Progression.Context(
                year = ctx.oldYear,
                coaching = coachDevRating(ctx.league, p),
                snaps = snapsFromDepth(p, state.depthRank[p.id.v]),
            )
            val change = Progression.progress(p, progCtx, rng)
            deltaSum += change.delta
            deltaCount++
            val bracket = ageBracket(p.age(ctx.oldYear))
            ageSum[bracket] = (ageSum[bracket] ?: 0) + change.delta
            ageCount[bracket] = (ageCount[bracket] ?: 0) + 1
            p.teamId?.let {
                teamSum[it.v] = (teamSum[it.v] ?: 0) + change.delta
                teamCount[it.v] = (teamCount[it.v] ?: 0) + 1
                // Coaching only touches players who are still growing - SPEC
                // 7.1 leaves it out of the decline branch entirely. Mixing the
                // two hides the coaching signal under roster age.
                if (p.age(ctx.oldYear) <= 24) {
                    youngSum[it.v] = (youngSum[it.v] ?: 0) + change.delta
                    youngCount[it.v] = (youngCount[it.v] ?: 0) + 1
                }
            }
            if (kotlin.math.abs(change.delta) >= 4 || change.note != null) {
                developments += Development(
                    p.id.v, p.name, p.position.label, change.delta, change.note)
            }
            change.player
        }

        return state.copy(
            players = developed,
            developments = state.developments + developments,
            deltaSum = state.deltaSum + deltaSum,
            deltaCount = state.deltaCount + deltaCount,
            ageSum = ageSum,
            ageCount = ageCount,
            teamDeltaSum = teamSum,
            teamDeltaCount = teamCount,
            teamYoungSum = youngSum,
            teamYoungCount = youngCount,
        )
    }

    /** Rating points a million of dead money is worth when choosing who to cut. */
    private const val DEAD_MONEY_WEIGHT = 1f

    /**
     * Cuts every roster to 53, releasing the worst players in scheme terms.
     *
     * Filling rosters without cutting them let teams carry sixty players -
     * everyone under contract, plus seven draft picks, plus whatever free
     * agency added. A roster limit is what makes the draft a decision.
     *
     * Ability alone released guaranteed rookies to keep slightly better
     * minimum veterans. A player the club pays either way costs nothing
     * extra to keep, so his dead money counts in his favour.
     */
    internal fun enforceRosterLimit(
        league: League,
        players: List<Player>,
        year: Int,
        scheme: (TeamId?, Position) -> Scheme,
    ): List<Player> {
        val byTeam = players.filter { it.teamId != null }.groupBy { it.teamId!! }
        val released = mutableListOf<Player>()
        val kept = mutableListOf<Player>()

        league.teams.forEach { team ->
            val roster = byTeam[team.id] ?: emptyList()
            if (roster.size <= ROSTER_LIMIT) { kept += roster; return@forEach }

            fun keepValue(p: Player) = rosterValue(p, scheme(team.id, p.position), year) +
                (p.contract?.deadCap(year)?.thisYear ?: 0) / 1_000f * DEAD_MONEY_WEIGHT

            // Protect the positional minimums first, then keep the best of the
            // rest - otherwise a team cuts its only long snapper to keep a
            // seventh receiver.
            val protectedIds = mutableSetOf<Int>()
            TeamNeeds.ROSTER_TEMPLATE.forEach { (position, required) ->
                roster.filter { it.position == position }
                    .sortedByDescending { keepValue(it) }
                    .take(required)
                    .forEach { protectedIds += it.id.v }
            }

            val core = roster.filter { it.id.v in protectedIds }
            val fringe = roster.filter { it.id.v !in protectedIds }
                .sortedByDescending { keepValue(it) }

            val room = (ROSTER_LIMIT - core.size).coerceAtLeast(0)
            kept += core + fringe.take(room)
            released += fringe.drop(room).map {
                it.copy(teamId = null, contract = null, status = PlayerStatus.FREE_AGENT)
            }
        }

        return kept + released + players.filter { it.teamId == null }
    }

    /**
     * Works out what happens to everyone nobody signed.
     *
     * A veteran who clears waivers and gets no calls retires - that is how the
     * majority of careers end, and it should read as a retirement in the news
     * rather than a player quietly vanishing from the league. A young player
     * in the same position waits, because somebody always needs a body in camp.
     *
     * Whatever is left after that is capped, so the wire stays a wire.
     */
    private fun resolveUnsigned(
        players: List<Player>,
        year: Int,
        scheme: (TeamId?, Position) -> Scheme,
        rng: Rng,
    ): Pair<List<Player>, List<Retirement>> {
        val rostered = players.filter { it.teamId != null }
        val unsigned = players.filter { it.teamId == null }
        val retirements = mutableListOf<Retirement>()

        val remaining = unsigned.filter { p ->
            val ovr = overall(p, scheme(null, p.position))
            val age = p.age(year)

            val byAge = when {
                age >= 33 -> 0.88f
                age >= 31 -> 0.70f
                age >= 29 -> 0.46f
                age >= 27 -> 0.26f
                age >= 25 -> 0.13f
                else -> 0.05f
            }
            // A player who can still play waits for the phone. A replacement
            // level thirty year old already knows.
            val quality = ((70 - ovr) / 70f).coerceIn(-0.30f, 0.30f)
            val stubbornness = (p.traits.workEthic - 50) / 400f

            val chance = (byAge * (1f + quality) - stubbornness).coerceIn(0.01f, 0.97f)
            val walks = rng.nextFloat() < chance

            if (walks) {
                retirements += Retirement(
                    p.id.v, p.name, p.position.label, age, ovr,
                    reason = if (age >= 30) "released, did not sign" else "washed out",
                )
            }
            !walks
        }

        // Anyone still unsigned after that stays available, up to a limit.
        val pool = remaining
            .sortedByDescending {
                overall(it, scheme(null, it.position)) - (it.age(year) - 26).coerceAtLeast(0) * 2
            }
            .take(FREE_AGENT_POOL)

        return (rostered + pool) to retirements
    }

    private const val ROSTER_LIMIT = 53

    /** Roughly eight per team, which is about what a real wire holds. */
    /** Brackets match the age histogram in the CLI health check. */
    fun ageBracket(age: Int): String = when {
        age <= 24 -> "21-24"
        age <= 27 -> "25-27"
        age <= 30 -> "28-30"
        else -> "31+"
    }

    private fun List<Int>.averageOrZero(): Float =
        if (isEmpty()) 0f else sum().toFloat() / size

    /**
     * How much of its cap space the league commits in a single offseason.
     * Not all of it: teams keep room for the season's injuries and for the
     * extensions they will hand their own players in the spring.
     */
    private const val SPEND_SHARE = 0.85f

    private const val FREE_AGENT_POOL = 260

    /** Overall a street free agent is generated at. */
    private const val CAMP_BODY = 55

    /**
     * Signs free agents until every roster is legal, best fit first.
     *
     * A full market with competing offers and reservation prices is SPEC 8.3
     * and comes next; this is the part that has to exist for a second season
     * to be playable at all.
     */
    private fun fillRosters(
        league: League,
        players: List<Player>,
        year: Int,
        deadMoney: Map<Int, Int>,
        scheme: (TeamId?, Position) -> Scheme,
        pricer: MarketValue.Pricer,
        rng: Rng,
    ): Pair<List<Player>, List<Signing>> {
        val roster = players.filter { it.teamId != null }
            .groupBy { it.teamId!! }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
        val freeAgents = players.filter { it.teamId == null }.toMutableList()
        val signings = mutableListOf<Signing>()
        var nextId = (players.maxOfOrNull { it.id.v } ?: 0) + 1

        // Worst teams pick first, same as the draft - it keeps the league from
        // pooling every spare player on the same three rosters.
        val order = league.teams.map { it.id }.shuffled(rng)

        TeamNeeds.ROSTER_TEMPLATE.forEach { (position, required) ->
            order.forEach { teamId ->
                val current = roster.getOrPut(teamId) { mutableListOf() }
                var have = current.count { it.position == position }
                while (have < required) {
                    val candidates = freeAgents.filter { it.position == position }
                    val best = candidates.maxByOrNull { p ->
                        rosterValue(p, scheme(teamId, position), year) + rng.gaussian(0f, 3f)
                    }

                    // A league genuinely runs out of long snappers - only 32
                    // exist and a draft class rarely has one. Rather than
                    // leave a team without a position it has to field, sign a
                    // camp body: replacement level, minimum money, exactly the
                    // player a real team signs off the street in August.
                    val pick = best ?: PlayerGenerator.generate(
                        id = PlayerId(nextId++),
                        position = position,
                        targetOverall = CAMP_BODY + rng.nextInt(7),
                        year = year,
                        rng = rng,
                        teamId = null,
                        ageBias = -1,
                    )

                    freeAgents.remove(pick)

                    // What he is worth, or what the team can afford, whichever
                    // is less. A capped-out team fills its roster with minimum
                    // deals, which is exactly how a good roster gets thin.
                    val worth = pricer.annual(pick, scheme(teamId, position), year)
                    val space = CapManagement.spaceFor(current, year, deadMoney[teamId.v] ?: 0)
                    val value = when {
                        space <= Contract.MIN_BASE_SALARY * 2 -> Contract.MIN_BASE_SALARY
                        else -> worth.coerceAtMost((space / 3).coerceAtLeast(Contract.MIN_BASE_SALARY))
                    }

                    val signed = pick.copy(
                        teamId = teamId,
                        status = PlayerStatus.ACTIVE,
                        yearsInSystem = 0,
                        contract = Contract.of(
                            years = if (value > 12_000) 4 else if (value > 5_000) 3 else 2,
                            totalValue = value * 3,
                            signedYear = year,
                        ),
                    )
                    current += signed
                    signings += Signing(signed.id.v, signed.name, position.label, teamId.v, value)
                    have++
                }
            }
        }

        // Anyone still on the street stays a free agent, available in season.
        val assigned = roster.values.flatten()
        val stillFree = freeAgents.map { it.copy(teamId = null, status = PlayerStatus.FREE_AGENT) }
        return (assigned + stillFree) to signings
    }

    /**
     * How well a player's coaches develop him: his position coach mostly,
     * his head coach some - the head coach sets the culture, but it is the
     * position coach who runs his individual drills every day.
     *
     * A free agent between teams and a camp body with no staff assigned yet
     * get a league-average guess rather than a hole in the calculation.
     */
    private fun coachDevRating(league: League, player: Player): Int {
        val staff = player.teamId?.let { league.team(it).staff } ?: return DEFAULT_COACHING
        val positionDev = staff.positionCoaches[player.position.group]
            ?.let { league.coaches[it] }?.ratings?.development
        val headDev = league.coaches[staff.headCoach]?.ratings?.development
        return when {
            positionDev != null && headDev != null -> (positionDev * 0.65f + headDev * 0.35f).toInt()
            positionDev != null -> positionDev
            headDev != null -> headDev
            else -> DEFAULT_COACHING
        }
    }

    /** Tracks the generator's mean, so an unattached player is not quietly penalised. */
    private const val DEFAULT_COACHING = 65

    /**
     * Playing time from where a player sits on the depth chart.
     *
     * A starting guard takes a thousand snaps and records no statistics at
     * all, so a stat line is the wrong place to look. Rank is.
     */
    private fun snapsFromDepth(player: Player, rank: Int?): Int {
        if (player.teamId == null) return 0
        val starters = TeamNeeds.requiredStarters(player.position)
        return when {
            rank == null -> 120
            rank < starters -> 950
            rank < starters + 1 -> 420
            rank < starters + 2 -> 190
            else -> 70
        }
    }
}
