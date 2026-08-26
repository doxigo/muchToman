package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Savings goals and «آیا ارزشش را داشت؟».
 *
 * The other half of the reward system — the caps — is `BudgetTest`, which is where the cap
 * arithmetic went when it stopped sharing a function with this.
 */
class GoalsTest {

    private var n = 0
    private val year = 1405
    private val month = 5
    private val first = jalaliDay(year, month, 1)

    private fun entry(
        day: Long,
        signed: Long,
        categoryId: String = "cat_shopping",
        review: Boolean = false,
        merchant: String = "جایی",
        owner: String = "",
    ): LedgerEntry {
        n++
        val ref = "s:%04d:0".format(n)
        return LedgerEntry(
            txn = Txn(
                ref = ref, srcHash = ref, seq = 0, at = tehranDayStart(day), day = day,
                bank = "SAMAN", accountId = "SAMAN",
                direction = if (signed > 0) "in" else "out",
                amountRial = kotlin.math.abs(signed), signedRial = signed,
                balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
                merchant = merchant, merchantNorm = merchant, refNo = "", printedAt = "",
                channel = "unknown", unitPrinted = "none", inferred = false,
                parserVer = PARSER_VERSION, ownerMemberId = owner,
            ),
            categoryId = categoryId, categoryFa = "خرید", confidence = 95,
            needsReview = review, duplicate = false, transfer = false,
        )
    }

    private fun goal(target: Long, endsOn: Long? = null) = Goal(
        id = "g1", nameFa = "سفر", targetRial = target, kind = GoalKind.SAVE,
        period = GoalPeriod.ONCE, startsOn = first, endsOn = endsOn, createdAt = 0, updatedAt = 0,
    )

    @Test
    fun `a savings goal counts what was kept, not what came in`() {
        val rows = listOf(entry(first, 100_000_000), entry(first + 1, -40_000_000))
        val p = goalProgress(goal(60_000_000), rows, first + 5)
        assertEquals(60_000_000L, p.currentRial)
        assertTrue(p.done)
        assertEquals(1f, p.share, 0.001f)
    }

    @Test
    fun `a transfer between her own accounts moves no goal`() {
        val rows = listOf(
            entry(first, 100_000_000),
            entry(first + 1, -500_000_000).copy(transfer = true),
        )
        assertEquals(100_000_000L, goalProgress(goal(50_000_000), rows, first + 5).currentRial)
    }

    @Test
    fun `a goal under water shows nothing set aside, never a negative figure`() {
        // "−45 million of 50 million" is arithmetic read aloud, and it lands as a scolding.
        // Nothing was set aside; that is what the screen has to say.
        val rows = listOf(entry(first, 10_000_000), entry(first + 1, -55_000_000))
        val p = goalProgress(goal(50_000_000), rows, first + 5)
        assertTrue(p.underWater)
        assertEquals(0L, p.currentRial)
        assertEquals(0f, p.share, 0.001f)
        assertTrue(!p.done)
    }

    @Test
    fun `progress never runs past its own bar`() {
        val rows = listOf(entry(first, 900_000_000))
        assertEquals(1f, goalProgress(goal(10_000_000), rows, first + 5).share, 0.001f)
    }

    // ─────────────────────── the deadline, and the rate it implies ───────────────────────

    @Test
    fun `a goal with no deadline says nothing about days or a monthly rate`() {
        val p = goalProgress(goal(50_000_000), listOf(entry(first, 10_000_000)), first + 5)
        assertNull(p.daysLeft)
        assertNull(p.perMonthRial)
        assertTrue(!p.expired)
    }

    @Test
    fun `the deadline day still counts as a day she can save in`() {
        // A zero on the deadline itself would read as "the time is up" on a day it is not.
        val ends = first + 30
        assertEquals(1, goalProgress(goal(50_000_000, ends), emptyList(), ends).daysLeft)
        assertEquals(0, goalProgress(goal(50_000_000, ends), emptyList(), ends + 1).daysLeft)
    }

    @Test
    fun `the monthly rate is what lands on the target, rounded up`() {
        // 40 million over three months is 13.34 a month, and flooring it lands short in the one
        // month where landing short is the whole outcome.
        val rows = listOf(entry(first, 10_000_000))
        val p = goalProgress(goal(50_000_000, first + 89), rows, first)
        assertEquals(40_000_000L, p.remainingRial)
        assertEquals(3, p.daysLeft!! / 30)
        assertEquals(13_333_334L, p.perMonthRial)
        assertTrue(p.perMonthRial!! * 3 >= p.remainingRial)
    }

    @Test
    fun `under a month left, there is no monthly rate to quote`() {
        // A per-month figure over eleven days is a number with no month to spend it in.
        val p = goalProgress(goal(50_000_000, first + 10), listOf(entry(first, 10_000_000)), first)
        assertEquals(11, p.daysLeft)
        assertNull(p.perMonthRial)
    }

    @Test
    fun `a met goal is never asked to keep saving, and never expires`() {
        val rows = listOf(entry(first, 60_000_000))
        val p = goalProgress(goal(50_000_000, first + 1), rows, first + 40)
        assertTrue(p.done)
        assertEquals(0L, p.remainingRial)
        assertNull(p.perMonthRial)
        assertTrue("a goal she reached did not expire", !p.expired)
    }

    @Test
    fun `a deadline that passed unmet says so, and says what was left`() {
        val rows = listOf(entry(first, 10_000_000))
        val p = goalProgress(goal(50_000_000, first + 10), rows, first + 40)
        assertTrue(p.expired)
        assertEquals(40_000_000L, p.remainingRial)
    }

    @Test
    fun `a horizon lands on the last day of its own month`() {
        // «۳ ماه» from مرداد is the end of آبان — the whole of the third month, not three days of it.
        val late = jalaliDay(1405, 5, 28)
        val end = GoalHorizon.QUARTER.endsOn(late)!!
        assertEquals(JalaliDate(1405, 8, 30), jalaliOf(end))
        assertEquals(JalaliDate(1405, 9, 1), jalaliOf(end + 1))
        assertNull(GoalHorizon.OPEN.endsOn(late))
    }

    @Test
    fun `she is asked about at most two purchases a week, largest first`() {
        val rows = (1..6).map { entry(first + 1, -(it * 10_000_000L)) }
        val asked = worthItCandidates(rows, emptySet(), first + 1, threshold = 10_000_000)
        assertEquals(WORTH_IT_PER_WEEK, asked.size)
        assertEquals(60_000_000L, asked.first().txn.amountRial)
    }

    @Test
    fun `bills, fees, cash and transfers are never asked about`() {
        // "Was it worth it" is not a question about the electricity bill, and asking it would
        // read as the app being smug.
        val rows = listOf(
            entry(first, -90_000_000, categoryId = "cat_bills"),
            entry(first, -90_000_000, categoryId = CAT_FEES),
            entry(first, -90_000_000, categoryId = CAT_CASH),
            entry(first, -90_000_000, categoryId = CAT_TRANSFER),
        )
        assertTrue(worthItCandidates(rows, emptySet(), first, threshold = 1).isEmpty())
    }

    @Test
    fun `nothing unconfirmed is asked about, and nothing is asked twice`() {
        val pending = entry(first, -90_000_000, review = true)
        assertTrue(worthItCandidates(listOf(pending), emptySet(), first, 1).isEmpty())

        val settled = entry(first, -90_000_000)
        assertEquals(1, worthItCandidates(listOf(settled), emptySet(), first, 1).size)
        assertTrue(worthItCandidates(listOf(settled), setOf(settled.txn.ref), first, 1).isEmpty())
    }

    @Test
    fun `only the person who spent it is asked about it`() {
        // A household ledger holds her partner's rows too, and both phones were being handed the
        // same two purchases — the one who did not make them being asked about a card that is
        // not hers.
        val hers = entry(first, -90_000_000)
        val his = entry(first, -95_000_000, owner = "m_partner")
        val asked = worthItCandidates(listOf(hers, his), emptySet(), first, 1)
        assertEquals(listOf(hers.txn.ref), asked.map { it.txn.ref })
    }

    @Test
    fun `income is never asked about`() {
        val rows = listOf(entry(first, 90_000_000, categoryId = "cat_shopping"))
        assertTrue(worthItCandidates(rows, emptySet(), first, 1).isEmpty())
    }

    @Test
    fun `her verdicts add up to something she can act on`() {
        val a = entry(first, -50_000_000)
        val b = entry(first, -30_000_000)
        val c = entry(first, -20_000_000)
        val summary = worthItSummary(
            listOf(a, b, c),
            mapOf(
                a.txn.ref to WorthIt.YES,
                b.txn.ref to WorthIt.NO,
                c.txn.ref to WorthIt.NEEDED,
            ),
        )
        assertEquals(50_000_000L, summary.worth)
        assertEquals(30_000_000L, summary.regretted)
        assertEquals(20_000_000L, summary.needed)
        assertEquals(100_000_000L, summary.total)
    }

    @Test
    fun `a quiet month does not start asking about bus fares`() {
        // With too little history the threshold stays at its floor rather than collapsing onto
        // whatever small purchases happen to exist.
        val few = (1..5).map { entry(first, -100_000L) }
        assertEquals(20_000_000L, largeSpendThreshold(few))
    }
}
