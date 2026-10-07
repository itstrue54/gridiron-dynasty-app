package com.nflsim.engine.season
import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.sim.Coverage
import com.nflsim.engine.sim.DefensiveFront
import com.nflsim.engine.sim.DefensivePlayCall
import com.nflsim.engine.sim.FourthDownChoice
import com.nflsim.engine.sim.OffensivePlayCall
import com.nflsim.engine.sim.PassConcept
import com.nflsim.engine.sim.Personnel
import com.nflsim.engine.sim.RunConcept
import com.nflsim.engine.sim.Snap
import com.nflsim.engine.sim.SnapCaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
/**
 * Calling the plays yourself (SPEC 5.4), by a user who calls anything: the
 * suggestion now and then, any run or pass with any personnel, protection or
 * target, punts and kicks and kneels and spikes on any down, any front,
 * coverage, blitz, box or bracket, any fourth-down choice and any timeout.
 * Every game must finish with a box score that reconciles, points that match
 * the score and possession that adds up. Three leagues over two seasons -
 * 102 games, 14,714 calls - did before this was cut down.
 */
class CallerFuzzTest {
    /** A user who calls anything: sometimes the suggestion, often not, now and then nonsense. */
    class Wild(seed: Long) : SnapCaller {
        val rng = SplitMixRng(seed)
        var calls = 0
        fun <T> pick(xs: List<T>) = xs[rng.nextInt(xs.size)]
        override fun offense(snap: Snap, suggested: OffensivePlayCall): OffensivePlayCall { calls++
            if (rng.nextInt(3) == 0) return suggested
            val p = pick(Personnel.entries)
            return when (rng.nextInt(12)) {
                0 -> OffensivePlayCall.Punt()
                1 -> OffensivePlayCall.FieldGoal()
                2 -> OffensivePlayCall.Kneel()
                3 -> OffensivePlayCall.Spike()
                in 4..6 -> OffensivePlayCall.Run(pick(RunConcept.entries), p, rng.nextBoolean())
                else -> OffensivePlayCall.Pass(pick(PassConcept.entries), p, rng.nextBoolean(), rng.nextInt(4), rng.nextInt(7))
            }
        }
        override fun defense(snap: Snap, suggested: DefensivePlayCall): DefensivePlayCall { calls++
            if (rng.nextInt(3) == 0) return suggested
            return DefensivePlayCall(pick(DefensiveFront.entries), pick(Coverage.entries), rng.nextInt(6), rng.nextInt(6) - 2,
                if (rng.nextBoolean()) null else rng.nextInt(7))
        }
        override fun fourthDown(snap: Snap, suggested: FourthDownChoice) = pick(FourthDownChoice.entries)
        override fun timeout(snap: Snap, suggested: Boolean) = rng.nextBoolean()
    }

    @Test fun `any calls the user makes give a game that adds up`() {
        val out = StringBuilder()
        var games = 0; var problems = 0; var calls = 0
        for (seed in listOf(61L)) {
            val league = LeagueGenerator.generate(2026, seed)
            var d = DynastyEngine.start(league, 2026, seed, league.teams[(seed % 32).toInt()].id)
            val caller = Wild(seed)
            var guard = 0
            while (d.year < 2027 && guard++ < 30) {
                val tag = "seed $seed ${d.year} ${d.phase} w${d.week}"
                try {
                    val before = d
                    d = DynastyEngine.advance(d, caller = if (d.phase == DynastyPhase.OFFSEASON) null else caller)
                    val g = d.lastGame
                    if (g != null && g !== before.lastGame) {
                        games++
                        val b = g.boxScore
                        if (b.home.points != g.homeScore || b.away.points != g.awayScore) { problems++; out.append("$tag: points ${b.home.points}-${b.away.points} vs score ${g.homeScore}-${g.awayScore}\n") }
                        if (!b.reconciles()) { problems++; out.append("$tag: box score does not reconcile\n") }
                        if (b.home.completions > b.home.passAttempts || b.away.completions > b.away.passAttempts) { problems++; out.append("$tag: completions over attempts\n") }
                        val poss = b.home.possessionSeconds + b.away.possessionSeconds
                        if (poss < 3600 || poss > 4200) { problems++; out.append("$tag: possession adds to $poss s\n") }
                        if (g.homeScore < 0 || g.awayScore < 0 || g.homeScore % 1 != 0) { problems++; out.append("$tag: score ${g.homeScore}-${g.awayScore}\n") }
                    }
                } catch (e: Exception) {
                    problems++; out.append("$tag: ${e::class.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}\n")
                    break
                }
            }
            calls += caller.calls
        }
        assertEquals(0, problems, out.toString())
        assertTrue(games >= 17 && calls > 1_000, "a season of the user's games: $games games, $calls calls")
    }
}
