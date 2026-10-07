package com.nflsim.engine.offseason

import com.nflsim.engine.model.GmProfile
import com.nflsim.engine.model.League
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.rng.Rng
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** One club's new general manager, for the news screen. */
@Serializable
data class GmChange(
    val team: Int,
    val fired: String,
    val hired: String,
    /** The club's win percentage, in thousandths, the season he went. */
    val record: Int,
    /** A general manager out of work hired again, rather than an outside candidate. */
    val rehired: Boolean = false,
)

/**
 * SPEC 7 phase 2: owners replace general managers.
 *
 * An owner fires his general manager after two losing seasons in a row, once
 * he has had a couple of seasons to build, and hires one of a few outside
 * candidates or a general manager out of work. Nobody knows in March which
 * front office will work, so he picks by lot: a general manager's style is
 * not a rating, and an owner who always hired the same kind would turn the
 * whole league into it. The man he fires joins the pool, and the user's club
 * hires from the same one (Staffing). The user's own club is never touched:
 * its staffing is the user's.
 */
object GmCarousel {

    data class Result(val league: League, val changes: List<GmChange>)

    fun run(
        league: League,
        winPct: (TeamId) -> Float,
        previousWinPct: Map<Int, Float>,
        userTeam: TeamId?,
        newYear: Int,
        rng: Rng,
        /** General managers the user's club has agreed to hire (Dynasty.pendingGm): no owner takes them. */
        reserved: Set<String> = emptySet(),
    ): Result {
        val t = league.tuning.staff
        var pool = league.gmPool
        val changes = mutableListOf<GmChange>()
        val hired = mutableSetOf<String>()
        val teams = league.teams.map { team ->
            if (team.id == userTeam) return@map team
            val now = winPct(team.id)
            val before = previousWinPct[team.id.v] ?: 0.5f
            val gm = team.gm
            if (now >= t.gmFireWinPct || before >= t.gmFirePreviousWinPct || newYear - gm.since < t.gmTenure) return@map team

            val taken = league.teams.map { it.gm.name }.toSet() + pool.map { it.name } + hired
            val outside = Staffing.freshGms(league, newYear - 1, t.gmCandidates, "gm-hire|$newYear|${team.id.v}")
                .filter { it.name !in taken }
            // The shortlist and the lot are drawn for this owner and each man
            // by name, not from a shared stream, so a man more or less in the
            // pool changes an owner's choice only if he is the one it drew -
            // and the user's agreed man is passed over only at the choice.
            fun lot(label: String, gm: GmProfile) = rng.split("$label|${team.id.v}|${gm.name}").nextFloat()
            val outOfWork = pool.sortedBy { lot("gm-look", it) }.take(t.gmRehireLook)
            val chosen = (outside + outOfWork).filter { it.name !in reserved }
                .minByOrNull { lot("gm-pick", it) } ?: return@map team
            hired += chosen.name
            pool = (listOf(gm) + pool.filterNot { it.name == chosen.name }).take(t.gmPoolLimit)
            changes += GmChange(team.id.v, gm.name, chosen.name, (now * 1000).roundToInt(), rehired = chosen in outOfWork)
            team.copy(gm = chosen.copy(since = newYear))
        }
        return Result(league.copy(teams = teams, gmPool = pool), changes)
    }
}
