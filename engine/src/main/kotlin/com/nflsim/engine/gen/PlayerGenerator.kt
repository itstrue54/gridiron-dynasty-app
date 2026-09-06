package com.nflsim.engine.gen

import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.DevCurve
import com.nflsim.engine.model.HiddenTraits
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.Ratings
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.OverallWeights
import com.nflsim.engine.ratings.rawOverall
import com.nflsim.engine.rng.Rng
import kotlin.math.roundToInt

/**
 * Builds a believable player from a target overall.
 *
 * The hard part is the inverse problem: overall is derived from weighted
 * ratings, so to hit a target we shape a sheet from the archetype profile and
 * then nudge the position-relevant ratings until the derived overall lands.
 */
object PlayerGenerator {

    /**
     * Ratings a position uses but that do not appear in its overall formula.
     *
     * A running back's overall does not weigh route running, so without this
     * these came out at the irrelevant-position baseline of 34 - and a back
     * running a checkdown with 34 route running is uncoverable in the wrong
     * direction. They generate at a discount to the player's level: real, but
     * not what he is paid for.
     */
    private val SECONDARY: Map<PositionGroup, Set<RatingId>> = mapOf(
        PositionGroup.RB to setOf(
            RatingId.ROUTE_SHORT, RatingId.ROUTE_MID, RatingId.RELEASE,
            RatingId.CATCH_IN_TRAFFIC, RatingId.RUN_BLOCK, RatingId.LEAD_BLOCK,
        ),
        PositionGroup.TE to setOf(
            RatingId.ROUTE_DEEP, RatingId.RELEASE, RatingId.SPECTACULAR_CATCH,
            RatingId.PASS_BLOCK_POWER, RatingId.LEAD_BLOCK,
        ),
        PositionGroup.WR to setOf(
            RatingId.CARRYING, RatingId.BREAK_TACKLE, RatingId.ELUSIVENESS,
            RatingId.RUN_BLOCK,
        ),
        PositionGroup.QB to setOf(RatingId.CARRYING, RatingId.BALL_SECURITY),
        PositionGroup.OL to setOf(RatingId.PASS_BLOCK_FINESSE, RatingId.RUN_BLOCK_FINESSE),
        PositionGroup.EDGE to setOf(RatingId.MAN_COVERAGE, RatingId.ZONE_COVERAGE, RatingId.HIT_POWER),
        PositionGroup.DT to setOf(RatingId.HIT_POWER),
        PositionGroup.LB to setOf(RatingId.POWER_MOVES, RatingId.FINESSE_MOVES, RatingId.PRESS),
        PositionGroup.CB to setOf(RatingId.PURSUIT, RatingId.HIT_POWER, RatingId.SPECTACULAR_CATCH),
        PositionGroup.S to setOf(RatingId.PRESS, RatingId.BLOCK_SHEDDING, RatingId.SPECTACULAR_CATCH),
    )

    /** How far below his position ratings a player's secondary skills sit. */
    private const val SECONDARY_DISCOUNT = 12

    /** Ratings every player has some version of, regardless of position. */
    private val UNIVERSAL = setOf(
        RatingId.SPEED, RatingId.ACCELERATION, RatingId.AGILITY, RatingId.STRENGTH,
        RatingId.JUMPING, RatingId.STAMINA, RatingId.INJURY_RESIST, RatingId.TOUGHNESS,
        RatingId.AWARENESS, RatingId.PLAY_RECOGNITION, RatingId.DISCIPLINE,
    )

    fun generate(
        id: PlayerId,
        position: Position,
        targetOverall: Int,
        year: Int,
        rng: Rng,
        archetype: Archetype? = null,
        teamId: TeamId? = null,
        ageBias: Int = 0,
    ): Player {
        val arch = archetype ?: pickArchetype(position, rng)
        val ratings = buildRatings(position, arch, targetOverall, rng)
        val age = pickAge(rng, ageBias)
        val (height, weight) = measurements(position, rng)

        return Player(
            id = id,
            firstName = NameGenerator.firstName(rng),
            lastName = NameGenerator.lastName(rng),
            position = position,
            archetype = arch,
            birthYear = year - age,
            heightIn = height,
            weightLb = weight,
            college = NameGenerator.college(rng),
            ratings = ratings,
            traits = generateTraits(rng),
            teamId = teamId,
            yearsInSystem = minOf(rng.nextInt(4), maxOf(0, age - 22)),
            accruedSeasons = maxOf(0, age - 22),
            morale = 60 + rng.nextInt(35),
        )
    }

    fun pickArchetype(position: Position, rng: Rng): Archetype {
        val options = Archetype.forPosition(position)
        return options[rng.nextInt(options.size)]
    }

    // ---------------------------------------------------------------

    private fun buildRatings(
        position: Position,
        archetype: Archetype,
        target: Int,
        rng: Rng,
    ): Ratings {
        val weighted = OverallWeights.forPosition(position).keys
        val relevant = weighted + UNIVERSAL
        val secondary = SECONDARY[position.group] ?: emptySet()
        val offsets = ArchetypeProfile.offsetsFor(archetype)

        val values = IntArray(RatingId.COUNT)
        for (rating in RatingId.entries) {
            val base = if (rating in relevant) {
                target + (offsets[rating] ?: 0) + rng.gaussian(0f, 4.5f).roundToInt()
            } else if (rating in secondary) {
                target - SECONDARY_DISCOUNT + (offsets[rating] ?: 0) +
                    rng.gaussian(0f, 6f).roundToInt()
            } else {
                // A quarterback does not have useful man coverage. Keep the
                // irrelevant parts of the sheet low so player cards read true.
                irrelevantBaseline(rating, position) + rng.gaussian(0f, 6f).roundToInt()
            }
            values[rating.ordinal] = base.coerceIn(Ratings.MIN, Ratings.MAX)
        }

        return converge(position, Ratings(values), target, weighted)
    }

    /**
     * Nudges only the position-weighted ratings until the derived overall hits
     * the target.
     *
     * The subtlety is clamping. An elite man-press corner gets MAN_COVERAGE +13
     * and PRESS +12 from his archetype profile, which pins both at 99 before the
     * overall has arrived. So each pass moves only the ratings that still have
     * room in the direction we need, and pushes the rest of the way through
     * them. Stops when nothing can move rather than spinning.
     */
    private fun converge(
        position: Position,
        start: Ratings,
        target: Int,
        weighted: Set<RatingId>,
    ): Ratings {
        var current = start
        repeat(MAX_CONVERGE_PASSES) {
            val actual = rawOverall(position, current)
            val diff = target - actual
            if (diff == 0) return current
            val step = if (diff > 0) 1 else -1

            val movable = weighted.filter {
                if (step > 0) current[it] < Ratings.MAX else current[it] > Ratings.MIN
            }
            if (movable.isEmpty()) return current

            current = current.with(*movable.map { it to (current[it] + step) }.toTypedArray())
        }
        return current
    }

    private fun irrelevantBaseline(rating: RatingId, position: Position): Int = when {
        rating in KICKING && position.group != PositionGroup.ST -> 12
        position.group == PositionGroup.ST && rating !in KICKING -> 42
        else -> 34
    }

    private val KICKING = setOf(
        RatingId.KICK_POWER, RatingId.KICK_ACCURACY,
        RatingId.PUNT_POWER, RatingId.PUNT_ACCURACY,
    )

    // ---------------------------------------------------------------

    private fun generateTraits(rng: Rng): HiddenTraits {
        val dev = pickDevCurve(rng)
        // Work ethic correlates mildly with development - not perfectly, or
        // scouting would be trivial.
        val ethicCentre = when (dev) {
            DevCurve.SLOW -> 42f
            DevCurve.NORMAL -> 50f
            DevCurve.QUICK -> 58f
            DevCurve.SUPERSTAR -> 66f
            DevCurve.X_FACTOR -> 72f
        }
        fun roll(centre: Float = 50f, spread: Float = 16f) =
            (centre + rng.gaussian(0f, spread)).roundToInt().coerceIn(1, 99)

        return HiddenTraits(
            developmentCurve = dev,
            peakAgeOffset = (rng.gaussian(0f, 1.4f)).roundToInt().coerceIn(-3, 3),
            workEthic = roll(ethicCentre, 14f),
            footballIq = roll(),
            coachability = roll(),
            schemeVersatility = roll(),
            injuryProneness = roll(),
            clutch = roll(),
            bigGame = roll(),
            consistency = roll(),
            ego = roll(),
            loyalty = roll(),
            penaltyProne = roll(),
            durabilityUnderLoad = roll(),
        )
    }

    private fun pickDevCurve(rng: Rng): DevCurve {
        val roll = rng.nextInt(1000)
        return when {
            roll < 200 -> DevCurve.SLOW        // 20%
            roll < 700 -> DevCurve.NORMAL      // 50%
            roll < 900 -> DevCurve.QUICK       // 20%
            roll < 980 -> DevCurve.SUPERSTAR   //  8%
            else -> DevCurve.X_FACTOR          //  2%
        }
    }

    private fun pickAge(rng: Rng, bias: Int): Int =
        (26 + bias + rng.gaussian(0f, 3.2f)).roundToInt().coerceIn(21, 38)

    private fun measurements(position: Position, rng: Rng): Pair<Int, Int> {
        val (hMin, hMax, wMin, wMax) = FRAME[position]!!
        val height = hMin + rng.nextInt(hMax - hMin + 1)
        // Weight tracks height within the position's range.
        val heightPct = (height - hMin).toFloat() / maxOf(1, hMax - hMin)
        val centre = wMin + (wMax - wMin) * heightPct
        val weight = (centre + rng.gaussian(0f, 8f)).roundToInt().coerceIn(wMin - 12, wMax + 12)
        return height to weight
    }

    private data class Frame(val hMin: Int, val hMax: Int, val wMin: Int, val wMax: Int)

    private val FRAME: Map<Position, Frame> = mapOf(
        Position.QB to Frame(72, 78, 205, 240),
        Position.RB to Frame(68, 74, 195, 230),
        Position.FB to Frame(70, 74, 235, 255),
        Position.WR to Frame(69, 77, 175, 220),
        Position.TE to Frame(74, 79, 240, 265),
        Position.LT to Frame(76, 80, 300, 325),
        Position.LG to Frame(74, 78, 300, 330),
        Position.C to Frame(74, 78, 295, 320),
        Position.RG to Frame(74, 78, 300, 330),
        Position.RT to Frame(76, 80, 300, 325),
        Position.EDGE to Frame(74, 79, 245, 275),
        Position.DT to Frame(73, 78, 295, 340),
        Position.LB to Frame(72, 76, 225, 250),
        Position.CB to Frame(69, 74, 180, 205),
        Position.S to Frame(70, 75, 195, 215),
        Position.K to Frame(70, 75, 185, 210),
        Position.P to Frame(71, 76, 190, 220),
        Position.LS to Frame(73, 77, 240, 255),
    )

    private const val MAX_CONVERGE_PASSES = 40
}
