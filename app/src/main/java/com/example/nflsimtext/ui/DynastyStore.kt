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

    suspend fun newDynasty(teamAbbrev: String? = null, seed: Long = System.nanoTime(), into: Int = slot) {
        busy = true
        slot = into
        try {
            val fresh = withContext(Dispatchers.Default) {
                val league = LeagueGenerator.generate(YEAR, seed)
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
            val next = withContext(Dispatchers.Default) { DynastyEngine.advance(current) }
            dynasty = next
            persist(next)
            // SPEC 9.1: an autosave every time the phase turns over, so the
            // worst a bad write can cost is the week it happened in.
            if (next.phase != current.phase) {
                withContext(Dispatchers.IO) { runCatching { saves.autosave(next) } }
            }
        } finally {
            busy = false
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

    /** Finishes the draft and the rest of the offseason. */
    suspend fun finishOffseason() {
        val room = draftRoom ?: return
        busy = true
        try {
            val next = withContext(Dispatchers.Default) {
                val (rolled, report) = room.pause.finish(room.picks)
                rolled.copy(lastOffseason = report)
            }
            draftRoom = null
            dynasty = next
            persist(next)
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
    suspend fun extendContract(playerId: Int) {
        val stats = dynasty?.playerStats ?: return
        transact { league, team ->
            com.nflsim.engine.season.ContractDisputes.extend(
                league, team, com.nflsim.engine.model.PlayerId(playerId), wireWeek(), stats)
        }
    }

    /** Tell him to play out his deal. */
    suspend fun refuseDemand(playerId: Int) = transact { league, team ->
        com.nflsim.engine.season.ContractDisputes.refuse(
            league, team, com.nflsim.engine.model.PlayerId(playerId), wireWeek())
    }

    /** SPEC 8.3: base salary into bonus - cheap now, dearer every year after. */
    suspend fun restructure(playerId: Int) = transact { league, team ->
        com.nflsim.engine.season.Transactions.restructure(
            league, team, com.nflsim.engine.model.PlayerId(playerId), wireWeek())
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

        fun forContext(context: Context) = DynastyStore(File(context.filesDir, "saves"))
    }
}
