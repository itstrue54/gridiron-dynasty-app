package com.nflsim.data
import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.offseason.ContractChoice
import com.nflsim.engine.offseason.ContractDecision
import com.nflsim.engine.offseason.ContractOptions
import com.nflsim.engine.offseason.CutdownPause
import com.nflsim.engine.offseason.FreeAgency
import com.nflsim.engine.offseason.OffseasonEngine
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import com.nflsim.engine.season.Transactions
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
/**
 * The user's own offseason under random decisions (SPEC 7): re-signings of
 * every length and shape, tags, walks and the front office; talks and offers
 * in free agency; a man near the top of the board at every pick; the cut to
 * 53 as suggested, moved a little, or left to the front office. After each
 * offseason: nobody on two rosters, every roster 46 to 53, every rostered man
 * under contract, every club under the cap, and the save read back as
 * written. Four leagues over twelve offseasons each ran clean before it was
 * cut to this.
 */
class OffseasonFuzzTest {
    @Test fun `random offseason decisions keep every roster rule`() {
        val out = StringBuilder()
        var problems = 0
        var offseasons = 0
        for (seed in listOf(41L)) {
            val league = LeagueGenerator.generate(2026, seed)
            var d = DynastyEngine.start(league, 2026, seed, league.teams[(seed % 32).toInt()].id)
            val rng = SplitMixRng(seed * 11)
            repeat(3) { season ->
                try {
                    while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
                    val tag = "seed $seed s$season"
                    // Contracts.
                    val contracts = OffseasonEngine.runToContracts(d)
                    var tagged = false
                    val decisions = if (rng.nextInt(5) == 0) null else contracts.expiring.associate { e ->
                        val r = rng.nextFloat()
                        e.player.id.v to when {
                            r < 0.45f -> ContractDecision(ContractChoice.RESIGN, 1 + rng.nextInt(maxOf(1, e.years + 1)),
                                ContractOptions.Structure.entries[rng.nextInt(ContractOptions.Structure.entries.size)])
                            r < 0.52f && !tagged -> { tagged = true; ContractDecision(ContractChoice.FRANCHISE) }
                            r < 0.57f && !tagged -> { tagged = true; ContractDecision(ContractChoice.TRANSITION) }
                            else -> ContractDecision(ContractChoice.WALK)
                        }
                    }
                    var fa = contracts.toFreeAgency(decisions)
                    // Free agency: a talk now and then, and some offers.
                    if (rng.nextInt(3) == 0 && fa.candidates.isNotEmpty()) {
                        val c = fa.candidates[rng.nextInt(minOf(20, fa.candidates.size))]
                        fa = fa.negotiate(c.player.id.v, (c.market * (0.7f + rng.nextFloat() * 0.6f)).roundToInt(), 1 + rng.nextInt(4)).pause
                    }
                    val offers = if (rng.nextInt(5) == 0) null else (0 until rng.nextInt(5)).mapNotNull {
                        val pool = fa.candidates.take(30)
                        if (pool.isEmpty()) null else pool[rng.nextInt(pool.size)].let { c ->
                            FreeAgency.Offer(c.player.id.v, (c.market * (0.7f + rng.nextFloat() * 0.6f)).roundToInt(), 1 + rng.nextInt(5))
                        }
                    }.distinctBy { it.player }
                    val draft = fa.decide(offers, if (rng.nextBoolean()) fa.suggestedMatches else emptyMap())
                    // The draft: a random man near the top at each of the club's picks.
                    val picks = mutableMapOf<Int, Int>()
                    var guard = 0
                    while (guard++ < 30) {
                        val board = draft.boardFor(d.userTeamId, picks)
                        val slot = board.stoppedAt ?: break
                        val choices = board.available.take(15)
                        if (choices.isEmpty()) break
                        picks[slot] = choices[rng.nextInt(choices.size)].id.v
                    }
                    // Camp: the front office, the suggestion, or the suggestion moved a little.
                    val camp = draft.toCutdown(picks)
                    val cut: CutdownPause.Cut? = when (rng.nextInt(4)) {
                        0 -> null
                        1 -> camp.suggested
                        else -> {
                            var c = camp.suggested
                            val kept = camp.roster.filter { it.id.v !in c.release }
                            val size = kept.size + c.sign.size
                            val spare = kept.filter { it.position !in camp.mustField }
                            if (size > 46 && spare.isNotEmpty() && rng.nextBoolean()) c = c.copy(release = c.release + spare[rng.nextInt(spare.size)].id.v)
                            val after = camp.roster.count { it.id.v !in c.release } + c.sign.size
                            if (after < 53 && camp.pool.isNotEmpty() && rng.nextBoolean()) c = c.copy(sign = c.sign + camp.pool[rng.nextInt(minOf(20, camp.pool.size))].id.v)
                            c
                        }
                    }
                    val (rolled, report) = camp.decide(cut)
                    d = rolled.copy(lastOffseason = report)
                    offseasons++
                    // Invariants.
                    val l = d.league
                    val seen = mutableMapOf<Int, String>()
                    l.teams.forEach { t ->
                        (t.roster + t.practiceSquad).forEach { id ->
                            seen.put(id.v, t.abbrev)?.let { other -> problems++; out.append("$tag: player ${id.v} on $other and ${t.abbrev}\n") }
                        }
                        if (t.roster.size !in 46..53) { problems++; out.append("$tag: ${t.abbrev} has ${t.roster.size} on the roster\n") }
                        t.roster.map { l.player(it) }.forEach { p ->
                            if (p.teamId != t.id) { problems++; out.append("$tag: ${p.name} on ${t.abbrev}'s roster but teamId ${p.teamId}\n") }
                            if (p.contract?.isActive(l.year) != true) { problems++; out.append("$tag: ${p.name} (${t.abbrev}) has no contract for ${l.year}\n") }
                        }
                        val space = Transactions.spaceFor(l, t.id)
                        if (space < 0) { problems++; out.append("$tag: ${t.abbrev} over the cap after the offseason ($space)\n") }
                    }
                    if (SaveFile.decode(SaveFile.encode(d)) != d) { problems++; out.append("$tag: save round trip differs\n") }
                } catch (e: Exception) {
                    problems++; out.append("seed $seed s$season: ${e::class.simpleName}: ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}\n")
                    return@repeat
                }
            }
        }
        assertEquals(0, problems, out.toString())
        assertEquals(3, offseasons)
    }
}
