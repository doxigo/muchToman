package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotTest {

    private val day = DAY_MS
    private val now = 100 * DAY_MS + 5_000L

    private fun anchored(bank: String, balance: Double) =
        BankAccount(bank = bank, balance = balance, anchored = true)

    @Test
    fun `nothing on the list records nothing`() {
        assertNull(snapshotHistory(emptyMap(), emptyList(), mapOf("usd" to 100.0), now, now))
    }

    @Test
    fun `stale rates record nothing`() {
        val list = listOf(Holding("usd", 10.0))
        val dayOld = now - 25 * 60 * 60_000L
        assertNull(snapshotHistory(emptyMap(), list, mapOf("usd" to 100.0), dayOld, now))
    }

    @Test
    fun `a missing rate records nothing rather than a partial total`() {
        val list = listOf(Holding("usd", 10.0), Holding("btc", 1.0))
        assertNull(snapshotHistory(emptyMap(), list, mapOf("usd" to 100.0), now, now))
    }

    @Test
    fun `the same day is overwritten, not appended`() {
        val list = listOf(Holding("usd", 10.0))
        val first = snapshotHistory(emptyMap(), list, mapOf("usd" to 100.0), now, now)!!
        val second = snapshotHistory(first, list, mapOf("usd" to 120.0), now, now)!!

        assertEquals(1, second.size)
        assertEquals(1_200.0, second.getValue(now / day), 0.0)
    }

    @Test
    fun `the bank row lands right after cash`() {
        val holdings = listOf(Holding("usd", 1.0), Holding(TOMAN_ID, 5.0), Holding("btc", 1.0))
        val list = listHoldings(holdings, true, listOf(anchored("SAMAN", 300.0)), emptySet())

        assertEquals(listOf("usd", TOMAN_ID, BANK_ID, "btc"), list.map { it.typeId })
        assertEquals(300.0, list.first { it.typeId == BANK_ID }.amount, 0.0)
    }

    @Test
    fun `without cash the bank row comes first`() {
        val list = listHoldings(listOf(Holding("usd", 1.0)), true, listOf(anchored("SAMAN", 300.0)), emptySet())
        assertEquals(listOf(BANK_ID, "usd"), list.map { it.typeId })
    }

    @Test
    fun `switching messages off removes the bank row`() {
        val holdings = listOf(Holding("usd", 1.0))
        val list = listHoldings(holdings, false, listOf(anchored("SAMAN", 300.0)), emptySet())
        assertEquals(holdings, list)
    }
}
