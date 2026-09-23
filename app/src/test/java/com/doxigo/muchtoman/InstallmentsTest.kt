package com.doxigo.muchtoman

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «قسط»: the due-date arithmetic, the plan's figures, and which rows are offered as payments. */
class InstallmentsTest {

    private var n = 0

    /** The 31st of شهریور — the last month with a 31st, so the next due date has to clamp. */
    private val first = jalaliDay(1405, 6, 31)

    private fun entry(
        day: Long,
        rial: Long,
        direction: String = "out",
        owner: String = "",
    ): LedgerEntry {
        n++
        val ref = "s:%04d:0".format(n)
        return LedgerEntry(
            txn = Txn(
                ref = ref, srcHash = ref, seq = 0, at = tehranDayStart(day), day = day,
                bank = "SAMAN", accountId = "SAMAN", direction = direction,
                amountRial = rial, signedRial = if (direction == "out") -rial else rial,
                balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
                merchant = "جایی", merchantNorm = "جایی", refNo = "", printedAt = "",
                channel = "unknown", unitPrinted = "none", inferred = false,
                parserVer = PARSER_VERSION,
            ),
            categoryId = "cat_shopping", categoryFa = "خرید", confidence = 95,
            needsReview = false, duplicate = false, transfer = false, ownerMemberId = owner,
        )
    }

    private fun plan(id: String = "phone", payment: Long = 10_000_000, count: Int = 3) = Goal(
        id = id, nameFa = "گوشی", targetRial = payment, kind = GoalKind.INSTALLMENT,
        period = GoalPeriod.MONTH, startsOn = first, endsOn = jalaliMonthsAfter(first, count - 1),
        createdAt = 0, updatedAt = 0,
    )

    private fun links(plan: Goal, vararg paid: Pair<String, Long>) =
        paid.associate { (ref, rial) -> ref to InstallmentLink(plan.id, rial) }

    @Test
    fun `a due date keeps its day where the month has one and clamps where it does not`() {
        assertEquals(JalaliDate(1405, 7, 30), jalaliOf(jalaliMonthsAfter(first, 1)))
        assertEquals(JalaliDate(1405, 12, 29), jalaliOf(jalaliMonthsAfter(first, 6)))
        // Measured from the first due date each time, not from the last one — so the 31st comes
        // back the moment a month has one, instead of having been worn down to the 29th for good.
        assertEquals(JalaliDate(1406, 1, 31), jalaliOf(jalaliMonthsAfter(first, 7)))
        assertEquals(JalaliDate(1405, 5, 31), jalaliOf(jalaliMonthsAfter(first, -1)))
    }

    @Test
    fun `the first due date is the next such day, today included`() {
        val today = jalaliDay(1405, 7, 10)
        assertEquals(today, firstInstallmentDue(10, today))
        assertEquals(jalaliDay(1405, 7, 15), firstInstallmentDue(15, today))
        assertEquals(jalaliDay(1405, 8, 5), firstInstallmentDue(5, today))
        // مهر has no 31st: a plan due on the 31st is due on its last day, not in آبان.
        assertEquals(jalaliDay(1405, 7, 30), firstInstallmentDue(31, jalaliDay(1405, 7, 20)))
    }

    @Test
    fun `a plan is refused unless every answer is one, and is private when it is stored`() {
        val now = tehranDayStart(jalaliDay(1405, 7, 10)) + 1
        assertNull(newInstallment("x", "  ", 10_000_000, 12, 15, now))
        assertNull(newInstallment("x", "گوشی", 0, 12, 15, now))
        assertNull(newInstallment("x", "گوشی", MAX_PLAUSIBLE_RIAL + 1, 12, 15, now))
        assertNull(newInstallment("x", "گوشی", 10_000_000, 0, 15, now))
        assertNull(newInstallment("x", "گوشی", 10_000_000, MAX_INSTALLMENTS + 1, 15, now))
        assertNull(newInstallment("x", "گوشی", 10_000_000, 12, 0, now))
        assertNull(newInstallment("x", "گوشی", 10_000_000, 12, 32, now))

        val plan = newInstallment("x", " گوشی ", 10_000_000, 12, 15, now)!!
        assertEquals(GoalKind.INSTALLMENT, plan.kind)
        assertEquals("گوشی", plan.nameFa)
        // Never shared, so the sync — which publishes shared rows only — never sends it anywhere.
        assertFalse(plan.shared)
        assertEquals(jalaliDay(1405, 7, 15), plan.startsOn)
        assertEquals(jalaliDay(1406, 6, 15), plan.endsOn)
        assertEquals(12, installmentCount(plan))
    }

    @Test
    fun `partial payments settle one due payment between them`() {
        val p = plan()
        val progress = installmentProgress(p, links(p, "a" to 4_000_000, "b" to 6_000_000), emptyList(), first)
        assertEquals(10_000_000L, progress.paidRial)
        assertEquals(1, progress.paidCount)
        assertEquals(0L, progress.overdueRial)
        assertEquals(installmentDueOn(p, 1), progress.nextDue)
    }

    @Test
    fun `a payment is late only once its own day has passed`() {
        val p = plan()
        val paidOne = links(p, "a" to 10_000_000)
        val second = installmentDueOn(p, 1)
        // On the due day itself she can still pay it: not late yet.
        val dueToday = installmentProgress(p, paidOne, emptyList(), second)
        assertEquals(0L, dueToday.overdueRial)
        assertEquals(second, dueToday.nextDue)
        assertEquals(10_000_000L, installmentProgress(p, paidOne, emptyList(), second + 1).overdueRial)
    }

    @Test
    fun `a link too large for the plan reads as paid off, never as a negative balance`() {
        val p = plan()
        val huge = links(p, "a" to MAX_PLAUSIBLE_RIAL, "b" to MAX_PLAUSIBLE_RIAL)
        val progress = installmentProgress(p, huge, listOf(entry(first, 10_000_000)), first)
        assertEquals(30_000_000L, progress.paidRial)
        assertTrue(progress.done)
        assertEquals(1f, progress.share, 0f)
        assertNull(progress.nextDue)
        assertTrue(progress.candidates.isEmpty())
    }

    @Test
    fun `a payment still counts after the ledger has forgotten its message`() {
        // Sources past the thirteen-month horizon are pruned. A two-year loan must not start
        // reading as overdue in its fourteenth month for payments the app watched her make.
        val p = plan()
        val live = entry(first, 10_000_000)
        val progress = installmentProgress(
            p, links(p, live.txn.ref to 10_000_000, "s:pruned:0" to 10_000_000), listOf(live), first,
        )
        assertEquals(20_000_000L, progress.paidRial)
        assertEquals(listOf(live.txn.ref), progress.payments.map { it.txn.ref })
        assertEquals(10_000_000L, progress.olderRial)
    }

    @Test
    fun `payments offered are her own outgoing rows since the last due date, its exact amount first`() {
        val p = plan()
        val olderExact = entry(first - 5, 10_000_000)
        val newerOther = entry(first, 3_000_000)
        val newerExact = entry(first, 10_000_000)
        val incoming = entry(first, 10_000_000, direction = "in")
        val lastMonths = entry(jalaliMonthsAfter(first, -1), 10_000_000)
        val his = entry(first, 10_000_000, owner = "b".repeat(32))
        val transfer = entry(first, 10_000_000).copy(transfer = true)
        val paysOther = entry(first, 10_000_000)
        val progress = installmentProgress(
            p,
            mapOf(paysOther.txn.ref to InstallmentLink("fridge", 10_000_000)),
            listOf(olderExact, newerOther, newerExact, incoming, lastMonths, his, transfer, paysOther),
            first,
        )
        assertEquals(
            listOf(newerExact, olderExact, newerOther).map { it.txn.ref },
            progress.candidates.map { it.txn.ref },
        )
    }

    @Test
    fun `only readable links to a plan that still exists are counted`() {
        val live = plan("phone")
        val gone = plan("tv").copy(deleted = true)
        fun decision(ref: String, value: String?, deleted: Boolean = false) =
            TxnDecision(ref, ref, DecisionKind.INSTALLMENT, value, 0, 0, deleted = deleted)
        val found = installmentLinks(
            listOf(
                decision("a", InstallmentLink("phone", 10_000_000).encode()),
                decision("b", InstallmentLink("tv", 10_000_000).encode()),
                decision("c", "phone:abc"),
                decision("d", "phone:-5"),
                decision("e", null),
                decision("f", InstallmentLink("phone", 10_000_000).encode(), deleted = true),
                TxnDecision("g", "g", DecisionKind.NOTE, "phone:10000000", 0, 0),
            ),
            listOf(live, gone),
        )
        assertEquals(mapOf("a" to InstallmentLink("phone", 10_000_000)), found)
    }
}

/** The one read the screen takes, against a real database: a plan is a plan and nothing else. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentsLedgerTest {
    @Test
    fun `an installment is read as one, and never as a savings goal`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val durable = Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()
        val derived = Room.inMemoryDatabaseBuilder(context, DerivedDb::class.java).build()
        try {
            seedBuiltins(durable)
            val now = System.currentTimeMillis()
            val today = tehranDay(now)
            durable.goals().put(newInstallment("plan", "گوشی", 10_000_000, 3, jalaliOf(today).day, now)!!)
            durable.goals().put(
                Goal(
                    id = "trip", nameFa = "سفر", targetRial = 50_000_000, kind = GoalKind.SAVE,
                    period = GoalPeriod.ONCE, startsOn = today, createdAt = now, updatedAt = now,
                )
            )
            // Linked to a message the ledger no longer holds, which is the case the copied amount is for.
            durable.decisions().put(
                TxnDecision(
                    "d1", "s:gone:0", DecisionKind.INSTALLMENT,
                    InstallmentLink("plan", 10_000_000).encode(), now, now,
                )
            )

            val view = ledgerView(derived, durable)
            // Before, every row that was not a cap came back as a savings goal.
            assertEquals(listOf("trip"), view.goals.map { it.goal.id })
            assertTrue(view.budgets.isEmpty())
            val plan = view.installments.single()
            assertEquals("plan", plan.plan.id)
            assertEquals(10_000_000L, plan.paidRial)
            assertEquals(0L, plan.overdueRial)
        } finally {
            derived.close()
            durable.close()
        }
    }
}
