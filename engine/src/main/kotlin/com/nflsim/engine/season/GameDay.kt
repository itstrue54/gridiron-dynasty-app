package com.nflsim.engine.season

import com.nflsim.engine.gen.RosterGenerator
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall

/**
 * Game-day inactives (CBA Article 25): a club dresses 47 of its 53, or 48 if
 * at least eight of them are offensive linemen, and declares the rest
 * inactive. Men hurt but not on reserve are inactive first, since they could
 * not play anyway; the healthy scratches make up the rest.
 *
 * A club scratches the man whose absence costs it least: the deepest at his
 * position for a roster's usual depth there, the lower-rated first. It never
 * scratches a position down below what a game needs - two quarterbacks, a
 * kicker, a punter and a long snapper - nor a lineman when that would cost it
 * the 48th place. The user's club may name its own scratches (DepthPins.inactive);
 * the club's own choice fills in for any it leaves unnamed.
 *
 * A club may also call up two of its practice squad for the game, each man at
 * most three times a season, and they dress within the same 47 or 48. A club
 * calls one up only when it is short: a position below what a game needs,
 * fewer than eight linemen, a position thinner than it wants (all but one of
 * a roster's usual depth there), or fewer fit men than it may dress. The user may
 * name his own call-ups (DepthPins.callUp), who push his deepest men out.
 */
object GameDay {

    /** The players a club dresses. */
    const val ACTIVES = 47

    /** The players a club dresses when at least [LINEMEN] of them are offensive linemen. */
    const val ACTIVES_WITH_LINE = 48
    const val LINEMEN = 8

    /** Practice-squad men a club may call up for one game. */
    const val CALL_UPS = 2

    /** Games a practice-squad man may be called up for in a season. */
    const val CALL_UP_LIMIT = 3

    /** A position a game cannot be played without, and how many it has to dress. */
    private val FLOOR = mapOf(Position.QB to 2, Position.K to 1, Position.P to 1, Position.LS to 1)

    /** How deep a roster usually runs at each position: the generator's template. */
    private val DEPTH = RosterGenerator.TEMPLATE.associate { (position, slots) -> position to slots.size }

    /**
     * Who of [eligible] (the men fit to play) dresses, with any of [squad]
     * called up: all of them if the club has no more than it may dress,
     * otherwise less the scratches - [named] first, in order, where they are
     * eligible and allowed, then the club's own choice. A man called up is
     * never the one scratched.
     */
    fun actives(
        eligible: List<Player>,
        offence: Scheme,
        defence: Scheme,
        named: List<Int> = emptyList(),
        squad: List<Player> = emptyList(),
        callUp: List<Int> = emptyList(),
    ): List<Player> {
        val up = callUps(eligible, squad, offence, defence, callUp)
        val dressed = (eligible + up).toMutableList()
        val byId = dressed.associateBy { it.id.v }
        for (id in named) {
            if (dressed.size <= limit(dressed)) break
            val man = byId[id] ?: continue
            if (man in dressed && man !in up && canSit(dressed, man)) dressed.remove(man)
        }
        while (dressed.size > limit(dressed)) {
            dressed.remove(sitOrder(dressed, offence, defence, keep = up.toSet()).firstOrNull() ?: break)
        }
        return dressed
    }

    /**
     * The practice-squad men a club calls up: [named] first, where they are
     * fit and have call-ups left, then any the club needs - a position below
     * what a game needs, then an eighth lineman, then the position thinnest
     * against all but one of a roster's usual depth there, then the position
     * furthest short of that depth while it has fewer fit men than it may dress.
     */
    fun callUps(
        eligible: List<Player>,
        squad: List<Player>,
        offence: Scheme,
        defence: Scheme,
        named: List<Int> = emptyList(),
    ): List<Player> {
        fun rating(p: Player) = overall(p, if (p.position.isOffense) offence else defence)
        val open = squad.filter { it.injuryWeeks == 0 && it.elevations < CALL_UP_LIMIT }.toMutableList()
        val chosen = mutableListOf<Player>()
        fun take(man: Player) { chosen += man; open.remove(man) }
        named.forEach { id -> if (chosen.size < CALL_UPS) open.firstOrNull { it.id.v == id }?.let(::take) }
        while (chosen.size < CALL_UPS && open.isNotEmpty()) {
            val dressed = eligible + chosen
            fun best(position: Position) = open.filter { it.position == position }.maxByOrNull(::rating)
            val floor = FLOOR.entries.firstOrNull { (pos, need) -> dressed.count { it.position == pos } < need && best(pos) != null }
            val line = dressed.count { it.position.group == PositionGroup.OL } < LINEMEN
            // A position thinner than a club wants on game day: all but one of
            // a roster's usual depth there, where it runs three or more deep.
            val thin = open.map { it.position }.distinct()
                .filter { pos -> (DEPTH[pos] ?: 0) >= 3 && dressed.count { it.position == pos } < DEPTH.getValue(pos) - 1 }
                .maxByOrNull { pos -> (DEPTH.getValue(pos) - 1 - dressed.count { it.position == pos }).toFloat() / DEPTH.getValue(pos) }
            val pick = when {
                floor != null -> best(floor.key)
                line && open.any { it.position.group == PositionGroup.OL } ->
                    open.filter { it.position.group == PositionGroup.OL }.maxByOrNull(::rating)
                thin != null -> best(thin)
                dressed.size < limit(dressed) -> open.maxByOrNull { p ->
                    // The position furthest short of a roster's depth there, the better man first.
                    val depth = DEPTH[p.position] ?: 1
                    (depth - dressed.count { it.position == p.position }).toFloat() / depth * 100 + rating(p) / 100f
                }
                else -> null
            } ?: break
            take(pick)
        }
        return chosen
    }

    /** How many a club may dress: 48 with eight linemen among them, 47 without. */
    fun limit(dressed: List<Player>): Int =
        if (dressed.count { it.position.group == PositionGroup.OL } >= LINEMEN) ACTIVES_WITH_LINE else ACTIVES

    /**
     * Whether a club dressing [dressed] can sit [man]: not below what a game
     * needs at his position, and not the eighth lineman, whose place in the
     * 48 would go with him - one man out would become two.
     */
    fun canSit(dressed: List<Player>, man: Player): Boolean {
        if (dressed.count { it.position == man.position } - 1 < (FLOOR[man.position] ?: 0)) return false
        return !(man.position.group == PositionGroup.OL &&
            dressed.count { it.position.group == PositionGroup.OL } == LINEMEN)
    }

    /**
     * The men a club dressing [dressed] would sit, first choice first: the
     * deepest at his position for how deep a roster runs there, the
     * lower-rated of two as deep.
     */
    fun sitOrder(dressed: List<Player>, offence: Scheme, defence: Scheme, keep: Set<Player> = emptySet()): List<Player> {
        fun rating(p: Player) = overall(p, if (p.position.isOffense) offence else defence)
        val depth = dressed.groupBy { it.position }.values.flatMap { men ->
            men.sortedByDescending(::rating).mapIndexed { i, p -> p to (i + 1).toFloat() / (DEPTH[p.position] ?: 1) }
        }.toMap()
        return dressed.filter { it !in keep && canSit(dressed, it) }
            .sortedWith(compareByDescending<Player> { depth.getValue(it) }.thenBy(::rating))
    }
}
