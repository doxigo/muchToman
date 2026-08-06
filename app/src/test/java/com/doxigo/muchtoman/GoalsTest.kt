package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Goals and «آیا ارزشش را داشت؟» — the entire reward system, and all of it arithmetic. */
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
                parserVer = PARSER_VERSION,
            ),
            categoryId = categoryId, categoryFa = "خرید", confidence = 95,
            needsReview = review, duplicate = false, transfer = false,
        )
    }

    private fun goal(target: Long, kind: String = GoalKind.SAVE, category: String? = null) = Goal(
        id = "g1", nameFa = "سفر", targetRial = target, kind = kind, categoryId = category,
        period = GoalPeriod.ONCE, startsOn = first, createdAt = 0, updatedAt = 0,
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
    fun `a cap is met by not reaching it`() {
        // Easy to get backwards, and getting it backwards congratulates her for overspending.
        val rows = listOf(entry(first, -30_000_000, categoryId = "cat_dining"))
        val under = goalProgress(goal(50_000_000, GoalKind.CAP, "cat_dining"), rows, first + 5)
        assertEquals(30_000_000L, under.currentRial)
        assertTrue("under the cap is met", under.done)

        val over = goalProgress(goal(20_000_000, GoalKind.CAP, "cat_dining"), rows, first + 5)
        assertTrue("over the cap is not", !over.done)
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
