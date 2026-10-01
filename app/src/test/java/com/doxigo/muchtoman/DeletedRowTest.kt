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

/** A row she deleted, against a real derive: what it was paired with stays paired. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeletedRowTest {
    private val saman = "0999 992 0000"
    private val blu = "0999 998 7641"
    private val now = 1_780_000_000_000L

    private fun source(sender: String, body: String, at: Long) = SmsSource(
        srcHash = srcHash(sender, body, at), sender = sender, addrKey = srcAddrKeyV1(sender),
        body = body, at = at, ingestedAt = at,
    )

    @Test
    fun `deleting a row keeps its echo hidden and its transfer partner a transfer`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val durable = Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()
        val derived = Room.inMemoryDatabaseBuilder(context, DerivedDb::class.java).build()
        try {
            seedBuiltins(durable, now)
            val body = "بانک سامان\nبرداشت مبلغ 1,000,000 ریال\nشماره پیگیری 771205"
            val purchase = source(saman, body, now - 3_600_000)
            // The carrier's second copy: same words, stamped a minute later.
            val echo = source(saman, body, now - 3_540_000)
            val sent = source(saman, "بانک سامان\nانتقال مبلغ 50,000,000 ریال", now - 600_000)
            val received = source(blu, "واریز مبلغ 50,000,000 ریال", now - 540_000)
            durable.smsSource().insertAll(listOf(purchase, echo, sent, received))
            derive(durable, derived, emptyMap(), now)
            val before = ledgerView(derived, durable).entries.associateBy { it.txn.srcHash }
            assertTrue(before.getValue(echo.srcHash).duplicate)
            assertTrue(before.getValue(received.srcHash).transfer)

            for (gone in listOf(purchase, sent)) durable.decisions().put(
                TxnDecision(uuid7(now), refOf(gone.srcHash), DecisionKind.HIDE, "1", now, now),
            )
            derive(durable, derived, emptyMap(), now)
            val after = ledgerView(derived, durable).entries.associateBy { it.txn.srcHash }
            // The copy goes with the row she deleted, rather than coming back as a spend of its own…
            assertEquals(setOf(received.srcHash), after.keys)
            // …and the other leg of the transfer is still a transfer, not income.
            assertTrue(after.getValue(received.srcHash).transfer)
        } finally {
            durable.close()
            derived.close()
        }
    }
}
