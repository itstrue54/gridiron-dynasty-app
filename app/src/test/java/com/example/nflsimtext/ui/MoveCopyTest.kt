package com.example.nflsimtext.ui

import com.nflsim.engine.model.Transaction
import com.nflsim.engine.model.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moves read from the right club's side; a coach's job in words. */
class MoveCopyTest {

    private val abbrev = mapOf(1 to "IND", 2 to "LA", 3 to "CLE")

    /** IND sign LA's practice-squad tackle. */
    private val poached = Transaction(2026, 13, TransactionKind.SIGNED_OFF_SQUAD, team = 1, player = 9,
        name = "Nathan Hines", position = "LT", amount = 233, other = 2)

    @Test
    fun `a squad signing reads as the signing club's move, or the losing club's loss`() {
        assertEquals("took from" to "LA", moveAndTerms(poached, abbrev, own = null))
        assertEquals("took from" to "LA", moveAndTerms(poached, abbrev, own = 1))
        assertEquals("signed away" to "by IND", moveAndTerms(poached, abbrev, own = 2))
    }

    @Test
    fun `a trade reads from the club that lost the man in its own view`() {
        val trade = poached.copy(kind = TransactionKind.TRADED)
        assertEquals("traded in" to "from LA", moveAndTerms(trade, abbrev, own = null))
        assertEquals("traded away" to "to IND", moveAndTerms(trade, abbrev, own = 2))
    }

    @Test
    fun `a head coach's job is said in words, with the numbers behind it`() {
        assertTrue(jobSecurity(12, 44).startsWith("His job is safe."))
        assertTrue(jobSecurity(25, 44).startsWith("Losing has put some pressure on him."))
        assertTrue(jobSecurity(40, 44).startsWith("He is on the hot seat"))
        assertTrue(jobSecurity(12, 44).endsWith("Pressure 12; this club fires a coach at 44."))
    }
}
