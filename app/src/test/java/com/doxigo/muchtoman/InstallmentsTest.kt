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

    /** Rent: no end, due on [first]'s day every month. */
    private fun bill(id: String = "rent", payment: Long = 80_000_000) = Goal(
        id = id, nameFa = "اجاره", targetRial = payment, kind = GoalKind.INSTALLMENT,
        period = GoalPeriod.MONTH, startsOn = first, endsOn = null, createdAt = 0, updatedAt = 0,
    )

    private fun links(plan: Goal, vararg paid: Pair<String, Long>) =
        paid.associate { (ref, rial) -> ref to InstallmentLink(plan.id, rial) }

    @Test
    fun `a reminder comes once per due date, inside her days and waking hours`() {
        val p = plan()
        val second = jalaliMonthsAfter(first, 1)
        fun at(day: Long, hour: Int) = tehranDayStart(day) + hour * 3_600_000L
        val unpaid = installmentProgress(p, emptyMap(), emptyList(), first - 1)

        val news = installmentNews(listOf(unpaid), 1, emptyMap(), at(first - 1, 10))
        assertEquals(listOf(unpaid to first), news.due)
        assertEquals("گوشی: سررسید قسط فرداست", installmentReminderTitle(p, first, first - 1))
        // Said once: the next sweep that day, and the one on the due day itself, stay quiet.
        assertTrue(installmentNews(listOf(unpaid), 1, news.marks, at(first - 1, 16)).due.isEmpty())
        assertTrue(installmentNews(listOf(unpaid), 1, news.marks, at(first, 10)).due.isEmpty())
        assertEquals(news.marks, installmentNews(listOf(unpaid), 1, news.marks, at(first, 10)).marks)
        // Not at three in the morning, not two days out on «a day before», not when off.
        assertTrue(installmentNews(listOf(unpaid), 1, emptyMap(), at(first - 1, 3)).due.isEmpty())
        assertTrue(installmentNews(listOf(unpaid), 1, emptyMap(), at(first - 2, 10)).due.isEmpty())
        assertTrue(installmentNews(listOf(unpaid), -1, emptyMap(), at(first - 1, 10)).due.isEmpty())

        // Paid ahead and linked: nothing to remind of until the payment after it.
        val paid = installmentProgress(p, links(p, "a" to 10_000_000), emptyList(), first - 1)
        assertTrue(installmentNews(listOf(paid), 1, emptyMap(), at(first - 1, 10)).due.isEmpty())
        // Paid but never linked: the calendar still moves on to next month's date.
        val behind = installmentProgress(p, emptyMap(), emptyList(), second - 1)
        assertEquals(listOf(behind to second), installmentNews(listOf(behind), 1, news.marks, at(second - 1, 10)).due)
    }

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
    fun `only her own outgoing payments can pay a plan, from either end of the link`() {
        assertTrue(installmentPayable(entry(first, 10_000_000), mineId = ""))
        assertFalse(installmentPayable(entry(first, 10_000_000, owner = "b".repeat(32)), mineId = ""))
        assertFalse(installmentPayable(entry(first, 10_000_000, direction = "in"), mineId = ""))
        assertFalse(installmentPayable(entry(first, 10_000_000).copy(transfer = true), mineId = ""))
        assertFalse(installmentPayable(entry(first, 10_000_000).copy(duplicate = true), mineId = ""))
    }

    @Test
    fun `a plan made from a payment starts on its day and counts it as the first installment`() {
        // Filed as قسط three days ago; the plan made from it must not push its schedule a month on.
        val paidOn = first - 3
        val plan = newInstallment("p", "گوشی", 10_000_000, 12, jalaliOf(paidOn).day, tehranDayStart(first), firstDue = paidOn)!!
        assertEquals(paidOn, plan.startsOn)
        assertEquals(12, installmentCount(plan))
        val payment = entry(paidOn, 10_000_000)
        val progress = installmentProgress(plan, links(plan, payment.txn.ref to 10_000_000), listOf(payment), first)
        assertEquals(1, progress.paidCount)
        assertEquals(0L, progress.overdueRial)
        assertEquals(installmentDueOn(plan, 1), progress.nextDue)
        assertEquals("p", installmentPaidBy(payment.txn.ref, listOf(progress))?.plan?.id)
        assertNull(installmentPaidBy("s:elsewhere:0", listOf(progress)))
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

    @Test
    fun `plans are listed soonest payment first, a late one ahead and a paid-off one last`() {
        val late = plan("late")
        val later = plan("later").copy(startsOn = first + 10, endsOn = jalaliMonthsAfter(first + 10, 2))
        val done = plan("done")
        val paid = links(done, "a" to 30_000_000)
        val list = listOf(done, later, late).map { installmentProgress(it, paid, emptyList(), first + 5) }
        assertEquals(listOf("late", "later", "done"), installmentsByDue(list).map { it.plan.id })
    }

    @Test
    fun `the card says how many are left and when the last one falls due`() {
        val p = plan()
        val one = installmentProgress(p, links(p, "a" to 10_000_000), emptyList(), first)
        assertEquals("۲ از ۳ قسط مونده، تا ۳۰ آبان ۱۴۰۵", installmentLeftFa(one))
        assertNull(installmentLeftFa(installmentProgress(p, links(p, "a" to 30_000_000), emptyList(), first)))
    }

    @Test
    fun `the section adds up the running plans, and counts this month's payments paid off or not`() {
        val phone = plan("phone")
        val tv = plan("tv", payment = 5_000_000, count = 12)
        val old = plan("old")
        val paid = links(phone, "a" to 10_000_000) + links(old, "b" to 30_000_000)
        val today = jalaliDay(1405, 6, 20)
        val all = listOf(phone, tv, old).map { installmentProgress(it, paid, emptyList(), today) }

        assertEquals(
            InstallmentSummary(
                monthlyRial = 15_000_000,
                leftRial = 20_000_000 + 60_000_000,
                lastDue = jalaliMonthsAfter(first, 11),
                dueThisMonth = 3,
                paidThisMonth = 2,
            ),
            installmentSummary(all, today),
        )
        // One running plan is its own card already.
        assertNull(installmentSummary(all.filter { it.plan.id != "tv" }, today))
        // A month none of them has a payment in says nothing about this month.
        assertEquals(0, installmentSummary(all, jalaliMonthsAfter(first, 12))!!.dueThisMonth)
    }

    @Test
    fun `home hears of a payment inside the reminder's window, or once it is late`() {
        val p = plan()
        val unpaid = { today: Long -> installmentProgress(p, emptyMap(), emptyList(), today) }

        val tomorrow = pressingInstallment(listOf(unpaid(first - 1)), first - 1, daysBefore = 1)!!
        assertEquals(0, tomorrow.second)
        val insight = installmentInsight(tomorrow.first, tomorrow.second, first - 1)
        assertEquals("سررسید قسط «گوشی» فرداست.", insight.text)
        assertEquals("قسطی که خودت ساختی: ماهی ۱ میلیون تومان، قسط اول از ۳، ۳۱ شهریور ۱۴۰۵.", insight.why)
        assertEquals(Insight.Tone.ATTENTION, insight.tone)

        assertNull(pressingInstallment(listOf(unpaid(first - 1)), first - 1, daysBefore = 0))
        // Reminders off still leaves the day itself.
        assertEquals(0, pressingInstallment(listOf(unpaid(first)), first, daysBefore = -1)!!.second)
        val late = pressingInstallment(listOf(unpaid(first + 1)), first + 1, daysBefore = 1)!!
        assertEquals("سررسید قسط «گوشی» گذشته.", installmentInsight(late.first, late.second, first + 1).text)
        // Paid ahead, the next one is a month off: nothing to say.
        val ahead = installmentProgress(p, links(p, "a" to 10_000_000), emptyList(), first - 1)
        assertNull(pressingInstallment(listOf(ahead), first - 1, daysBefore = 3))
    }

    @Test
    fun `a payment falling due outranks the review queue for home's one slot`() {
        val progress = installmentProgress(plan(), emptyMap(), emptyList(), first - 1)
        val rows = listOf(entry(first - 3, 1_000_000).copy(needsReview = true))
        val story = buildStory(rows, liquidRial = 0L, today = first - 1, installments = listOf(progress))
        assertEquals(progress, story.attentionInstallment)
        assertEquals("سررسید قسط «گوشی» فرداست.", story.attention!!.text)
        val quiet = buildStory(rows, liquidRial = 0L, today = first - 1)
        assertNull(quiet.attentionInstallment)
        assertTrue(quiet.attention!!.text.contains("منتظر"))
    }

    @Test
    fun `a bill is a plan with no end, private like any other`() {
        val now = tehranDayStart(jalaliDay(1405, 7, 10)) + 1
        val rent = newInstallment("x", "اجاره", 80_000_000, NO_END, 5, now)!!
        assertEquals(GoalKind.INSTALLMENT, rent.kind)
        assertNull(rent.endsOn)
        assertTrue(rent.isBill)
        assertFalse(rent.shared)
        assertEquals(jalaliDay(1405, 8, 5), rent.startsOn)
        assertNull(installmentCount(rent))
        // A cheque has no end either until it is passed, and is not a bill.
        assertFalse(rent.copy(kind = GoalKind.CHEQUE).isBill)
        // «۰» left is still no plan at all, and the other answers are checked as before.
        assertNull(newInstallment("x", "اجاره", 80_000_000, 0, 5, now))
        assertNull(newInstallment("x", "اجاره", 80_000_000, NO_END, 32, now))
        // Made from a payment: it starts on the payment's day, and that payment is its first month.
        val fromPayment = newInstallment("y", "اینترنت", 3_000_000, NO_END, 31, now, firstDue = first)!!
        assertEquals(first, fromPayment.startsOn)
        assertNull(fromPayment.endsOn)
    }

    @Test
    fun `a bill has no count, no total and no end, and is never paid off`() {
        val rent = bill()
        val months = installmentProgress(rent, links(rent, "a" to 80_000_000L * 12), emptyList(), jalaliMonthsAfter(first, 11))
        assertNull(months.count)
        assertNull(months.totalRial)
        assertFalse(months.done)
        assertEquals(0f, months.share)
        assertEquals(12, months.paidCount)
        assertEquals(0L, months.overdueRial)
        assertEquals(jalaliMonthsAfter(first, 12), months.nextDue)
        assertNull(installmentLeftFa(months))
        // Still offered as the payment of next month's rent.
        val next = entry(jalaliMonthsAfter(first, 11), 80_000_000)
        assertEquals(
            listOf(next.txn.ref),
            installmentProgress(rent, emptyMap(), listOf(next), first).candidates.map { it.txn.ref },
        )
        assertEquals("ماهی ۸ میلیون تومان • سی و یکم هر ماه", installmentLineFa(months))
    }

    @Test
    fun `a bill falls behind month by month, and only once a date has passed unpaid`() {
        val rent = bill()
        val second = jalaliMonthsAfter(first, 1)
        fun note(paid: Long, today: Long) =
            installmentNoteFa(installmentProgress(rent, if (paid > 0) links(rent, "a" to paid) else emptyMap(), emptyList(), today), today)

        assertEquals("اولین سررسید: ۳۱ شهریور ۱۴۰۵" to false, note(0, jalaliDay(1405, 5, 10)))
        assertEquals("این ماه هنوز پرداخت نشده، سررسید ۳۱ شهریور ۱۴۰۵." to false, note(0, first - 1))
        assertEquals("سررسید این ماه امروزه." to false, note(0, first))
        assertEquals("این ماه پرداخت شده، بعدی ۳۰ مهر ۱۴۰۵." to false, note(80_000_000, first))
        // A new month: last month's is paid, this one's not yet due.
        assertEquals("این ماه هنوز پرداخت نشده، سررسید ۳۰ مهر ۱۴۰۵." to false, note(80_000_000, first + 1))
        // Behind, said out loud, and it grows with each month gone by.
        assertEquals(
            "۸ میلیون تومان از ماه‌هایی که سررسیدشون گذشته، هنوز پرداخت نشده." to true,
            note(0, first + 1),
        )
        assertEquals(160_000_000L, installmentProgress(rent, emptyMap(), emptyList(), second + 1).overdueRial)
        // Half a month paid is still behind by the other half.
        assertEquals(40_000_000L, installmentProgress(rent, links(rent, "a" to 40_000_000), emptyList(), first + 1).overdueRial)
    }

    @Test
    fun `a link far too large for a bill reads as years ahead, never as a wrapped count`() {
        val tiny = bill(payment = 1)
        val progress = installmentProgress(tiny, links(tiny, "a" to MAX_PLAUSIBLE_RIAL), emptyList(), first)
        assertEquals(MAX_INSTALLMENTS, progress.paidCount)
        assertEquals(jalaliMonthsAfter(first, MAX_INSTALLMENTS), progress.nextDue)
        assertEquals(jalaliMonthsAfter(first, MAX_INSTALLMENTS), installmentUpcoming(progress, first))
    }

    @Test
    fun `a bill is reminded of every month, past any count a plan could have`() {
        val rent = bill()
        val far = jalaliMonthsAfter(first, MAX_INSTALLMENTS + 5)
        val paidUp = installmentProgress(rent, links(rent, "a" to 80_000_000L * (MAX_INSTALLMENTS + 5)), emptyList(), far - 1)
        val news = installmentNews(listOf(paidUp), 1, emptyMap(), tehranDayStart(far - 1) + 10 * 3_600_000L)
        assertEquals(listOf(paidUp to far), news.due)
        assertEquals("اجاره: سررسیدش فرداست", installmentReminderTitle(rent, far, far - 1))
        assertEquals("۸ میلیون تومان • ${faDate(far)}", installmentReminderBody(rent, far))
        assertEquals("یادآوری قبض", installmentPublicTitle(rent))
        assertEquals("یادآوری قسط", installmentPublicTitle(plan()))

        // Home: the bill by its own name, with no «قسط سوم از ۲۴».
        val tomorrow = pressingInstallment(listOf(installmentProgress(rent, emptyMap(), emptyList(), first - 1)), first - 1, 1)!!
        val insight = installmentInsight(tomorrow.first, tomorrow.second, first - 1)
        assertEquals("سررسید «اجاره» فرداست.", insight.text)
        assertEquals("قبضی که خودت ساختی: ماهی ۸ میلیون تومان، ۳۱ شهریور ۱۴۰۵.", insight.why)
        // Behind, it leads home's slot and is said as gone by.
        val late = pressingInstallment(listOf(installmentProgress(rent, emptyMap(), emptyList(), first + 40)), first + 40, 1)!!
        assertEquals(0, late.second)
        assertEquals("سررسید «اجاره» گذشته.", installmentInsight(late.first, late.second, first + 40).text)
    }

    @Test
    fun `bills are left out of the installments' sum and add up to this month's line instead`() {
        val today = jalaliDay(1405, 6, 20)
        val phone = plan("phone")
        val tv = plan("tv", payment = 5_000_000, count = 12)
        val rent = bill("rent")
        val net = bill("net", payment = 3_000_000)
        val paid = links(phone, "a" to 10_000_000) + links(net, "b" to 1_000_000)
        val all = listOf(phone, tv, rent, net).map { installmentProgress(it, paid, emptyList(), today) }

        val summary = installmentSummary(all, today)!!
        assertEquals(15_000_000L, summary.monthlyRial)
        assertEquals(2, summary.dueThisMonth)
        // One installment and two bills is no installments' sum, but the bills' line stands.
        assertNull(installmentSummary(all.filter { it.plan.id != "tv" }, today))
        // The rent whole, and what the internet's partial payment leaves of its month.
        assertEquals(80_000_000L + 2_000_000L, billsLeftThisMonth(all, today))
        assertEquals("این ماه هنوز ۸٫۲ میلیون تومان قبض مونده.", billsLeftFa(82_000_000))
        // One bill: its card says it. All paid: nothing to say.
        assertNull(billsLeftThisMonth(all.filter { it.plan.id != "net" }, today))
        val settled = links(rent, "c" to 80_000_000) + links(net, "d" to 3_000_000)
        assertNull(billsLeftThisMonth(listOf(rent, net).map { installmentProgress(it, settled, emptyList(), today) }, today))
        // Last month gone by unpaid is the card's to say, not this line's.
        val later = jalaliMonthsAfter(first, 1)
        assertEquals(83_000_000L, billsLeftThisMonth(listOf(rent, net).map { installmentProgress(it, emptyMap(), emptyList(), later) }, later))
    }

    @Test
    fun `a figure pre-filled into a Toman field saves back to the same Rial`() {
        // Pre-filled as whole Toman, an installment made from a 4,567,891 Rial payment was stored
        // at 4,567,890 and never matched the next identical payment.
        for (rial in listOf(4_567_891L, 4_567_890L, 125_000L, 7L)) {
            assertEquals(rial, tomanFieldToRial(rialToField(rial)))
        }
    }

    @Test
    fun `a Toman field is whole Rial, or nothing this app would store`() {
        assertEquals(125_000L, tomanFieldToRial("۱۲٬۵۰۰"))
        assertNull(tomanFieldToRial("0"))
        // Positive as typed, nothing once it is whole Rial: it was a manual row of 0 dropped unsaid.
        assertNull(tomanFieldToRial("0.04"))
        assertEquals(1L, tomanFieldToRial("0.05"))
        assertNull(tomanFieldToRial("abc"))
        assertNull(tomanFieldToRial(MAX_PLAUSIBLE_RIAL.toString()))
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

    @Test
    fun `a bill keeps its missing end through the database and comes back a bill`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val durable = Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()
        val derived = Room.inMemoryDatabaseBuilder(context, DerivedDb::class.java).build()
        try {
            seedBuiltins(durable)
            val now = System.currentTimeMillis()
            durable.goals().put(newInstallment("rent", "اجاره", 80_000_000, NO_END, jalaliOf(tehranDay(now)).day, now)!!)
            val rent = ledgerView(derived, durable).installments.single()
            assertTrue(rent.plan.isBill)
            assertNull(rent.count)
            assertNull(rent.totalRial)
            assertFalse(rent.done)
            assertEquals(tehranDay(now), rent.nextDue)
        } finally {
            derived.close()
            durable.close()
        }
    }
}
