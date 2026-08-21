package com.doxigo.muchtoman

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What a month did, worked out from the ledger and nothing else.
 *
 * Every figure here is deterministic and every one of them can name the transactions it came
 * from. No model computes money, and the narrative below is a template over these numbers
 * rather than prose that has been asked to be true.
 *
 * Four rules run through all of it:
 *
 *  - **A transfer is neither income nor spending.** Moving fifty million between her own
 *    accounts must not read as fifty million earned and fifty million spent.
 *  - **Money that only passes through is held apart, never hidden.** A قرض and the same قرض back
 *    are one movement in two halves — see [PASS_THROUGH_CATEGORIES] — so they are kept out of
 *    both sides by default and stated in Toman on the same card, because a summary that leaves
 *    fifty million out without saying so is the friendlier kind of lie.
 *  - **Never celebrate spending fewer Toman.** Iranian inflation makes a nominal fall meaningless
 *    and often a lie. Everything compared here is a rate, a share, or a number of days.
 *  - **A month only ever speaks for itself.** Every range is closed-open on the Jalali month, so
 *    a report on تیر cannot quietly cite a transaction from مرداد as its evidence, and today's
 *    cash balance is never offered as a past month's runway.
 */

/**
 * One Jalali month, as a value that can be stepped through, compared and written down.
 *
 * The report is a walk over these, so the rollover at فروردین/اسفند lives here once rather than
 * at every call site that wanted the month before.
 */
data class ReportMonth(val year: Int, val month: Int) : Comparable<ReportMonth> {
    init {
        require(month in 1..12) { "jalali month out of range: $month" }
    }

    /** The first Tehran day of the month — the range's lower bound, and its identity when saved. */
    val startDay: Long get() = jalaliDay(year, month, 1)

    /**
     * The first day of the *next* month. Every range in this file is `startDay until endDay`,
     * which is the whole reason a historical report cannot reach forward into a later one.
     */
    val endDay: Long get() = startDay + jalaliMonthLength(year, month)

    fun previous(): ReportMonth =
        if (month == 1) ReportMonth(year - 1, 12) else ReportMonth(year, month - 1)

    fun next(): ReportMonth =
        if (month == 12) ReportMonth(year + 1, 1) else ReportMonth(year, month + 1)

    /** [months] steps back, which is how a window of whole months finds its own first one. */
    fun back(months: Int): ReportMonth {
        val total = ordinal - months
        return ReportMonth(total / 12, total % 12 + 1)
    }

    /** «مرداد ۱۴۰۵» — the month as she would say it. */
    val fa: String get() = "${MONTHS[month - 1]} ${faYearDigits(year)}"

    private val ordinal: Int get() = year * 12 + (month - 1)

    override fun compareTo(other: ReportMonth): Int = ordinal.compareTo(other.ordinal)
}

/**
 * A run of whole Jalali months, [first] through [last] inclusive — the window a report covers.
 *
 * Whole months, never «the last ninety days». The asset report can slice by day because a
 * balance exists on every one of them; دخل و خرج cannot, because half of فروردین against half of
 * اردیبهشت is a comparison of two things that are each missing a rent payment. Everything below
 * still runs `startDay until endDay`, so a range cannot cite a transaction from outside itself
 * any more than a single month could.
 */
data class ReportRange(val first: ReportMonth, val last: ReportMonth) {
    init {
        require(first <= last) { "range runs backwards: ${first.fa} → ${last.fa}" }
    }

    /** How many months it holds. One, for the report that is still about a single month. */
    val count: Int get() = (last.year * 12 + last.month) - (first.year * 12 + first.month) + 1

    val startDay: Long get() = first.startDay
    val endDay: Long get() = last.endDay

    val months: List<ReportMonth>
        get() = generateSequence(first) { if (it < last) it.next() else null }.toList()

    /** Whether [day] falls inside — the closed-open test every figure here is built on. */
    operator fun contains(day: Long): Boolean = day in startDay until endDay

    operator fun contains(month: ReportMonth): Boolean = month in first..last

    /**
     * The window written out: «مرداد ۱۴۰۵», «خرداد تا مرداد ۱۴۰۵», «بهمن ۱۴۰۴ تا مرداد ۱۴۰۵».
     *
     * The year is said once where both ends share it, because «خرداد ۱۴۰۵ تا مرداد ۱۴۰۵» is the
     * same year read twice and the eye has to check that it is the same year.
     */
    val fa: String get() = when {
        first == last -> last.fa
        first.year == last.year -> "${MONTHS[first.month - 1]} تا ${last.fa}"
        else -> "${first.fa} تا ${last.fa}"
    }

    /**
     * What to call it when it ends at the month she is standing in — «۳ ماه گذشته» rather than
     * a pair of month names, because a window she is inside is described by its length.
     */
    val recentFa: String get() = when (count) {
        1 -> "این ماه"
        12 -> "یک سال گذشته"
        else -> "${faNumber(count.toDouble())} ماه گذشته"
    }

    /** The name to use in a sentence, given whether the window reaches today. */
    fun nameFa(current: Boolean): String = if (current) recentFa else fa

    /** «ماه قبل» over one month, «دورهٔ قبل» over more — what the comparison reaches back to. */
    val beforeFa: String get() = if (count == 1) "ماه قبل" else "دورهٔ قبل"

    /** The window of the same length immediately before this one. */
    fun before(): ReportRange = ReportRange(first.back(count), first.previous())
}

/**
 * How much of the ledger دخل و خرج is reading, as offered at the top of the report.
 *
 * The same four lengths گزارش دارایی offers, and for the same reason: «بیشتر شده یا کمتر؟» over
 * one month is a question about a month, and over a year it is a question about a household.
 * There is no «همه» here, though — the asset chart can draw every day it has, while a cash-flow
 * report over an unbounded number of months would put a bar chart on screen with no fixed scale
 * and a savings rate averaged over years of different incomes.
 */
enum class ReportSpan(val months: Int, val fa: String) {
    MONTH(1, "۱ ماه"),
    QUARTER(3, "۳ ماه"),
    HALF(6, "۶ ماه"),
    YEAR(12, "۱ سال"),
}

/** ۱۴۰۵ and never ۱٬۴۰۵: a year is a name, not a quantity, so it takes no group separator. */
private fun faYearDigits(year: Int): String = buildString {
    for (c in year.toString()) append(if (c in '0'..'9') '۰' + (c - '0') else c)
}

/** The month a Tehran day falls in. */
fun reportMonthOf(day: Long): ReportMonth =
    jalaliOf(day).let { ReportMonth(it.year, it.month) }

/**
 * Ten years of months, which is an order of magnitude more than the ledger keeps.
 *
 * The bound is not a product decision: it is what stops one transaction stamped with a corrupt
 * day from turning the month selector into thousands of entries built on the UI thread.
 */
private const val MAX_REPORT_MONTHS = 120

/**
 * Every month she can ask about: the first one the ledger recorded anything in, through the one
 * she is standing in, with nothing skipped and nothing invented.
 *
 * Empty months in between are kept — a month with no transactions is an answer, and dropping it
 * would make the selector step from تیر to شهریور as though مرداد had not happened. Months after
 * this one are never offered: a future month is not a report, it is a blank.
 */
fun availableReportMonths(entries: List<LedgerEntry>, today: Long): List<ReportMonth> {
    val here = reportMonthOf(today)
    var floor = here
    repeat(MAX_REPORT_MONTHS - 1) { floor = floor.previous() }

    val earliest = spendable(entries).minOfOrNull { it.txn.day }?.let { reportMonthOf(it) }
    // A row dated in the future cannot open a month before the current one, and it must not
    // open one after it either.
    var first = earliest ?: here
    if (first > here) first = here
    if (first < floor) first = floor

    val out = mutableListOf<ReportMonth>()
    var m = first
    while (m <= here) {
        out += m
        m = m.next()
    }
    return out
}

/**
 * The months the chart shows around [selected] — at most [span], always real, always in order.
 *
 * The selected month sits at the right-hand end of its own history where there is history to
 * show; near the start of the ledger the window slides forward instead of padding with months
 * that never existed. Either way the count is what the ledger can actually answer for.
 */
fun chartWindow(
    available: List<ReportMonth>,
    selected: ReportMonth,
    span: Int = 6,
): List<ReportMonth> {
    val at = available.indexOf(selected)
    if (at < 0) return emptyList()
    val end = (at + 1).coerceAtLeast(minOf(span, available.size))
    val start = (end - span).coerceAtLeast(0)
    return available.subList(start, end)
}

/** One window of the ledger — a single Jalali month, or a run of them. */
data class PeriodReport(
    val range: ReportRange,
    val incomeRial: Long,
    val spentRial: Long,
    /** Where the money went, biggest first. Only the negative side of the window. */
    val spendingByCategory: List<Pair<String, Long>>,
    /** Where the money came from, biggest first. Only the positive side. */
    val incomeByCategory: List<Pair<String, Long>>,
    val transactions: Int,
    val handledAutomatically: Int,
    /**
     * What only passed through — قرض, پس‌گرفتن قرض, همسر — as one magnitude with both directions
     * in it, exactly as [incomeRial] and [spentRial] are each one direction of the same window.
     *
     * Always the real figure, whether or not this window is counting it. That is what lets the
     * card offer to fold it in: a report that only knew about this money while it was excluded
     * could never say there was anything to include.
     */
    val passedRial: Long,
    /** «قرض», «همسر», «قرض و همسر» — only the ones this window actually holds. Empty for none. */
    val passedFa: String,
    /** Whether [passedRial] is inside the two figures above, or is being held apart from them. */
    val countedPassThrough: Boolean,
) {
    /** The month it covers, for the report that covers exactly one — the chart's bars, and home. */
    val month: ReportMonth get() = range.last

    val netRial: Long get() = incomeRial - spentRial

    /**
     * The share of what came in that stayed. Null when nothing came in, because a savings rate
     * against no income is a division by zero dressed up as a hard month.
     */
    val savingsRate: Double?
        get() = if (incomeRial > 0) (incomeRial - spentRial).toDouble() / incomeRial else null

    /** The share of transactions she never had to touch. The number the app is judged on. */
    val automaticShare: Double?
        get() = if (transactions > 0) handledAutomatically.toDouble() / transactions else null
}

/** Entries that count as money moving, with transfers and hidden duplicates left out. */
fun spendable(entries: List<LedgerEntry>): List<LedgerEntry> =
    entries.filterNot { it.duplicate || it.transfer }

/**
 * The window, both sides of it.
 *
 * Income is categorised as well as spending: «درآمد» and «حقوق» and «فروش» are different answers
 * to "where did it come from", and totalling them into one bar threw that away on the one side
 * of the ledger she has the least of and thinks about the most.
 *
 * A run of months adds up exactly as one month does, because the range is the only thing that
 * changed: no figure here is a mean of monthly figures, so a three-month «خواربار» share is that
 * quarter's real share and not the average of three shares computed against three different
 * incomes.
 *
 * [countPassThrough] folds قرض and همسر back into the two sides — see [PASS_THROUGH_CATEGORIES].
 * It is a way of reading a window and not a fact about the ledger, so it is a parameter here
 * rather than a filter inside [spendable]: the goals, the «می‌ارزید؟» queue and the list of months
 * she can open all run through that gate too, and none of them asked this question.
 */
fun periodReport(
    entries: List<LedgerEntry>,
    range: ReportRange,
    countPassThrough: Boolean = false,
): PeriodReport {
    val inRange = spendable(entries).filter { it.txn.day in range }

    var income = 0L
    var spent = 0L
    var passed = 0L
    val passedNames = mutableSetOf<String>()
    val spending = mutableMapOf<String, Long>()
    val earning = mutableMapOf<String, Long>()
    for (entry in inRange) {
        val signed = entry.txn.signedRial ?: continue
        val passes = PASS_THROUGH_CATEGORIES[entry.categoryId]
        if (passes != null) {
            passed += abs(signed)
            passedNames += passes
            // Measured either way, counted only when she has asked for it. Skipping the rest of
            // the body is the whole of what the switch does: turning it on puts the قرض back
            // into خرج, back into the savings rate, and back into the دسته‌ها list below.
            if (!countPassThrough) continue
        }
        if (signed > 0) {
            income += signed
            earning[entry.categoryFa] = (earning[entry.categoryFa] ?: 0L) + signed
        } else {
            spent += -signed
            spending[entry.categoryFa] = (spending[entry.categoryFa] ?: 0L) + -signed
        }
    }
    return PeriodReport(
        range = range,
        incomeRial = income,
        spentRial = spent,
        spendingByCategory = spending.toList().sortedByDescending { it.second },
        incomeByCategory = earning.toList().sortedByDescending { it.second },
        transactions = inRange.size,
        handledAutomatically = inRange.count { !it.needsReview },
        passedRial = passed,
        // Named in the table's order rather than the ledger's, so the label is «قرض و همسر» in
        // every window that holds both and never «همسر و قرض» because a transfer landed first.
        passedFa = PASS_THROUGH_CATEGORIES.values.distinct()
            .filter { it in passedNames }
            .joinToString(" و "),
        countedPassThrough = countPassThrough,
    )
}

/** One month of it, which is what the chart's bars and the home screen are made of. */
fun monthReport(
    entries: List<LedgerEntry>,
    month: ReportMonth,
    countPassThrough: Boolean = false,
): PeriodReport = periodReport(entries, ReportRange(month, month), countPassThrough)

fun monthReport(entries: List<LedgerEntry>, year: Int, month: Int): PeriodReport =
    monthReport(entries, ReportMonth(year, month))

/** The month a day falls in. */
fun currentMonthReport(entries: List<LedgerEntry>, today: Long): PeriodReport =
    monthReport(entries, reportMonthOf(today))

/**
 * How many days of ordinary spending the liquid money would cover.
 *
 * The median daily outflow, not the mean: one car repair should not tell her she has three
 * weeks less runway than she does. Null when there is not enough history to say — and saying
 * nothing is the right answer then, rather than a number she might plan around.
 *
 * «خرج معمولی» is what the sentence this feeds actually says, so money that only passed through
 * is left out on the same terms as everywhere else: lending is not a habit that eats a balance.
 * The median already shrugs off a single large day, but the figure would still be wrong for a
 * household that lends most weeks, and the word in the sentence would be wrong for any of them.
 */
fun bufferDays(
    entries: List<LedgerEntry>,
    liquidRial: Long,
    today: Long,
    over: Int = 90,
    countPassThrough: Boolean = false,
): Int? {
    val from = today - over
    val daily = spendable(entries)
        .filter {
            it.txn.day in from..today && (it.txn.signedRial ?: 0) < 0 &&
                (countPassThrough || it.categoryId !in PASS_THROUGH_CATEGORIES)
        }
        .groupBy { it.txn.day }
        .map { (_, rows) -> rows.sumOf { -(it.txn.signedRial ?: 0L) } }
    if (daily.size < 14) return null
    val median = daily.sorted()[daily.size / 2]
    if (median <= 0) return null
    return (liquidRial.toDouble() / median).toInt().coerceAtMost(3650)
}

// ─────────────────────────── one category, taken apart ───────────────────────────

/**
 * One category out of the window's list, opened up: its total, its share of its own side, six
 * months of it side by side, and the transactions the figure is made of — because «every number
 * can be traced back to a transaction» applies to a share bar exactly as much as to the total.
 */
data class CategoryWindow(
    val name: String,
    val income: Boolean,
    val range: ReportRange,
    /** What this category moved inside [range]. */
    val totalRial: Long,
    /** That side's whole figure for the window, which is what the share is a share of. */
    val sideRial: Long,
    /** The trailing months ending at the window's last, oldest first. Months with nothing are zero. */
    val trend: List<Pair<ReportMonth, Long>>,
    /** The rows behind [totalRial], newest first. */
    val rows: List<LedgerEntry>,
) {
    val share: Double? get() = if (sideRial > 0) totalRial.toDouble() / sideRial else null
}

/**
 * [name] rather than an id, because the window's list is aggregated by name — a category she
 * archived and remade under the same word is one line there, so it is one sheet here.
 */
fun categoryWindow(
    entries: List<LedgerEntry>,
    name: String,
    income: Boolean,
    range: ReportRange,
    sideRial: Long,
    trendMonths: Int = 6,
): CategoryWindow {
    val side = spendable(entries).filter {
        val signed = it.txn.signedRial ?: return@filter false
        it.categoryFa == name && (if (income) signed > 0 else signed < 0)
    }
    val inRange = side.filter { it.txn.day in range }
    val months = (trendMonths - 1 downTo 0).map { back -> range.last.back(back) }
    val trend = months.map { month ->
        month to side
            .filter { it.txn.day in month.startDay until month.endDay }
            .sumOf { abs(it.txn.signedRial ?: 0L) }
    }
    return CategoryWindow(
        name = name,
        income = income,
        range = range,
        totalRial = inRange.sumOf { abs(it.txn.signedRial ?: 0L) },
        sideRial = sideRial,
        trend = trend,
        rows = inRange.sortedByDescending { it.txn.at },
    )
}

// ─────────────────────────── the narrative ───────────────────────────

/**
 * One statement the app is prepared to make, and the transactions behind it.
 *
 * [refs] is not decoration. «چرا این را می‌بینم؟» has to be answerable for every line, and the
 * cheapest way to guarantee that is for the line to carry its own evidence rather than for
 * something else to try to reconstruct it later.
 */
data class Insight(
    val text: String,
    val why: String,
    val refs: List<String>,
    val tone: Tone = Tone.NEUTRAL,
) {
    enum class Tone { NEUTRAL, GOOD, ATTENTION }
}

private fun faPercent(share: Double): String = faNumber((share * 100).roundToLong().toDouble())

/**
 * What changed, in Persian, from figures that were computed before any of this ran.
 *
 * Templates, not generation. Deterministic in and deterministic out, no dependency, no cost,
 * and — the reason it matters here — no way whatsoever to invent a number.
 *
 * [current] is the difference between «این ماه» and «در تیر ۱۴۰۵». It is passed rather than
 * worked out from a clock so that the same month reads the same way in a test as on the phone,
 * and so that a report on a past month can never claim to be describing the present one.
 */
fun narrate(
    now: PeriodReport,
    before: PeriodReport?,
    entries: List<LedgerEntry>,
    bufferDays: Int?,
    current: Boolean,
): List<Insight> {
    val out = mutableListOf<Insight>()
    // Closed-open, both ends. With only a lower bound — which is all a report on the current
    // month ever needed — every historical line would carry every transaction since as its
    // evidence, and «چرا این را می‌بینم؟» would answer with months she did not ask about.
    val inMonth = spendable(entries).filter { it.txn.day in now.range }
    val monthRefs = inMonth.map { it.txn.ref }
    // «این ماه» reads as a sentence on its own; every other name for a window needs «در … ،»
    // in front of it, and a window of three months she is standing in is one of those.
    val named = now.range.nameFa(current)
    val head = if (current && now.range.count == 1) named else "در $named،"
    val beforeNamed = now.range.beforeFa

    now.savingsRate?.let { rate ->
        val beforeRate = before?.savingsRate
        // A negative rate is not "minus twenty-nine percent remained" — nothing remained, she
        // spent more than came in. Saying it the first way is arithmetic read aloud; saying it
        // the second is what actually happened, and this app answers in the second.
        val text = when {
            rate < 0 -> "$head ${faPercent(-rate)}٪ بیشتر از درآمدت خرج کردی."
            beforeRate == null ->
                "$head ${faPercent(rate)}٪ از درآمدت مونده."
            rate > beforeRate + 0.01 ->
                "$head ${faPercent(rate)}٪ از درآمدت مونده؛ $beforeNamed ${faPercent(beforeRate)}٪ بود."
            rate < beforeRate - 0.01 ->
                "$head ${faPercent(rate)}٪ از درآمدت مونده؛ $beforeNamed ${faPercent(beforeRate)}٪ بود."
            current -> "پس‌اندازت مثل $beforeNamed شد: ${faPercent(rate)}٪."
            else -> "پس‌انداز $named مثل ${beforeNamed}ش بود: ${faPercent(rate)}٪."
        }
        out += Insight(
            text = text,
            // A rate, never a Toman figure: with this inflation a smaller number of Toman is
            // not a smaller amount of money, and calling it progress would be a lie.
            //
            // «چرا این را می‌بینم؟» has to name everything the figure left out, not just the
            // transfers. A savings rate computed without a fifty-million قرض in it owes her that
            // sentence more than it owes her the one about her own accounts.
            why = "این عدد از درآمد و خرج $named به‌دست اومده؛ پولی که بین حساب‌های خودت جابه‌جا کردی، حساب نشده." +
                if (now.passedRial > 0 && !now.countedPassThrough) " ${now.passedFa} هم حساب نشده." else "",
            refs = monthRefs,
            tone = when {
                beforeRate == null -> Insight.Tone.NEUTRAL
                rate > beforeRate + 0.01 -> Insight.Tone.GOOD
                else -> Insight.Tone.NEUTRAL
            },
        )
    }

    // The single category that moved most, as a share of spending rather than in Toman.
    if (before != null && now.spentRial > 0 && before.spentRial > 0) {
        val nowShare = now.spendingByCategory
            .associate { it.first to it.second.toDouble() / now.spentRial }
        val beforeShare = before.spendingByCategory
            .associate { it.first to it.second.toDouble() / before.spentRial }
        val moved = nowShare
            .map { (name, share) -> Triple(name, share, share - (beforeShare[name] ?: 0.0)) }
            .filter { abs(it.third) >= 0.05 }
            .maxByOrNull { abs(it.third) }
        moved?.let { (name, share, delta) ->
            val refs = inMonth.filter { it.categoryFa == name }.map { it.txn.ref }
            val lead = if (current && now.range.count == 1) "" else "در $named "
            out += Insight(
                text = if (delta > 0) {
                    "${lead}سهم «$name» از خرجت بیشتر شده: ${faPercent(share)}٪ از کل."
                } else {
                    "${lead}سهم «$name» از خرجت کمتر شده: ${faPercent(share)}٪ از کل."
                },
                why = "این دسته رو با $beforeNamed مقایسه کردیم.",
                refs = refs,
                tone = Insight.Tone.NEUTRAL,
            )
        }
    }

    // Only ever present tense — see [buildCashFlow], which is where it is refused for a past
    // month. What is in her accounts today says nothing about what تیر would have covered.
    bufferDays?.let { days ->
        out += Insight(
            text = "با خرج معمولی‌ات، پول نقدت برای ${faNumber(days.toDouble())} روز می‌رسه.",
            why = "موجودی حساب‌ها تقسیم بر میانهٔ خرج روزانهٔ سه ماه گذشته.",
            refs = emptyList(),
            tone = if (days >= 90) Insight.Tone.GOOD else Insight.Tone.NEUTRAL,
        )
    }

    now.automaticShare?.let { share ->
        if (now.transactions >= 5) {
            out += Insight(
                // The app earning its silence, which is the thing actually worth reporting.
                text = "${faPercent(share)}٪ از ${faNumber(now.transactions.toDouble())} تراکنش $named خودشون دسته‌بندی شدن.",
                why = "تراکنش‌هایی که بدون پرسیدن ازت دسته‌بندی شدن.",
                refs = monthRefs,
                tone = if (share >= 0.9) Insight.Tone.GOOD else Insight.Tone.NEUTRAL,
            )
        }
    }

    // The queue is a thing to do now, not a fact about a month: it counts the whole ledger, so
    // it belongs to the month she is standing in and nowhere else. On a past report it would
    // both cite transactions from outside the month and ask her to act on a screen she is
    // reading as history.
    if (current) {
        val waiting = entries.filter { it.needsReview && !it.duplicate && !it.transfer }
        if (waiting.isNotEmpty()) {
            out += Insight(
                text = "${faNumber(waiting.size.toDouble())} تراکنش منتظر انتخاب توئه.",
                why = "تراکنش‌هایی که با اطمینان کافی دسته‌بندی نشدن.",
                refs = waiting.map { it.txn.ref },
                tone = Insight.Tone.ATTENTION,
            )
        }
    }

    return out
}

/**
 * Wins worth marking, and nothing else.
 *
 * No points, no streaks, no confetti, and nothing that fires because she opened the app. Each
 * one is a real financial outcome, and each is stated once with the figure that earned it.
 */
fun quietWins(
    now: PeriodReport,
    before: PeriodReport?,
    bufferDays: Int?,
    current: Boolean,
): List<Insight> {
    val wins = mutableListOf<Insight>()
    val named = now.range.nameFa(current)
    val beforeNamed = now.range.beforeFa
    val rate = now.savingsRate
    if (rate != null && rate > 0 && (before?.savingsRate ?: -1.0) <= 0) {
        wins += Insight(
            // Not «اولین ماهی که…»: the comparison behind this line reaches exactly one window
            // back, so "the first month ever" was a claim about a history it had never read.
            // What it can prove is that this window turned, and that is what it now says.
            if (current && now.range.count == 1) {
                "درآمد این ماه از خرجت بیشتر شد."
            } else {
                "در $named درآمدت از خرجت بیشتر شد."
            },
            "${beforeNamed}ش چیزی از درآمدت نمونده بود.",
            emptyList(),
            Insight.Tone.GOOD,
        )
    }
    if (bufferDays != null && bufferDays >= 30) {
        wins += Insight(
            "یک ماه خرج ضروری‌ات رو کنار گذاشتی.",
            "موجودی نقدی تقسیم بر میانهٔ خرج روزانه.",
            emptyList(),
            Insight.Tone.GOOD,
        )
    }
    val beforeRate = before?.savingsRate
    if (rate != null && beforeRate != null && rate > beforeRate + 0.05) {
        wins += Insight(
            if (current) {
                "پس‌اندازت نسبت به $beforeNamed بهتر شده."
            } else {
                "پس‌انداز $named از ${beforeNamed}ش بهتر بود."
            },
            "مهم اینه چند درصد از درآمدت مونده، نه چند تومان.",
            emptyList(),
            Insight.Tone.GOOD,
        )
    }
    return wins
}

// ─────────────────────────── one month, assembled ───────────────────────────

/**
 * Everything the cash-flow report says about the month she picked, worked out in one pass.
 *
 * The screen renders this and computes nothing. Totals, categories, chart bars and every
 * sentence come out of the same walk over the same entries, so the bar she taps and the figure
 * she reads cannot disagree — the class exists to make that impossible rather than tested.
 */
data class CashFlowReport(
    /** The month the window ends at. Stepping it is what the arrows either side of the title do. */
    val selected: ReportMonth,
    /** How far back from [selected] the window reaches — never one the ledger cannot fill. */
    val span: ReportSpan,
    /** Every month she may move to, oldest first. Never empty: it always holds this month. */
    val available: List<ReportMonth>,
    /** The lengths this ledger can answer at this anchor. Never empty: one month always fits. */
    val spans: List<ReportSpan>,
    val period: PeriodReport,
    val previous: PeriodReport,
    /** The chart's bars: the window, plus context around it where the ledger has any. */
    val series: List<PeriodReport>,
    val insights: List<Insight>,
    val wins: List<Insight>,
    /** Cash runway, and only ever for a window she is standing in. */
    val bufferDays: Int?,
    /** Whether the window reaches the month containing today — the one allowed to say «این ماه». */
    val current: Boolean,
) {
    val range: ReportRange get() = period.range
    val canGoBack: Boolean get() = selected > available.first()
    val canGoForward: Boolean get() = selected < available.last()
}

fun buildCashFlow(
    entries: List<LedgerEntry>,
    liquidRial: Long,
    today: Long,
    selected: ReportMonth,
    span: ReportSpan = ReportSpan.MONTH,
    /** Whether قرض and همسر are being counted as ordinary خرج و درآمد. Off is the honest default. */
    countPassThrough: Boolean = false,
): CashFlowReport {
    val available = availableReportMonths(entries, today)
    val here = reportMonthOf(today)
    // A month she can no longer reach — a selection restored from a state older than the ledger
    // it was made against — reports as now rather than as an empty month she cannot leave.
    val month = if (selected in available) selected else here
    val at = available.indexOf(month)
    // A window is only offered where the ledger can fill all of it. «۶ ماه» over four months of
    // history is not a six-month report with two quiet months in it — it is a four-month report
    // wearing the wrong label, and the savings rate it prints would be compared against a
    // previous six months that never existed. The picker dims what does not fit, exactly as the
    // asset report's does, and a span restored from a state the ledger has since outgrown falls
    // back to the longest that does.
    val spans = ReportSpan.entries.filter { it.months <= at + 1 }
    val use = if (span in spans) span else spans.last()
    val range = ReportRange(month.back(use.months - 1), month)
    val current = month == here
    // One setting through every figure on the screen, or the card and the chart under it would
    // be answering the same question two ways — see [CashFlowReport].
    val report = periodReport(entries, range, countPassThrough)
    val before = range.before()
    val previous = periodReport(entries, before, countPassThrough)
    // Refused outright for a past window: the runway is today's cash against today's habits, and
    // pairing it with تیر's spending would state a balance تیر never had.
    val buffer = if (current) {
        bufferDays(entries, liquidRial, today, countPassThrough = countPassThrough)
    } else {
        null
    }
    // The comparison has to be a whole window the ledger actually watched. Half a quarter of
    // history against a full one is a savings rate compared with an artefact of when she
    // installed the app.
    val had = previous.takeIf { it.transactions > 0 && before.first >= available.first() }
    return CashFlowReport(
        selected = month,
        span = use,
        available = available,
        spans = spans,
        period = report,
        previous = previous,
        // Six bars where the window is shorter than six months, so a single month is still read
        // against the ones around it; the window's own length once it is longer than that.
        series = chartWindow(available, month, maxOf(use.months, 6))
            .map { monthReport(entries, it, countPassThrough) },
        insights = narrate(report, had, entries, buffer, current),
        wins = quietWins(report, had, buffer, current),
        bufferDays = buffer,
        current = current,
    )
}

/**
 * Everything the home screen says, worked out in one pass.
 *
 * The screen itself renders this and computes nothing, which is the point: what she is told
 * about her money and what the tests check are the same function.
 */
data class HomeStory(
    val month: PeriodReport,
    val previous: PeriodReport,
    val insights: List<Insight>,
    val wins: List<Insight>,
    val bufferDays: Int?,
    /**
     * The budget behind [attention], when a budget is what is asking for her.
     *
     * Here rather than as a field on [Insight] because it is the *destination* that differs, not the
     * sentence: the attention card is the one card on home with a button on it, and a button that
     * says «دفتر رو باز کن» under a line about a budget is a button that lies. An [Insight] is a
     * statement and a piece of evidence; where to go about it is the screen's business.
     */
    val attentionBudget: BudgetProgress? = null,
) {
    /** The one line worth leading with, and never more than one. */
    val headline: Insight? get() = wins.firstOrNull() ?: insights.firstOrNull { it.tone != Insight.Tone.ATTENTION }

    /** The single thing asking for her, if anything is. */
    val attention: Insight? get() = insights.firstOrNull { it.tone == Insight.Tone.ATTENTION }
}

/**
 * The current month, for the home screen.
 *
 * The same walk as [buildCashFlow] minus the six-month series: home shows one month and never
 * the chart, and this is built once for every state the app produces, so it does not pay for
 * five month-scans the field will not draw.
 */
fun buildStory(
    entries: List<LedgerEntry>,
    liquidRial: Long,
    today: Long,
    /**
     * The caps she keeps, so home can carry the one that has run past itself. Empty is the ordinary
     * case — most households keep none — and then nothing about this screen changes.
     */
    budgets: List<BudgetProgress> = emptyList(),
    /**
     * Home has no switch of its own, and should not grow one: this card is the glance, and the
     * report is where a figure is taken apart. It reads at the default, so what it shows is what
     * دخل و خرج opens on.
     */
    countPassThrough: Boolean = false,
): HomeStory {
    val here = reportMonthOf(today)
    val month = monthReport(entries, here, countPassThrough)
    val previous = monthReport(entries, here.previous(), countPassThrough)
    val buffer = bufferDays(entries, liquidRial, today, countPassThrough = countPassThrough)
    val had = previous.takeIf { it.transactions > 0 }
    val pressing = pressingBudget(budgets)
    return HomeStory(
        month = month,
        previous = previous,
        // First, so that [HomeStory.attention] — which takes the first ATTENTION line there is —
        // prefers a cap she has run past over a review queue. See [pressingBudget].
        insights = listOfNotNull(pressing?.let { budgetInsight(it, entries) }) +
            narrate(month, had, entries, buffer, current = true),
        wins = quietWins(month, had, buffer, current = true),
        bufferDays = buffer,
        attentionBudget = pressing,
    )
}
