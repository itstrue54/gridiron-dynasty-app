package com.nflsim.data.migration

import com.nflsim.engine.gen.StaffGenerator
import com.nflsim.engine.gen.Tendencies
import com.nflsim.engine.model.CoachId
import com.nflsim.engine.model.Staff
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.Dynasty

/**
 * Save migration, as a chain (docs/SPEC.md 9.1): one step for each save
 * version, taking a dynasty written by version `from` to what version
 * `from + 1` reads. A save is walked up the chain one step at a time to the
 * current version, so every step only ever has to know about the one before
 * it.
 *
 * Every model change that alters the shape of a Dynasty needs a step here in
 * the same commit - a dynasty game that eats saves on update is a dead
 * dynasty game. A step with nothing to move is still written down, with why.
 */
object Migrations {

    /** One step up the chain, and the reason it is the step it is. */
    class Step(val from: Int, val why: String, val apply: (Dynasty) -> Dynasty)

    val steps: List<Step> = listOf(
        Step(1, "coaching staffs arrived in M7", ::hireStaffs),
        Step(2, "draft picks became assets a club owns (SPEC 8.4)", ::handOutPicks),
        Step(3, "coaches gained tendencies (SPEC 5.4)", ::giveTendencies),
        Step(4, "careers and league history arrived (SPEC 9.2); nothing to move") { it },
        Step(5, "practice squads arrived (SPEC 6.1)", ::formSquads),
        Step(6, "the transactions wire arrived (SPEC 4.7); nothing to move") { it },
        Step(7, "box scores are archived (SPEC 9.2); nothing to move") { it },
        Step(8, "assisted tackles arrived (SPEC 5.9b); nothing to move") { it },
        Step(9, "in-season form arrived (SPEC 5.9a); nothing to move") { it },
        Step(10, "contract disputes arrived (SPEC 10.4); nothing to move") { it },
        Step(11, "the user's play-by-play is kept for the season (SPEC 9.2); nothing to move") { it },
        Step(12, "the user can hand his roster moves to the front office (SPEC 6.1); nothing to move") { it },
        Step(13, "the tuning table learned who comes back after being let go (SPEC 7); nothing to move") { it },
        // The carryover field was always in the save, at zero; a club starts
        // carrying room at its next offseason, the first year-end it sees.
        Step(14, "cap carryover arrived (SPEC 8.1); nothing to move") { it },
        Step(15, "the league's clubs haggle over contract demands (SPEC 8.3); nothing to move") { it },
        // An old save's pending demands have named nothing yet; 0 is right for them.
        Step(16, "a demand remembers the figure his agent named (SPEC 8.3); nothing to move") { it },
        Step(17, "general managers have names (SPEC 8.2)", ::nameGms),
        // An old save decodes with the default names - the game's own, unnamed
        // league and its American and Continental conferences - which is what
        // it always showed.
        Step(18, "the league and its conferences can be named (SPEC 9.4); nothing to move") { it },
        Step(19, "the tuning table learned when a defence plays prevent (SPEC 5.4); nothing to move") { it },
        // The play caller's and fourth down's coefficients moved into the
        // table at the values they had in code: an old save plays the same.
        Step(20, "the tuning table holds how coordinators call plays and fourth down (SPEC 12); nothing to move") { it },
        // A save stores only the tuning its user moved off the defaults, so an
        // old save takes the new pace and clock with everything else; one
        // whose user moved a runoff slider keeps his figure.
        Step(21, "the clock has a two-minute warning, a hurry-up and timeouts (SPEC 5.10); nothing to move") { it },
    )

    /** The dynasty a save of version [from] holds, as version [to] reads it. */
    fun migrate(dynasty: Dynasty, from: Int, to: Int): Dynasty {
        require(from <= to) { "save was written by version $from, this build reads $to" }
        return steps.filter { it.from in from until to }
            .sortedBy { it.from }
            .fold(dynasty) { d, step -> step.apply(d) }
            .also {
                val missing = (from until to).filter { v -> steps.none { it.from == v } }
                check(missing.isEmpty()) { "no migration from version ${missing.first()}" }
            }
    }

    /**
     * 1 -> 2: coaching staffs arrived in M7. A save written before them has no
     * staff field, so every team decodes as [Staff.UNASSIGNED] and would
     * develop its players at a flat league average forever. Hire each of them
     * a staff off the dynasty's own seed, so the same save always migrates to
     * the same coaches.
     */
    private fun hireStaffs(dynasty: Dynasty): Dynasty {
        val league = dynasty.league
        if (league.teams.none { it.staff == Staff.UNASSIGNED }) return dynasty

        val coaches = league.coaches.toMutableMap()
        var nextCoachId = (coaches.keys.maxOfOrNull { it.v } ?: 0) + 1
        val rng = SplitMixRng(dynasty.seed)

        val teams = league.teams.map { team ->
            if (team.staff != Staff.UNASSIGNED) return@map team
            val (staff, hired) = StaffGenerator.generate(
                offenseScheme = team.offenseScheme,
                defenseScheme = team.defenseScheme,
                nextId = { CoachId(nextCoachId++) },
                rng = rng.split("staff|team=${team.id.v}"),
            )
            hired.forEach { coaches[it.id] = it }
            team.copy(staff = staff)
        }
        return dynasty.copy(league = league.copy(teams = teams, coaches = coaches))
    }

    /**
     * 2 -> 3: draft picks became assets a club owns (SPEC 8.4). A save written
     * before them has none, so every club is handed its own picks for the
     * next three drafts - the one after the season in progress and the two
     * after that.
     */
    private fun handOutPicks(dynasty: Dynasty): Dynasty {
        val league = dynasty.league
        if (league.picks.isNotEmpty()) return dynasty
        val picks = com.nflsim.engine.offseason.Picks.own(
            league.teams.map { it.id },
            (dynasty.year + 1)..(dynasty.year + com.nflsim.engine.offseason.Picks.WINDOW))
        return dynasty.copy(league = league.copy(picks = picks))
    }

    /**
     * 3 -> 4: coaches gained tendencies (SPEC 5.4). A save written before them
     * has coaches with none, who would all call games straight off their
     * schemes. Each draws his from his own id off the dynasty's seed, so the
     * same save always migrates to the same staffs.
     */
    private fun giveTendencies(dynasty: Dynasty): Dynasty {
        val league = dynasty.league
        val coaches = league.coaches.mapValues { (_, c) -> Tendencies.forExisting(c, dynasty.seed) }
        return dynasty.copy(league = league.copy(coaches = coaches))
    }

    /*
     * 4 -> 5: careers and league history arrived. Nothing needs moving: a save
     * written before them has no history, and both start accumulating from
     * the next season the save plays. What is gone is gone - a dynasty carried
     * over from version 4 has no record of the seasons it already played,
     * which is honest about what was never written down.
     */

    /**
     * 5 -> 6: practice squads arrived. A save from before them has sixteen
     * empty places per club; they are filled the way a new league's are, from
     * whoever is on the street and then from camp bodies, seeded off the
     * dynasty so the same save always gets the same squads.
     */
    private fun formSquads(dynasty: Dynasty): Dynasty =
        dynasty.copy(league = com.nflsim.engine.season.PracticeSquads.fill(dynasty.league, dynasty.seed))

    /**
     * 17 -> 18: general managers have names. A save from before them has a
     * blank in every chair; each club's GM is named the way a new league names
     * him, off the dynasty's seed and the club, so the same save always gets
     * the same names. A name the save already carries is kept.
     */
    private fun nameGms(dynasty: Dynasty): Dynasty {
        val league = dynasty.league
        val rng = SplitMixRng(dynasty.seed)
        val teams = league.teams.map { team ->
            if (team.gm.name.isNotBlank()) team
            else team.copy(gm = team.gm.copy(name = com.nflsim.engine.gen.LeagueGenerator.gmName(rng.split("team=${team.abbrev}"))))
        }
        return dynasty.copy(league = league.copy(teams = teams))
    }

    /*
     * 6 -> 7 onward carry nothing to move, each for its own reason:
     *  6: the transactions wire starts empty and fills from the next move.
     *  7: a save from before box scores keeps the games it played as results.
     *  8: seasons already played have no assists; their tackles mean what they meant.
     *  9: every player starts level on form, which is what a new season does.
     * 10: nobody is asking about his contract; the asking starts next season.
     * 11: archived games keep their box scores; the logs start with the next game.
     * 12: every save starts with the roster moves the user's own.
     */
}
