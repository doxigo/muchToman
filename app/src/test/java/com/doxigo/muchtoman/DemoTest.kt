package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invented ledger.
 *
 * It is only ever loaded on a dev build, so nothing here is about her money — but it is the data
 * every long-window report gets looked at against before it ships, and a demo ledger that is
 * quietly wrong sends somebody hunting for a bug in the report instead.
 */
class DemoTest {

    private val today = jalaliDay(1405, 5, 8)
    private val now = tehranDayStart(today) + 10 * 3_600_000L

    private fun entriesOf(demo: DemoLedger): List<LedgerEntry> {
        val filed = demo.filings.associate { it.ref to it.value }
        return demo.transactions.map { row ->
            val txn = manualToRow(row)
            val category = filed[txn.ref]
            LedgerEntry(
                txn = txn,
                categoryId = category ?: CAT_UNCATEGORISED,
                categoryFa = category ?: "دسته‌بندی نشده",
                confidence = if (category != null) Confidence.USER_PINNED else Confidence.NONE,
                needsReview = category == null,
                duplicate = false,
                transfer = category == CAT_TRANSFER,
            )
        }
    }

    @Test
    fun `every row is marked, so clearing them can take exactly them`() {
        val demo = demoLedger(today, now)
        assertTrue(demo.transactions.isNotEmpty())
        assertTrue(demo.transactions.all { it.id.startsWith(DEMO_PREFIX) })
        // The one string the delete query matches on. A filing that does not start with it would
        // outlive the transaction it files and go on pinning a category onto nothing.
        assertTrue(demo.filings.all { it.ref.startsWith(manualRef(DEMO_PREFIX)) })
        assertEquals(demo.transactions.size, demo.transactions.map { it.id }.distinct().size)
        assertEquals(demo.filings.size, demo.filings.map { it.ref }.distinct().size)
    }

    @Test
    fun `it fills exactly the months the year-long window needs`() {
        val demo = demoLedger(today, now)
        val here = reportMonthOf(today)
        val months = demo.transactions.map { reportMonthOf(it.day) }.distinct().sorted()

        assertEquals(DEMO_MONTHS, months.size)
        assertEquals(here, months.last())
        assertEquals(here.back(DEMO_MONTHS - 1), months.first())
        // Which is what makes «۱ سال» offerable: twelve months of window plus the twelve before
        // it would be twenty-four, but the report only needs the window itself to be full.
        val cash = buildCashFlow(entriesOf(demo), 0, today, here, ReportSpan.YEAR)
        assertEquals(ReportSpan.entries.toList(), cash.spans)
        assertEquals(12, cash.range.count)
        assertTrue(cash.period.incomeRial > 0)
    }

    @Test
    fun `the month she is standing in stops today`() {
        val demo = demoLedger(today, now)
        // Not one row dated after today: a demo that fills مرداد to its last day would put the
        // report's «تا امروز» over a finished month.
        assertTrue(demo.transactions.all { it.day <= today })
        assertTrue(demo.transactions.all { it.at <= now })
        // And it did write into today's month rather than stopping at the end of last one.
        assertTrue(demo.transactions.any { reportMonthOf(it.day) == reportMonthOf(today) })
    }

    @Test
    fun `it is the same household every time`() {
        // A demo ledger that reshuffles between runs makes every before-and-after screenshot a
        // comparison of two different households.
        assertEquals(demoLedger(today, now), demoLedger(today, now))
    }

    @Test
    fun `every month earns, spends, and mostly keeps something`() {
        val entries = entriesOf(demoLedger(today, now))
        val here = reportMonthOf(today)
        // The current month is half-written, so it is judged on nothing but having started.
        val whole = (1 until DEMO_MONTHS).map { monthReport(entries, here.back(it)) }

        assertTrue("a month with no income", whole.all { it.incomeRial > 0 })
        assertTrue("a month with no spending", whole.all { it.spentRial > 0 })
        assertTrue("a thin month: ${whole.map { it.transactions }}", whole.all { it.transactions >= 10 })
        // Neither a household that saves every month — which no report would have anything to
        // say about — nor one that never does.
        val kept = whole.count { it.netRial > 0 }
        assertTrue("only $kept of ${whole.size} months kept anything", kept >= whole.size / 2)
        assertTrue(monthReport(entries, here).transactions > 0)
    }

    @Test
    fun `prices climb, so a year-long chart has a shape`() {
        val entries = entriesOf(demoLedger(today, now))
        val here = reportMonthOf(today)
        val oldest = monthReport(entries, here.back(DEMO_MONTHS - 1))
        val newest = monthReport(entries, here.back(1))
        assertTrue("the newest month must cost more than the oldest", newest.spentRial > oldest.spentRial)
        assertTrue(newest.incomeRial > oldest.incomeRial)
    }

    @Test
    fun `there is something to file, and something to leave out`() {
        val entries = entriesOf(demoLedger(today, now))
        // A queue for the deck and the tab badge…
        val waiting = entries.filter { it.needsReview && !it.transfer }
        assertTrue("the review deck needs cards", waiting.isNotEmpty())
        assertTrue("nothing incoming is left unfiled — rule_income would file it anyway", waiting.all { (it.txn.signedRial ?: 0) < 0 })

        // …and both legs of her own money moving, which the report must count as neither side.
        val legs = entries.filter { it.transfer }
        assertTrue(legs.size >= 2)
        assertEquals(0L, legs.sumOf { it.txn.signedRial ?: 0L })
        assertTrue(spendable(entries).none { it.transfer })
    }

    @Test
    fun `no two rows can be mistaken for one`() {
        val demo = demoLedger(today, now)
        // Same account, same signed amount, same merchant, ninety seconds apart is what the
        // duplicate detector looks for — an invented pair of those would arrive on her deck as
        // a question about money that does not exist. See [findDuplicates].
        val txns = demo.transactions.map(::manualToRow)
        assertTrue(findDuplicates(txns).isEmpty())
        assertEquals(txns.size, txns.map { it.at }.distinct().size)
    }

    @Test
    fun `the asset chart it writes ends at today's real total`() {
        val history = demoHistory(now, total = 900_000_000.0)
        assertEquals(900_000_000.0, history.getValue(now / DAY_MS), 0.5)
        assertTrue(history.size <= HISTORY_KEEP_DAYS)
        // A year of it, so گزارش دارایی's «۱ سال» is answerable rather than dimmed.
        assertTrue(history.keys.min() <= now / DAY_MS - 365)
        assertTrue("an invented past must not be worth more than the present", history.values.max() <= 900_000_000.0 * 1.02)
        // Nothing to draw from nothing: an empty portfolio gets no invented chart.
        assertTrue(demoHistory(now, total = 0.0).isEmpty())
    }
}
