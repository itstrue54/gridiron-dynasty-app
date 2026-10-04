package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.DemandState
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPEC 8.3: the figure his agent names stands. A snub costs him morale, and a
 * market priced off morale dips - so without a floor he could sign below the
 * figure he was just quoted.
 */
class DemandFloorTest {

    private val base: League by lazy { LeagueGenerator.generate(2026, 21L) }

    /** [club]'s best-paid man, asking to be paid, a little proud. */
    private fun asking(club: Int): Pair<League, Player> {
        val man = base.roster(base.teams[club].id).filter { it.contract != null }.maxBy { it.capHit(2026) }
        val set = man.copy(traits = man.traits.copy(ego = 60, loyalty = 40), demand = DemandState.PENDING)
        return base.copy(players = base.players.map { if (it.id == man.id) set else it }) to set
    }

    /** His market with no figure named: what the pricer alone says after the snub. */
    private fun rawMarket(league: League, man: Player): Int = ContractDisputes.ask(league.copy(players = league.players.map {
        if (it.id == man.id) it.copy(demandFloor = 0) else it
    }), man.copy(demandFloor = 0))!!.market

    /**
     * The case the floor exists for: a man whose market, after the snub,
     * dips below the figure his agent named. Most men's markets do not move,
     * so the first club in league order whose star dips is the fixture; a
     * change to how leagues are generated moves which club that is, not
     * whether one exists.
     */
    private val dipping: Int by lazy {
        base.teams.indices.firstOrNull { club ->
            val (league, man) = snubbed(asking(club))
            rawMarket(league, man) < man.demandFloor
        } ?: error("no club's star has a market that dips below the figure named")
    }

    private fun asking(): Pair<League, Player> = asking(dipping)

    /** The user's lowball, turned down: the league after, and the figure named. */
    private fun snubbed(): Triple<League, Player, Int> = snubbed(asking()).let { (l, m) -> Triple(l, m, m.demandFloor) }

    private fun snubbed(asked: Pair<League, Player>): Pair<League, Player> {
        val (league, man) = asked
        val floor = ContractDisputes.reservation(man, league.tuning)
        val out = ContractDisputes.offer(league, man.teamId!!, man.id, floor - 0.1f) as Transactions.Outcome.Done
        val after = out.league.playersById.getValue(man.id)
        assertEquals(DemandState.PENDING, after.demand, "a lowball does not settle him")
        assertTrue(after.demandFloor > 0, "his agent named a figure: ${out.note}")
        return out.league to after
    }

    @Test
    fun `the snub is real - his raw market dips below the figure named`() {
        val (league, man, named) = snubbed()
        val raw = rawMarket(league, man)
        assertTrue(raw < named, "the dip this guards against: market $raw, named $named")
    }

    @Test
    fun `his market reads the named figure while it stands`() {
        val (league, man, named) = snubbed()
        assertEquals(named, ContractDisputes.ask(league, man)!!.market)
        assertEquals(named, ContractDisputes.pending(league, man.teamId!!).single { it.player.id == man.id }.market)
    }

    @Test
    fun `no offer after a snub signs him below the figure named`() {
        val (league, man, named) = snubbed()
        listOf(0.8f, 0.9f, ContractDisputes.reservation(man, league.tuning), 1f).forEach { share ->
            val out = ContractDisputes.offer(league, man.teamId!!, man.id, share)
            if (out is Transactions.Outcome.Done) {
                val after = out.league.playersById.getValue(man.id)
                if (after.demand == DemandState.SETTLED) {
                    val paid = out.league.transactions.last { it.player == man.id.v }.amount
                    assertTrue(paid >= named, "signed at $paid a year after his agent named $named (share $share)")
                } else {
                    assertEquals(named, after.demandFloor, "a second no names the same figure, not a lower one")
                    assertTrue(out.note.contains("will not go below"), out.note)
                }
            }
        }
    }

    @Test
    fun `paying him the market pays at least the figure named`() {
        val (league, man, named) = snubbed()
        val out = ContractDisputes.extend(league, man.teamId!!, man.id) as Transactions.Outcome.Done
        val paid = out.league.transactions.last { it.player == man.id.v }.amount
        assertTrue(paid >= named, "paid $paid a year, named $named")
    }

    @Test
    fun `the figure goes when the demand is answered`() {
        val (league, man, _) = snubbed()
        val paid = ContractDisputes.offer(league, man.teamId!!, man.id, 1f) as Transactions.Outcome.Done
        assertEquals(0, paid.league.playersById.getValue(man.id).demandFloor, "settled: nothing left to name")
        val refused = ContractDisputes.refuse(league, man.teamId!!, man.id) as Transactions.Outcome.Done
        assertEquals(0, refused.league.playersById.getValue(man.id).demandFloor, "told no: nothing left to name")
    }
}
