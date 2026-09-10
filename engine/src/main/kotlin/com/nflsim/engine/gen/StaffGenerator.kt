package com.nflsim.engine.gen

import com.nflsim.engine.model.Coach
import com.nflsim.engine.model.CoachId
import com.nflsim.engine.model.CoachRatings
import com.nflsim.engine.model.CoachRole
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.model.Staff
import com.nflsim.engine.rng.Rng
import kotlin.math.roundToInt

/**
 * Builds a full coaching staff for one team: a head coach, three
 * coordinators, and one position coach per position group.
 *
 * Step 1 of the real coaching model (docs/SPEC.md 4.7) - every team starts
 * with a staff at league creation. There is no carousel yet: nobody is
 * fired, hired, or poached until M8.
 */
object StaffGenerator {

    private val OFFENSIVE_GROUPS = setOf(
        PositionGroup.QB, PositionGroup.RB, PositionGroup.WR, PositionGroup.TE, PositionGroup.OL)

    fun generate(
        offenseScheme: String,
        defenseScheme: String,
        nextId: () -> CoachId,
        rng: Rng,
    ): Pair<Staff, List<Coach>> {
        val coaches = mutableListOf<Coach>()

        fun hire(role: CoachRole, scheme: String): CoachId {
            val (first, last) = NameGenerator.fullName(rng)
            val coach = Coach(
                id = nextId(),
                name = "$first $last",
                age = 38 + rng.nextInt(28),
                role = role,
                scheme = scheme,
                ratings = randomRatings(rng),
                hotSeat = rng.nextInt(40),
                contractYearsLeft = 1 + rng.nextInt(4),
            )
            coaches += coach
            return coach.id
        }

        val staff = Staff(
            headCoach = hire(CoachRole.HEAD_COACH, offenseScheme),
            offCoordinator = hire(CoachRole.OFFENSIVE_COORDINATOR, offenseScheme),
            defCoordinator = hire(CoachRole.DEFENSIVE_COORDINATOR, defenseScheme),
            stCoordinator = hire(CoachRole.SPECIAL_TEAMS_COORDINATOR, offenseScheme),
            positionCoaches = PositionGroup.entries.associateWith { group ->
                hire(CoachRole.POSITION_COACH, if (group in OFFENSIVE_GROUPS) offenseScheme else defenseScheme)
            },
            scoutingDept = 40 + rng.nextInt(41),
            trainingStaff = 40 + rng.nextInt(41),
            medicalStaff = 40 + rng.nextInt(41),
        )
        return staff to coaches
    }

    /**
     * Spread wide enough that some staffs are genuinely good developers and
     * some are not, centred where SPEC 7.1 was calibrated.
     *
     * The mean is 65 rather than a natural-looking 50 because coaching used to
     * be a stand-in formula averaging ~67 across the league. Generating honest
     * N(50,15) coaches dropped league-average coaching by seventeen points and
     * took progression down with it - so the spread is the new information
     * here, and the mean has to stay where the curve was tuned.
     *
     * The spread is wide because a player's coaching blends his position
     * coach with the head coach, and a team's staff is eleven such draws: at
     * sd 12 team ratings bunched into 53-74 and staffs barely differed.
     */
    private fun randomRatings(rng: Rng): CoachRatings {
        // Rounded, and clamped the same distance either side of 65, so the
        // generated mean is the 65 that DEFAULT_COACHING and SPEC 7.1 assume.
        fun stat() = (65 + rng.gaussian(0f, 20f)).roundToInt().coerceIn(30, 100)
        return CoachRatings(
            development = stat(),
            gameplan = stat(),
            adjustments = stat(),
            discipline = stat(),
            motivation = stat(),
            evaluation = stat(),
        )
    }
}
