package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hand-typed row carries "MANUAL" as its bank and names its real account in accountId; the
 * family's bank veto has to read the account, or a row typed against a set-aside bank leaks.
 */
class ManualRowBankGateTest {

    private fun typed(account: String?) =
        manualToRow(ManualTxn(id = "a", at = 1L, day = 0L, amountRial = -5_000L, accountId = account, createdAt = 1L, updatedAt = 1L))

    private val decision = TxnDecision(id = "note:m:a", ref = "m:a", kind = "note", value = "x", createdAt = 1, updatedAt = 1, memberId = "me")

    @Test
    fun `a typed row is at the bank of its account`() {
        assertEquals("MELLAT", bankOfRow(typed("MELLAT")))
        assertEquals("MELLAT", bankOfRow(typed("MELLAT:0457")))
        assertEquals("MANUAL", bankOfRow(typed(null)))
    }

    @Test
    fun `a row that is not typed keeps its own bank`() {
        assertEquals("SAMAN", bankOfRow(typed(null).copy(ref = "s:h:0", bank = "SAMAN", accountId = "SAMAN:1")))
        assertEquals("MANUAL", bankOfRow(typed(null).copy(ref = "f:x", bank = "MANUAL", accountId = "family:him:MANUAL")))
    }

    @Test
    fun `a note on a typed row at a set-aside bank may not leave, cash may`() {
        val set = setOf("MELLAT")
        assertFalse(decisionMayLeave(decision, typed("MELLAT"), shareSms = false, member = "me", excludedBanks = set))
        assertFalse(decisionMayLeave(decision, typed("MELLAT:0457"), shareSms = false, member = "me", excludedBanks = set))
        assertTrue(decisionMayLeave(decision, typed(null), shareSms = false, member = "me", excludedBanks = set))
        assertTrue(decisionMayLeave(decision, typed("MELLAT"), shareSms = false, member = "me", excludedBanks = setOf("SAMAN")))
    }
}
