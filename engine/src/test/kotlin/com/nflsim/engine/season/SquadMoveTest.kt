package com.nflsim.engine.season

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A man moved from the 53 to the club's own practice squad - or refused before anything moves. */
class SquadMoveTest {

    private val generated = LeagueGenerator.generate(2026, 4L)
    private val club = generated.teams.first().id
    /** A generated league starts with every squad full: this club's is emptied, so it has room. */
    private val league = generated.copy(
        players = generated.players.map { p -> if (p.id in generated.team(club).practiceSquad) p.copy(status = PlayerStatus.FREE_AGENT) else p },
        teams = generated.teams.map { if (it.id == club) it.copy(practiceSquad = emptyList()) else it },
    )
    /** A young backup, developmental by the CBA's count. */
    private val young = league.roster(club).filter { !PracticeSquads.isVeteran(it) && it.injuryWeeks == 0 }.minBy { it.capHit(2026) }

    @Test
    fun `a man the squad has room for is released and signed to it, and pays his dead money`() {
        val out = Transactions.releaseToPracticeSquad(league, club, young.id) as Transactions.Outcome.Done
        val after = out.league
        assertTrue(young.id !in after.team(club).roster)
        assertTrue(young.id in after.team(club).practiceSquad)
        assertEquals(PlayerStatus.PRACTICE_SQUAD, after.player(young.id).status)
        assertEquals(league.team(club).finances.deadMoney + (young.contract?.deadCap(2026)?.thisYear ?: 0),
            after.team(club).finances.deadMoney)
        assertEquals(listOf(TransactionKind.RELEASED, TransactionKind.TO_SQUAD),
            after.transactions.takeLast(2).map { it.kind })
        assertTrue("practice squad" in out.note, out.note)
    }

    @Test
    fun `a man the squad can't take is refused, and nothing moves`() {
        // A squad already carrying three at his position.
        val others = league.players.filter { it.teamId == null && it.position == young.position }.take(3)
        val crowded = league.copy(
            players = league.players.map { p -> if (p in others) p.copy(status = PlayerStatus.PRACTICE_SQUAD) else p },
            teams = league.teams.map { if (it.id == club) it.copy(practiceSquad = others.map { o -> o.id }) else it },
        )
        assertTrue(others.size == 3, "need three free agents at ${young.position}")
        val refusal = Transactions.squadRefusal(crowded, club, young.id)
        assertTrue(refusal != null && young.position.label in refusal, "$refusal")
        val out = Transactions.releaseToPracticeSquad(crowded, club, young.id)
        assertTrue(out is Transactions.Outcome.Refused)
        assertNull(crowded.transactions.lastOrNull { it.player == young.id.v })
    }

    @Test
    fun `a full squad refuses him`() {
        assertTrue(Transactions.squadRefusal(generated, club, young.id)!!.contains("full"))
    }

    @Test
    fun `a hurt man, a squad man or another club's man can't be sent down`() {
        val hurt = league.copy(players = league.players.map { if (it.id == young.id) it.copy(injuryWeeks = 2) else it })
        assertTrue(Transactions.squadRefusal(hurt, club, young.id)!!.contains("hurt"))
        val theirs = league.roster(league.teams[1].id).first()
        assertTrue(Transactions.squadRefusal(league, club, theirs.id) != null)
        assertNull(Transactions.squadRefusal(league, club, young.id))
    }

    private fun League.withVeteranSquad(n: Int): League {
        val vets = players.filter { it.teamId == null && PracticeSquads.isVeteran(it) }.distinctBy { it.position }.take(n)
        return copy(
            players = players.map { p -> if (p in vets) p.copy(status = PlayerStatus.PRACTICE_SQUAD) else p },
            teams = teams.map { if (it.id == club) it.copy(practiceSquad = vets.map { v -> v.id }) else it },
        )
    }

    @Test
    fun `a veteran needs one of the six veteran places`() {
        val vet = league.roster(club).firstOrNull { PracticeSquads.isVeteran(it) && it.injuryWeeks == 0 } ?: return
        val full = league.withVeteranSquad(PracticeSquads.VETERANS)
        if (full.team(club).practiceSquad.size < PracticeSquads.VETERANS) return
        assertTrue(Transactions.squadRefusal(full, club, vet.id)!!.contains("veteran"))
    }
}
