package com.doxigo.muchtoman

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A plain category pick written to its one row: the rows a full derive would have written. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PinClassTest {
    private val saman = "0999 992 0000"
    private val blu = "0999 998 7641"
    private val now = 1_780_000_000_000L

    private fun source(sender: String, body: String, at: Long) = SmsSource(
        srcHash = srcHash(sender, body, at), sender = sender, addrKey = srcAddrKeyV1(sender),
        body = body, at = at, ingestedAt = at,
    )

    @Test
    fun `pinning one row matches a full derive`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val durable = Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()
        val derived = Room.inMemoryDatabaseBuilder(context, DerivedDb::class.java).build()
        try {
            seedBuiltins(durable, now)
            val purchase = source(saman, "بانک سامان\nبرداشت مبلغ 1,000,000 ریال\nشماره پیگیری 771205", now - 3_600_000)
            val sent = source(saman, "بانک سامان\nانتقال مبلغ 50,000,000 ریال", now - 600_000)
            val received = source(blu, "واریز مبلغ 50,000,000 ریال", now - 540_000)
            durable.smsSource().insertAll(listOf(purchase, sent, received))
            derive(durable, derived, emptyMap(), now)
            val before = derived.classes().all().sortedBy { it.ref }
            val txn = ledgerView(derived, durable).entries.first { it.txn.srcHash == purchase.srcHash }.txn

            durable.decisions().put(TxnDecision(uuid7(now), txn.ref, DecisionKind.CATEGORY, "cat_groceries", now, now))
            assertTrue(pinClass(derived, txn, "cat_groceries"))
            val pinned = derived.classes().all().sortedBy { it.ref }
            assertNotEquals(before, pinned)
            derive(durable, derived, emptyMap(), now)
            assertEquals(derived.classes().all().sortedBy { it.ref }, pinned)

            // A row the derive did not write is not pinned into being: the caller derives instead.
            assertFalse(pinClass(derived, txn.copy(ref = "s:gone:0"), "cat_groceries"))
            assertEquals(pinned, derived.classes().all().sortedBy { it.ref })
        } finally {
            durable.close()
            derived.close()
        }
    }
}
