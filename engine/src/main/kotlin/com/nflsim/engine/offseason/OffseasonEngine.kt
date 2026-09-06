package com.nflsim.engine.offseason

import com.nflsim.engine.gen.PlayerGenerator
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
data class Signing(val player: Int, val name: String, val position: String, val team: Int, val value: Int)

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
    /** Every free agent signed, not just the twenty the news screen lists. */
    val signingCount: Int = 0,
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
)

/**
 * The year turning over.
 *
 * Order matters and is not arbitrary: players retire before anyone develops
 * (a 38 year old should not gain a point on his way out), contracts expire
 * before the draft so needs are honest, and rosters are filled after the draft
 * so a team that took a corner in round one is not still shopping for one.
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

        // ---- 1. retirements -----------------------------------------
        val retirements = mutableListOf<Retirement>()
        val afterRetirement = league.players.filter { p ->
            val ovr = overall(p, sideScheme(p.teamId, p.position))
            val retiring = Progression.retires(p, oldYear, ovr, rng)
            if (retiring) {
                retirements += Retirement(p.id.v, p.name, p.position.label, p.age(oldYear), ovr,
                    reason = "retired")
            }
            !retiring
        }

        // ---- 2. development -----------------------------------------
        // Depth rank, not statistics. Inferring playing time from a stat line
        // gives every offensive lineman zero snaps, so linemen never developed
        // and the whole league's average slid a third of a point a year.
        val depthRank: Map<Int, Int> = afterRetirement
            .filter { it.teamId != null }
            .groupBy { it.teamId!! to it.position }
            .flatMap { (key, group) ->
                group.sortedByDescending { overall(it, sideScheme(key.first, key.second)) }
                    .mapIndexed { rank, p -> p.id.v to rank }
            }
            .toMap()

        val developments = mutableListOf<Development>()
        var deltaSum = 0
        var deltaCount = 0
        val ageSum = mutableMapOf<String, Int>()
        val ageCount = mutableMapOf<String, Int>()
        val developed = afterRetirement.map { p ->
            val ctx = Progression.Context(
                year = oldYear,
                coaching = 55 + (p.teamId?.v ?: 0) % 25,
                snaps = snapsFromDepth(p, depthRank[p.id.v]),
            )
            val change = Progression.progress(p, ctx, rng)
            deltaSum += change.delta
            deltaCount++
            val bracket = ageBracket(p.age(oldYear))
            ageSum[bracket] = (ageSum[bracket] ?: 0) + change.delta
            ageCount[bracket] = (ageCount[bracket] ?: 0) + 1
            if (kotlin.math.abs(change.delta) >= 4 || change.note != null) {
                developments += Development(
                    p.id.v, p.name, p.position.label, change.delta, change.note)
            }
            change.player
        }

        // ---- 3. contracts expire ------------------------------------
        val afterContracts = developed.map { p ->
            val stillUnder = p.contract?.isActive(newYear) == true
            if (stillUnder) p else p.copy(teamId = null, contract = null,
                status = PlayerStatus.FREE_AGENT, yearsInSystem = 0)
        }

        // ---- 4. get under the cap -----------------------------------
        // Before the draft, so team needs reflect the roster a team can
        // actually afford rather than the one it wishes it had.
        val (afterCap, deadMoney, releases) = CapManagement.enforce(
            league, afterContracts, newYear, ::sideScheme, rng.split("cap|$newYear"))

        // ---- 5. the draft -------------------------------------------
        val nextId = (afterCap.maxOfOrNull { it.id.v } ?: 0) + 1
        val prospects = SyntheticDraftClass.generate(newYear, nextId, rng.split("draft|$newYear"))

        val standings = Standings(league, dynasty.results, rng.split("order|$newYear"))
        val draftOrder = league.teams
            .sortedWith(compareBy({ standings.record(it.id).winPct }, { standings.record(it.id).pointsFor }))
            .map { it.id }

        val rosterNow = afterCap.filter { it.teamId != null }.groupBy { it.teamId!! }
        val draft = DraftRunner.run(
            order = draftOrder,
            prospects = prospects,
            schemeFor = { id -> schemeFor(id).first },
            needsFor = { id ->
                TeamNeeds.assess(rosterNow[id] ?: emptyList(), { pos -> sideScheme(id, pos) }, newYear)
            },
            year = newYear,
            rng = rng.split("picks|$newYear"),
        )

        val afterDraft = afterCap + draft.drafted.values
        val undraftedPool = draft.undrafted.map {
            it.copy(teamId = null, contract = null, status = PlayerStatus.FREE_AGENT)
        }

        // ---- 6. fill the rosters ------------------------------------
        val (filled, signings) = fillRosters(
            league = league,
            players = afterDraft + undraftedPool,
            year = newYear,
            deadMoney = deadMoney,
            scheme = ::sideScheme,
            rng = rng.split("fa|$newYear"),
        )

        // ---- 7. cut to the limit ------------------------------------
        val trimmed = enforceRosterLimit(league, filled, newYear, ::sideScheme)

        // ---- 8. players who did not catch on ------------------------
        // Getting cut and not signing anywhere is how most careers actually
        // end - not with a decision in February but with a phone that stops
        // ringing in August. Older players take the hint; younger ones hang
        // around the practice squad circuit and wait.
        val (survivors, washedOut) = resolveUnsigned(
            trimmed, newYear, ::sideScheme, rng.split("waiver|$newYear"))
        retirements += washedOut

        // ---- 9. rebuild ---------------------------------------------
        val byTeam = survivors.filter { it.teamId != null }.groupBy { it.teamId!! }
        val teams: List<Team> = league.teams.map { t ->
            t.copy(
                roster = (byTeam[t.id] ?: emptyList()).map { it.id },
                finances = t.finances.copy(
                    salaryCap = CapManagement.capFor(newYear),
                    // Dead money is carried forward: a cut you make this year
                    // is still on the books next year, which is what makes a
                    // bad contract hurt for seasons rather than one afternoon.
                    deadMoney = (deadMoney[t.id.v] ?: 0) / 2,
                ),
            )
        }
        val newLeague = league.copy(year = newYear, teams = teams, players = survivors)

        val schedule = ScheduleGenerator.generate(
            newLeague, newYear, rng.split("schedule|$newYear"))

        val report = OffseasonReport(
            year = newYear,
            retirementCount = retirements.size,
            retirements = retirements.sortedByDescending { it.overall }.take(20),
            risers = developments.filter { it.delta > 0 }.sortedByDescending { it.delta }.take(15),
            fallers = developments.filter { it.delta < 0 }.sortedBy { it.delta }.take(15),
            draftPicks = draft.picks.take(32),
            signings = signings.sortedByDescending { it.value }.take(20),
            signingCount = signings.size,
            releases = releases.sortedByDescending { it.overall }.take(20),
            capCasualties = releases.size,
            retiredMean = retirements.map { it.overall }.averageOrZero(),
            draftedCount = draft.drafted.size,
            draftedStarters = draft.drafted.values.count {
                overall(it, sideScheme(it.teamId, it.position)) >= 70
            },
            draftedMean = draft.drafted.values
                .map { overall(it, sideScheme(it.teamId, it.position)) }.averageOrZero(),
            developmentNet = if (deltaCount == 0) 0f else deltaSum.toFloat() / deltaCount,
            developmentByAge = ageSum.mapValues { (k, v) -> v.toFloat() / (ageCount[k] ?: 1) },
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

    /**
     * Cuts every roster to 53, releasing the worst players in scheme terms.
     *
     * Filling rosters without cutting them let teams carry sixty players -
     * everyone under contract, plus seven draft picks, plus whatever free
     * agency added. A roster limit is what makes the draft a decision.
     */
    private fun enforceRosterLimit(
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

            // Protect the positional minimums first, then keep the best of the
            // rest - otherwise a team cuts its only long snapper to keep a
            // seventh receiver.
            val protectedIds = mutableSetOf<Int>()
            TeamNeeds.ROSTER_TEMPLATE.forEach { (position, required) ->
                roster.filter { it.position == position }
                    .sortedByDescending { rosterValue(it, scheme(team.id, position), year) }
                    .take(required)
                    .forEach { protectedIds += it.id.v }
            }

            val core = roster.filter { it.id.v in protectedIds }
            val fringe = roster.filter { it.id.v !in protectedIds }
                .sortedByDescending { rosterValue(it, scheme(team.id, it.position), year) }

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
    /**
     * How a front office ranks a player for a roster spot: what he is now, how
     * well he fits, and how much of him is left.
     *
     * Two players of equal ability are not equal to a team - the younger one is
     * cheaper, has upside, and is not about to fall off. Without this term the
     * league ages a year every eight seasons: a declining thirty-two year old
     * still outrates a rookie, so he keeps the roster spot, keeps losing three
     * points a year, and never reaches the free agency that would end his
     * career. Rosters skew young because of decisions like this one, not
     * because players spontaneously retire.
     */
    private fun rosterValue(player: Player, scheme: Scheme, year: Int): Float =
        overall(player, scheme) + schemeFit(player, scheme) * 8f -
            (player.age(year) - AGE_CLIFF).coerceAtLeast(0) * AGE_PENALTY

    /** Age past which a team starts discounting a player. */
    private const val AGE_CLIFF = 29

    /** Rating points of discount per year past the cliff. */
    private const val AGE_PENALTY = 2.2f

    /** Brackets match the age histogram in the CLI health check. */
    fun ageBracket(age: Int): String = when {
        age <= 24 -> "21-24"
        age <= 27 -> "25-27"
        age <= 30 -> "28-30"
        else -> "31+"
    }

    private fun List<Int>.averageOrZero(): Float =
        if (isEmpty()) 0f else sum().toFloat() / size

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
                    val worth = TeamNeeds.marketValue(pick, scheme(teamId, position), year)
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
