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

    private val saveFile: File get() = File(saveDir, SAVE_NAME)

    val hasSave: Boolean get() = saveFile.exists()

    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        runCatching { SaveFile.decode(saveFile.readBytes()) }
            .onSuccess { dynasty = it }
            .onFailure { message = "Could not read the save: ${it.message}" }
            .isSuccess
    }

    suspend fun newDynasty(teamAbbrev: String? = null, seed: Long = System.nanoTime()) {
        busy = true
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
    suspend fun signFreeAgent(playerId: Int) = transact { league, team ->
        com.nflsim.engine.season.Transactions.sign(
            league, team, com.nflsim.engine.model.PlayerId(playerId))
    }

    /** Releases a player, charging the dead money his contract says. */
    suspend fun releasePlayer(playerId: Int) = transact { league, team ->
        com.nflsim.engine.season.Transactions.release(
            league, team, com.nflsim.engine.model.PlayerId(playerId))
    }

    /** Onto the club's practice squad, off the 53. */
    suspend fun signToPracticeSquad(playerId: Int) = transact { league, team ->
        com.nflsim.engine.season.Transactions.signToPracticeSquad(
            league, team, com.nflsim.engine.model.PlayerId(playerId))
    }

    suspend fun releaseFromPracticeSquad(playerId: Int) = transact { league, team ->
        com.nflsim.engine.season.Transactions.releaseFromPracticeSquad(
            league, team, com.nflsim.engine.model.PlayerId(playerId))
    }

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
        runCatching {
            saveDir.mkdirs()
            saveFile.writeBytes(SaveFile.encode(state))
        }.onFailure { message = "Save failed: ${it.message}" }
    }

    companion object {
        const val YEAR = 2026
        private const val SAVE_NAME = "dynasty.sav"

        fun forContext(context: Context) = DynastyStore(File(context.filesDir, "saves"))
    }
}
