package com.nflsim.cli

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

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "calibrate" -> println("calibrate: not implemented yet (milestone M4)")
        "simseason" -> println("simseason: not implemented yet (milestone M5)")
        "rngdemo" -> rngDemo()
        "schemefit" -> schemeFitDemo()
        "schemes" -> listSchemes()
        else -> help()
    }
}

private fun help() {
    println(
        """
        NFL Sim Text - headless engine tools

          schemes     List the shipped offensive and defensive schemes
          schemefit   Show how scheme choice changes a player's value
          rngdemo     Prove the RNG is deterministic
          calibrate   Run N seasons and report statistical bands   (M4)
          simseason   Simulate one season and print the results    (M5)

        Example:
          ./gradlew :engine-cli:run --args="schemefit"
        """.trimIndent()
    )
}

private fun listSchemes() {
    println("OFFENSE")
    SchemeCatalog.offensive.forEach {
        println("  %-20s %-26s pass %.0f%%  tempo %.2f".format(it.id, it.name, it.basePassRate * 100, it.tempo))
    }
    println()
    println("DEFENSE")
    SchemeCatalog.defensive.forEach {
        println("  %-20s %-26s blitz %.0f%%  man %.0f%%".format(it.id, it.name, it.blitzRate * 100, it.manZoneSplit * 100))
    }
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
                id = PlayerId(1),
                firstName = "Test", lastName = "Back",
                position = Position.RB, archetype = archetype,
                birthYear = 2000, heightIn = 71, weightLb = 215, college = "Test",
                ratings = Ratings.uniform(85),
                traits = HiddenTraits.AVERAGE,
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
