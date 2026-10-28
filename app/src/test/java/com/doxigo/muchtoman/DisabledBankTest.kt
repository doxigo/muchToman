package com.doxigo.muchtoman

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A bank switched off in تنظیمات, against a real derive: out of the ledger and out of the note. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DisabledBankTest {
    private val now = 1_780_000_000_000L

    private fun source(sender: String, body: String, at: Long) = SmsSource(
        srcHash = srcHash(sender, body, at), sender = sender, addrKey = srcAddrKeyV1(sender),
        body = body, at = at, ingestedAt = at,
    )

    @Test
    fun `a switched-off bank leaves the ledger and the filing note`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val durable = Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()
        val derived = Room.inMemoryDatabaseBuilder(context, DerivedDb::class.java).build()
        try {
            seedBuiltins(durable, now)
            durable.smsSource().insertAll(
                listOf(
                    source("0999 992 0000", "بانک سامان\nبرداشت مبلغ 1,000,000 ریال", now - 3_600_000),
                    source("0999 998 7641", "برداشت مبلغ 2,000,000 ریال", now - 600_000),
                )
            )
            derive(durable, derived, emptyMap(), now)

            val whole = ledgerView(derived, durable)
            val off = ledgerView(derived, durable, disabledBanks = setOf("SAMAN"))
            assertEquals(setOf("SAMAN", "BLU"), whole.entries.map { it.txn.bank }.toSet())
            assertEquals(listOf("BLU"), off.entries.map { it.txn.bank })
            assertEquals(listOf("BLU"), off.review.map { it.txn.bank })
            assertEquals(listOf("BLU"), filingNews(off.entries, off.review, said = 1L, now = now).landed.map { it.txn.bank })
        } finally {
            durable.close()
            derived.close()
        }
    }
}
