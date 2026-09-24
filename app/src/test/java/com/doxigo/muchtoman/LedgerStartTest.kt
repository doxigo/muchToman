package com.doxigo.muchtoman

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «شروع دفتر» against a real database: the months before the start leave every screen that reads
 * the ledger, and nothing that counts money she actually has moves with them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerStartTest {
    @Test
    fun `a start sets earlier months aside without touching goals or the picker's months`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val durable = Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()
        val derived = Room.inMemoryDatabaseBuilder(context, DerivedDb::class.java).build()
        try {
            seedBuiltins(durable)
            val now = System.currentTimeMillis()
            val today = tehranDay(now)
            val thisMonth = jalaliMonthStart(today)
            val lastMonth = jalaliMonthStart(thisMonth - 1)
            fun manual(id: String, day: Long, rial: Long) = ManualTxn(
                id = id, at = tehranDayStart(day) + 3_600_000L, day = day, amountRial = rial,
                createdAt = now, updatedAt = now,
            )
            durable.manual().putAll(
                listOf(
                    manual("salary", lastMonth + 2, 50_000_000),
                    manual("rent", lastMonth + 3, -10_000_000),
                    manual("bread", today, -1_000_000),
                )
            )
            durable.goals().put(
                Goal(
                    id = "trip", nameFa = "سفر", targetRial = 100_000_000, kind = GoalKind.SAVE,
                    period = GoalPeriod.ONCE, startsOn = lastMonth, createdAt = now, updatedAt = now,
                )
            )
            derive(durable, derived, emptyMap())

            val whole = ledgerView(derived, durable)
            val clean = ledgerView(derived, durable, startsOn = thisMonth)

            assertEquals(3, whole.entries.size)
            assertEquals(listOf(today), clean.entries.map { it.txn.day })
            assertTrue(clean.review.all { it.txn.day >= thisMonth })
            assertEquals(thisMonth, clean.health.startsOn)
            assertEquals(2, clean.health.setAside)
            assertEquals(1, clean.health.transactionCount)
            // The picker still sees every month, or «از اول» could never be offered honestly.
            assertEquals(whole.health.months, clean.health.months)
            assertEquals(3, clean.health.months.sumOf { it.second })
            // Money put aside before the start is still put aside.
            assertEquals(39_000_000L, whole.goals.single().currentRial)
            assertEquals(whole.goals.single().currentRial, clean.goals.single().currentRial)
            // «از اول» is the whole ledger, nothing filtered.
            assertEquals(whole.entries, startingFrom(whole.entries, 0L))
        } finally {
            durable.close()
            derived.close()
        }
    }
}
