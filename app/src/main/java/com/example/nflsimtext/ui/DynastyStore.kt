package com.example.nflsimtext.ui

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
