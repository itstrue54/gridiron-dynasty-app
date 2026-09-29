package com.nflsim.data.roster

import com.nflsim.engine.gen.Tendencies
import com.nflsim.engine.model.Coach
import com.nflsim.engine.model.CoachId
import com.nflsim.engine.model.CoachRatings
import com.nflsim.engine.model.CoachRole
import com.nflsim.engine.model.League
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.SchemeSide
import com.nflsim.engine.rng.SplitMixRng

/**
 * A roster file's front offices and coaching staffs, laid over the clubs the
 * file names (SPEC 9.4).
 *
 * Each coach the file names takes his slot's generated coach's place, and the
 * generated man leaves the league rather than wait on the street for the
 * carousel to hire him. What the file leaves out of a coach - age, ratings,
 * contract, hot seat - he takes from the man he replaces, so a staff given only names
 * still rates like the league the sim was tuned on. His tendencies are drawn
 * from his scheme as a generated coach's are, under whatever the file gives.
 *
 * One person may fill several slots - a defensive line coach who also coaches
 * the edge, a head coach who calls his own plays - and is then one coach, with
 * the levers of every job he holds. A slot the file does not fill keeps its
 * generated coach, and the import says so.
 *
 * The club's schemes are the file's if it gives them, else its coordinators',
 * else its head coach's on his side of the ball - a head coach can come from
 * the offense or the defense, and the carousel hires around either.
 */
object StaffImport {

    fun apply(league: League, slots: Map<TeamId, RosterJson.Club>, seed: Long, notes: MutableList<String>): League {
        val coaches = league.coaches.toMutableMap()
        var nextId = (coaches.keys.maxOfOrNull { it.v } ?: 0) + 1
        val teams = league.teams.map { team ->
            val club = slots[team.id] ?: return@map team
            val staff = club.staff
            if (staff == null && club.gm == null && club.offenseScheme == null && club.defenseScheme == null) return@map team

            val headScheme = staff?.headCoach?.scheme
            val headDefensive = headScheme != null && SchemeCatalog[headScheme].side == SchemeSide.DEFENSE
            val offense = club.offenseScheme ?: staff?.offCoordinator?.scheme ?: headScheme?.takeIf { !headDefensive } ?: team.offenseScheme
            val defense = club.defenseScheme ?: staff?.defCoordinator?.scheme ?: headScheme?.takeIf { headDefensive } ?: team.defenseScheme
            val rng = SplitMixRng(seed).split("import|staff|${club.abbrev}")
            val byName = mutableMapOf<String, CoachId>()
            val replaced = mutableSetOf<CoachId>()
            val employed = mutableSetOf<CoachId>()

            /** Seats [spec] in the slot [current] holds, or keeps [current] with a note when the file is silent. */
            fun seat(spec: StaffJson.CoachSpec?, current: CoachId, role: CoachRole, scheme: String, slot: String): CoachId {
                val generated = coaches[current]
                if (spec == null) {
                    notes += "${club.abbrev}: no $slot in the file, kept a generated one"
                    generated?.let { coaches[it.id] = it.copy(scheme = scheme) }
                    employed += current
                    return current
                }
                replaced += current
                val drawn = Tendencies.draw(role, scheme, rng.split("tendencies|$slot"))
                val existing = byName[spec.name.lowercase()]?.let { coaches[it] }
                if (existing != null) {
                    // The same man in a second job: he gains its levers, keeps his own.
                    coaches[existing.id] = existing.copy(tendencies = existing.tendencies.over(spec.tendencies.over(drawn)))
                    return existing.id
                }
                val base = generated?.ratings
                fun r(key: String, fallback: Int) = spec.ratings[key] ?: fallback
                val coach = Coach(
                    id = CoachId(nextId++),
                    name = spec.name,
                    age = spec.age ?: generated?.age ?: 50,
                    role = role,
                    scheme = spec.scheme ?: scheme,
                    ratings = CoachRatings(
                        development = r("development", base?.development ?: 65),
                        gameplan = r("gameplan", base?.gameplan ?: 65),
                        adjustments = r("adjustments", base?.adjustments ?: 65),
                        discipline = r("discipline", base?.discipline ?: 65),
                        motivation = r("motivation", base?.motivation ?: 65),
                        evaluation = r("evaluation", base?.evaluation ?: 65),
                    ),
                    hotSeat = spec.hotSeat ?: generated?.hotSeat ?: 0,
                    contractYearsLeft = spec.contractYears ?: generated?.contractYearsLeft ?: 2,
                    tendencies = spec.tendencies.over(drawn),
                )
                coaches[coach.id] = coach
                byName[spec.name.lowercase()] = coach.id
                employed += coach.id
                return coach.id
            }

            val s = team.staff
            val newStaff = if (staff == null) s else s.copy(
                headCoach = seat(staff.headCoach, s.headCoach, CoachRole.HEAD_COACH, if (headDefensive) defense else offense, "head coach"),
                offCoordinator = seat(staff.offCoordinator, s.offCoordinator, CoachRole.OFFENSIVE_COORDINATOR, offense, "offensive coordinator"),
                defCoordinator = seat(staff.defCoordinator, s.defCoordinator, CoachRole.DEFENSIVE_COORDINATOR, defense, "defensive coordinator"),
                stCoordinator = seat(staff.stCoordinator, s.stCoordinator, CoachRole.SPECIAL_TEAMS_COORDINATOR, offense, "special teams coordinator"),
                positionCoaches = PositionGroup.entries.associateWith { group ->
                    val current = s.positionCoaches[group] ?: CoachId(0)
                    seat(staff.positionCoaches[group], current, CoachRole.POSITION_COACH,
                        if (group in OFFENSIVE_GROUPS) offense else defense, "${group.name} coach")
                },
            )
            // The generated men he replaced are gone, unless one still holds another slot.
            (replaced - employed).forEach { coaches.remove(it) }
            if (staff == null) {
                // New schemes over the generated staff: its coaches run what the club now runs.
                listOf(s.headCoach, s.offCoordinator, s.stCoordinator).forEach { id -> coaches[id]?.let { coaches[id] = it.copy(scheme = offense) } }
                coaches[s.defCoordinator]?.let { coaches[it.id] = it.copy(scheme = defense) }
            }

            val gm = club.gm?.let { g ->
                team.gm.copy(
                    name = g.name ?: team.gm.name,
                    aggression = g.aggression ?: team.gm.aggression,
                    winNowVsFuture = g.winNowVsFuture ?: team.gm.winNowVsFuture,
                    loyaltyToOwnPlayers = g.loyaltyToOwnPlayers ?: team.gm.loyaltyToOwnPlayers,
                    riskTolerance = g.riskTolerance ?: team.gm.riskTolerance,
                )
            } ?: team.gm
            team.copy(offenseScheme = offense, defenseScheme = defense, staff = newStaff, gm = gm)
        }
        return league.copy(teams = teams, coaches = coaches)
    }

    private val OFFENSIVE_GROUPS = setOf(PositionGroup.QB, PositionGroup.RB, PositionGroup.WR, PositionGroup.TE, PositionGroup.OL)
}
