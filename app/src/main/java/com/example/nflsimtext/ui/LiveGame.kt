package com.example.nflsimtext.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nflsim.engine.playbook.Playbook
import com.nflsim.engine.sim.DefensivePlayCall
import com.nflsim.engine.sim.FourthDownChoice
import com.nflsim.engine.sim.OffensivePlayCall
import com.nflsim.engine.sim.Snap
import com.nflsim.engine.sim.SnapCaller
import java.util.concurrent.LinkedBlockingQueue

/**
 * The user's game, called by the user (SPEC 5.4). The game runs on a worker
 * thread and waits here at each of his snaps; the screen shows [ask] and
 * answers it. Either side can be handed back to the coordinator at any time,
 * and the rest of the game with it.
 */
class LiveGame(val offense: Playbook, val defense: Playbook) : SnapCaller {

    /** What the game is waiting on: the snap and the coordinator's call. */
    sealed interface Ask {
        val snap: Snap

        data class Offense(override val snap: Snap, val suggested: OffensivePlayCall) : Ask
        data class Defense(override val snap: Snap, val suggested: DefensivePlayCall) : Ask
        data class FourthDown(override val snap: Snap, val suggested: FourthDownChoice) : Ask
    }

    var ask by mutableStateOf<Ask?>(null)
        private set

    /** Whether the user calls this side; off, the coordinator does and nothing waits. */
    var callOffense by mutableStateOf(true)
    var callDefense by mutableStateOf(true)

    /** The game as it last stood, for the screen between snaps. */
    var last by mutableStateOf<Snap?>(null)
        private set

    /**
     * Answers, each with the question it answers. Buffered, so one sent
     * before the game starts waiting is not lost; tagged, so a second tap on
     * an old question is not taken as the answer to the next.
     */
    private val answers = LinkedBlockingQueue<Pair<Ask, Any>>()

    override fun offense(snap: Snap, suggested: OffensivePlayCall): OffensivePlayCall {
        last = snap
        return if (!callOffense) suggested else wait(Ask.Offense(snap, suggested)) as OffensivePlayCall
    }

    override fun defense(snap: Snap, suggested: DefensivePlayCall): DefensivePlayCall {
        last = snap
        return if (!callDefense) suggested else wait(Ask.Defense(snap, suggested)) as DefensivePlayCall
    }

    override fun fourthDown(snap: Snap, suggested: FourthDownChoice): FourthDownChoice {
        last = snap
        return if (!callOffense) suggested else wait(Ask.FourthDown(snap, suggested)) as FourthDownChoice
    }

    private fun wait(a: Ask): Any {
        ask = a
        while (true) {
            val (to, answer) = answers.take()
            if (to === a) {
                ask = null
                return answer
            }
        }
    }

    /** The user's call for what [ask] is waiting on. */
    fun answer(call: Any) {
        val a = ask ?: return
        answers.offer(a to call)
    }

    /** Take the coordinator's call for the snap in hand. */
    fun takeSuggestion() {
        when (val a = ask) {
            is Ask.Offense -> answer(a.suggested)
            is Ask.Defense -> answer(a.suggested)
            is Ask.FourthDown -> answer(a.suggested)
            null -> Unit
        }
    }

    /** Hand one side back to the coordinator, now and for the rest of the game. */
    fun handOff(offence: Boolean) {
        if (offence) callOffense = false else callDefense = false
        val a = ask
        if (a is Ask.Offense && offence || a is Ask.FourthDown && offence || a is Ask.Defense && !offence) takeSuggestion()
    }

    /** Let the coordinators finish the game. */
    fun finish() {
        callOffense = false
        callDefense = false
        takeSuggestion()
    }
}
