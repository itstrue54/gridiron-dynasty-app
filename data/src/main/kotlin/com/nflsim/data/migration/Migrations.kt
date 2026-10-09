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
        Step(22, "a play can end out of bounds and stop the clock (SPEC 5.10); nothing to move") { it },
        Step(23, "stadiums have a climate, and games weather (SPEC 5.10)", ::stadiumClimates),
        Step(24, "coordinators adapt to each other during a game (SPEC 5.4); nothing to move") { it },
        Step(25, "the tuning table knows how often clubs call the user with trade offers (SPEC 8.4); nothing to move") { it },
        Step(26, "the news can carry a deadline trade (SPEC 8.4); nothing to move") { it },
        // An old save has nobody on the block, which is the empty default.
        Step(27, "the user has a trade block, and its tuning (SPEC 8.4); nothing to move") { it },
        Step(28, "the news can carry a storyline (SPEC 10.4); nothing to move") { it },
        Step(29, "the tuning table knows who holds out of camp, and what it costs (SPEC 10.4); nothing to move") { it },
        // The trade, contract and player-intent numbers moved into the table
        // at the values they had in code: an old save plays the same.
        Step(30, "the tuning table holds trades, player intent and the contract market (SPEC 12); nothing to move") { it },
        Step(31, "the tuning table holds the coaching carousel, the draft, team needs, camp and the honours (SPEC 12); nothing to move") { it },
        Step(32, "the tuning table holds scouting, roster value, production pricing and the sim's last literals (SPEC 12); nothing to move") { it },
        // Old box scores have no carry fumbles counted apart: 0 is all they
        // can say, and the archive reads team totals, not that split.
        Step(33, "run and pass rebalanced, fumbles off sacks and catches, box scores count carry fumbles apart (SPEC 13.2); nothing to move") { it },
        Step(34, "catches break open for long gains, runs break longer, and coaches go for it more in easy kicking range (SPEC 13.2); nothing to move") { it },
        Step(35, "screens, a deeper-only throw tail, and completion falling faster with depth (SPEC 5.7); nothing to move") { it },
        // Box scores already saved keep the possession they were played with.
        Step(36, "a quarter ends when its clock does, possession counts the kicks, run runoff 37 s (SPEC 5.10); nothing to move") { it },
        Step(37, "the tuning table holds the kick runoffs, fourth down's field lines, the play caller's depth and front lines and the punter's aim (SPEC 12); nothing to move") { it },
        Step(38, "growth slows near the top (SPEC 7.1): a saved league takes it from its next offseason; nothing to move") { it },
        Step(39, "stop routes are caught standing, and passing is re-tuned around them (SPEC 5.7); nothing to move") { it },
        Step(40, "a defence loads the box against a predictable run offence far more readily (SPEC 5.4); nothing to move") { it },
        // A dynasty saved before player editing existed reads with it off.
        Step(41, "a dynasty chooses whether its players may be edited (SPEC 10.5); nothing to move") { it },
        // An old save's general managers read as in the chair since before
        // the league began, which is what they were, and nobody is out of work.
        Step(42, "the user hires and fires coaches and general managers, and owners fire theirs (SPEC 4.7); nothing to move") { it },
        // A save stores only the tuning its user moved, so an old one takes the
        // new coefficients at their defaults.
        Step(43, "the tuning table learned how a head coach's discipline moves his side's flags (SPEC 5.8); nothing to move") { it },
        Step(44, "every coach rating has a job: game plans, motivation and evaluation (SPEC 4.7); nothing to move") { it },
        // A dynasty saved in the spring window had agreed to hire nobody yet.
        Step(45, "the user can hire the coaches the league lets go this spring (SPEC 4.7); nothing to move") { it },
        // An old agreement names a man by id, as it always did; old reports promoted nobody.
        Step(46, "clubs promote other clubs' coordinators to head coach, under the NFL's rules (SPEC 4.7); nothing to move") { it },
        // Every coach has always carried an age; from this spring on it counts.
        Step(47, "every coach ages, and coaches in jobs retire (SPEC 4.7); nothing to move") { it },
        // A dynasty saved in the spring window had agreed to hire no general manager yet.
        Step(48, "the user can hire the general managers the owners let go this spring (SPEC 8.2); nothing to move") { it },
        Step(49, "a finished offseason clears the spring's agreements (SPEC 4.7)", ::clearStaleAgreements),
        // A coach keeps the ratings he has; his career moves them from his next spring on.
        Step(50, "coaches have careers, and position coaches can be promoted to coordinator (SPEC 4.7); nothing to move") { it },
        // An old report's promotions were all coordinators made head coaches, which is what the new fields default to.
        Step(51, "clubs promote other clubs' position coaches to coordinator (SPEC 4.7); nothing to move") { it },
        // Nobody has named an inactive yet, so every club's own choice decides, as for a new league.
        Step(52, "clubs declare game-day inactives, and the user may name his (SPEC 6.1); nothing to move") { it },
        // Nobody has been called up yet this season, and nobody is named to be.
        Step(53, "clubs call up practice-squad men for game day (SPEC 6.1); nothing to move") { it },
        // Units pick themselves until the user pins a core special teamer; the new tuning takes its defaults.
        Step(54, "special teams play as units read from their men's ratings (SPEC 5.10); nothing to move") { it },
        // Seasons already played had no returns or coverage tackles counted; the counting starts with the next game.
        Step(55, "box scores count returns and coverage tackles (SPEC 5.10); nothing to move") { it },
        // A save's tuning takes the new defaults; its pinned returners stay pinned.
        Step(56, "clubs keep their starters off returns (SPEC 5.10); nothing to move") { it },
        // An old game's lines read as snaps; the screen knows its kicks by their words.
        Step(57, "each line of the play-by-play says what it is (SPEC 10); nothing to move") { it },
        // An old game's snaps carry no calls and name no defender; the box at the top shows just the line.
        Step(58, "each snap names both sides' calls and the defender it turned on (SPEC 10); nothing to move") { it },
        // Games already played counted no reps; the tally starts with the next game.
        Step(59, "box scores tally each defender's reps won and lost (SPEC 10); nothing to move") { it },
        // An old game's lines name no one hurt; the game's own list still does.
        Step(60, "each snap names who was hurt on it (SPEC 10); nothing to move") { it },
        // An old game's log has no kickoff lines; its punts read as they did.
        Step(61, "kickoffs and their returns are lines of the play-by-play (SPEC 10); nothing to move") { it },
        // The tuning table's injuries gain camp's rate, defaulted on read.
        Step(62, "training camp comes before the cut to 53, and can hurt a man (SPEC 7); nothing to move") { it },
        // The tuning table's AI gains camp's thresholds, defaulted on read.
        Step(63, "clubs cut and trade at camp on what it showed (SPEC 7); nothing to move") { it },
    )

    /**
     * Before version 50 an offseason left the spring's agreements on the
     * dynasty, holding jobs the user had filled. Outside the spring window
     * any agreement is one of those, and goes. In the window an agreement
     * may be this spring's or last spring's: the ones that no longer hold -
     * a job already filled, a man already hired - go, and the rest stay.
     */
    private fun clearStaleAgreements(d: Dynasty): Dynasty = when {
        d.pendingHires.isEmpty() && d.pendingGm == null -> d
        d.phase != com.nflsim.engine.season.DynastyPhase.OFFSEASON -> d.copy(pendingHires = emptyList(), pendingGm = null)
        else -> com.nflsim.engine.offseason.Staffing.dropBrokenAgreements(d)
    }

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
     * A save from before weather has every stadium in the default climate.
     * Each club gets its region back from teams.json, found by its stadium
     * (which an imported league keeps from its slot) or, failing that, its
     * abbreviation. A club neither finds keeps the default.
     */
    private fun stadiumClimates(dynasty: Dynasty): Dynasty {
        val seeds = com.nflsim.engine.gen.LeagueGenerator.teamSeeds
        val teams = dynasty.league.teams.map { team ->
            val seed = seeds.firstOrNull { it.stadium.name == team.stadium.name }
                ?: seeds.firstOrNull { it.abbrev == team.abbrev }
                ?: return@map team
            team.copy(stadium = team.stadium.copy(climate = seed.stadium.climate))
        }
        return dynasty.copy(league = dynasty.league.copy(teams = teams))
    }

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
