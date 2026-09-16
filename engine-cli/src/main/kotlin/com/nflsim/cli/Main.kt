package com.nflsim.cli

import com.nflsim.data.roster.RosterExporter
import com.nflsim.data.roster.RosterImporter
import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.gen.NameGenerator
import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.HiddenTraits
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.Ratings
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.model.Conference
import com.nflsim.engine.season.LeagueLeaders
import com.nflsim.engine.season.PlayoffRound
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import com.nflsim.engine.season.Schedule
import com.nflsim.engine.season.SeasonSimulator
import com.nflsim.engine.season.Standings
import com.nflsim.engine.sim.GameCalibration
import com.nflsim.engine.sim.GameSimulator
import com.nflsim.engine.sim.GameTeam
import com.nflsim.engine.sim.Side
import com.nflsim.engine.sim.DefenseUnit
import com.nflsim.engine.sim.DefensiveFront
import com.nflsim.engine.sim.DepthChart
import com.nflsim.engine.sim.OffenseUnit
import com.nflsim.engine.sim.PlayCaller
import com.nflsim.engine.sim.PlayContext
import com.nflsim.engine.sim.PlayOutcome
import com.nflsim.engine.sim.PlaySimulator
import com.nflsim.engine.sim.PlayState
import com.nflsim.engine.sim.Personnel
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.rng.SplitMixRng

private const val DEFAULT_SEED = 2026L
private const val YEAR = 2026

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "league" -> league(seedFrom(args))
        "export" -> export(args, seedFrom(args))
        "import" -> importRoster(args.getOrNull(1))
        "template" -> template(args)
        "playdemo" -> gameCal(args)
        "snap" -> snap(args)
        "game" -> game(args)
        "gamecal" -> gameCal(args)
        "season" -> season(args)
        "dynasty" -> dynasty(args)
        "roster" -> roster(args.getOrNull(1)?.uppercase(), seedFrom(args))
        "schemes" -> listSchemes()
        "schemefit" -> schemeFitDemo()
        "rngdemo" -> rngDemo()
        "calibrate" -> gameCal(args)
        "simseason" -> season(args)
        else -> help()
    }
}

private fun seedFrom(args: Array<String>): Long =
    args.firstOrNull { it.startsWith("--seed=") }?.removePrefix("--seed=")?.toLongOrNull()
        ?: DEFAULT_SEED

private fun help() {
    println(
        """
        NFL Sim Text - headless engine tools

          league [--seed=N]       Generate a 32-team league and show the divisions
          roster ABBR [--seed=N]  Print one team's 53-man roster
          export [ABBR] [--out=F] Write rosters to CSV (all teams if no ABBR)
          import FILE             Read a roster CSV and report what it found
          template [--out=F]      Write a starter roster CSV you can fill in
          snap [--n=N]            Sim N snaps and show the engine's working
          game [HOME] [AWAY]      Sim one full game and print the box score
          season [--seed=N]       Sim a full season: standings, playoffs, leaders, awards
          dynasty [--years=N]     Sim N years and report how the league holds up
          gamecal [--games=N]     Sim N games and check every stat against target bands
                                  (playdemo and calibrate are aliases)
          schemes               List the shipped schemes
          schemefit             Show how scheme choice changes a player's value
          rngdemo               Prove the RNG is deterministic
          calibrate             Run N seasons and report stat bands   (M4)
          simseason             Simulate one season                   (M5)

        Examples:
          ./gradlew :engine-cli:run --args="league"
          ./gradlew :engine-cli:run --args="roster KC"
          ./gradlew :engine-cli:run --args="export KC --out=kc.csv"
          ./gradlew :engine-cli:run --args="import kc.csv"
        """.trimIndent()
    )
}

private fun league(seed: Long) {
    val t0 = System.nanoTime()
    val league = LeagueGenerator.generate(YEAR, seed)
    val ms = (System.nanoTime() - t0) / 1_000_000

    println("NFL Sim Text - ${league.year} league  (seed $seed)")
    println("${league.teams.size} teams, ${league.players.size} players, generated in ${ms}ms")
    println()

    val ranked = league.teams.associateWith { team ->
        league.roster(team.id).map { overall(it) }.sortedDescending().take(22).average()
    }

    for ((key, teams) in league.divisions().entries.sortedBy { "${it.key.first}${it.key.second}" }) {
        val (conference, division) = key
        println("${conference.label} ${division.name.lowercase().replaceFirstChar { it.uppercase() }}")
        teams.sortedByDescending { ranked[it] }.forEach { team ->
            val off = SchemeCatalog[team.offenseScheme].name
            val def = SchemeCatalog[team.defenseScheme].name
            println(
                "  %-4s %-24s  top22 %.1f   %-24s %s".format(
                    team.abbrev, team.name, ranked[team], off, def
                )
            )
        }
        println()
    }

    val best = league.players.maxByOrNull { overall(it) }!!
    val bestTeam = league.team(best.teamId!!)
    println("Best player in the league:")
    println("  ${best.name}, ${best.position} (${best.archetype.label}), ${overall(best)} ovr")
    println("  ${bestTeam.name}, age ${best.age(league.year)}, ${best.college}")
    println("  fit in his own offense: %.2f".format(
        if (best.position.isOffense) schemeFit(best, SchemeCatalog[bestTeam.offenseScheme])
        else schemeFit(best, SchemeCatalog[bestTeam.defenseScheme])
    ))
}

private fun roster(abbrev: String?, seed: Long) {
    if (abbrev == null) {
        println("Usage: roster ABBR   (e.g. roster KC)")
        return
    }
    val league = LeagueGenerator.generate(YEAR, seed)
    val team = league.teams.firstOrNull { it.abbrev == abbrev }
    if (team == null) {
        println("No team '$abbrev'. Try one of: ${league.teams.joinToString(" ") { it.abbrev }}")
        return
    }

    val offense = SchemeCatalog[team.offenseScheme]
    val defense = SchemeCatalog[team.defenseScheme]
    println("${team.name}  (${team.divisionName})")
    println("${team.stadium.name}${if (team.stadium.domed) " (dome)" else ""}, " +
            "cap ${team.stadium.capacity}, noise ${team.stadium.crowdNoise}")
    println("Offense: ${offense.name}    Defense: ${defense.name}")
    println()
    println("%-3s %-22s %-5s %-22s %3s %3s %3s %5s %-4s".format(
        "#", "NAME", "POS", "ARCHETYPE", "OVR", "FIT", "AGE", "HT/WT", ""))
    println("-".repeat(84))

    league.roster(team.id)
        .sortedWith(compareBy({ RosterOrder.of(it.position) }, { -overall(it) }))
        .forEachIndexed { i, p ->
            val scheme = if (p.position.isOffense) offense else defense
            val fit = schemeFit(p, scheme)
            val schemeOvr = overall(p, scheme)
            val flag = when {
                fit >= 0.95f -> "**"
                fit <= 0.55f -> "!!"
                else -> ""
            }
            println("%-3d %-22s %-5s %-22s %3d %3d %3d %5s %-4s".format(
                i + 1, p.name, p.position.label, p.archetype.label,
                overall(p), schemeOvr, p.age(league.year),
                "%d-%d".format(p.heightIn / 12, p.heightIn % 12), flag
            ))
        }
    println()
    println("OVR = on paper.  FIT = what he is actually worth in this scheme.")
    println("**  perfect scheme fit        !!  badly miscast")
}

private object RosterOrder {
    private val order = listOf(
        Position.QB, Position.RB, Position.FB, Position.WR, Position.TE,
        Position.LT, Position.LG, Position.C, Position.RG, Position.RT,
        Position.EDGE, Position.DT, Position.LB, Position.CB, Position.S,
        Position.K, Position.P, Position.LS,
    )
    fun of(p: Position) = order.indexOf(p)
}

private fun listSchemes() {
    println("OFFENSE")
    SchemeCatalog.offensive.forEach {
        println("  %-20s %-26s pass %.0f%%  tempo %.2f".format(
            it.id, it.name, it.basePassRate * 100, it.tempo))
    }
    println()
    println("DEFENSE")
    SchemeCatalog.defensive.forEach {
        println("  %-20s %-26s blitz %.0f%%  man %.0f%%".format(
            it.id, it.name, it.blitzRate * 100, it.manZoneSplit * 100))
    }
    println()
    println("Name pools: ${NameGenerator.combinations} combinations, ${NameGenerator.colleges.size} colleges")
}

private fun schemeFitDemo() {
    val backs = listOf(
        "One-Cut Zone back" to Archetype.ONE_CUT_ZONE,
        "Power back" to Archetype.POWER_BACK,
        "Receiving back" to Archetype.RECEIVING_BACK,
    )
    println("The same 85-rated running back, three different body types,")
    println("evaluated in all seven offensive schemes. Third year in the system.")
    println()
    print("%-24s".format(""))
    backs.forEach { print("%-22s".format(it.first)) }
    println()
    println("-".repeat(24 + backs.size * 22))
    for (scheme in SchemeCatalog.offensive) {
        print("%-24s".format(scheme.name))
        for ((_, archetype) in backs) {
            val p = Player(
                id = PlayerId(1), firstName = "Test", lastName = "Back",
                position = Position.RB, archetype = archetype,
                birthYear = 2000, heightIn = 71, weightLb = 215, college = "Test",
                ratings = Ratings.uniform(85), traits = HiddenTraits.AVERAGE,
                yearsInSystem = 3,
            )
            print("%-22s".format("%d ovr  (fit %.2f)".format(overall(p, scheme), schemeFit(p, scheme))))
        }
        println()
    }
    println()
    println("Same player on paper: 85. Where he lands depends on who drafts him.")
}

private fun rngDemo() {
    println("Two independent RNGs, same seed:")
    val a = SplitMixRng(2026L)
    val b = SplitMixRng(2026L)
    repeat(5) { println("  %d   %d".format(a.nextInt(100), b.nextInt(100))) }
    println()
    println("Same game re-simmed from a split stream, twice:")
    repeat(2) {
        val game = SplitMixRng(2026L).split("year=2026|week=7|game=3")
        println("  " + (0 until 8).map { game.nextInt(100) })
    }
    println()
    println("Identical both times. That is the whole point.")
}

// ---------------------------------------------------------------------------
// Roster import / export
//
// The game ships fictional. This is how you bring your own roster in: a CSV
// you supply, at whatever level of detail you happen to have.
// ---------------------------------------------------------------------------

private fun outPath(args: Array<String>): String? =
    args.firstOrNull { it.startsWith("--out=") }?.removePrefix("--out=")

private fun export(args: Array<String>, seed: Long) {
    val abbrev = args.getOrNull(1)?.takeIf { !it.startsWith("--") }?.uppercase()
    val league = LeagueGenerator.generate(YEAR, seed)

    val csv = if (abbrev == null) {
        RosterExporter.toCsv(league)
    } else {
        RosterExporter.teamToCsv(league, abbrev) ?: run {
            println("No team '$abbrev'. Try: ${league.teams.joinToString(" ") { it.abbrev }}")
            return
        }
    }

    val target = outPath(args)
    if (target == null) {
        println(csv.lineSequence().take(4).joinToString("\n"))
        println("... (${csv.lineSequence().count() - 1} players)")
        println()
        println("Add --out=roster.csv to write the whole thing to a file.")
    } else {
        java.io.File(target).writeText(csv)
        val rows = csv.lineSequence().count() - 1
        println("Wrote $rows players to $target")
        println("Edit it in a spreadsheet, then: import $target")
    }
}

private fun importRoster(path: String?) {
    if (path == null) {
        println("Usage: import FILE.csv")
        return
    }
    val file = java.io.File(path)
    if (!file.exists()) {
        println("No such file: ${file.absolutePath}")
        return
    }

    val result = RosterImporter.import(file.readText(), YEAR)
    println(result.report.summary())

    if (result.players.isEmpty()) return

    println()
    println("First few players as the engine now sees them:")
    println("%-24s %-5s %-22s %3s %3s %-6s".format("NAME", "POS", "ARCHETYPE", "OVR", "AGE", "DEV"))
    println("-".repeat(70))
    result.players.take(12).forEach { p ->
        println("%-24s %-5s %-22s %3d %3d %-6s".format(
            p.name, p.position.label, p.archetype.label,
            overall(p), p.age(YEAR), p.traits.developmentCurve.label))
    }
    if (result.players.size > 12) println("... and ${result.players.size - 12} more")
}

private fun template(args: Array<String>) {
    val text = RosterExporter.template()
    val target = outPath(args)
    if (target == null) {
        println(text)
    } else {
        java.io.File(target).writeText(text)
        println("Wrote a starter roster file to $target")
    }
}

// ---------------------------------------------------------------------------
// Play engine
// ---------------------------------------------------------------------------

private fun buildContext(offAbbrev: String, defAbbrev: String, seed: Long): Pair<PlayContext, String> {
    val league = LeagueGenerator.generate(YEAR, seed)
    val offTeam = league.teams.first { it.abbrev == offAbbrev }
    val defTeam = league.teams.first { it.abbrev == defAbbrev }
    val offScheme = SchemeCatalog[offTeam.offenseScheme]
    val defScheme = SchemeCatalog[defTeam.defenseScheme]
    val ctx = PlayContext(
        offense = OffenseUnit.from(
            DepthChart.auto(league.roster(offTeam.id), offScheme), Personnel.P_11, offScheme),
        defense = DefenseUnit.from(
            DepthChart.auto(league.roster(defTeam.id), defScheme),
            DefensiveFront.FOUR_THREE_OVER, defScheme),
        state = PlayState(),
        crowdNoise = defTeam.stadium.crowdNoise,
    )
    val header = "${offTeam.name} (${offScheme.name}) at ${defTeam.name} (${defScheme.name})"
    return ctx to header
}

private fun intArg(args: Array<String>, name: String, default: Int): Int =
    args.firstOrNull { it.startsWith("--$name=") }?.removePrefix("--$name=")?.toIntOrNull() ?: default

private fun snap(args: Array<String>) {
    val n = intArg(args, "n", 8)
    val (base, header) = buildContext("KC", "SEA", DEFAULT_SEED)
    val rng = SplitMixRng(intArg(args, "seed", 99).toLong())

    println(header)
    println()
    repeat(n) {
        val state = PlayState(
            down = 1 + rng.nextInt(3),
            distance = 1 + rng.nextInt(12),
            yardLine = 20 + rng.nextInt(60),
        )
        val ctx = base.copy(state = state)
        val off = PlayCaller.offense(ctx, rng)
        val def = PlayCaller.defense(ctx, rng)
        val r = PlaySimulator.simPlay(ctx, off, def, rng)

        println("%d and %d at the %d".format(state.down, state.distance,
            if (state.yardLine > 50) 100 - state.yardLine else state.yardLine))
        println("  ${def.front.label}, ${def.coverage.label}" +
                if (def.isBlitz) ", ${def.rushers} rushing" else "")
        println("  ${r.log.narrative}")
        r.penalty?.let { println("  FLAG: ${it.description}") }
        val working = r.log.values.entries.sortedBy { it.key }
            .joinToString("  ") { "%s=%.2f".format(it.key, it.value) }
        if (working.isNotEmpty()) println("  [$working]")
        println()
    }
}

// ---------------------------------------------------------------------------
// Full games
// ---------------------------------------------------------------------------

private fun gameTeam(league: com.nflsim.engine.model.League, abbrev: String, aggression: Float) =
    league.teams.first { it.abbrev == abbrev }.let { t ->
        GameTeam(t, league.roster(t.id),
            SchemeCatalog[t.offenseScheme], SchemeCatalog[t.defenseScheme], aggression)
    }

private fun game(args: Array<String>) {
    val league = LeagueGenerator.generate(YEAR, DEFAULT_SEED)
    val homeAbbr = args.getOrNull(1)?.takeIf { !it.startsWith("--") }?.uppercase() ?: "KC"
    val awayAbbr = args.getOrNull(2)?.takeIf { !it.startsWith("--") }?.uppercase() ?: "SEA"
    val seed = intArg(args, "seed", 7).toLong()

    if (league.teams.none { it.abbrev == homeAbbr } || league.teams.none { it.abbrev == awayAbbr }) {
        println("Teams: ${league.teams.joinToString(" ") { it.abbrev }}")
        return
    }

    val home = gameTeam(league, homeAbbr, 0.5f)
    val away = gameTeam(league, awayAbbr, 0.5f)
    val g = GameSimulator(home, away).simulate(SplitMixRng(seed))

    println("=".repeat(72))
    println("%s at %s".format(away.team.name, home.team.name))
    println("%s, %s".format(home.team.stadium.name, if (home.team.stadium.domed) "dome" else "outdoors"))
    println("=".repeat(72))
    println()
    println("FINAL   %-26s %3d".format(away.team.name, g.awayScore))
    println("        %-26s %3d".format(home.team.name, g.homeScore))
    println()

    val h = g.boxScore.home
    val a = g.boxScore.away
    println("%-28s %10s %10s".format("", awayAbbr, homeAbbr))
    println("-".repeat(50))
    fun row(label: String, x: Any, y: Any) = println("%-28s %10s %10s".format(label, x, y))
    row("First downs", a.firstDowns, h.firstDowns)
    row("Total yards", a.totalYards, h.totalYards)
    row("  Rushing", "%d-%d".format(a.rushAttempts, a.rushYards), "%d-%d".format(h.rushAttempts, h.rushYards))
    row("  Passing", a.passYards, h.passYards)
    row("Comp-Att", "%d-%d".format(a.completions, a.passAttempts), "%d-%d".format(h.completions, h.passAttempts))
    row("Sacked-yards", "%d-%d".format(a.sacksAllowed, -a.sackYards), "%d-%d".format(h.sacksAllowed, -h.sackYards))
    row("Third downs", "%d-%d".format(a.thirdDownConversions, a.thirdDownAttempts),
        "%d-%d".format(h.thirdDownConversions, h.thirdDownAttempts))
    row("Fourth downs", "%d-%d".format(a.fourthDownConversions, a.fourthDownAttempts),
        "%d-%d".format(h.fourthDownConversions, h.fourthDownAttempts))
    row("Red zone TDs", "%d-%d".format(a.redZoneTouchdowns, a.redZoneTrips),
        "%d-%d".format(h.redZoneTouchdowns, h.redZoneTrips))
    row("Turnovers", a.turnovers, h.turnovers)
    row("Penalties-yards", "%d-%d".format(a.penalties, a.penaltyYards), "%d-%d".format(h.penalties, h.penaltyYards))
    row("Possession", a.possessionText, h.possessionText)
    println()

    fun leaders(side: Side, teamObj: GameTeam) {
        println("${teamObj.team.name}")
        val ids = teamObj.roster.map { it.id.v }.toSet()
        val lines = g.boxScore.players.filterKeys { it in ids }
        val byId = teamObj.roster.associateBy { it.id.v }

        lines.entries.filter { it.value.passAttempts > 0 }
            .sortedByDescending { it.value.passYards }.take(1).forEach { (id, s) ->
                println("  PASS  %-22s %d-%d, %d yds, %d TD, %d INT, rating %.1f".format(
                    byId[id]!!.name, s.completions, s.passAttempts, s.passYards,
                    s.passTouchdowns, s.interceptionsThrown, s.passerRating))
            }
        lines.entries.filter { it.value.carries > 0 }
            .sortedByDescending { it.value.rushYards }.take(3).forEach { (id, s) ->
                println("  RUSH  %-22s %d car, %d yds (%.1f), %d TD".format(
                    byId[id]!!.name, s.carries, s.rushYards, s.yardsPerCarry, s.rushTouchdowns))
            }
        lines.entries.filter { it.value.receptions > 0 }
            .sortedByDescending { it.value.receivingYards }.take(4).forEach { (id, s) ->
                println("  REC   %-22s %d rec on %d, %d yds, %d TD".format(
                    byId[id]!!.name, s.receptions, s.targets, s.receivingYards, s.receivingTouchdowns))
            }
        println()
    }
    leaders(Side.AWAY, away)
    leaders(Side.HOME, home)

    println("SCORING DRIVES")
    g.drives.filter { it.isScore }.forEach { d ->
        val who = if (d.offense == Side.HOME) homeAbbr else awayAbbr
        println("  Q%d %2d:%02d  %-4s %-16s %2d plays, %3d yds, %d:%02d".format(
            d.startQuarter, d.startClock / 60, d.startClock % 60, who,
            d.ending.label, d.plays, d.yards, d.seconds / 60, d.seconds % 60))
    }
    println()
    println("Play by play: ${g.playByPlay.size} entries. Add --pbp to print them.")
    if (args.any { it == "--pbp" }) {
        println()
        g.playByPlay.forEach {
            println("Q%d %s  %s".format(it.quarter, it.clockText, it.text))
        }
    }
}

private fun gameCal(args: Array<String>) {
    val games = intArg(args, "games", 500)
    // One seed, one league: without --seed every run is the same draw, and a
    // band sitting on its edge passes or fails on that single draw.
    val seed = args.firstOrNull { it.startsWith("--seed=") }?.substringAfter("=")?.toLongOrNull() ?: DEFAULT_SEED
    val league = LeagueGenerator.generate(YEAR, seed)
    println("Simulating $games full games across the league.")
    println()
    val t0 = System.nanoTime()
    val report = GameCalibration.run(league, games = games, seed = seed)
    val ms = (System.nanoTime() - t0) / 1_000_000
    print(report.table())
    println("${report.plays} total plays, ${ms}ms")
    println("Bands from docs/SPEC.md 13.2 - the half that needs whole games to measure.")
}

// ---------------------------------------------------------------------------
// Season
// ---------------------------------------------------------------------------

private fun season(args: Array<String>) {
    val seed = intArg(args, "seed", 2026).toLong()
    val league = LeagueGenerator.generate(YEAR, DEFAULT_SEED)

    val t0 = System.nanoTime()
    val s = SeasonSimulator(league, YEAR, seed).simulate()
    val ms = (System.nanoTime() - t0) / 1_000_000

    println("=".repeat(74))
    println("  ${s.year} SEASON".padEnd(50) + "seed $seed")
    println("=".repeat(74))
    println()

    val standings = Standings(league, s.results, SplitMixRng(seed))
    Conference.entries.forEach { conference ->
        val seeds = s.seedsFor(conference)
        println("${conference.label.uppercase()} CONFERENCE")
        com.nflsim.engine.model.Division.entries.forEach { division ->
            println("  ${division.name.lowercase().replaceFirstChar { it.uppercase() }}")
            standings.division(conference, division).forEach { id ->
                val r = s.record(id)
                val seed = seeds.indexOf(id).let { if (it >= 0) "(${it + 1})" else "" }
                println("    %-24s %-7s  PF %4d  PA %4d  %+4d  %s".format(
                    league.team(id).name, r.recordText, r.pointsFor, r.pointsAgainst,
                    r.pointDifferential, seed))
            }
        }
        println()
    }

    println("PLAYOFFS")
    PlayoffRound.entries.forEach { round ->
        val games = s.playoffs.filter { it.round == round }
        if (games.isEmpty()) return@forEach
        println("  ${round.label}")
        games.forEach { g ->
            val h = league.team(g.home); val a = league.team(g.away)
            val mark = { id: com.nflsim.engine.model.TeamId -> if (g.winner == id) "*" else " " }
            println("    %s(%d) %-22s %3d    %s(%d) %-22s %3d".format(
                mark(g.away), g.awaySeed, a.name, g.awayScore,
                mark(g.home), g.homeSeed, h.name, g.homeScore))
        }
    }
    println()
    s.champion?.let { println("  CHAMPION: ${league.team(it).name}") }
    println()

    println("AWARDS")
    fun award(label: String, w: com.nflsim.engine.season.AwardWinner?) {
        if (w == null) return
        val p = league.player(com.nflsim.engine.model.PlayerId(w.player))
        val team = league.teamsById.values.firstOrNull { it.id.v == w.team }?.abbrev ?: ""
        println("  %-28s %-22s %-4s %-5s %s".format(label, p.name, team, p.position.label, w.summary))
    }
    award("Most Valuable Player", s.awards.mostValuablePlayer)
    award("Offensive Player of the Year", s.awards.offensivePlayerOfTheYear)
    award("Defensive Player of the Year", s.awards.defensivePlayerOfTheYear)
    award("Offensive Rookie of the Year", s.awards.offensiveRookieOfTheYear)
    award("Defensive Rookie of the Year", s.awards.defensiveRookieOfTheYear)
    println()

    fun leaders(title: String, entries: List<LeagueLeaders.Entry>) {
        println(title)
        entries.forEach { e ->
            val team = e.team?.let { league.team(it).abbrev } ?: ""
            println("  %5d  %-22s %-4s %s".format(e.value, e.name, team, e.detail))
        }
        println()
    }
    leaders("PASSING YARDS", LeagueLeaders.passingYards(league, s.playerStats, 5))
    leaders("RUSHING YARDS", LeagueLeaders.rushingYards(league, s.playerStats, 5))
    leaders("RECEIVING YARDS", LeagueLeaders.receivingYards(league, s.playerStats, 5))
    leaders("SACKS", LeagueLeaders.sacks(league, s.playerStats, 5))

    println("${s.results.size} regular season games, ${s.playoffs.size} playoff games, ${ms}ms")
}

// ---------------------------------------------------------------------------
// Multi-season
//
// A single season can look perfect while a decade quietly falls apart: talent
// drifting, one roster never declining, the draft failing to replenish. This
// is the only view that shows it.
// ---------------------------------------------------------------------------

private fun pearson(xs: List<Float>, ys: List<Float>): Float {
    val mx = xs.average()
    val my = ys.average()
    var cov = 0.0
    var varX = 0.0
    var varY = 0.0
    xs.indices.forEach { i ->
        val a = xs[i] - mx
        val b = ys[i] - my
        cov += a * b
        varX += a * a
        varY += b * b
    }
    return if (varX == 0.0 || varY == 0.0) 0f else (cov / kotlin.math.sqrt(varX * varY)).toFloat()
}

private fun dynasty(args: Array<String>) {
    val years = intArg(args, "years", 10)
    val seed = intArg(args, "seed", 2026).toLong()
    val league = LeagueGenerator.generate(YEAR, seed)
    val userTeam = league.teams.first { it.abbrev == "KC" }

    var d = DynastyEngine.start(league, YEAR, seed, userTeam.id)

    println("Simulating $years seasons from ${d.year}. You are the ${userTeam.name}.")
    println()
    println("%-6s %-24s %-22s %-8s %6s %8s %6s %5s".format(
        "YEAR", "CHAMPION", "MVP", "YOUR REC", "MEAN", "STARTERS", "AGE", "80+"))
    println("-".repeat(92))

    val champions = mutableListOf<String>()
    val flow = mutableListOf<Pair<Int, com.nflsim.engine.offseason.OffseasonReport>>()
    val t0 = System.nanoTime()

    repeat(years) {
        repeat(Schedule.WEEKS) { d = DynastyEngine.advance(d) }
        d = DynastyEngine.advance(d)   // playoffs

        val champ = d.champion?.let { d.league.team(com.nflsim.engine.model.TeamId(it)).name } ?: "-"
        champions += champ
        val yourRecord = d.record().recordText

        val mvp = com.nflsim.engine.season.AwardVoting
            .decide(d.league, d.standings().records, d.playerStats, d.year)
            .mostValuablePlayer
            ?.let { d.league.player(com.nflsim.engine.model.PlayerId(it.player)).name } ?: "-"

        // Rate every player against the scheme he actually plays in. Scoring
        // a defence against its own offence's scheme reads as a league-wide
        // slide that is really just the wrong yardstick.
        val overalls = d.league.teams.flatMap { t ->
            val off = SchemeCatalog[t.offenseScheme]
            val def = SchemeCatalog[t.defenseScheme]
            d.league.roster(t.id).map { overall(it, if (it.position.isOffense) off else def) }
        }

        // What the league actually looks like on the field: the best player at
        // each position on each roster. Depth players drag the mean around
        // without changing a single game.
        val starters = d.league.teams.flatMap { t ->
            val off = SchemeCatalog[t.offenseScheme]
            val def = SchemeCatalog[t.defenseScheme]
            d.league.roster(t.id).groupBy { it.position }.values.map { group ->
                group.maxOf { overall(it, if (it.position.isOffense) off else def) }
            }
        }
        val ages = d.league.players.filter { it.teamId != null }.map { it.age(d.year) }
        // The top tier. A league keeps its shape only if it replaces these as
        // fast as age takes them away.
        val elite = overalls.count { it >= 80 }

        println("%-6d %-24s %-22s %-8s %6.1f %8.1f %6.1f %5d".format(
            d.year, champ.take(24), mvp.take(22), yourRecord,
            overalls.average(), starters.average(), ages.average(), elite))

        d = DynastyEngine.advance(d)   // offseason, into the next year
        d.lastOffseason?.let { flow += d.year to it }
    }

    val ms = (System.nanoTime() - t0) / 1_000_000
    println()

    val distinct = champions.toSet().size
    val repeats = champions.groupingBy { it }.eachCount().filterValues { it > 1 }
    println("HEALTH CHECK")
    println("  %-34s %d of %d".format("distinct champions", distinct, years))
    if (repeats.isNotEmpty()) {
        repeats.entries.sortedByDescending { it.value }.take(3).forEach { (team, n) ->
            println("    %-32s %d titles".format(team, n))
        }
    }

    val report = d.lastOffseason
    if (report != null) {
        println("  %-34s %d".format("retirements last offseason", report.retirementCount))
        println("  %-34s %d".format("free agents signed", report.signingCount))
        println("  %-34s %d".format("cap casualties", report.capCasualties))
        println("  %-34s %d".format("full rosters trading up", report.upgradeCount))
        println("  %-34s %d, %d signed this offseason, %,d dead".format(
            "cut at the 53", report.cutdownCount, report.cutdownFresh, report.cutdownDeadMoney))
        println("  %-34s %d exercised, %d declined; %d and %d over the run".format(
            "fifth-year options", report.optionsExercised, report.optionsDeclined,
            flow.sumOf { it.second.optionsExercised }, flow.sumOf { it.second.optionsDeclined }))
        val comp = d.league.picks.filter { it.compensatory && it.year == d.year + 1 }
        println("  %-34s %d for the %d draft, to %d clubs".format(
            "compensatory picks", comp.size, d.year + 1, comp.map { it.original }.toSet().size))
        println("  %-34s %d last offseason, %d over the run, %d young players back".format(
            "stars traded to contenders", report.starTrades,
            flow.sumOf { it.second.starTrades }, flow.sumOf { it.second.youngTraded }))
        println("  %-34s %d last offseason, %d over the run".format(
            "draft picks traded", report.pickTrades.size, flow.sumOf { it.second.pickTrades.size }))
        report.awards.mostValuablePlayer?.let {
            println("  %-34s %s %s, %s".format("MVP last season", it.name, it.position, it.summary))
        }
        println("  %-34s %d last offseason, %d over the run, %d transition, %d kept".format("franchise tags",
            report.tags.count { it.kind == "franchise" },
            flow.sumOf { r -> r.second.tags.count { it.kind == "franchise" } },
            flow.sumOf { r -> r.second.tags.count { it.kind == "transition" } },
            flow.sumOf { it.second.transitionKept }))
        println("  %-34s %d last offseason, %d over the run, %d brought new schemes, %d rehired, %d kept offense, %d kept defense".format(
            "head coaches replaced", report.coachingChanges.size,
            flow.sumOf { it.second.coachingChanges.size },
            flow.sumOf { r -> r.second.coachingChanges.count { it.schemeChanged } },
            flow.sumOf { r -> r.second.coachingChanges.count { it.rehired } },
            flow.sumOf { r -> r.second.coachingChanges.count { it.keptOffense } },
            flow.sumOf { r -> r.second.coachingChanges.count { it.keptDefense } }))

        // Whether buying a star paid: the buyer's record the season before the
        // trade against the season after, beside contenders who stood pat -
        // good teams regress, so the buyers alone would flatter nobody.
        fun records(bought: Boolean) = flow.zipWithNext().flatMap { (now, next) ->
            now.second.moneyByTeam
                .filter { (_, m) -> m.winPermille >= 550 && (m.starsBought > 0) == bought }
                .mapNotNull { (id, m) -> next.second.moneyByTeam[id]?.let { m.winPermille to it.winPermille } }
        }
        listOf("star buyers' record" to records(true), "contenders who stood pat" to records(false))
            .filter { it.second.isNotEmpty() }
            .forEach { (label, r) ->
                println("  %-34s .%03d before, .%03d after (%d clubs)".format(label,
                    r.map { it.first }.average().toInt(), r.map { it.second }.average().toInt(), r.size))
            }
        println("  %-34s %d".format("players kept by their own team", report.extensionCount))
        println("  %-34s %d asked out, %d moved".format(
            "trade requests", report.tradeRequests, report.trades.size))
        report.wishes.take(4).forEach { w ->
            println("      %-22s %-4s %s".format(w.name.take(22), w.position, w.note))
        }
        println("  %-34s %d per team".format("under contract before signing", report.underContract))
        println("  %-34s %d over 1.3x  %d over 1.5x  %d over 1.7x".format(
            "contracts above player value",
            report.overpaidBy30, report.overpaidBy50, report.overpaidBy70))
        println("  %-34s %,d kept  %,d market  %,d minimum".format(
            "money committed", report.extensionSpend, report.auctionSpend, report.fillSpend))

        // What the auction cost, measured over every signing rather than the
        // twenty the news screen keeps.
        if (report.auctionCount > 0) {
            println("  %-34s %.2fx market".format("free agent prices", report.auctionOverpay))
            println("  %-34s %d of %d".format(
                "contested signings", report.auctionContested, report.auctionCount))
        }
        println("  %-34s %,d".format("mean cap space per team", report.meanCapSpace))
        println("  %-34s %d of 32".format("teams under 10m of space", report.teamsTightOnCap))
    }

    val rosterSizes = d.league.teams.map { d.league.roster(it.id).size }
    println("  %-34s %d to %d".format("roster sizes", rosterSizes.min(), rosterSizes.max()))
    println("  %-34s %d".format("players in the league", d.league.players.size))

    val byAge = d.league.players.filter { it.teamId != null }.groupBy {
        when (val a = it.age(d.year)) {
            in 0..24 -> "21-24"
            in 25..27 -> "25-27"
            in 28..30 -> "28-30"
            in 31..33 -> "31-33"
            else -> "34+"
        }
    }
    // Where the league's talent comes from and where it goes. A league that
    // slides is losing more than it drafts or develops; this says which.
    if (flow.isNotEmpty()) {
        println()
        println("TALENT FLOW")
        val brackets = listOf("21-24", "25-27", "28-30", "31+")
        println("  %-6s %8s %7s %8s %7s %6s %8s   %s".format(
            "YEAR", "RETIRED", "MEAN", "DRAFTED", "MEAN", "70+", "NET DEV",
            brackets.joinToString(" ") { "%7s".format(it) }))
        flow.forEach { (year, r) ->
            println("  %-6d %8d %7.1f %8d %7.1f %6d %8.2f   %s".format(
                year, r.retirementCount, r.retiredMean,
                r.draftedCount, r.draftedMean, r.draftedStarters, r.developmentNet,
                brackets.joinToString(" ") { "%7.2f".format(r.developmentByAge[it] ?: 0f) }))
        }
    }

    // Whether the cap binds, year by year - a league that leaves more room
    // every season is one where the cap stops forcing decisions. Space against
    // each GM's spend share says whether the room is a choice some front
    // offices made or a market that ran out of players worth paying.
    if (flow.isNotEmpty()) {
        println()
        println("CAP")
        // Spend columns are annual value committed, as a share of the whole
        // league's cap. Falling market spend while space piles up is demand
        // drying up, not money running out.
        println("  %-6s %9s %11s %7s %10s %7s %7s %7s %7s".format(
            "YEAR", "CAP", "MEAN SPACE", "OF CAP", "UNDER 10M", "FA SIGN", "MARKET", "KEPT", "MIN"))
        flow.forEach { (year, r) ->
            val cap = com.nflsim.engine.offseason.CapManagement.capFor(year)
            val leagueCap = cap * 32f / 100f
            println("  %-6d %,9d %,11d %6.1f%% %4d of 32 %7d %6.1f%% %6.1f%% %6.1f%%".format(
                year, cap, r.meanCapSpace, r.meanCapSpace * 100f / cap, r.teamsTightOnCap,
                r.auctionCount, r.auctionSpend / leagueCap, r.extensionSpend / leagueCap,
                r.fillSpend / leagueCap))
        }
        val last = flow.last().second.capSpaceByTeam
        val rows = d.league.teams.mapNotNull { t ->
            last[t.id.v]?.let { t.gm.spendShare to it.toFloat() }
        }
        if (rows.size >= 8) {
            val band = rows.size / 4
            val bySpend = rows.sortedByDescending { it.first }
            println("  %-34s %,.0f vs %,.0f".format("space, top $band spenders vs bottom",
                bySpend.take(band).map { it.second }.average(),
                bySpend.takeLast(band).map { it.second }.average()))
            println("  %-34s %.2f".format("correlation, spend share vs space",
                pearson(rows.map { it.first }, rows.map { it.second })))
            println("  %-34s %,.0f to %,.0f".format("space, tightest team to loosest",
                rows.minOf { it.second }, rows.maxOf { it.second }))
        }
    }

    // Whether front offices actually behave differently with money. ADR-009
    // says the reckless ones fill the league with bad contracts; this is the
    // only place that claim gets checked, club by club.
    if (flow.any { it.second.moneyByTeam.isNotEmpty() }) {
        val traits = listOf<Pair<String, (com.nflsim.engine.model.GmProfile) -> Float>>(
            "aggression" to { it.aggression },
            "win now" to { it.winNowVsFuture },
            "loyalty" to { it.loyaltyToOwnPlayers },
            "risk tolerance" to { it.riskTolerance },
        )
        val band = d.league.teams.size / 4
        fun ratio(a: Long, b: Long) = if (b == 0L) 0.0 else a.toDouble() / b
        println()
        println("GM  (top vs bottom $band clubs on each trait, over every offseason)")
        println("  %-16s %-5s %9s %9s %8s %10s %9s %11s %6s".format(
            "TRAIT", "", "FA/MKT", "KEPT/MKT", "KEPT/YR", "OVER 1.5X", "DEAD/CAP", "CASUALTIES", "AGE"))
        traits.forEach { (name, trait) ->
            val ranked = d.league.teams.sortedByDescending { trait(it.gm) }
            listOf("high" to ranked.take(band), "low" to ranked.takeLast(band)).forEach { (label, clubs) ->
                val ids = clubs.map { it.id.v }.toSet()
                val rows = flow.flatMap { (year, r) ->
                    r.moneyByTeam.filterKeys { it in ids }.values.map { year to it }
                }
                val dead = rows.map { (y, m) ->
                    m.deadMoney * 100.0 / com.nflsim.engine.offseason.CapManagement.capFor(y)
                }.average()
                println("  %-16s %-5s %8.2fx %8.2fx %8.1f %9.1f%% %8.1f%% %11.1f %6.1f".format(
                    if (label == "high") name else "", label,
                    ratio(rows.sumOf { it.second.faPaid.toLong() }, rows.sumOf { it.second.faMarket.toLong() }),
                    ratio(rows.sumOf { it.second.keptPaid.toLong() }, rows.sumOf { it.second.keptMarket.toLong() }),
                    rows.map { it.second.keptCount }.average(),
                    ratio(rows.sumOf { it.second.overpaid.toLong() },
                        rows.sumOf { it.second.bigContracts.toLong() }) * 100,
                    dead,
                    rows.map { it.second.casualties }.average(),
                    ratio(rows.sumOf { it.second.rosterAgeSum.toLong() },
                        rows.sumOf { it.second.rosterSize.toLong() })))
            }
        }
    }

    // Whether a losing club pays to be worth joining. Free agents prefer a
    // winner, so a bad club that wants a veteran has to outbid for him.
    // Records change every year, so the bands are club-offseasons.
    val clubSeasons = flow.flatMap { it.second.moneyByTeam.values }
    if (clubSeasons.size >= 8) {
        fun ratio(a: Long, b: Long) = if (b == 0L) 0.0 else a.toDouble() / b
        val quarter = clubSeasons.size / 4
        val byRecord = clubSeasons.sortedBy { it.winPermille }
        println()
        println("BY RECORD  (worst vs best $quarter club-offseasons by last season's record)")
        println("  %-8s %9s %9s %9s".format("", "FA/MKT", "VET/MKT", "VETS/YR"))
        listOf("worst" to byRecord.take(quarter), "best" to byRecord.takeLast(quarter)).forEach { (label, g) ->
            println("  %-8s %8.2fx %8.2fx %9.2f".format(label,
                ratio(g.sumOf { it.faPaid.toLong() }, g.sumOf { it.faMarket.toLong() }),
                ratio(g.sumOf { it.vetPaid.toLong() }, g.sumOf { it.vetMarket.toLong() }),
                g.map { it.vetSigned }.average()))
        }
    }

    // Coaching is only worth a hiring screen if a good staff visibly
    // out-develops a bad one. The league-wide net dev figure is exactly the
    // number that cannot show that. Staffs do not move until the M8 carousel,
    // so each team's rating is fixed across the run and the pairing is clean.
    if (flow.isNotEmpty()) {
        val staffRating = d.league.teams.mapNotNull { t ->
            val hc = d.league.coaches[t.staff.headCoach]?.ratings?.development
            val posDevs = t.staff.positionCoaches.values.mapNotNull {
                d.league.coaches[it]?.ratings?.development
            }
            val blended = when {
                posDevs.isNotEmpty() && hc != null ->
                    posDevs.map { it * 0.65f + hc * 0.35f }.average().toFloat()
                posDevs.isNotEmpty() -> posDevs.average().toFloat()
                hc != null -> hc.toFloat()
                else -> null
            }
            blended?.let { t.id.v to it }
        }.toMap()

        // Whole-roster dev is mostly a team's age profile: SPEC 7.1 leaves
        // coaching out of the decline branch, so old rosters read as badly
        // coached ones. The under-25 figure is the only one that isolates it.
        fun paired(pick: (com.nflsim.engine.offseason.OffseasonReport) -> Map<Int, Float>) =
            d.league.teams.mapNotNull { t ->
                val staff = staffRating[t.id.v] ?: return@mapNotNull null
                val years = flow.mapNotNull { (_, r) -> pick(r)[t.id.v] }
                if (years.isEmpty()) null else Triple(t, staff, years.average().toFloat())
            }.sortedByDescending { it.second }

        val all = paired { it.developmentByTeam }
        val young = paired { it.youngDevelopmentByTeam }

        if (all.size >= 8) {
            val band = all.size / 4
            println()
            println("COACHING EFFECT")
            println("  %-34s %.0f worst  %.0f mean  %.0f best".format(
                "staff development rating",
                all.minOf { it.second }, all.map { it.second }.average(),
                all.maxOf { it.second }))

            listOf("whole roster" to all, "under 25 only" to young).forEach { (label, rows) ->
                if (rows.size < 8) return@forEach
                val best = rows.take(band).map { it.third }.average()
                val worst = rows.takeLast(band).map { it.third }.average()
                val byDev = rows.sortedByDescending { it.third }
                println("  $label")
                println("    %-32s %.2f  vs  %.2f".format(
                    "net dev, top $band staffs vs bottom", best, worst))
                println("    %-32s %.2f".format("top minus bottom", best - worst))
                println("    %-32s %.2f to %.2f".format("best team to worst",
                    byDev.first().third, byDev.last().third))
                println("    %-32s %.2f".format("correlation, staff vs dev",
                    pearson(rows.map { it.second }, rows.map { it.third })))
            }
            listOf("best staff" to all.first(), "worst staff" to all.last()).forEach { (label, t) ->
                println("    %-14s %-24s %3.0f staff".format(label, t.first.name.take(24), t.second))
            }
        }
    }

    // One league is one sample. Champion turnover swings hard on a single
    // seed, so judging parity from one run is guessing with extra steps.
    val runs = intArg(args, "runs", 1)
    if (runs > 1) {
        println()
        println("PARITY ACROSS $runs LEAGUES")
        println("  %-10s %10s %10s".format("SEED", "DISTINCT", "MOST TITLES"))
        var distinctSum = 0
        var mostSum = 0
        repeat(runs) { k ->
            val s = seed + k * 101L
            val lg = LeagueGenerator.generate(YEAR, s)
            var sim = DynastyEngine.start(
                lg, YEAR, s, lg.teams.first { it.abbrev == "KC" }.id)
            val champs = mutableListOf<String>()
            repeat(years) {
                repeat(Schedule.WEEKS) { sim = DynastyEngine.advance(sim) }
                sim = DynastyEngine.advance(sim)   // playoffs
                champs += sim.champion
                    ?.let { sim.league.team(com.nflsim.engine.model.TeamId(it)).name } ?: "-"
                sim = DynastyEngine.advance(sim)   // offseason
            }
            val n = champs.toSet().size
            val most = champs.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            distinctSum += n
            mostSum += most
            println("  %-10d %10d %10d".format(s, n, most))
        }
        println("  %-10s %10.1f %10.1f".format(
            "average", distinctSum.toFloat() / runs, mostSum.toFloat() / runs))
    }

    println()
    println("AGE DISTRIBUTION")
    listOf("21-24", "25-27", "28-30", "31-33", "34+").forEach { bucket ->
        val n = byAge[bucket]?.size ?: 0
        val pct = n * 100.0 / d.league.players.count { it.teamId != null }
        println("  %-8s %5d  %s".format(bucket, n, "#".repeat((pct / 2).toInt())))
    }

    println()
    println("$years seasons in ${ms}ms")
}
