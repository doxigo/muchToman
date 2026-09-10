package com.doxigo.muchtoman

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lock screen: every note is VISIBILITY_PRIVATE and carries a redacted public version, so a
 * locked phone names the kind of news and nothing else — no merchant, no figure, no category.
 * The full words behind the lock are asserted unchanged, because the unlocked experience is not
 * this change's to alter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotifyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private var n = 0

    /** A transaction fixture, copied from [FilingTest]: اسنپ, ۴۵۰ هزار تومان out. */
    private fun waiting(at: Long, merchant: String = "اسنپ", signed: Long? = -4_500_000L): LedgerEntry {
        n++
        val ref = "s:%04d:0".format(n)
        return LedgerEntry(
            txn = Txn(
                ref = ref, srcHash = ref, seq = 0, at = at, day = tehranDay(at),
                bank = "SAMAN", accountId = "SAMAN",
                direction = signed?.let { if (it > 0) "in" else "out" },
                amountRial = signed?.let { kotlin.math.abs(it) }, signedRial = signed,
                balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
                merchant = merchant, merchantNorm = merchant, refNo = "", printedAt = "",
                channel = "unknown", unitPrinted = "none", inferred = false,
                parserVer = PARSER_VERSION,
            ),
            categoryId = CAT_UNCATEGORISED, categoryFa = "دسته‌بندی نشده", confidence = Confidence.NONE,
            needsReview = true, duplicate = false, transfer = false,
        )
    }

    // ─────────── the budget fixture, copied from [BudgetTest] ───────────

    private val first = jalaliDay(1405, 5, 1)
    private val dining = "cat_dining"

    private fun spend(day: Long, signed: Long): LedgerEntry =
        waiting(at = tehranDayStart(day), signed = signed).copy(
            txn = waiting(at = tehranDayStart(day), signed = signed).txn.copy(day = day, merchant = "جایی", merchantNorm = "جایی"),
            categoryId = dining, categoryFa = "رستوران و کافه", needsReview = false,
        )

    private fun budget(cap: Long) = Goal(
        id = "b1", nameFa = "رستوران و کافه", targetRial = cap, kind = GoalKind.CAP,
        categoryId = dining, period = BudgetPeriod.MONTH.id, startsOn = first,
        createdAt = 0, updatedAt = 0,
    )

    private fun title(note: Notification): String =
        note.extras.getCharSequence(Notification.EXTRA_TITLE)!!.toString()

    private fun text(note: Notification): String =
        note.extras.getCharSequence(Notification.EXTRA_TEXT)!!.toString()

    // ─────────── the three notes ───────────

    @Test
    fun `the budget note is private and its public face names no category and no figure`() {
        val progress = budgetProgress(budget(50_000_000), listOf(spend(first, -41_500_000)), first + 5)
        val note = budgetNote(context, progress)

        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, note.visibility)
        val public = note.publicVersion
        assertNotNull(public)
        assertEquals(budgetPublicTitle(), title(public!!))
        assertEquals(publicBody(), text(public))
        // The actual point: nothing of the category or the figures reaches a locked screen.
        for (line in listOf(title(public), text(public))) {
            assertTrue(!line.contains(progress.categoryFa))
            assertTrue(!line.contains(faNumber(progress.percent.toDouble())))
            assertTrue(!line.contains(faCompact(tomanOf(progress.capRial))))
            assertTrue(!line.contains(faCompact(tomanOf(progress.leftRial))))
        }
        // Behind the lock, nothing changed.
        assertEquals(budgetAlertTitle(progress), title(note))
        assertEquals(budgetAlertBody(progress), text(note))
    }

    @Test
    fun `a landed note is private and its public face names no merchant and no amount`() {
        val entry = waiting(at = 1_000L)
        val note = landedNote(context, entry)

        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, note.visibility)
        val public = note.publicVersion
        assertNotNull(public)
        assertEquals(landedPublicTitle(), title(public!!))
        assertEquals(publicBody(), text(public))
        // The actual point: neither the merchant nor a digit of the amount is on a locked screen.
        val rial = entry.txn.amountRial!!
        for (line in listOf(title(public), text(public))) {
            assertTrue(!line.contains(entry.txn.merchant))
            assertTrue(!line.contains(faCompact(tomanOf(rial))))
            assertTrue(!line.contains(faNumber(tomanOf(rial))))
        }
        // Behind the lock, the full words are unchanged.
        assertEquals(landedTitle(entry), title(note))
        assertEquals(landedBody(entry), text(note))
    }

    @Test
    fun `the summary is private and its public face only counts`() {
        val alert = FilingAlert(fresh = 2, waiting = 3, newest = waiting(at = 2_000L), filed = 1)
        val note = filingSummary(context, alert)

        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, note.visibility)
        val public = note.publicVersion
        assertNotNull(public)
        assertEquals(filingPublicTitle(3), title(public!!))
        assertEquals(publicBody(), text(public))
        for (line in listOf(title(public), text(public))) {
            assertTrue(!line.contains(alert.newest.txn.merchant))
            assertTrue(!line.contains(faNumber(tomanOf(alert.newest.txn.amountRial!!))))
        }
        // Behind the lock, the full words are unchanged.
        assertEquals(filingAlertTitle(alert), title(note))
        assertEquals(filingAlertBody(alert), text(note))
    }

    // ─────────── the public words themselves ───────────

    @Test
    fun `the public words count in Persian and a lone one reads like a note`() {
        assertEquals("۳ تراکنش تازه", filingPublicTitle(3))
        assertEquals(landedPublicTitle(), filingPublicTitle(1))
    }
}
