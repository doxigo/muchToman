package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reports, which are the only place the ledger becomes a claim about her money — so every
 * one of them has to be arithmetic that can be checked by hand.
 */
class ReportsTest {

    private var n = 0

    private fun entry(
        day: Long,
        signed: Long?,
        category: String = "خرید",
        transfer: Boolean = false,
        duplicate: Boolean = false,
        review: Boolean = false,
    ): LedgerEntry {
        n++
        val ref = "s:%04d:0".format(n)
        return LedgerEntry(
            txn = Txn(
                ref = ref, srcHash = ref, seq = 0, at = tehranDayStart(day), day = day,
                bank = "SAMAN", accountId = "SAMAN",
                direction = signed?.let { if (it > 0) "in" else "out" },
                amountRial = signed?.let { kotlin.math.abs(it) }, signedRial = signed,
                balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
                merchant = "", merchantNorm = "", refNo = "", printedAt = "",
                channel = "unknown", unitPrinted = "none", inferred = false,
                parserVer = PARSER_VERSION,
            ),
            categoryId = "cat_x", categoryFa = category, confidence = if (review) 40 else 95,
            needsReview = review, duplicate = duplicate, transfer = transfer,
        )
    }

    private val year = 1405
    private val month = 5
    private val first = jalaliDay(year, month, 1)

    @Test
    fun `income and spending are what the month actually moved`() {
        val rows = listOf(
            entry(first, 100_000_000),
            entry(first + 1, -30_000_000),
            entry(first + 2, -20_000_000),
        )
        val r = monthReport(rows, year, month)
        assertEquals(100_000_000L, r.incomeRial)
        assertEquals(50_000_000L, r.spentRial)
        assertEquals(50_000_000L, r.netRial)
        assertEquals(0.5, r.savingsRate!!, 0.0001)
    }

    @Test
    fun `a transfer between her own accounts is neither income nor spending`() {
        // Without this, moving money between her own accounts reads as earning it and spending
        // it in the same month — the same quietly-wrong total this app exists to avoid.
        val rows = listOf(
            entry(first, 100_000_000),
            entry(first + 1, -500_000_000, transfer = true),
            entry(first + 1, 500_000_000, transfer = true),
        )
        val r = monthReport(rows, year, month)
        assertEquals(100_000_000L, r.incomeRial)
        assertEquals(0L, r.spentRial)
        assertEquals(1, r.transactions)
    }

    @Test
    fun `the later leg of a duplicate is not counted twice`() {
        val rows = listOf(
            entry(first, -5_000_000),
            entry(first, -5_000_000, duplicate = true),
        )
        assertEquals(5_000_000L, monthReport(rows, year, month).spentRial)
    }

    @Test
    fun `a month with no income has no savings rate rather than a bad one`() {
        // Zero income is not a hard month, it is no information. A rate here would be a
        // division by zero wearing a percentage sign.
        val r = monthReport(listOf(entry(first, -5_000_000)), year, month)
        assertNull(r.savingsRate)
    }

    @Test
    fun `nothing from another month leaks in`() {
        val rows = listOf(
            entry(first - 1, -9_000_000),
            entry(first, -1_000_000),
            entry(first + jalaliMonthLength(year, month), -8_000_000),
        )
        assertEquals(1_000_000L, monthReport(rows, year, month).spentRial)
    }

    @Test
    fun `the month before فروردین is اسفند of the year before`() {
        assertEquals(1404 to 12, previousMonth(1405, 1))
        assertEquals(1405 to 4, previousMonth(1405, 5))
    }

    @Test
    fun `runway uses the median day so one big repair does not shorten it`() {
        // Twenty ordinary days and one enormous one. A mean would report roughly half the
        // runway the median does, and she would plan around the wrong number.
        val rows = (0 until 20).map { entry(first + it, -1_000_000) } +
            entry(first + 20, -500_000_000)
        val days = bufferDays(rows, liquidRial = 100_000_000, today = first + 25)
        assertEquals(100, days)
    }

    @Test
    fun `too little history says nothing at all`() {
        val rows = (0 until 5).map { entry(first + it, -1_000_000) }
        assertNull(bufferDays(rows, liquidRial = 100_000_000, today = first + 10))
    }

    @Test
    fun `the automatic share is the number the app is judged on`() {
        val rows = listOf(
            entry(first, -1_000_000),
            entry(first, -1_000_000),
            entry(first, -1_000_000, review = true),
            entry(first, -1_000_000),
        )
        assertEquals(0.75, monthReport(rows, year, month).automaticShare!!, 0.0001)
    }

    @Test
    fun `every insight carries the transactions it came from`() {
        val rows = listOf(entry(first, 100_000_000), entry(first + 1, -40_000_000))
        val now = monthReport(rows, year, month)
        val insights = narrate(now, null, rows, bufferDays = 45)
        assertTrue(insights.isNotEmpty())
        // «چرا این را می‌بینم؟» has to be answerable for every line on the screen.
        for (insight in insights) {
            assertTrue("«${insight.text}» explains nothing", insight.why.isNotBlank())
        }
        val savings = insights.first { it.text.contains("درآمدت") }
        assertTrue("a claim about the month must name its transactions", savings.refs.isNotEmpty())
    }

    @Test
    fun `nothing is celebrated for being fewer toman`() {
        // Inflation makes a nominal fall meaningless. Every comparison the narrative draws has
        // to be a rate, a share, or a number of days — never an amount.
        val (py, pm) = previousMonth(year, month)
        val before = monthReport(
            listOf(entry(first - 31, 100_000_000), entry(first - 30, -90_000_000)),
            py, pm,
        )
        val now = monthReport(
            listOf(entry(first, 40_000_000), entry(first + 1, -38_000_000)),
            year, month,
        )
        // Spending fell in Toman (90m to 38m) while the savings rate got worse (10% to 5%).
        assertTrue(now.spentRial < before.spentRial)
        assertTrue(now.savingsRate!! < before.savingsRate!!)
        val text = narrate(now, before, emptyList(), null).joinToString(" ") { it.text }
        assertTrue("a worse month must not be reported as an improvement", !text.contains("بهتر"))
        assertTrue(quietWins(now, before, null).isEmpty())
    }

    @Test
    fun `spending more than came in is said plainly, not as a negative percentage`() {
        // "minus twenty-nine percent of your income remained" is arithmetic read aloud.
        // Nothing remained; she spent more than came in, and that is what the line has to say.
        val rows = listOf(entry(first, 100_000_000), entry(first + 1, -129_000_000))
        val now = monthReport(rows, year, month)
        assertTrue(now.savingsRate!! < 0)
        val text = narrate(now, null, rows, null).first { it.text.contains("درآمد") }.text
        assertTrue("«$text» should not report a negative remainder", !text.contains("−"))
        assertTrue("«$text» should not report a negative remainder", !text.contains("-"))
        assertTrue("«$text» should say she overspent", text.contains("بیشتر از درآمدت"))
    }

    @Test
    fun `a win is a real outcome, never an app-opening`() {
        val (py, pm) = previousMonth(year, month)
        val before = monthReport(listOf(entry(first - 31, -5_000_000)), py, pm)
        val now = monthReport(
            listOf(entry(first, 100_000_000), entry(first + 1, -10_000_000)),
            year, month,
        )
        val wins = quietWins(now, before, bufferDays = 45)
        assertTrue(wins.any { it.text.contains("اولین ماهی") })
        assertTrue(wins.any { it.text.contains("یک ماه خرج") })
        assertTrue(wins.all { it.tone == Insight.Tone.GOOD })
    }

    @Test
    fun `the review queue counts every open item, not a capped deck's worth`() {
        // This number is the tab badge. It used to be trimmed to twelve, which meant a real
        // backlog of sixty-seven read as twelve and never moved as she worked through it.
        val open = (1..40).map { entry(first, -it * 1_000_000L, review = true) }
        val settled = (1..5).map { entry(first, -1_000_000, review = false) }
        val excluded = listOf(
            entry(first, -999_000_000, review = true, transfer = true),
            entry(first, -998_000_000, review = true, duplicate = true),
        )
        val view = LedgerView(entries = open + settled + excluded)

        assertEquals(40, view.review.size)
        // Biggest first, so the deck opens on the ones worth her attention.
        assertEquals(40_000_000L, view.review.first().txn.amountRial)
        // A transfer and a hidden duplicate are bookkeeping; neither is a question for her.
        assertTrue(view.review.none { it.transfer || it.duplicate })
    }
}
