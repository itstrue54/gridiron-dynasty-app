package com.example.nflsimtext.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nflsim.engine.model.TeamId
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
 *
 * In the postseason one of these calls every game his club plays, one after
 * another: each opens with a kickoff he starts, or hands to the coordinators.
 */
class LiveGame(val offense: Playbook, val defense: Playbook) : SnapCaller {

    /** What the game is waiting on: the snap and the coordinator's call. */
    sealed interface Ask {
        /** The snap in hand; none before a playoff game kicks off. */
        val snap: Snap?

        /** A playoff game about to start: his to call, or the coordinators'. */
        data class Kickoff(val title: String, val home: TeamId, val away: TeamId) : Ask {
            override val snap: Snap? get() = null
        }

        data class Offense(override val snap: Snap, val suggested: OffensivePlayCall) : Ask
        data class Defense(override val snap: Snap, val suggested: DefensivePlayCall) : Ask
        data class FourthDown(override val snap: Snap, val suggested: FourthDownChoice) : Ask
    }

    var ask by mutableStateOf<Ask?>(null)
        private set

    /**
     * A timeout called ahead: taken after the next snap that leaves the
     * clock running, then cleared. Left alone, his timeouts are the
     * coordinators' to spend, as they would.
     */
    var timeoutArmed by mutableStateOf(false)

    /** Whether the user calls this side; off, the coordinator does and nothing waits. */
    var callOffense by mutableStateOf(true)
    var callDefense by mutableStateOf(true)

    /** The game as it last stood, for the screen between snaps. */
    var last by mutableStateOf<Snap?>(null)
        private set

    /** The playoff game being played, and what it is called; null in the regular season. */
    var matchup by mutableStateOf<Pair<TeamId, TeamId>?>(null)
        private set
    var title by mutableStateOf<String?>(null)
        private set

    /** The playoff game he last finished: its teams and score, home first. */
    var previous by mutableStateOf<Final?>(null)
        private set

    data class Final(val title: String, val home: TeamId, val away: TeamId, val homeScore: Int, val awayScore: Int)

    /** Set when he hands the rest of the postseason over: no kickoff waits on him again. */
    private var restToCoordinators = false

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

    override fun timeout(snap: Snap, suggested: Boolean): Boolean {
        if (!timeoutArmed) return suggested
        timeoutArmed = false
        return true
    }

    override fun fourthDown(snap: Snap, suggested: FourthDownChoice): FourthDownChoice {
        last = snap
        return if (!callOffense) suggested else wait(Ask.FourthDown(snap, suggested)) as FourthDownChoice
    }

    override fun kickoff(title: String, home: TeamId, away: TeamId) {
        // A new game: whatever he handed over in the last one, this one starts his.
        matchup = home to away
        this.title = title
        last = null
        timeoutArmed = false
        callOffense = !restToCoordinators
        callDefense = !restToCoordinators
        if (restToCoordinators) return
        val play = wait(Ask.Kickoff(title, home, away)) as Boolean
        if (!play) { callOffense = false; callDefense = false }
    }

    override fun final(homeScore: Int, awayScore: Int) {
        val (home, away) = matchup ?: return
        previous = Final(title.orEmpty(), home, away, homeScore, awayScore)
    }

    /** Start the playoff game waiting to kick off, calling it himself - or not. */
    fun kickOff(callIt: Boolean) {
        if (ask is Ask.Kickoff) answer(callIt)
    }

    /** Hand the rest of the postseason to the coordinators, this game included. */
    fun finishPostseason() {
        restToCoordinators = true
        if (ask is Ask.Kickoff) answer(false) else finish()
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
            // Before a playoff game, the coordinators' call is to play it for him.
            is Ask.Kickoff -> answer(false)
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
