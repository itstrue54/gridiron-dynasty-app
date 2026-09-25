package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.DemandState
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 8.3: a league club haggles over a demand the way the user can. */
class AiHaggleTest {

    private val base: League by lazy { LeagueGenerator.generate(2026, 21L) }

    /** A club's best-paid man, with his traits set, asking to be paid. */
    private fun asking(ego: Int, loyalty: Int): Pair<League, Player> {
        val club = base.teams[3]
        val man = base.roster(club.id).filter { it.contract != null }.maxBy { it.capHit(2026) }
        val set = man.copy(traits = man.traits.copy(ego = ego, loyalty = loyalty), demand = DemandState.PENDING)
        return base.copy(players = base.players.map { if (it.id == man.id) set else it }) to set
    }

    private fun answer(league: League, man: Player): Pair<Player, Int> {
        val done = ContractDisputes.frontOfficeAnswer(league, man.teamId!!, man.id) as Transactions.Outcome.Done
        val after = done.league.playersById.getValue(man.id)
        val signed = done.league.transactions.last { it.player == man.id.v }
        return after to signed.amount
    }

    @Test
    fun `a man who likes it there takes the opening offer, under his market`() {
        val (league, man) = asking(ego = 0, loyalty = 100)
        val market = ContractDisputes.ask(league, man)!!.market
        val (after, annual) = answer(league, man)
        assertEquals(DemandState.SETTLED, after.demand)
        assertTrue(annual < market, "opened under the market and he took it: $annual of $market")
        assertTrue(after.morale >= man.morale, "no snub for an offer he took")
    }

    @Test
    fun `a proud man says no, names his floor, and is paid it`() {
        val (league, man) = asking(ego = 100, loyalty = 0)
        val market = ContractDisputes.ask(league, man)!!.market
        val floor = ContractDisputes.reservation(man, league.tuning)
        val open = league.tuning.ai.disputeAiOpenBase + league.team(man.teamId!!).gm.aggression * league.tuning.ai.disputeAiOpenAggression
        assertTrue(open < floor, "set up so the opening falls short: $open against $floor")
        val (after, annual) = answer(league, man)
        assertEquals(DemandState.SETTLED, after.demand)
        val named = (market * floor).toInt()
        assertTrue(annual in named - 1..named + 1, "paid the $named his agent named, got $annual")
        assertTrue(after.morale < man.morale + league.tuning.ai.disputeSettledMorale,
            "the snub before the deal cost him something")
    }
}
