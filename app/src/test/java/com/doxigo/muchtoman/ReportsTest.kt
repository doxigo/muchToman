package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    private val here = ReportMonth(year, month)
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
    fun `each lens shows its own side, and only «همه» shows what is neither`() {
        val income = entry(first, 100_000_000)
        val spent = entry(first, -30_000_000)
        // Both legs of a transfer, and a message that stated a balance without moving anything.
        val legIn = entry(first, 500_000_000, transfer = true)
        val legOut = entry(first, -500_000_000, transfer = true)
        val stated = entry(first, null)
        val rows = listOf(income, spent, legIn, legOut, stated)

        assertEquals(rows, rows.filter { LedgerLens.ALL.matches(it) })
        assertEquals(listOf(income), rows.filter { LedgerLens.INCOME.matches(it) })
        assertEquals(listOf(spent), rows.filter { LedgerLens.EXPENSE.matches(it) })

        // The load-bearing one: a transfer leg is her own money moving, so it is neither income
        // nor spending on either side. Letting the incoming leg through «درآمد» would show her
        // half a million she never earned — the same wrong total the struck-through row prevents.
        for (lens in listOf(LedgerLens.INCOME, LedgerLens.EXPENSE)) {
            assertTrue(rows.filter { lens.matches(it) }.none { it.transfer })
            assertTrue(rows.filter { lens.matches(it) }.all { it.txn.signedRial != null })
        }
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

    // ─────────────────────────── the month as a value ───────────────────────────

    @Test
    fun `the month before فروردین is اسفند of the year before`() {
        assertEquals(ReportMonth(1404, 12), ReportMonth(1405, 1).previous())
        assertEquals(ReportMonth(1405, 1), ReportMonth(1404, 12).next())
        assertEquals(ReportMonth(1405, 4), here.previous())
        assertEquals(ReportMonth(1405, 6), here.next())
        // …and it sorts as it reads, across the year boundary as well as inside it.
        assertTrue(ReportMonth(1404, 12) < ReportMonth(1405, 1))
        assertTrue(ReportMonth(1405, 6) > here)
    }

    @Test
    fun `a month ends exactly where the next one starts`() {
        // The one invariant every range in the report rests on. A day of daylight between two
        // months is a transaction that belongs to neither, and اسفند is the leap year's problem.
        for (m in 1..12) {
            val it = ReportMonth(1404, m)
            assertEquals(it.next().startDay, it.endDay)
        }
        assertEquals(jalaliDay(1405, 1, 1), ReportMonth(1404, 12).endDay)
    }

    @Test
    fun `a month writes itself out with its year and no separator`() {
        assertEquals("مرداد ۱۴۰۵", here.fa)
        assertEquals("اسفند ۱۴۰۴", ReportMonth(1404, 12).fa)
    }

    // ─────────────────────────── which months she may ask about ───────────────────────────

    @Test
    fun `with nothing recorded, the only month she can report on is this one`() {
        assertEquals(listOf(here), availableReportMonths(emptyList(), first + 10))
    }

    @Test
    fun `an empty month between the first and this one is still a month`() {
        // تیر has nothing in it. Skipping it would make the selector step from خرداد to مرداد as
        // though تیر had not happened, and «هیچ تراکنشی ثبت نشده» is an answer.
        val rows = listOf(entry(jalaliDay(1405, 3, 5), -1_000_000))
        assertEquals(
            listOf(ReportMonth(1405, 3), ReportMonth(1405, 4), here),
            availableReportMonths(rows, first + 10),
        )
    }

    @Test
    fun `a transfer or a duplicate does not open a month of its own`() {
        val rows = listOf(
            entry(jalaliDay(1404, 1, 5), -5_000_000, transfer = true),
            entry(jalaliDay(1404, 2, 5), -5_000_000, duplicate = true),
            entry(jalaliDay(1405, 4, 3), -1_000_000),
        )
        assertEquals(
            listOf(ReportMonth(1405, 4), here),
            availableReportMonths(rows, first + 10),
        )
    }

    @Test
    fun `no month after this one is ever offered`() {
        // A row stamped with a later day — a phone's clock, a bank's clock, a hand-entered date —
        // must not open a month that has not happened, and must not push the list past today.
        val rows = listOf(entry(first + 40, -1_000_000))
        assertEquals(listOf(here), availableReportMonths(rows, first + 10))
    }

    @Test
    fun `the chart shows six months, in order, and never invents one`() {
        val available = (1..10).map { ReportMonth(1405, it) }
        assertEquals((5..10).map { ReportMonth(1405, it) }, chartWindow(available, ReportMonth(1405, 10)))
        // Near the start of the ledger the window slides forward rather than padding with
        // months that never existed.
        assertEquals((1..6).map { ReportMonth(1405, it) }, chartWindow(available, ReportMonth(1405, 2)))
        // A short ledger gives back only what it has, and keeps its order.
        val short = listOf(ReportMonth(1405, 4), ReportMonth(1405, 5))
        assertEquals(short, chartWindow(short, ReportMonth(1405, 5)))
        assertEquals(emptyList<ReportMonth>(), chartWindow(short, ReportMonth(1399, 1)))
    }

    // ─────────────────────────── both sides of the month ───────────────────────────

    @Test
    fun `income is categorised as well as spending`() {
        // «حقوق» and «فروش» are different answers to "where did it come from", and the report
        // used to total them into one figure while giving the other side six rows.
        val rows = listOf(
            entry(first, 90_000_000, category = "حقوق"),
            entry(first, 10_000_000, category = "فروش"),
            entry(first + 1, -30_000_000, category = "خوراکی"),
            entry(first + 2, -40_000_000, category = "اجاره"),
        )
        val r = monthReport(rows, year, month)
        assertEquals(listOf("حقوق" to 90_000_000L, "فروش" to 10_000_000L), r.incomeByCategory)
        assertEquals(listOf("اجاره" to 40_000_000L, "خوراکی" to 30_000_000L), r.spendingByCategory)
    }

    @Test
    fun `neither side of the categories counts a transfer or a duplicate`() {
        val rows = listOf(
            entry(first, 100_000_000, category = "حقوق"),
            entry(first, 500_000_000, category = "حقوق", transfer = true),
            entry(first, 100_000_000, category = "حقوق", duplicate = true),
            entry(first + 1, -20_000_000, category = "خوراکی"),
            entry(first + 1, -20_000_000, category = "خوراکی", duplicate = true),
            entry(first + 1, -500_000_000, category = "خوراکی", transfer = true),
        )
        val r = monthReport(rows, year, month)
        assertEquals(listOf("حقوق" to 100_000_000L), r.incomeByCategory)
        assertEquals(listOf("خوراکی" to 20_000_000L), r.spendingByCategory)
        assertEquals(100_000_000L, r.incomeRial)
        assertEquals(20_000_000L, r.spentRial)
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

    // ─────────────────────────── what it says out loud ───────────────────────────

    @Test
    fun `every insight carries the transactions it came from`() {
        val rows = listOf(entry(first, 100_000_000), entry(first + 1, -40_000_000))
        val now = monthReport(rows, year, month)
        val insights = narrate(now, null, rows, bufferDays = 45, current = true)
        assertTrue(insights.isNotEmpty())
        // «چرا این را می‌بینم؟» has to be answerable for every line on the screen.
        for (insight in insights) {
            assertTrue("«${insight.text}» explains nothing", insight.why.isNotBlank())
        }
        val savings = insights.first { it.text.contains("درآمدت") }
        assertTrue("a claim about the month must name its transactions", savings.refs.isNotEmpty())
    }

    @Test
    fun `a report on a past month cites that month and nothing else`() {
        // With only a lower bound — all a report on the current month ever needed — every line
        // of a تیر report would carry مرداد's transactions as its evidence.
        val july = ReportMonth(1405, 4)
        val before = entry(july.startDay - 1, -3_000_000)
        val inside = entry(july.startDay + 2, -3_000_000)
        val after = entry(july.endDay, -3_000_000)
        val earned = entry(july.startDay + 1, 20_000_000)
        val rows = listOf(before, inside, after, earned)

        val insights = narrate(monthReport(rows, july), null, rows, null, current = false)
        assertTrue(insights.isNotEmpty())
        val refs = insights.flatMap { it.refs }.toSet()
        assertTrue(refs.contains(inside.txn.ref))
        assertTrue(refs.contains(earned.txn.ref))
        assertTrue("a past month must not cite an earlier one", !refs.contains(before.txn.ref))
        assertTrue("a past month must not cite a later one", !refs.contains(after.txn.ref))
    }

    @Test
    fun `only the month she is standing in says «این ماه»`() {
        val rows = listOf(entry(first, 100_000_000), entry(first + 1, -40_000_000))
        val now = monthReport(rows, year, month)

        val today = narrate(now, null, rows, null, current = true).joinToString(" ") { it.text }
        assertTrue(today.contains("این ماه"))

        val past = narrate(now, null, rows, null, current = false).joinToString(" ") { it.text }
        assertTrue("a past month must not call itself the current one", !past.contains("این ماه"))
        assertTrue("a past month has to name itself", past.contains("مرداد ۱۴۰۵"))
    }

    @Test
    fun `nothing is celebrated for being fewer toman`() {
        // Inflation makes a nominal fall meaningless. Every comparison the narrative draws has
        // to be a rate, a share, or a number of days — never an amount.
        val before = monthReport(
            listOf(entry(first - 31, 100_000_000), entry(first - 30, -90_000_000)),
            here.previous(),
        )
        val now = monthReport(
            listOf(entry(first, 40_000_000), entry(first + 1, -38_000_000)),
            year, month,
        )
        // Spending fell in Toman (90m to 38m) while the savings rate got worse (10% to 5%).
        assertTrue(now.spentRial < before.spentRial)
        assertTrue(now.savingsRate!! < before.savingsRate!!)
        val text = narrate(now, before, emptyList(), null, current = true).joinToString(" ") { it.text }
        assertTrue("a worse month must not be reported as an improvement", !text.contains("بهتر"))
        assertTrue(quietWins(now, before, null, current = true).isEmpty())
    }

    @Test
    fun `spending more than came in is said plainly, not as a negative percentage`() {
        // "minus twenty-nine percent of your income remained" is arithmetic read aloud.
        // Nothing remained; she spent more than came in, and that is what the line has to say.
        val rows = listOf(entry(first, 100_000_000), entry(first + 1, -129_000_000))
        val now = monthReport(rows, year, month)
        assertTrue(now.savingsRate!! < 0)
        val text = narrate(now, null, rows, null, current = true).first { it.text.contains("درآمد") }.text
        assertTrue("«$text» should not report a negative remainder", !text.contains("−"))
        assertTrue("«$text» should not report a negative remainder", !text.contains("-"))
        assertTrue("«$text» should say she overspent", text.contains("بیشتر از درآمدت"))
    }

    @Test
    fun `a win is a real outcome, never an app-opening`() {
        val before = monthReport(listOf(entry(first - 31, -5_000_000)), here.previous())
        val now = monthReport(
            listOf(entry(first, 100_000_000), entry(first + 1, -10_000_000)),
            year, month,
        )
        val wins = quietWins(now, before, bufferDays = 45, current = true)
        // Not «اولین ماهی که…»: the comparison behind that line reaches exactly one month back,
        // so it was a claim about a history it had never read.
        assertTrue(wins.none { it.text.contains("اولین") })
        assertTrue(wins.any { it.text.contains("درآمد این ماه از خرجت بیشتر شد") })
        assertTrue(wins.any { it.text.contains("یک ماه خرج") })
        assertTrue(wins.all { it.tone == Insight.Tone.GOOD })

        // The same month, read as history, names itself and claims nothing about the present.
        val past = quietWins(now, before, bufferDays = null, current = false)
        assertTrue(past.none { it.text.contains("این ماه") })
        assertTrue(past.any { it.text.contains("مرداد ۱۴۰۵") })
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

    // ─────────────────────────── the report, assembled ───────────────────────────

    /** خرداد, تیر and مرداد, with a transfer and a duplicate that must not reach any figure. */
    private fun threeMonths(): List<LedgerEntry> = listOf(
        entry(jalaliDay(1405, 3, 5), 60_000_000, category = "حقوق"),
        entry(jalaliDay(1405, 3, 8), -20_000_000, category = "خوراکی"),
        entry(jalaliDay(1405, 4, 10), 50_000_000, category = "حقوق"),
        entry(jalaliDay(1405, 4, 12), -20_000_000, category = "اجاره"),
        entry(first + 1, 100_000_000, category = "حقوق"),
        entry(first + 2, -40_000_000, category = "خوراکی"),
        entry(first + 3, -500_000_000, category = "خوراکی", transfer = true),
        entry(first + 4, -40_000_000, category = "خوراکی", duplicate = true),
    )

    @Test
    fun `the chart's months are the same arithmetic as the totals under them`() {
        val cash = buildCashFlow(threeMonths(), liquidRial = 0, today = first + 20, selected = here)
        assertEquals(listOf(ReportMonth(1405, 3), ReportMonth(1405, 4), here), cash.series.map { it.month })
        val selected = cash.series.first { it.month == cash.selected }
        assertEquals(cash.period.incomeRial, selected.incomeRial)
        assertEquals(cash.period.spentRial, selected.spentRial)
        // The transfer and the duplicate are gone from the bar as well as from the figure.
        assertEquals(100_000_000L, selected.incomeRial)
        assertEquals(40_000_000L, selected.spentRial)
    }

    @Test
    fun `she can reach every month the ledger has, and no month it does not`() {
        val rows = threeMonths()
        val today = first + 20

        val now = buildCashFlow(rows, 0, today, here)
        assertTrue(now.current)
        assertTrue(now.canGoBack)
        assertTrue("there is no month after this one", !now.canGoForward)

        val oldest = buildCashFlow(rows, 0, today, ReportMonth(1405, 3))
        assertTrue(!oldest.current)
        assertTrue("خرداد is the first month the ledger has", !oldest.canGoBack)
        assertTrue(oldest.canGoForward)

        // A month restored from a state older than the ledger it was chosen against reports as
        // now, rather than as an empty month she has no way to leave.
        assertEquals(here, buildCashFlow(rows, 0, today, ReportMonth(1399, 1)).selected)
    }

    @Test
    fun `a past month is never told what today's cash would cover`() {
        // Enough ordinary days for the runway to be computable at all, so its absence on the
        // historical report is a refusal rather than a shortage of history.
        val rows = threeMonths() + (0 until 20).map { entry(first + it, -1_000_000) }
        val today = first + 25

        val now = buildCashFlow(rows, liquidRial = 100_000_000, today = today, selected = here)
        assertNotNull(now.bufferDays)
        assertTrue(now.insights.any { it.text.contains("روز می‌رسه") })

        val past = buildCashFlow(rows, liquidRial = 100_000_000, today = today, selected = ReportMonth(1405, 4))
        assertTrue(!past.current)
        assertNull("today's balance is not تیر's runway", past.bufferDays)
        assertTrue(past.insights.isNotEmpty())
        assertTrue(past.insights.none { it.text.contains("روز می‌رسه") })
        assertTrue(past.insights.none { it.text.contains("این ماه") })
        assertTrue(past.wins.none { it.text.contains("این ماه") })
        assertTrue(past.insights.any { it.text.contains("تیر ۱۴۰۵") })
        // Every figure it does state is تیر's own.
        assertEquals(50_000_000L, past.period.incomeRial)
        assertEquals(20_000_000L, past.period.spentRial)
    }

    @Test
    fun `the queue of open items is asked about now and not in a past month`() {
        val rows = threeMonths() + entry(first + 6, -3_000_000, review = true)
        val today = first + 20
        assertTrue(
            buildCashFlow(rows, 0, today, here).insights
                .any { it.tone == Insight.Tone.ATTENTION },
        )
        // On a past report it would both cite a transaction from outside the month and ask her
        // to act on a screen she is reading as history.
        assertTrue(
            buildCashFlow(rows, 0, today, ReportMonth(1405, 4)).insights
                .none { it.tone == Insight.Tone.ATTENTION },
        )
    }

    // ─────────────────────────── longer windows ───────────────────────────

    /** A year and a month of months, each one earning and spending a little more than the last. */
    private fun aYear(): List<LedgerEntry> {
        val rows = mutableListOf<LedgerEntry>()
        for (back in 0..12) {
            val m = here.back(back)
            rows += entry(m.startDay + 1, 10_000_000L * (13 - back), category = "حقوق")
            rows += entry(m.startDay + 2, -4_000_000L * (13 - back), category = "خوراکی")
        }
        return rows
    }

    @Test
    fun `a window of months is the months added up, and nothing from outside them`() {
        val rows = aYear()
        val quarter = buildCashFlow(rows, 0, first + 20, here, ReportSpan.QUARTER)

        assertEquals(ReportRange(here.back(2), here), quarter.range)
        assertEquals(3, quarter.range.count)
        // مرداد, تیر and خرداد: 130 + 120 + 110 million in, and 40% of each back out.
        assertEquals(360_000_000L, quarter.period.incomeRial)
        assertEquals(144_000_000L, quarter.period.spentRial)
        // The same three months read one at a time have to add up to exactly that.
        val byMonth = (0..2).map { monthReport(rows, here.back(it)) }
        assertEquals(quarter.period.incomeRial, byMonth.sumOf { it.incomeRial })
        assertEquals(quarter.period.spentRial, byMonth.sumOf { it.spentRial })
        // And the category split is the window's own, not an average of three months' splits.
        assertEquals(listOf("خوراکی" to 144_000_000L), quarter.period.spendingByCategory)
        assertEquals(listOf("حقوق" to 360_000_000L), quarter.period.incomeByCategory)
    }

    @Test
    fun `a window is compared with the whole window before it`() {
        val rows = aYear()
        val quarter = buildCashFlow(rows, 0, first + 20, here, ReportSpan.QUARTER)
        // اردیبهشت, فروردین and اسفند — three months, not one, and none of them in the report.
        assertEquals(ReportRange(here.back(5), here.back(3)), quarter.previous.range)
        assertEquals(270_000_000L, quarter.previous.incomeRial)
        assertTrue(quarter.range.months.none { it in quarter.previous.range })
    }

    @Test
    fun `a length the ledger cannot fill is not offered, and not silently shortened`() {
        // Four months of ledger. «۶ ماه» over it would be a four-month report wearing a
        // six-month label, and its comparison would be against months that never existed.
        val rows = (0..3).map { entry(here.back(it).startDay + 1, -1_000_000) }
        val cash = buildCashFlow(rows, 0, first + 20, here, ReportSpan.HALF)

        assertEquals(listOf(ReportSpan.MONTH, ReportSpan.QUARTER), cash.spans)
        // Asked for six, given the longest it can actually answer rather than a mislabelled four.
        assertEquals(ReportSpan.QUARTER, cash.span)
        assertEquals(3, cash.range.count)

        val year = buildCashFlow(aYear(), 0, first + 20, here, ReportSpan.YEAR)
        assertEquals(ReportSpan.entries.toList(), year.spans)
        assertEquals(12, year.range.count)
    }

    @Test
    fun `a window at the start of the ledger is not compared with one nobody watched`() {
        // Exactly six months of ledger, read as two quarters: the older quarter is real, so the
        // comparison stands. Step the window one month back and half of *its* previous quarter
        // is before the ledger began — comparing against that is comparing against nothing.
        val rows = (0..5).map { entry(here.back(it).startDay + 1, -1_000_000) }
        val full = buildCashFlow(rows, 0, first + 20, here, ReportSpan.QUARTER)
        assertTrue(full.previous.transactions > 0)

        val stepped = buildCashFlow(rows, 0, first + 20, here.previous(), ReportSpan.QUARTER)
        assertEquals(3, stepped.range.count)
        // It still reports its own three months in full; it just declines the comparison.
        assertEquals(3, stepped.period.transactions)
        assertTrue(stepped.insights.none { it.text.contains("دورهٔ قبل") })
    }

    @Test
    fun `a window says its own name and never calls three months «این ماه»`() {
        val rows = aYear()
        val quarter = buildCashFlow(rows, 0, first + 20, here, ReportSpan.QUARTER)
        assertEquals("۳ ماه گذشته", quarter.range.recentFa)
        assertEquals("خرداد تا مرداد ۱۴۰۵", quarter.range.fa)

        val text = (quarter.insights + quarter.wins).joinToString(" ") { it.text }
        assertTrue(text.isNotBlank())
        assertTrue("three months are not «این ماه»", !text.contains("این ماه"))
        assertTrue(text.contains("۳ ماه گذشته"))
        // The comparison names a window, not a month, because that is what it reached back to.
        assertTrue(text.contains("دورهٔ قبل"))
        assertTrue("a window is never compared with «ماه قبل»", !text.contains("ماه قبل "))

        // A past window names its months at both ends, and one that crosses new year says both.
        val past = buildCashFlow(rows, 0, first + 20, ReportMonth(1405, 2), ReportSpan.QUARTER)
        assertEquals("اسفند ۱۴۰۴ تا اردیبهشت ۱۴۰۵", past.range.fa)
        assertTrue(past.insights.joinToString(" ") { it.text }.contains("اسفند ۱۴۰۴ تا اردیبهشت ۱۴۰۵"))
    }

    @Test
    fun `a single month is exactly the report it always was`() {
        // The whole point of the span defaulting to one month: nothing about the existing report
        // moved. Same figures, same six bars, same words.
        val rows = threeMonths()
        val cash = buildCashFlow(rows, 0, first + 20, here)

        assertEquals(ReportSpan.MONTH, cash.span)
        assertEquals(ReportRange(here, here), cash.range)
        assertEquals(monthReport(rows, here), cash.period)
        assertEquals(monthReport(rows, here.previous()), cash.previous)
        // One month still reaches back exactly one month, and still calls it «ماه قبل».
        val text = cash.insights.joinToString(" ") { it.text }
        assertTrue(text.contains("ماه قبل"))
        assertTrue(!text.contains("دورهٔ قبل"))
    }

    @Test
    fun `the chart keeps six months of context under a shorter window, and grows for a longer one`() {
        val rows = aYear()
        // Three months read against the six around them: the bars are still context, and the
        // window is a stretch of them rather than the whole chart.
        val quarter = buildCashFlow(rows, 0, first + 20, here, ReportSpan.QUARTER)
        assertEquals(6, quarter.series.size)
        assertEquals(3, quarter.series.count { it.month in quarter.range })
        assertEquals(here, quarter.series.last().month)

        val year = buildCashFlow(rows, 0, first + 20, here, ReportSpan.YEAR)
        assertEquals(12, year.series.size)
        assertTrue(year.series.all { it.month in year.range })
        // Every bar is one month of the window, so they add up to the figures above them.
        assertEquals(year.period.incomeRial, year.series.sumOf { it.incomeRial })
        assertEquals(year.period.spentRial, year.series.sumOf { it.spentRial })
    }

    @Test
    fun `stepping the window moves it one month, not one window`() {
        val rows = aYear()
        val year = buildCashFlow(rows, 0, first + 20, here.previous(), ReportSpan.YEAR)
        assertEquals(ReportRange(here.back(12), here.previous()), year.range)
        assertTrue(!year.current)
        assertTrue(year.canGoForward)
    }

    @Test
    fun `a range cannot run backwards`() {
        var refused = false
        runCatching { ReportRange(here, here.previous()) }.onFailure { refused = true }
        assertTrue("a window ending before it starts is not a window", refused)
    }

    @Test
    fun `the home story is still the month she is standing in`() {
        val rows = threeMonths()
        val story = buildStory(rows, liquidRial = 0, today = first + 20)
        assertEquals(here, story.month.month)
        assertEquals(100_000_000L, story.month.incomeRial)
        assertEquals(40_000_000L, story.month.spentRial)
        assertEquals(60_000_000L, story.month.netRial)
        assertEquals(ReportMonth(1405, 4), story.previous.month)
        // The home screen is always the present tense, so nothing on it names the month it is
        // describing — that is what «این ماه» means and what a dated line would contradict.
        assertTrue(story.insights.isNotEmpty())
        assertTrue(story.insights.none { it.text.contains("مرداد ۱۴۰۵") })
    }
}
