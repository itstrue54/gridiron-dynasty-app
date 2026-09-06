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
        "roster" -> roster(args.getOrNull(1)?.uppercase(), seedFrom(args))
        "schemes" -> listSchemes()
        "schemefit" -> schemeFitDemo()
        "rngdemo" -> rngDemo()
        "calibrate" -> println("calibrate: not implemented yet (milestone M4)")
        "simseason" -> println("simseason: not implemented yet (milestone M5)")
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
