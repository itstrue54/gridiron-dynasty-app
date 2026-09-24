package com.example.nflsimtext.ui

import com.nflsim.engine.model.GamePlan
import com.nflsim.engine.model.DepthPins
import com.nflsim.engine.tuning.TuningTable
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nflsim.data.SaveFile
import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Holds the dynasty and the one operation that changes it.
 *
 * Simulation runs off the main thread - a week is thirteen full games and
 * would drop frames otherwise. The save is written after every advance,
 * because a phone kills apps without asking.
 */
class DynastyStore(private val saveDir: File) {

    var dynasty by mutableStateOf<Dynasty?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /** How far a week's sim has got, as (games done, games in the week); null when it cannot say (SPEC 11). */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    /** SPEC 9.1: five slots and three rotating autosaves. */
    val saves = Saves(saveDir)

    /** The slot the dynasty in hand is written to. */
    var slot by mutableStateOf(1)
        private set

    // Existence only: reading five saves to answer it would block a frame.
    val hasSave: Boolean get() = saves.any()

    /** Every slot and autosave, for the start screen and the saves screen. */
    suspend fun cards(): List<Saves.Card> = withContext(Dispatchers.IO) {
        saves.adoptLegacySave()
        saves.cards()
    }

    suspend fun load(from: Int = slot): Boolean = withContext(Dispatchers.IO) {
        saves.adoptLegacySave()
        runCatching { saves.load(saves.slotFile(from)) }
            .onSuccess { dynasty = it; slot = from }
            .onFailure { message = "Could not read slot $from: ${it.message}" }
            .isSuccess
    }

    /** An autosave, read back into the slot it is loaded into. */
    suspend fun restore(card: Saves.Card, into: Int = slot): Boolean = withContext(Dispatchers.IO) {
        runCatching { saves.load(card.file) }
            .onSuccess {
                dynasty = it
                slot = into
                saves.write(into, it)
                message = "${card.label} restored into slot $into."
            }
            .onFailure { message = "Could not read ${card.label}: ${it.message}" }
            .isSuccess
    }

    /** Copies the dynasty in hand into another slot and keeps playing there. */
    suspend fun copyTo(other: Int) {
        val current = dynasty ?: return
        withContext(Dispatchers.IO) {
            runCatching { saves.write(other, current) }
                .onSuccess { slot = other; message = "Saved to slot $other." }
                .onFailure { message = "Save failed: ${it.message}" }
        }
    }

    suspend fun deleteSave(card: Saves.Card) = withContext(Dispatchers.IO) {
        saves.delete(card.file)
        if (!card.auto && card.slot == slot) dynasty = null
    }

    /**
     * A league generated and waiting for the user to choose his club. Held
     * in memory: nothing is saved until he has chosen.
     */
    var pendingLeague by mutableStateOf<com.nflsim.engine.model.League?>(null)
        private set
    private var pendingSeed = 0L
    private var pendingSlot = 1

    /** Generates the league a new dynasty will be played in, for the user to pick his club from. */
    suspend fun previewLeague(seed: Long = System.nanoTime(), into: Int = slot) {
        busy = true
        try {
            pendingLeague = withContext(Dispatchers.Default) { LeagueGenerator.generate(YEAR, seed) }
            pendingSeed = seed
            pendingSlot = into
        } finally {
            busy = false
        }
    }

    /** Back out of choosing a club; the generated league is thrown away. */
    fun cancelPreview() { pendingLeague = null }

    /** Starts the dynasty with the club the user chose, or a random one with null. */
    suspend fun startWith(teamAbbrev: String?) {
        val league = pendingLeague ?: return
        start(league, pendingSeed, teamAbbrev, pendingSlot)
        pendingLeague = null
    }

    suspend fun newDynasty(teamAbbrev: String? = null, seed: Long = System.nanoTime(), into: Int = slot) {
        val league = withContext(Dispatchers.Default) { LeagueGenerator.generate(YEAR, seed) }
        start(league, seed, teamAbbrev, into)
    }

    private suspend fun start(
        league: com.nflsim.engine.model.League,
        seed: Long,
        teamAbbrev: String?,
        into: Int,
    ) {
        busy = true
        slot = into
        try {
            val fresh = withContext(Dispatchers.Default) {
                val team = teamAbbrev
                    ?.let { a -> league.teams.firstOrNull { it.abbrev == a } }
                    ?: league.teams.random()
                DynastyEngine.start(league, YEAR, seed, TeamId(team.id.v))
            }
            dynasty = fresh
            persist(fresh)
            message = "New dynasty with the ${fresh.team.nickname}."
        } finally {
            busy = false
        }
    }

    suspend fun advance() {
        val current = dynasty ?: return
        busy = true
        try {
            val next = withContext(Dispatchers.Default) {
                DynastyEngine.advance(current, onGame = { done, total -> progress = done to total })
            }
            dynasty = next
            persist(next)
            // SPEC 9.1: an autosave every time the phase turns over, so the
            // worst a bad write can cost is the week it happened in.
            if (next.phase != current.phase) {
                withContext(Dispatchers.IO) { runCatching { saves.autosave(next) } }
            }
        } finally {
            busy = false
            progress = null
        }
    }

    /** SPEC 12: the league's tuning table, changed on the tuning screen and saved with it. */
    suspend fun setTuning(tuning: TuningTable) {
        val current = dynasty ?: return
        val next = current.copy(league = current.league.copy(tuning = tuning))
        dynasty = next
        persist(next)
    }

    /** SPEC 5.5: the user's depth-chart pins, saved with the league. */
    suspend fun setDepthPins(pins: DepthPins) {
        val current = dynasty ?: return
        val teams = current.league.teams.map { if (it.id == current.userTeamId) it.copy(depthPins = pins) else it }
        val next = current.copy(league = current.league.copy(teams = teams))
        dynasty = next
        persist(next)
    }

    /** SPEC 5.4: the user's game plan, saved with the league. */
    suspend fun setGamePlan(plan: GamePlan) {
        val current = dynasty ?: return
        val teams = current.league.teams.map { if (it.id == current.userTeamId) it.copy(gamePlan = plan) else it }
        val next = current.copy(league = current.league.copy(teams = teams))
        dynasty = next
        persist(next)
    }

    /**
     * The draft, with the club in the room (SPEC 8.5). The pause lives here
     * and is never saved: the phases before the draft are deterministic, so a
     * club that closes the app simply runs them again.
     */
    var draftRoom by mutableStateOf<DraftRoom?>(null)
        private set

    class DraftRoom(
        val pause: com.nflsim.engine.offseason.OffseasonEngine.DraftPause,
        val picks: Map<Int, Int>,
        val board: com.nflsim.engine.offseason.DraftRunner.Result,
    ) {
        /** The overall number the club is on the clock for, if it still is. */
        val onTheClock: Int? get() = board.stoppedAt
    }

    /** Runs the offseason up to the club's first pick and stops there. */
    /**
     * The offseason stopped before re-signing, so the user decides his own
     * expiring players (SPEC 7 phases 5-6). In memory, like the draft room:
     * everything before it is deterministic and simply runs again.
     */
    var contracts by mutableStateOf<com.nflsim.engine.offseason.ContractsPause?>(null)
        private set

    suspend fun openContracts() {
        val current = dynasty ?: return
        busy = true
        try {
            contracts = withContext(Dispatchers.Default) {
                com.nflsim.engine.offseason.OffseasonEngine.runToContracts(current)
            }
        } catch (e: Exception) {
            message = e.message ?: "The offseason would not open."
        } finally {
            busy = false
        }
    }

    /**
     * The user's decisions made - or, with null, his front office's, exactly
     * as the league's logic would have run his club - and on to free agency.
     */
    suspend fun decideContracts(choices: Map<Int, com.nflsim.engine.offseason.ContractDecision>?) {
        val pause = contracts ?: return
        busy = true
        try {
            freeAgency = withContext(Dispatchers.Default) { pause.toFreeAgency(choices) }
            contracts = null
        } catch (e: Exception) {
            message = e.message ?: "Free agency would not open."
        } finally {
            busy = false
        }
    }

    /** Free agency, stopped for the user's own offers (SPEC 7 phase 7). In memory, like the others. */
    var freeAgency by mutableStateOf<com.nflsim.engine.offseason.FreeAgencyPause?>(null)
        private set

    /**
     * The ten days, with the user's standing offers - or, with null, his
     * front office bidding for him - and on to the draft room, with word of
     * how each offer went.
     */
    suspend fun runFreeAgency(
        offers: List<com.nflsim.engine.offseason.FreeAgency.Offer>?,
        /** How far to match for each transition-tagged man; left out, the suggestion. */
        matches: Map<Int, Int> = emptyMap(),
    ) {
        val current = dynasty ?: return
        val pause = freeAgency ?: return
        busy = true
        try {
            val room = withContext(Dispatchers.Default) {
                val draft = pause.decide(offers, matches)
                DraftRoom(draft, emptyMap(), draft.boardFor(current.userTeamId))
            }
            draftRoom = room
            freeAgency = null
            message = offers?.let { freeAgencyReport(current, pause, room.pause, it) }
        } catch (e: Exception) {
            message = e.message ?: "The draft would not open."
        } finally {
            busy = false
        }
    }

    /** An offer to a free agent's agent before the market opens: he signs now, or names his floor. */
    fun negotiate(playerId: Int, annual: Int, years: Int) {
        val pause = freeAgency ?: return
        val talk = pause.negotiate(playerId, annual, years)
        freeAgency = talk.pause
        // A signing is news for the top of the screen, since he leaves the
        // list; a refusal belongs beside the offer that drew it.
        if (talk.signed) { message = talk.note; agentReply = null }
        else agentReply = playerId to talk.note
    }

    /** The last thing an agent said, and whose: shown in that man's own block. */
    var agentReply by mutableStateOf<Pair<Int, String>?>(null)
        private set

    /** Who the user's offers landed, and where the rest went. */
    private fun freeAgencyReport(
        current: Dynasty,
        before: com.nflsim.engine.offseason.FreeAgencyPause,
        after: com.nflsim.engine.offseason.OffseasonEngine.DraftPause,
        offers: List<com.nflsim.engine.offseason.FreeAgency.Offer>,
    ): String {
        val now = after.signedPlayers
        val abbrev = current.league.teams.associate { it.id to it.abbrev }
        // The transition-tagged men: matched or on the tender, or gone to an offer sheet.
        val tags = before.tagged.mapNotNull { t ->
            val man = now[t.candidate.player.id.v] ?: return@mapNotNull null
            if (man.teamId == before.userTeam) "Kept ${man.position.label} ${man.name}."
            else man.teamId?.let { "Lost ${man.name} to ${abbrev[it] ?: "?"}, past what you would match." }
        }.joinToString(" ")
        if (offers.isEmpty()) return listOf("You made no offers in free agency.", tags).filter { it.isNotEmpty() }.joinToString(" ")
        val won = offers.mapNotNull { o -> now[o.player]?.takeIf { it.teamId == before.userTeam } }
        val lost = offers.mapNotNull { o -> now[o.player]?.takeIf { it.teamId != null && it.teamId != before.userTeam } }
        val parts = mutableListOf<String>()
        if (won.isNotEmpty()) parts += "Signed " + won.joinToString { "${it.position.label} ${it.name}" } + "."
        if (lost.isNotEmpty()) parts += "Lost " + lost.joinToString {
            "${it.name} to ${abbrev[it.teamId] ?: "?"} (${com.example.nflsimtext.ui.dealMoney(it.contract?.averagePerYear ?: 0)} a year)"
        } + "."
        val unsigned = offers.size - won.size - lost.size
        if (unsigned > 0) parts += "$unsigned still unsigned: nobody met what they asked."
        if (tags.isNotEmpty()) parts += tags
        return parts.joinToString(" ")
    }

    suspend fun openDraftRoom() {
        val current = dynasty ?: return
        busy = true
        try {
            val room = withContext(Dispatchers.Default) {
                val pause = com.nflsim.engine.offseason.OffseasonEngine.runToDraft(current)
                DraftRoom(pause, emptyMap(), pause.boardFor(current.userTeamId))
            }
            draftRoom = room
        } catch (e: Exception) {
            message = e.message ?: "The draft would not open."
        } finally {
            busy = false
        }
    }

    /** Takes a player with the club's current pick and runs on to its next. */
    suspend fun draftPlayer(playerId: Int) {
        val current = dynasty ?: return
        val room = draftRoom ?: return
        val slot = room.onTheClock ?: return
        busy = true
        try {
            val next = withContext(Dispatchers.Default) {
                val picks = room.picks + (slot to playerId)
                DraftRoom(room.pause, picks, room.pause.boardFor(current.userTeamId, picks))
            }
            draftRoom = next
        } finally {
            busy = false
        }
    }

    /** Camp, stopped for the user's own fill and cut to 53 (SPEC 7 phases 10-11). In memory. */
    var cutdown by mutableStateOf<com.nflsim.engine.offseason.CutdownPause?>(null)
        private set

    /** The draft done: on to camp, where the user makes his own cut. */
    suspend fun goToCamp() {
        val room = draftRoom ?: return
        busy = true
        try {
            cutdown = withContext(Dispatchers.Default) { room.pause.toCutdown(room.picks) }
            draftRoom = null
        } catch (e: Exception) {
            message = e.message ?: "Camp would not open."
        } finally {
            busy = false
        }
    }

    /**
     * The cut made - or, with null, the front office's fill and cut - and
     * the rest of the offseason, into the new year.
     */
    suspend fun finishCamp(cut: com.nflsim.engine.offseason.CutdownPause.Cut?) {
        val camp = cutdown ?: return
        busy = true
        try {
            val next = withContext(Dispatchers.Default) {
                val (rolled, report) = camp.decide(cut)
                rolled.copy(lastOffseason = report)
            }
            cutdown = null
            dynasty = next
            persist(next)
            // The year turning over is a phase advance like any other (SPEC
            // 9.1), and it happens here rather than in advance(): without
            // this, the one autosave most worth having was never written.
            withContext(Dispatchers.IO) { runCatching { saves.autosave(next) } }
        } catch (e: Exception) {
            message = e.message ?: "The offseason would not finish."
        } finally {
            busy = false
        }
    }

    /** SPEC 4.6: where the club points its scouts before the draft. */
    suspend fun setScoutingFocus(focus: Set<com.nflsim.engine.model.Position>) {
        val current = dynasty ?: return
        val teams = current.league.teams.map {
            if (it.id == current.userTeamId) it.copy(scoutingFocus = focus) else it
        }
        val next = current.copy(league = current.league.copy(teams = teams))
        dynasty = next
        persist(next)
    }

    /** Signs a free agent, or says why the club cannot (SPEC 7, out of season). */
    suspend fun signFreeAgent(playerId: Int) {
        val weeks = dynasty?.weeksLeft ?: return
        val wire = dynasty?.wireWeek ?: 0
        transact { league, team ->
            com.nflsim.engine.season.Transactions.sign(
                league, team, com.nflsim.engine.model.PlayerId(playerId), weeksLeft = weeks, week = wire)
        }
    }

    /** Off injured reserve, once he is healthy and there is a place. */
    suspend fun activateFromReserve(playerId: Int) = transact { league, team ->
        com.nflsim.engine.season.Transactions.activate(
            league, team, com.nflsim.engine.model.PlayerId(playerId), week = wireWeek())
    }

    /** Releases a player, charging the dead money his contract says. */
    suspend fun releasePlayer(playerId: Int) = transact { league, team ->
        com.nflsim.engine.season.Transactions.release(
            league, team, com.nflsim.engine.model.PlayerId(playerId), week = wireWeek())
    }

    /** Onto the club's practice squad, off the 53. */
    suspend fun signToPracticeSquad(playerId: Int) = transact { league, team ->
        com.nflsim.engine.season.Transactions.signToPracticeSquad(
            league, team, com.nflsim.engine.model.PlayerId(playerId), week = wireWeek())
    }

    suspend fun releaseFromPracticeSquad(playerId: Int) = transact { league, team ->
        com.nflsim.engine.season.Transactions.releaseFromPracticeSquad(
            league, team, com.nflsim.engine.model.PlayerId(playerId), week = wireWeek())
    }

    /** Pay a man who has asked his club to fix his deal (SPEC 10.1). */
    suspend fun extendContract(
        playerId: Int,
        years: Int? = null,
        structure: com.nflsim.engine.offseason.ContractOptions.Structure? = null,
    ) {
        val stats = dynasty?.playerStats ?: return
        transact { league, team ->
            com.nflsim.engine.season.ContractDisputes.extend(
                league, team, com.nflsim.engine.model.PlayerId(playerId), wireWeek(), stats, years, structure)
        }
    }

    /** An offer at a share of what the market says he is worth (SPEC 8.3). */
    suspend fun offerContract(playerId: Int, share: Float) {
        val stats = dynasty?.playerStats ?: return
        transact { league, team ->
            com.nflsim.engine.season.ContractDisputes.offer(
                league, team, com.nflsim.engine.model.PlayerId(playerId), share, wireWeek(), stats)
        }
    }

    /** Hands the in-season roster moves to the front office, or takes them back. */
    suspend fun setFrontOfficeRoster(on: Boolean) {
        val current = dynasty ?: return
        val next = current.copy(frontOfficeRoster = on)
        dynasty = next
        persist(next)
        message = if (on) "Your front office will fill injured places and the practice squad."
            else "Injured places and the practice squad are yours to fill again."
    }

    /** The front office answers a demand the way the league's clubs do. */
    suspend fun frontOfficeAnswer(playerId: Int) {
        val stats = dynasty?.playerStats ?: return
        transact { league, team ->
            com.nflsim.engine.season.ContractDisputes.frontOfficeAnswer(
                league, team, com.nflsim.engine.model.PlayerId(playerId), wireWeek(), stats)
        }
    }

    /** Tell him to play out his deal. */
    suspend fun refuseDemand(playerId: Int) = transact { league, team ->
        com.nflsim.engine.season.ContractDisputes.refuse(
            league, team, com.nflsim.engine.model.PlayerId(playerId), wireWeek())
    }

    /** SPEC 8.3: base salary into bonus - cheap now, dearer every year after. */
    suspend fun restructure(playerId: Int, share: Float = com.nflsim.engine.offseason.CapManagement.RESTRUCTURE_SHARE) =
        transact { league, team ->
            com.nflsim.engine.season.Transactions.restructure(
                league, team, com.nflsim.engine.model.PlayerId(playerId), wireWeek(), share)
        }

    private fun wireWeek(): Int = dynasty?.wireWeek ?: 0

    private suspend fun transact(
        move: (com.nflsim.engine.model.League, com.nflsim.engine.model.TeamId) ->
            com.nflsim.engine.season.Transactions.Outcome,
    ) {
        val current = dynasty ?: return
        busy = true
        try {
            when (val outcome = move(current.league, current.userTeamId)) {
                is com.nflsim.engine.season.Transactions.Outcome.Done -> {
                    val next = current.copy(league = outcome.league)
                    dynasty = next
                    message = outcome.note
                    persist(next)
                }
                is com.nflsim.engine.season.Transactions.Outcome.Refused -> {
                    message = outcome.reason
                }
            }
        } finally {
            busy = false
        }
    }

    fun dismissMessage() { message = null }

    private suspend fun persist(state: Dynasty) = withContext(Dispatchers.IO) {
        runCatching { saves.write(slot, state) }
            .onFailure { message = "Save failed: ${it.message}" }
    }

    companion object {
        const val YEAR = 2026

        @Volatile private var instance: DynastyStore? = null

        /**
         * One store for the process, not one per Activity. The offseason's
         * pauses live in memory, and Android rebuilds the Activity on a
         * rotation, a dark-mode switch or a font change: a store made per
         * Activity was thrown away with it, taking the user's contract
         * calls, free-agency offers, draft picks and cut with it.
         */
        fun forContext(context: Context): DynastyStore = instance ?: synchronized(this) {
            instance ?: DynastyStore(File(context.applicationContext.filesDir, "saves")).also { instance = it }
        }
    }
}
