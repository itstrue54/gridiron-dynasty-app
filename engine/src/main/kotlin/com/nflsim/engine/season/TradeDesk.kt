package com.nflsim.engine.season

import com.nflsim.engine.econ.MarketValue
import com.nflsim.engine.model.League
import com.nflsim.engine.narrative.Banter
import com.nflsim.engine.model.PickAsset
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.model.Transaction
import com.nflsim.engine.model.TransactionKind
import com.nflsim.engine.offseason.CapManagement
import com.nflsim.engine.offseason.PickTrade
import com.nflsim.engine.offseason.PickValue
import com.nflsim.engine.offseason.TradeMove
import com.nflsim.engine.offseason.rosterValue
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall

/**
 * The user's trades (SPEC 8.4): players and picks, both ways, with any club.
 * The other club answers as the league's clubs answer each other
 * (ContenderTrades): everything valued on its own timeline, players by
 * rosterValue at its win now and picks by the chart tilted the same way, and
 * a deal only when it comes out ahead by the seller's margin. Both clubs
 * must stay under the cap and within their roster limit once the contracts
 * and the dead money have moved.
 *
 * The desk works on a [Book] - who is where, the picks, the dead money -
 * so the same rules serve the season (to the deadline) and the offseason (at
 * the draft room). Contracts move as they stand; each club eats the
 * unamortised bonus of the players it sends away (ADR-010).
 */
object TradeDesk {

    /** Trades are open until this week's games kick off: the NFL's deadline falls after week 9. */
    const val DEADLINE_WEEK = 10

    /** Everything a trade reads and moves. */
    data class Book(
        val league: League,
        /** Everyone and where they are. */
        val players: List<Player>,
        val picks: List<PickAsset>,
        /** Dead money each club already carries this year, by team id. */
        val deadMoney: Map<Int, Int>,
        /** The cap year. */
        val year: Int,
        /** The next draft, and where its picks are expected to fall. */
        val draftYear: Int,
        val order: List<TeamId>,
        /** Most men a club may have on its roster, not counting reserve: 53 in the season, 90 out of it. */
        val rosterLimit: Int,
        val scheme: (TeamId, Position) -> Scheme,
    )

    /** What the user sends and what he asks for, with [partner]. */
    data class Proposal(
        val partner: TeamId,
        val give: Set<Int> = emptySet(),
        val get: Set<Int> = emptySet(),
        val givePicks: List<PickAsset> = emptyList(),
        val getPicks: List<PickAsset> = emptyList(),
    ) {
        val empty: Boolean get() = give.isEmpty() && get.isEmpty() && givePicks.isEmpty() && getPicks.isEmpty()
    }

    /**
     * The other club's answer: yes or no, why, and how far short the offer
     * is - in the same value it weighs everything in, [shortBy] zero when it
     * says yes.
     */
    data class Verdict(
        val accepted: Boolean,
        val reasons: List<String>,
        val theyGet: Float,
        val theyGive: Float,
        val shortBy: Float,
        /** What stands in the way first, for the other GM to say: NONE when it says yes. */
        val snag: Snag = Snag.NONE,
    )

    /** What stops a deal: nothing; an offer that can't be made; the roster limit; the cap; or the value. */
    enum class Snag { NONE, INVALID, ROSTER, CAP, VALUE }

    /** A trade made: the book after it, and what it put on the wire and in the report. */
    data class Made(
        val book: Book,
        val wire: List<Transaction>,
        val moves: List<TradeMove>,
        val pickTrades: List<PickTrade>,
    )

    /** What [club] makes of [p]: his worth to its roster on its timeline, past a replacement's. */
    fun value(book: Book, p: Player, club: Team): Float =
        (rosterValue(p, book.scheme(club.id, p.position), book.year, club.gm.winNowVsFuture, book.league.tuning.ai) - MarketValue.REPLACEMENT)
            .coerceAtLeast(0f)

    fun value(book: Book, pick: PickAsset, club: Team): Float =
        PickValue.value(pick, book.draftYear, book.order, club.gm.winNowVsFuture, book.league.tuning.trades)

    fun evaluate(book: Book, user: TeamId, proposal: Proposal): Verdict {
        val partner = book.league.team(proposal.partner)
        val reasons = mutableListOf<String>()
        val byId = book.players.associateBy { it.id.v }
        val give = proposal.give.mapNotNull { byId[it] }
        val get = proposal.get.mapNotNull { byId[it] }

        if (proposal.partner == user) reasons += "A club can't trade with itself."
        if (proposal.empty) reasons += "Nothing is on the table."
        if (give.size != proposal.give.size || give.any { it.teamId != user }) reasons += "You can only send players you have."
        if (get.size != proposal.get.size || get.any { it.teamId != proposal.partner }) reasons += "You can only ask for their players."
        if (proposal.givePicks.any { it !in book.picks || it.owner != user.v }) reasons += "You can only send picks you hold."
        if (proposal.getPicks.any { it !in book.picks || it.owner != proposal.partner.v }) reasons += "You can only ask for picks they hold."
        (give + get).filter { it.status == PlayerStatus.PRACTICE_SQUAD }.forEach {
            reasons += "${it.name} is on a practice squad, not a roster: he can be signed, not traded."
        }
        if (reasons.isNotEmpty()) return Verdict(false, reasons, 0f, 0f, 0f, Snag.INVALID)

        // Roster limits: men on the roster, not on reserve.
        fun onRoster(p: Player) = p.status == PlayerStatus.ACTIVE
        fun count(team: TeamId) = book.players.count { it.teamId == team && onRoster(it) }
        val userAfter = count(user) - give.count(::onRoster) + get.count(::onRoster)
        val partnerAfter = count(proposal.partner) - get.count(::onRoster) + give.count(::onRoster)
        if (userAfter > book.rosterLimit) reasons += "You'd have $userAfter on the roster, over the ${book.rosterLimit}. Make room first."
        if (partnerAfter > book.rosterLimit) reasons += "${partner.name} would have $partnerAfter on the roster, over the ${book.rosterLimit}."
        val rosterSnag = reasons.isNotEmpty()

        // The cap, with the contracts and the dead money moved.
        fun room(team: TeamId, out: List<Player>, inn: List<Player>): Int {
            val roster = book.players.filter { it.teamId == team && it !in out } + inn
            val dead = (book.deadMoney[team.v] ?: 0) + out.sumOf { it.contract?.deadCap(book.year)?.thisYear ?: 0 }
            return CapManagement.spaceFor(roster, book.year, dead, carryover = book.league.team(team).finances.carryover)
        }
        fun roomNow(team: TeamId) = room(team, emptyList(), emptyList())
        val userRoom = room(user, give, get)
        val partnerRoom = room(proposal.partner, get, give)
        // A club already over the cap may stay there, but not go further over.
        if (userRoom < minOf(roomNow(user), 0)) reasons += "You'd be ${money(-userRoom)} over the cap."
        if (partnerRoom < minOf(roomNow(proposal.partner), 0)) reasons += "${partner.name} would be ${money(-partnerRoom)} over the cap."
        val capSnag = reasons.isNotEmpty() && !rosterSnag

        // What it's worth to them.
        val theyGet = give.sumOf { value(book, it, partner).toDouble() }.toFloat() +
            proposal.givePicks.sumOf { value(book, it, partner).toDouble() }.toFloat()
        val theyGive = get.sumOf { value(book, it, partner).toDouble() }.toFloat() +
            proposal.getPicks.sumOf { value(book, it, partner).toDouble() }.toFloat()
        val wants = theyGive * (1f + book.league.tuning.ai.tradeSellerMargin)
        val shortBy = (wants - theyGet).coerceAtLeast(0f)
        if (shortBy > 0f) reasons += "${partner.name} want more for it."
        val snag = when {
            rosterSnag -> Snag.ROSTER
            capSnag -> Snag.CAP
            shortBy > 0f -> Snag.VALUE
            else -> Snag.NONE
        }
        return Verdict(reasons.isEmpty(), reasons, theyGet, theyGive, if (reasons.isEmpty()) 0f else shortBy, snag)
    }

    /**
     * What the other club's GM says to [verdict] (SPEC 10.4): yes; the
     * roster or the cap; nothing in it for them; short of their margin only
     * (close); or short of even what they give (far). Null for an offer
     * that can't be made at all - that is the rules talking, not the GM.
     */
    fun answer(book: Book, user: TeamId, proposal: Proposal, verdict: Verdict): Banter.Quote? {
        val partner = book.league.team(proposal.partner)
        val tone = Banter.tone(partner)
        val key = when (verdict.snag) {
            Snag.INVALID -> return null
            Snag.NONE -> "gm.trade.yes.$tone"
            Snag.ROSTER -> "gm.trade.roster"
            Snag.CAP -> "gm.trade.cap"
            Snag.VALUE -> when {
                verdict.theyGet <= 0f -> "gm.trade.nothing"
                verdict.theyGet >= verdict.theyGive -> "gm.trade.close.$tone"
                else -> "gm.trade.far.$tone"
            }
        }
        return Banter.gm(book.league.seed, partner, key, context(proposal), "club" to book.league.team(user).nickname)
    }

    /** What the other club's GM says once [proposal] is made. */
    fun farewell(book: Book, user: TeamId, proposal: Proposal): Banter.Quote {
        val partner = book.league.team(proposal.partner)
        return Banter.gm(book.league.seed, partner, "gm.trade.done.${Banter.tone(partner)}", context(proposal),
            "club" to book.league.team(user).nickname)
    }

    /** A proposal as words to draw from: the same table, the same answer. */
    private fun context(p: Proposal): String =
        "${p.give.sorted()}|${p.get.sorted()}|${p.givePicks.map { "${it.year}.${it.round}.${it.original}" }.sorted()}|" +
            "${p.getPicks.map { "${it.year}.${it.round}.${it.original}" }.sorted()}"

    /** Makes [proposal] if the other club says yes; null if it doesn't. */
    fun make(book: Book, user: TeamId, proposal: Proposal, week: Int): Made? {
        if (!evaluate(book, user, proposal).accepted) return null
        val partner = proposal.partner
        val sentBy = mutableMapOf<Int, TeamId>()
        proposal.give.forEach { sentBy[it] = user }
        proposal.get.forEach { sentBy[it] = partner }
        val dead = book.deadMoney.toMutableMap()
        val wire = mutableListOf<Transaction>()
        val moves = mutableListOf<TradeMove>()
        val players = book.players.map { p ->
            val from = sentBy[p.id.v] ?: return@map p
            val to = if (from == user) partner else user
            val owed = p.contract?.deadCap(book.year)?.thisYear ?: 0
            dead[from.v] = (dead[from.v] ?: 0) + owed
            val moved = p.copy(teamId = to, yearsInSystem = 0, yearsWithClub = 0)
            wire += Transaction.of(book.year, week, TransactionKind.TRADED, to, moved, other = from)
            moves += TradeMove(p.id.v, p.name, p.position.label, from.v, to.v,
                overall(p, book.scheme(to, p.position)), owed, REASON)
            moved
        }
        val pickTrades = mutableListOf<PickTrade>()
        val picks = book.picks.map { pick ->
            when (pick) {
                in proposal.givePicks -> pick.copy(owner = partner.v).also {
                    pickTrades += PickTrade(pick.year, pick.round, pick.original, user.v, partner.v, REASON)
                }
                in proposal.getPicks -> pick.copy(owner = user.v).also {
                    pickTrades += PickTrade(pick.year, pick.round, pick.original, partner.v, user.v, REASON)
                }
                else -> pick
            }
        }
        return Made(book.copy(players = players, picks = picks, deadMoney = dead), wire, moves, pickTrades)
    }

    /** The book for a league in the regular season: the next draft is next year's, placed by the standings so far. */
    fun inSeason(dynasty: Dynasty): Book {
        val league = dynasty.league
        val standings = dynasty.standings()
        val order = com.nflsim.engine.offseason.Picks.draftOrder(
            league.teams.map { it.id }, { id -> standings.record(id).winPct }, dynasty.results, dynasty.playoffs)
        return Book(
            league = league,
            players = league.players,
            picks = league.picks,
            deadMoney = league.teams.associate { it.id.v to it.finances.deadMoney },
            year = league.year,
            draftYear = league.year + 1,
            order = order,
            rosterLimit = Transactions.ROSTER_LIMIT,
            scheme = { team, pos -> schemeFor(league, team, pos) },
        )
    }

    /** Whether the user can trade now: the regular season, before the deadline. */
    fun open(dynasty: Dynasty): Boolean =
        dynasty.phase == DynastyPhase.REGULAR_SEASON && dynasty.week <= DEADLINE_WEEK

    /** Makes the trade in a dynasty in the season: the players, their clubs' rosters and dead money, the picks and the wire. */
    fun makeInSeason(dynasty: Dynasty, proposal: Proposal): Pair<Dynasty, Made>? {
        if (!open(dynasty)) return null
        val made = make(inSeason(dynasty), dynasty.userTeamId, proposal, dynasty.week) ?: return null
        val moved = (proposal.give + proposal.get).map { com.nflsim.engine.model.PlayerId(it) }.toSet()
        val next = settle(dynasty.league, made.book.players, moved, made.book.picks, made.book.deadMoney, made.wire)
        return dynasty.copy(league = next) to made
    }

    /**
     * A league in the season after trades: [players] as they now stand, the
     * [moved] men off their old clubs' rosters and onto their new ones, the
     * picks and each club's dead money as the trades left them, and the wire.
     */
    internal fun settle(
        league: League,
        players: List<Player>,
        moved: Set<com.nflsim.engine.model.PlayerId>,
        picks: List<PickAsset>,
        deadMoney: Map<Int, Int>,
        wire: List<Transaction>,
    ): League {
        val now = players.associateBy { it.id }
        val teams = league.teams.map { team ->
            val out = team.roster.filter { it in moved && now[it]?.teamId != team.id }.toSet()
            val inn = moved.filter { now[it]?.teamId == team.id && it !in team.roster }
            if (out.isEmpty() && inn.isEmpty()) team
            else team.copy(
                roster = team.roster.filterNot { it in out } + inn,
                finances = team.finances.copy(deadMoney = deadMoney[team.id.v] ?: team.finances.deadMoney),
            )
        }
        return league.copy(players = players, picks = picks, teams = teams).logged(*wire.toTypedArray())
    }

    private fun schemeFor(league: League, team: TeamId, pos: Position): Scheme {
        val t = league.team(team)
        return com.nflsim.engine.ratings.SchemeCatalog.tuned(if (pos.isOffense) t.offenseScheme else t.defenseScheme, league.tuning)
    }

    private const val REASON = "trade"

    private fun money(thousands: Int): String =
        if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)
}
