package com.nflsim.data
import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.CoachId
import com.nflsim.engine.offseason.StaffJob
import com.nflsim.engine.offseason.Staffing
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
/**
 * The spring window under random moves (SPEC 4.7, 8.2): a few seasons of a
 * user firing, hiring from every source, agreeing and changing his mind,
 * with the staffing rules checked after every offseason - every job filled,
 * nobody in two, nobody past retiring, every club a general manager, every
 * agreement kept and none left behind - and every save read back as written.
 * Four leagues over fifteen seasons each ran clean before it was cut to this.
 */
class StaffingFuzzTest {
    @Test fun `random staff moves keep every staffing rule`() {
        val out = StringBuilder()
        var problems = 0
        var moves = 0; var refused = 0; var agreements = 0; var honoured = 0
        for (seed in listOf(32L)) {
            val league = LeagueGenerator.generate(2026, seed)
            var d = DynastyEngine.start(league, 2026, seed, league.teams[(seed % 32).toInt()].id)
            val rng = SplitMixRng(seed * 7)
            repeat(4) { season ->
                while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
                // The spring window: random moves.
                repeat(1 + rng.nextInt(5)) {
                    moves++
                    try {
                        when (rng.nextInt(6)) {
                            0, 1 -> d = Staffing.fire(d, StaffJob.ALL[rng.nextInt(StaffJob.ALL.size)])
                            2, 3 -> {
                                val open = StaffJob.ALL.filter { Staffing.isVacant(d, it) }
                                if (open.isNotEmpty()) {
                                    val job = open[rng.nextInt(open.size)]
                                    val pool = Staffing.pool(d, job)
                                    if (pool.isNotEmpty()) d = Staffing.hire(d, job, pool[rng.nextInt(minOf(pool.size, 8))])
                                }
                            }
                            4 -> d = if (d.team.gm.name.isNotBlank()) Staffing.fireGm(d) else {
                                val pool = Staffing.gmPool(d); if (pool.isNotEmpty() && d.pendingGm == null) Staffing.hireGm(d, pool[rng.nextInt(pool.size)]) else d
                            }
                            else -> {
                                // Change of mind on an agreement, now and then.
                                val p = d.pendingHires.firstOrNull()
                                if (p != null && rng.nextInt(3) == 0) d = Staffing.fire(d, p.job)
                            }
                        }
                    } catch (e: IllegalArgumentException) { refused++ }
                }
                // Round-trip the save in the window.
                val bytes = SaveFile.encode(d)
                if (SaveFile.decode(bytes) != d) { problems++; out.append("seed $seed s$season: window save round trip differs\n") }
                val agreed = d.pendingHires.map { it.job to (it.candidate?.name ?: d.league.coaches[CoachId(it.coach)]?.name) } +
                    listOfNotNull(d.pendingGm?.let { StaffJob.HEAD to "GM:$it" })
                agreements += d.pendingHires.size + (if (d.pendingGm != null) 1 else 0)
                val agreedGm = d.pendingGm
                val user = d.userTeamId
                d = DynastyEngine.advance(d)   // the offseason
                // Invariants.
                val l = d.league
                val jobs = l.teams.flatMap { t -> StaffJob.ALL.map { job -> Triple(t, job, Staffing.holder(t.staff, job)) } }
                jobs.filter { l.coaches[it.third] == null }.forEach { problems++; out.append("seed $seed s$season: ${it.first.abbrev} ${it.second.label} empty\n") }
                jobs.groupBy { it.third }.filter { it.key.v > 0 && it.value.size > 1 }.forEach { (id, js) -> problems++; out.append("seed $seed s$season: ${l.coaches[id]?.name} holds ${js.map { it.first.abbrev + " " + it.second.label }}\n") }
                val maxAge = l.tuning.staff.retireFrom + l.tuning.staff.retireSpread
                jobs.mapNotNull { l.coaches[it.third] }.filter { it.age > maxAge }.forEach { problems++; out.append("seed $seed s$season: ${it.name} still working at ${it.age}\n") }
                l.teams.filter { it.gm.name.isBlank() }.forEach { problems++; out.append("seed $seed s$season: ${it.abbrev} has no GM\n") }
                val gmNames = l.teams.map { it.gm.name }
                if (gmNames.size != gmNames.toSet().size) { problems++; out.append("seed $seed s$season: two clubs share a GM\n") }
                l.gmPool.filter { it.name in gmNames }.forEach { problems++; out.append("seed $seed s$season: ${it.name} both employed and in the pool\n") }
                agreed.filter { !it.second.orEmpty().startsWith("GM:") }.forEach { (job, name) ->
                    val now = l.coaches[Staffing.holder(l.team(user).staff, job)]?.name
                    if (now == name) honoured++ else out.append("seed $seed s$season: agreed ${job.label} $name, got $now\n")
                }
                if (agreedGm != null) { if (l.team(user).gm.name == agreedGm) honoured++ else out.append("seed $seed s$season: agreed GM $agreedGm, got ${l.team(user).gm.name}\n") }
                if (d.pendingHires.isNotEmpty() || d.pendingGm != null) { problems++; out.append("seed $seed s$season: agreements survived the offseason\n") }
                val after = SaveFile.decode(SaveFile.encode(d))
                if (after != d) { problems++; out.append("seed $seed s$season: save round trip differs\n") }
            }
        }
        assertEquals(0, problems, out.toString())
        assertEquals(agreements, honoured, "every agreement kept: " + out)
        assertTrue(moves > 0)
    }
}
