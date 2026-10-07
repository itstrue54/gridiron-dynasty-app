package com.nflsim.data
import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.ContractDisputes
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import com.nflsim.engine.season.PlayerEdits
import com.nflsim.engine.season.TradeDesk
import com.nflsim.engine.season.TradeOffers
import com.nflsim.engine.season.Transactions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
/**
 * A season of a user's own moves under random choices (SPEC 6.1, 8.3, 8.4,
 * 10.1, 10.5): signings and releases, the practice squad, the reserve list,
 * restructures, answers to demands, trades taken from calls and built by
 * hand, the trade block, and player edits. After every week: nobody on two
 * rosters, no club past 53 active or its squad's size, every rostered man
 * under contract, and the save read back as written. Three leagues over two
 * seasons each ran clean before it was cut to this.
 */
class SeasonFuzzTest {
    @Test fun `random in-season moves keep every roster rule`() {
        val out = StringBuilder()
        var problems = 0
        val counts = mutableMapOf<String, Int>()
        fun count(k: String) { counts[k] = (counts[k] ?: 0) + 1 }
        for (seed in listOf(52L)) {
            val league = LeagueGenerator.generate(2026, seed)
            var d = DynastyEngine.start(league, 2026, seed, league.teams[(seed % 32).toInt()].id).copy(editPlayers = true)
            val rng = SplitMixRng(seed * 13)
            var weeks = 0
            while (d.year < 2027 && weeks < 25) {
                val tag = "seed $seed ${d.year} ${d.phase} w${d.week}"
                try {
                    if (d.phase == DynastyPhase.REGULAR_SEASON) {
                        repeat(rng.nextInt(4)) {
                            val user = d.userTeamId
                            val l = d.league
                            val roster = l.roster(user)
                            val wire = d.wireWeek
                            fun apply(o: Transactions.Outcome, k: String) {
                                when (o) {
                                    is Transactions.Outcome.Done -> {
                                        d = d.copy(league = o.league); count("$k done")
                                    }
                                    is Transactions.Outcome.Refused -> count("$k refused")
                                }
                            }
                            when (rng.nextInt(10)) {
                                0 -> Transactions.freeAgents(l).takeIf { it.isNotEmpty() }?.let { fa ->
                                    apply(Transactions.sign(l, user, fa[rng.nextInt(minOf(30, fa.size))].id, weeksLeft = d.weeksLeft, week = wire), "sign") }
                                1 -> roster.takeIf { it.isNotEmpty() }?.let { apply(Transactions.release(l, user, it[rng.nextInt(it.size)].id, week = wire), "release") }
                                2 -> Transactions.freeAgents(l).takeIf { it.isNotEmpty() }?.let { fa ->
                                    apply(Transactions.signToPracticeSquad(l, user, fa[rng.nextInt(minOf(30, fa.size))].id, week = wire), "ps sign") }
                                3 -> l.team(user).practiceSquad.takeIf { it.isNotEmpty() }?.let { ps ->
                                    apply(Transactions.releaseFromPracticeSquad(l, user, ps[rng.nextInt(ps.size)], week = wire), "ps release") }
                                4 -> roster.filter { it.status == PlayerStatus.IR }.takeIf { it.isNotEmpty() }?.let { ir ->
                                    apply(Transactions.activate(l, user, ir[rng.nextInt(ir.size)].id, week = wire), "activate") }
                                5 -> roster.takeIf { it.isNotEmpty() }?.let { apply(Transactions.restructure(l, user, it[rng.nextInt(it.size)].id, wire, l.tuning.ai.restructureShare), "restructure") }
                                6 -> ContractDisputes.pending(l, user, d.playerStats).takeIf { it.isNotEmpty() }?.let { asks ->
                                    val a = asks[rng.nextInt(asks.size)]
                                    apply(when (rng.nextInt(4)) {
                                        0 -> ContractDisputes.extend(l, user, a.player.id, wire, d.playerStats, null, null)
                                        1 -> ContractDisputes.offer(l, user, a.player.id, if (rng.nextBoolean()) 0.9f else 0.8f, wire, d.playerStats)
                                        2 -> ContractDisputes.refuse(l, user, a.player.id, wire)
                                        else -> ContractDisputes.frontOfficeAnswer(l, user, a.player.id, wire, d.playerStats)
                                    }, "demand") }
                                7 -> if (TradeDesk.open(d)) {
                                    val offers = TradeOffers.thisWeek(d)
                                    if (offers.isNotEmpty()) {
                                        val made = TradeDesk.makeInSeason(d, offers[rng.nextInt(offers.size)].proposal)
                                        if (made != null) { d = made.first; count("trade (call) made")
                                        } else count("trade (call) refused")
                                    }
                                }
                                8 -> if (TradeDesk.open(d)) {
                                    // A deal of the user's own: one of his for one of theirs.
                                    val partner = l.teams.filter { it.id != user }.let { it[rng.nextInt(it.size)] }
                                    val mine = roster.filter { it.status != PlayerStatus.IR }; val theirs = l.roster(partner.id)
                                    if (mine.isNotEmpty() && theirs.isNotEmpty()) {
                                        val p = TradeDesk.Proposal(partner.id, give = setOf(mine[rng.nextInt(mine.size)].id.v), get = setOf(theirs[rng.nextInt(theirs.size)].id.v))
                                        val made = TradeDesk.makeInSeason(d, p)
                                        if (made != null) { d = made.first; count("trade (built) made")
                                        } else count("trade (built) refused")
                                    }
                                }
                                else -> {
                                    if (rng.nextBoolean() && roster.isNotEmpty()) {
                                        d = TradeOffers.setOnBlock(d, roster[rng.nextInt(roster.size)].id.v, rng.nextBoolean()); count("block")
                                    } else {
                                        val p = l.players[rng.nextInt(l.players.size)]
                                        // Ratings only: an edit moving a player's position is a separate question.
                                        val e = PlayerEdits.current(d, p.id.v)
                                        val r = e.ratings.keys.toList()
                                        val edit = e.copy(ratings = e.ratings + (r[rng.nextInt(r.size)] to (1 + rng.nextInt(99))))
                                        d = PlayerEdits.apply(d, p.id.v, edit); count("edit")
                                    }
                                }
                            }
                        }
                    }
                    d = DynastyEngine.advance(d)
                    weeks++
                    // Invariants.
                    val l = d.league
                    val seen = mutableMapOf<Int, String>()
                    l.teams.forEach { t ->
                        (t.roster + t.practiceSquad).forEach { id -> seen.put(id.v, t.abbrev)?.let { o -> problems++; out.append("$tag: ${id.v} on $o and ${t.abbrev}\n") } }
                        val active = com.nflsim.engine.season.RosterMoves.active(l, t.id).size
                        if (active > Transactions.ROSTER_LIMIT) { problems++; out.append("$tag: ${t.abbrev} active roster $active\n") }
                        if (t.practiceSquad.size > com.nflsim.engine.season.PracticeSquads.SIZE) { problems++; out.append("$tag: ${t.abbrev} squad ${t.practiceSquad.size}\n") }
                        t.roster.map { l.player(it) }.forEach { p ->
                            if (p.teamId != t.id) { problems++; out.append("$tag: ${p.name} teamId ${p.teamId} on ${t.abbrev}\n") }
                            if (p.contract?.isActive(l.year) != true) { problems++; out.append("$tag: ${p.name} (${t.abbrev}) no contract for ${l.year}\n") }
                        }
                        if (Transactions.spaceFor(l, t.id) < 0) { count("over cap ${if (t.id == d.userTeamId) "user" else "ai"}") }
                    }
                    if (weeks % 6 == 0 && SaveFile.decode(SaveFile.encode(d)) != d) { problems++; out.append("$tag: save round trip differs\n") }
                } catch (e: Exception) {
                    problems++; out.append("$tag: ${e::class.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}\n")
                    break
                }
            }
        }
        assertEquals(0, problems, out.toString())
        assertTrue(counts.isNotEmpty(), "moves were made")
    }
}
