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
 */
object GameDay {

    /** The players a club dresses. */
    const val ACTIVES = 47

    /** The players a club dresses when at least [LINEMEN] of them are offensive linemen. */
    const val ACTIVES_WITH_LINE = 48
    const val LINEMEN = 8

    /** A position a game cannot be played without, and how many it has to dress. */
    private val FLOOR = mapOf(Position.QB to 2, Position.K to 1, Position.P to 1, Position.LS to 1)

    /** How deep a roster usually runs at each position: the generator's template. */
    private val DEPTH = RosterGenerator.TEMPLATE.associate { (position, slots) -> position to slots.size }

    /**
     * Who of [eligible] (the men fit to play) dresses: all of them if the
     * club has no more than it may dress, otherwise less the scratches -
     * [named] first, in order, where they are eligible and allowed, then the
     * club's own choice.
     */
    fun actives(
        eligible: List<Player>,
        offence: Scheme,
        defence: Scheme,
        named: List<Int> = emptyList(),
    ): List<Player> {
        val dressed = eligible.toMutableList()
        val byId = dressed.associateBy { it.id.v }
        for (id in named) {
            if (dressed.size <= limit(dressed)) break
            val man = byId[id] ?: continue
            if (man in dressed && canSit(dressed, man)) dressed.remove(man)
        }
        while (dressed.size > limit(dressed)) {
            dressed.remove(sitOrder(dressed, offence, defence).firstOrNull() ?: break)
        }
        return dressed
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
    fun sitOrder(dressed: List<Player>, offence: Scheme, defence: Scheme): List<Player> {
        fun rating(p: Player) = overall(p, if (p.position.isOffense) offence else defence)
        val depth = dressed.groupBy { it.position }.values.flatMap { men ->
            men.sortedByDescending(::rating).mapIndexed { i, p -> p to (i + 1).toFloat() / (DEPTH[p.position] ?: 1) }
        }.toMap()
        return dressed.filter { canSit(dressed, it) }
            .sortedWith(compareByDescending<Player> { depth.getValue(it) }.thenBy(::rating))
    }
}
