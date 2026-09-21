package com.nflsim.engine.gen

import com.nflsim.engine.model.Coach
import com.nflsim.engine.model.CoachId
import com.nflsim.engine.model.GmProfile
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.model.TeamSeed
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.rng.SplitMixRng
import kotlinx.serialization.json.Json

/**
 * Builds a complete 32-team league from a single seed.
 *
 * Every team gets its own RNG stream keyed by abbreviation, so regenerating
 * one team reproduces it exactly and adding a team later does not shuffle
 * everybody else's players.
 */
object LeagueGenerator {

    private val json = Json { ignoreUnknownKeys = true }

    val teamSeeds: List<TeamSeed> by lazy {
        val text = LeagueGenerator::class.java.getResourceAsStream("/teams.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("/teams.json not found on the classpath")
        json.decodeFromString<List<TeamSeed>>(text)
    }

    fun generate(year: Int, seed: Long): League {
        val root = SplitMixRng(seed)
        val players = mutableListOf<Player>()
        val teams = mutableListOf<Team>()
        val coaches = mutableMapOf<CoachId, Coach>()
        var nextPlayerId = 1
        var nextCoachId = 1

        teamSeeds.forEachIndexed { index, teamSeed ->
            val teamId = TeamId(index + 1)
            val teamRng = root.split("team=${teamSeed.abbrev}")

            val offense = SchemeCatalog.offensive[teamRng.nextInt(SchemeCatalog.offensive.size)]
            val defense = SchemeCatalog.defensive[teamRng.nextInt(SchemeCatalog.defensive.size)]

            // Team quality. Most teams cluster near average; a few are genuinely
            // good or genuinely bad, which is what makes a league worth watching.
            //
            // The spread was wider and it produced too many blowouts - only 17%
            // of games finished within a field goal against a real 18-26%. A
            // league where the gap between best and worst is enormous is not
            // more dramatic, it is less: every result is known in advance.
            val strength = teamRng.gaussian(0f, 2.9f).coerceIn(-6.5f, 6.5f)

            val roster = RosterGenerator.generate(
                teamId = teamId,
                strength = strength,
                year = year,
                rng = teamRng,
            ) { PlayerId(nextPlayerId++) }

            val (staff, teamCoaches) = StaffGenerator.generate(
                offenseScheme = offense.id,
                defenseScheme = defense.id,
                nextId = { CoachId(nextCoachId++) },
                rng = teamRng.split("staff"),
            )
            teamCoaches.forEach { coaches[it.id] = it }

            players += roster
            teams += Team(
                id = teamId,
                city = teamSeed.city,
                nickname = teamSeed.nickname,
                abbrev = teamSeed.abbrev,
                conference = teamSeed.conference,
                division = teamSeed.division,
                stadium = teamSeed.stadium,
                marketSize = teamSeed.marketSize,
                offenseScheme = offense.id,
                defenseScheme = defense.id,
                roster = roster.map { it.id },
                staff = staff,
                gm = GmProfile.generate(teamRng.split("gm")),
            )
        }

        // Every club starts holding its own picks for the next three drafts.
        val picks = com.nflsim.engine.offseason.Picks.own(
            teams.map { it.id }, (year + 1)..(year + com.nflsim.engine.offseason.Picks.WINDOW))
        return com.nflsim.engine.season.PracticeSquads.fill(
            League(seed = seed, year = year, teams = teams, players = players, coaches = coaches, picks = picks),
            seed,
        )
    }

    /** Regenerates one team's roster in isolation - same seed, same players. */
    fun rosterFor(abbrev: String, year: Int, seed: Long, teamId: TeamId): List<Player> {
        val rng: Rng = SplitMixRng(seed).split("team=$abbrev")
        rng.nextInt(SchemeCatalog.offensive.size)
        rng.nextInt(SchemeCatalog.defensive.size)
        rng.gaussian(0f, 3.6f)
        var id = 1
        return RosterGenerator.generate(teamId, 0f, year, rng) { PlayerId(id++) }
    }
}
