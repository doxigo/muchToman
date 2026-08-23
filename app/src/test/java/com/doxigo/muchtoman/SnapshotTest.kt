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
    fun `setting an asset aside rebases the chart instead of stepping it down`() {
        val rates = mapOf("usd" to 100.0, "gold" to 100.0)
        val list = listOf(Holding("usd", 10.0), Holding("gold", 70.0, id = "g"))
        val before = computeTotals(list, rates) // 8_000
        val history = mapOf(90L to 7_600.0, 95L to 7_800.0)

        val aside = list.map { if (it.key == "g") it.copy(excluded = true) else it }
        val after = computeTotals(aside, rates) // 1_000
        val next = rebaseHistory(history, before, after)

        // What the report quotes: the same +5.3% month it quoted before she set anything aside.
        fun percent(h: Map<Long, Double>, now: Double) =
            changeOver(h, 100L, 10, now)!!.percent!!
        assertEquals(percent(history, before.toman), percent(next, after.toman), 1e-9)
        // And no step: today lands above the last point, not 87% below it.
        assertEquals(975.0, next.getValue(95L), 1e-9)
    }

    @Test
    fun `changing her mind puts the history back`() {
        val rates = mapOf("usd" to 100.0)
        val list = listOf(Holding("usd", 10.0), Holding("gold", 70.0, id = "g"))
        val priced = rates + ("gold" to 100.0)
        val counted = computeTotals(list, priced)
        val aside = computeTotals(list.map { if (it.key == "g") it.copy(excluded = true) else it }, priced)
        val history = mapOf(90L to 7_600.0)

        val there = rebaseHistory(history, counted, aside)
        val back = rebaseHistory(there, aside, counted)

        assertEquals(7_600.0, back.getValue(90L), 1e-9)
    }

    @Test
    fun `a partial or empty basis leaves the history alone`() {
        val history = mapOf(90L to 7_600.0)
        val partial = Totals(1_000.0, listOf("btc"))
        val whole = Totals(8_000.0, emptyList())

        assertEquals(history, rebaseHistory(history, whole, partial))
        assertEquals(history, rebaseHistory(history, partial, whole))
        // Everything set aside: nothing to scale onto, and a zero could never be scaled back.
        assertEquals(history, rebaseHistory(history, whole, Totals(0.0, emptyList())))
    }

    @Test
    fun `switching messages off removes the bank row`() {
        val holdings = listOf(Holding("usd", 1.0))
        val list = listHoldings(holdings, false, listOf(anchored("SAMAN", 300.0)), emptySet())
        assertEquals(holdings, list)
    }
}
