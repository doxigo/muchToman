package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Budgets: the window, the thresholds, and the promise that she is told once.
 *
 * Every figure a budget shows and every word it says is a pure function of the ledger and a day, so
 * all of it is checked here rather than on a phone. The three things that would be silently wrong
 * on a phone and are checked hardest: which days a window holds, which side of a threshold a figure
 * lands on, and whether a crossing that has already been announced announces itself again.
 */
class BudgetTest {

    private var n = 0
    private val year = 1405
    private val month = 5
    private val first = jalaliDay(year, month, 1)
    private val dining = "cat_dining"

    private fun entry(
        day: Long,
        signed: Long,
        categoryId: String = dining,
        transfer: Boolean = false,
        duplicate: Boolean = false,
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
                merchant = "جایی", merchantNorm = "جایی", refNo = "", printedAt = "",
                channel = "unknown", unitPrinted = "none", inferred = false,
                parserVer = PARSER_VERSION,
            ),
            categoryId = categoryId, categoryFa = "رستوران و کافه", confidence = 95,
            needsReview = false, duplicate = duplicate, transfer = transfer,
        )
    }

    private fun budget(
        cap: Long,
        period: BudgetPeriod = BudgetPeriod.MONTH,
        category: String? = dining,
        startsOn: Long = first,
        id: String = "b1",
    ) = Goal(
        id = id, nameFa = "رستوران و کافه", targetRial = cap, kind = GoalKind.CAP,
        categoryId = category, period = period.id, startsOn = startsOn,
        createdAt = 0, updatedAt = 0,
    )

    // ─────────────────────────── the window ───────────────────────────

    @Test
    fun `a monthly window is the Jalali month, closed-open`() {
        val w = budgetWindow(BudgetPeriod.MONTH, first + 10)
        assertEquals(first, w.startDay)
        assertEquals(31, w.days) // مرداد
        assertTrue(first in w)
        assertTrue(first + 30 in w)
        // The boundary belongs to the next window and to no other, which is the only arrangement
        // in which a transaction cannot land in both months or in neither.
        assertTrue(first + 31 !in w)
        assertEquals(first + 31, w.endDay)
    }

    @Test
    fun `a weekly window runs Saturday to Friday`() {
        val w = budgetWindow(BudgetPeriod.WEEK, first + 3)
        assertEquals(7, w.days)
        assertEquals(weekStart(first + 3), w.startDay)
        assertEquals(2L, w.startDay % 7) // epoch day 0 was a Thursday, so Saturday is 2 mod 7
    }

    @Test
    fun `a quarter is a season, and زمستان rolls the year`() {
        // تابستان: تیر, مرداد, شهریور — 31 + 31 + 31.
        val summer = budgetWindow(BudgetPeriod.QUARTER, first)
        assertEquals(jalaliDay(1405, 4, 1), summer.startDay)
        assertEquals(jalaliDay(1405, 7, 1), summer.endDay)
        assertEquals(93, summer.days)
        assertEquals("تابستان", summer.fa)

        // زمستان: دی, بهمن, اسفند — and the day after اسفند is فروردین of the next year, never a
        // thirteenth month.
        val winter = budgetWindow(BudgetPeriod.QUARTER, jalaliDay(1405, 12, 20))
        assertEquals(jalaliDay(1405, 10, 1), winter.startDay)
        assertEquals(jalaliDay(1406, 1, 1), winter.endDay)
        assertEquals("زمستان", winter.fa)
        // 1404 is the leap year in this run, so 1405's اسفند is 29 days: 30 + 30 + 29.
        assertEquals(29 + 30 + 30, winter.days)
    }

    @Test
    fun `a leap اسفند is counted from the leap table, not assumed`() {
        val leap = budgetWindow(BudgetPeriod.MONTH, jalaliDay(1403, 12, 5))
        assertEquals(30, leap.days)
        val plain = budgetWindow(BudgetPeriod.MONTH, jalaliDay(1405, 12, 5))
        assertEquals(29, plain.days)
    }

    @Test
    fun `days left counts today, and days gone starts at one`() {
        val w = budgetWindow(BudgetPeriod.MONTH, first)
        assertEquals(31, w.daysLeft(first))
        assertEquals(1, w.daysGone(first))
        // The last day of the month has one day left, not none: she can still spend it.
        assertEquals(1, w.daysLeft(first + 30))
        assertEquals(31, w.daysGone(first + 30))
        assertEquals(0, w.daysLeft(first + 31))
    }

    @Test
    fun `an unknown period reads as a month rather than throwing`() {
        // A row written by a later build, on a database restored onto this one. It must still draw
        // as something she can delete.
        assertEquals(BudgetPeriod.MONTH, BudgetPeriod.of("jdecade"))
        assertEquals(BudgetPeriod.QUARTER, BudgetPeriod.of(GoalPeriod.QUARTER))
    }

    // ─────────────────────────── what it counts ───────────────────────────

    @Test
    fun `a budget counts its own category, inside its own window`() {
        val rows = listOf(
            entry(first + 1, -10_000_000),
            entry(first + 2, -5_000_000, categoryId = "cat_groceries"), // another category
            entry(first - 1, -90_000_000), // last month
            entry(first + 31, -90_000_000), // next month
        )
        val p = budgetProgress(budget(50_000_000), rows, first + 5)
        assertEquals(10_000_000L, p.spentRial)
        assertEquals(40_000_000L, p.leftRial)
        assertEquals(0L, p.overRial)
    }

    @Test
    fun `transfers and hidden duplicates are not spending here either`() {
        val rows = listOf(
            entry(first + 1, -10_000_000),
            entry(first + 1, -80_000_000, transfer = true),
            entry(first + 1, -80_000_000, duplicate = true),
        )
        assertEquals(10_000_000L, budgetProgress(budget(50_000_000), rows, first + 5).spentRial)
    }

    @Test
    fun `money arriving under a spending category does not refund the budget`() {
        // The report puts a positive row under `incomeByCategory`, so netting it off here would
        // give «رستوران و کافه» one figure on this card and another in دخل و خرج.
        val rows = listOf(entry(first + 1, -10_000_000), entry(first + 2, 4_000_000))
        assertEquals(10_000_000L, budgetProgress(budget(50_000_000), rows, first + 5).spentRial)
    }

    @Test
    fun `the window is the whole month even when the budget was set part way through`() {
        // Anything else would disagree with the report about what the category cost this month.
        val rows = listOf(entry(first + 1, -30_000_000))
        val p = budgetProgress(budget(50_000_000, startsOn = first + 20), rows, first + 25)
        assertEquals(30_000_000L, p.spentRial)
        assertTrue("the card owes her the sentence that says so", p.partWindow)

        val fromTheStart = budgetProgress(budget(50_000_000, startsOn = first), rows, first + 25)
        assertTrue(!fromTheStart.partWindow)
    }

    // ─────────────────────────── the thresholds ───────────────────────────

    @Test
    fun `the thresholds land exactly where they say they do`() {
        val cap = 50_000_000L
        assertEquals(BudgetLevel.OK, budgetLevel(0L, cap))
        assertEquals(BudgetLevel.OK, budgetLevel(39_999_999L, cap))
        // Exactly 80%: on the threshold is over it, so the figure she can work out herself is the
        // figure that triggers it.
        assertEquals(BudgetLevel.NEAR, budgetLevel(40_000_000L, cap))
        assertEquals(BudgetLevel.NEAR, budgetLevel(47_499_999L, cap))
        assertEquals(BudgetLevel.CLOSE, budgetLevel(47_500_000L, cap))
        // Landing exactly on the cap is the budget being met, not overspent — the same reading a
        // cap has always had. Only the next Rial is over.
        assertEquals(BudgetLevel.CLOSE, budgetLevel(cap, cap))
        assertEquals(BudgetLevel.OVER, budgetLevel(cap + 1, cap))
    }

    @Test
    fun `a cap of nothing is over the moment anything is spent`() {
        // Not reachable through the sheet, which refuses a non-positive figure, but a restored row
        // must not divide by it.
        assertEquals(BudgetLevel.OK, budgetLevel(0L, 0L))
        assertEquals(BudgetLevel.OVER, budgetLevel(1L, 0L))
        val p = budgetProgress(budget(0L), listOf(entry(first + 1, -1_000_000)), first + 5)
        assertEquals(100, p.percent)
        assertEquals(1f, p.share, 0.001f)
    }

    @Test
    fun `being over is said in words and a percent, never by a bar past its own track`() {
        val rows = listOf(entry(first + 1, -65_000_000))
        val p = budgetProgress(budget(50_000_000), rows, first + 5)
        assertTrue(p.over)
        assertEquals(15_000_000L, p.overRial)
        assertEquals(0L, p.leftRial)
        assertEquals(130, p.percent)      // unclamped: this is the number she needs
        assertEquals(1f, p.share, 0.001f) // clamped: a bar cannot overflow
        assertNull(p.perDayRial)
    }

    // ─────────────────────────── pace ───────────────────────────

    @Test
    fun `the same share is outpacing early in the month and not late in it`() {
        // The one comparison that turns a budget from a scoreboard into something she can act on.
        val rows = listOf(entry(first, -35_000_000)) // 70% of the cap
        assertTrue(budgetProgress(budget(50_000_000), rows, first + 4).outpacing)
        assertTrue(!budgetProgress(budget(50_000_000), rows, first + 25).outpacing)
    }

    @Test
    fun `what is left per remaining day is what is left over the days that are left`() {
        val rows = listOf(entry(first, -20_000_000))
        val p = budgetProgress(budget(50_000_000), rows, first + 20)
        assertEquals(11, p.daysLeft)
        assertEquals(30_000_000L / 11, p.perDayRial)
    }

    @Test
    fun `a spent-out budget has no daily allowance to offer`() {
        val rows = listOf(entry(first, -50_000_000))
        val p = budgetProgress(budget(50_000_000), rows, first + 5)
        assertEquals(0L, p.leftRial)
        assertEquals(0L, p.overRial)
        assertNull(p.perDayRial)
        assertEquals(BudgetLevel.CLOSE, p.level)
    }

    // ─────────────────────────── the ordering ───────────────────────────

    @Test
    fun `budgets come back with the one closest to trouble first`() {
        val rows = listOf(
            entry(first, -45_000_000, categoryId = dining),
            entry(first, -10_000_000, categoryId = "cat_groceries"),
        )
        val goals = listOf(
            budget(50_000_000, category = "cat_groceries", id = "b_groceries"),
            budget(50_000_000, category = dining, id = "b_dining"),
        )
        val out = budgetsOf(goals, rows, first + 5)
        assertEquals(listOf("b_dining", "b_groceries"), out.map { it.goal.id })
    }

    @Test
    fun `a savings goal is not a budget, whichever way the table is read`() {
        val save = Goal(
            id = "g1", nameFa = "سفر", targetRial = 50_000_000, kind = GoalKind.SAVE,
            period = GoalPeriod.ONCE, startsOn = first, createdAt = 0, updatedAt = 0,
        )
        assertTrue(budgetsOf(listOf(save), emptyList(), first).isEmpty())
    }

    @Test
    fun `the live category name wins over the one snapshotted on the row`() {
        val out = budgetsOf(
            listOf(budget(50_000_000)),
            emptyList(),
            first,
            names = mapOf(dining to "رستوران"),
        )
        assertEquals("رستوران", out.single().categoryFa)
        // …and the snapshot is the fallback, for a category she has since archived.
        assertEquals("رستوران و کافه", budgetsOf(listOf(budget(50_000_000)), emptyList(), first).single().categoryFa)
    }

    // ─────────────────────────── saying it once ───────────────────────────

    private fun progress(spentRial: Long, cap: Long = 50_000_000L, day: Long = first + 5) =
        budgetProgress(budget(cap), listOf(entry(first, -spentRial)), day)

    @Test
    fun `a crossing is announced once, and the mark is what stops the second time`() {
        val at80 = progress(41_000_000)
        val firstPass = budgetNews(listOf(at80), emptyList())
        assertEquals(listOf("b1"), firstPass.alerts.map { it.goal.id })
        assertEquals(listOf(BudgetMark("b1", first, BudgetLevel.NEAR)), firstPass.marks)

        // Same figure, marks in hand: nothing new to say.
        val again = budgetNews(listOf(progress(42_000_000)), firstPass.marks)
        assertTrue(again.alerts.isEmpty())
        assertEquals(firstPass.marks, again.marks)
    }

    @Test
    fun `each higher line is its own announcement`() {
        var marks = budgetNews(listOf(progress(41_000_000)), emptyList()).marks
        val close = budgetNews(listOf(progress(48_000_000)), marks)
        assertEquals(1, close.alerts.size)
        assertEquals(BudgetLevel.CLOSE, close.alerts.single().level)
        marks = close.marks

        val over = budgetNews(listOf(progress(60_000_000)), marks)
        assertEquals(1, over.alerts.size)
        assertEquals(BudgetLevel.OVER, over.alerts.single().level)
        // And nothing above OVER, so a bigger overspend in the same window stays quiet.
        assertTrue(budgetNews(listOf(progress(90_000_000)), over.marks).alerts.isEmpty())
    }

    @Test
    fun `a level that falls back does not re-announce when it climbs again`() {
        // She refiled a receipt and the figure dropped under 80%. She was told about this month
        // already, and this month is one conversation.
        val marks = budgetNews(listOf(progress(41_000_000)), emptyList()).marks
        val dropped = budgetNews(listOf(progress(20_000_000)), marks)
        assertTrue(dropped.alerts.isEmpty())
        assertEquals(BudgetLevel.NEAR, dropped.marks.single().level) // high-water, never lowered
        assertTrue(budgetNews(listOf(progress(41_000_000)), dropped.marks).alerts.isEmpty())
    }

    @Test
    fun `a new window is a new conversation, and the old mark is dropped`() {
        val marks = budgetNews(listOf(progress(41_000_000)), emptyList()).marks
        // Next month, same budget, same share of it gone.
        val next = jalaliDay(year, month + 1, 1)
        val later = budgetProgress(
            budget(50_000_000),
            listOf(entry(next, -41_000_000)),
            next + 5,
        )
        val news = budgetNews(listOf(later), marks)
        assertEquals(1, news.alerts.size)
        // Nothing remembers مرداد any more: the pruning is a side effect of only ever writing a
        // mark for the current window.
        assertEquals(listOf(BudgetMark("b1", next, BudgetLevel.NEAR)), news.marks)
    }

    @Test
    fun `a deleted budget leaves no mark behind`() {
        val marks = budgetNews(listOf(progress(41_000_000)), emptyList()).marks
        assertTrue(budgetNews(emptyList(), marks).marks.isEmpty())
    }

    @Test
    fun `nothing under the first threshold is worth interrupting her for`() {
        val news = budgetNews(listOf(progress(10_000_000)), emptyList())
        assertTrue(news.alerts.isEmpty())
        // A mark is still written, at OK, which is what the pruning walks.
        assertEquals(listOf(BudgetMark("b1", first, BudgetLevel.OK)), news.marks)
    }

    // ─────────────────────────── the words ───────────────────────────

    @Test
    fun `the alert quotes the real share, not the threshold it crossed`() {
        val title = budgetAlertTitle(progress(41_500_000))
        assertTrue("said $title", title.contains("۸۳٪"))
        assertTrue(title.contains("رستوران و کافه"))
        assertTrue(title.contains("مرداد"))
    }

    @Test
    fun `being over is stated as a fact about her money, never as a verdict`() {
        val over = progress(65_000_000)
        assertEquals("رستوران و کافه: از بودجهٔ مرداد گذشتی", budgetAlertTitle(over))
        assertTrue(budgetAlertBody(over).startsWith("۱٫۵ میلیون بیشتر از ۵ میلیون تومان"))
        // No word anywhere in this app tells her she failed.
        for (text in listOf(budgetAlertTitle(over), budgetAlertBody(over), budgetNoteFa(over))) {
            assertTrue(text, !text.contains("اشتباه") && !text.contains("نتونستی"))
        }
    }

    @Test
    fun `the body carries both what is left and how long is left`() {
        val body = budgetAlertBody(progress(41_000_000, day = first + 19))
        assertTrue(body, body.contains("۹۰۰ هزار از ۵ میلیون تومان مونده"))
        assertTrue(body, body.contains("۱۲ روز تا آخر ماه"))
    }

    @Test
    fun `the last day of a window says so instead of counting one`() {
        assertEquals("امروز آخرین روزه", budgetDaysLeftFa(progress(10_000_000, day = first + 30)))
        assertEquals("۲ روز تا آخر ماه", budgetDaysLeftFa(progress(10_000_000, day = first + 29)))
    }

    @Test
    fun `a budget always has at least today left, so no window is ever read as finished`() {
        // The window is derived from the same day it is measured against, so today is always inside
        // it. Stepping a day past مرداد lands in شهریور with a whole month ahead — never on a spent
        // مرداد with zero days left, which is the sentence this invariant exists to make impossible.
        for (day in first..(first + 40)) {
            val p = budgetProgress(budget(50_000_000), listOf(entry(first, -10_000_000)), day)
            assertTrue("$day left ${p.daysLeft}", p.daysLeft >= 1)
            assertTrue(day in p.window)
        }
        assertEquals("شهریور", budgetProgress(budget(50_000_000), emptyList(), first + 31).window.fa)
    }

    @Test
    fun `a budget comfortably on pace says nothing beyond its own bar`() {
        assertEquals("", budgetNoteFa(progress(10_000_000, day = first + 25)))
    }

    @Test
    fun `the pace line only appears while there is a window left to change`() {
        // Outpacing on the fifth of the month: worth a rate. On the last day: worth a date.
        assertTrue(budgetNoteFa(progress(35_000_000, day = first + 4)).contains("روزی"))
        assertEquals("امروز آخرین روزه.", budgetNoteFa(progress(35_000_000, day = first + 30)))
    }

    // ─────────────────────────── home ───────────────────────────

    @Test
    fun `only a budget nearly out or past its cap reaches the home screen`() {
        assertNull(pressingBudget(listOf(progress(41_000_000))))  // NEAR is not front-page news
        assertEquals("b1", pressingBudget(listOf(progress(48_000_000)))!!.goal.id)
        assertEquals("b1", pressingBudget(listOf(progress(60_000_000)))!!.goal.id)
        assertNull(pressingBudget(emptyList()))
    }

    @Test
    fun `the worst one wins by share, not by amount`() {
        val rows = listOf(
            entry(first, -99_000_000, categoryId = "cat_home"),   // 99% of a hundred
            entry(first, -6_000_000, categoryId = dining),        // 120% of five
        )
        val goals = listOf(
            budget(100_000_000, category = "cat_home", id = "b_home"),
            budget(5_000_000, category = dining, id = "b_dining"),
        )
        assertEquals("b_dining", pressingBudget(budgetsOf(goals, rows, first + 5))!!.goal.id)
    }

    @Test
    fun `a budget on the home screen carries the rows behind its own figure`() {
        val mine = entry(first + 1, -60_000_000)
        val theirs = entry(first + 1, -60_000_000, categoryId = "cat_groceries")
        val rows = listOf(mine, theirs)
        val insight = budgetInsight(budgetsOf(listOf(budget(50_000_000)), rows, first + 5).single(), rows)
        assertEquals(Insight.Tone.ATTENTION, insight.tone)
        assertEquals(listOf(mine.txn.ref), insight.refs)
    }

    @Test
    fun `a cap she has run past outranks the review queue for the one slot home has`() {
        val rows = listOf(
            entry(first + 1, -60_000_000),
            entry(first + 1, -1_000_000, categoryId = "cat_groceries")
                .let { it.copy(needsReview = true) },
        )
        val budgets = budgetsOf(listOf(budget(50_000_000)), rows, first + 5)
        val story = buildStory(rows, liquidRial = 0L, today = first + 5, budgets = budgets)
        assertEquals(budgets.single(), story.attentionBudget)
        assertTrue(story.attention!!.text.contains("رستوران و کافه"))
    }

    @Test
    fun `with no budget kept, the home screen is exactly what it was`() {
        val rows = listOf(entry(first + 1, -1_000_000).let { it.copy(needsReview = true) })
        val story = buildStory(rows, liquidRial = 0L, today = first + 5)
        assertNull(story.attentionBudget)
        assertTrue(story.attention!!.text.contains("منتظر"))
    }
}
