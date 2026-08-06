package com.doxigo.muchtoman

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the month did, worked out from the ledger and nothing else.
 *
 * Every figure here is deterministic and every one of them can name the transactions it came
 * from. No model computes money, and the narrative below is a template over these numbers
 * rather than prose that has been asked to be true.
 *
 * Two rules run through all of it:
 *
 *  - **A transfer is neither income nor spending.** Moving fifty million between her own
 *    accounts must not read as fifty million earned and fifty million spent.
 *  - **Never celebrate spending fewer Toman.** Iranian inflation makes a nominal fall meaningless
 *    and often a lie. Everything compared here is a rate, a share, or a number of days.
 */

/** One Jalali month of the ledger. */
data class MonthReport(
    val year: Int,
    val month: Int,
    val incomeRial: Long,
    val spentRial: Long,
    val byCategory: List<Pair<String, Long>>,
    val transactions: Int,
    val handledAutomatically: Int,
) {
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

fun monthReport(entries: List<LedgerEntry>, year: Int, month: Int): MonthReport {
    val from = jalaliDay(year, month, 1)
    val until = from + jalaliMonthLength(year, month)
    val inMonth = spendable(entries).filter { it.txn.day in from until until }

    var income = 0L
    var spent = 0L
    val byCategory = mutableMapOf<String, Long>()
    for (entry in inMonth) {
        val signed = entry.txn.signedRial ?: continue
        if (signed > 0) {
            income += signed
        } else {
            spent += -signed
            byCategory[entry.categoryFa] = (byCategory[entry.categoryFa] ?: 0L) + -signed
        }
    }
    return MonthReport(
        year = year,
        month = month,
        incomeRial = income,
        spentRial = spent,
        byCategory = byCategory.toList().sortedByDescending { it.second },
        transactions = inMonth.size,
        handledAutomatically = inMonth.count { !it.needsReview },
    )
}

/** The month a day falls in, which is the only one the home screen ever shows. */
fun currentMonthReport(entries: List<LedgerEntry>, today: Long): MonthReport =
    jalaliOf(today).let { monthReport(entries, it.year, it.month) }

/** The month before [year]/[month], with the year rolling back at فروردین. */
fun previousMonth(year: Int, month: Int): Pair<Int, Int> =
    if (month == 1) (year - 1) to 12 else year to (month - 1)

/**
 * How many days of ordinary spending the liquid money would cover.
 *
 * The median daily outflow, not the mean: one car repair should not tell her she has three
 * weeks less runway than she does. Null when there is not enough history to say — and saying
 * nothing is the right answer then, rather than a number she might plan around.
 */
fun bufferDays(entries: List<LedgerEntry>, liquidRial: Long, today: Long, over: Int = 90): Int? {
    val from = today - over
    val daily = spendable(entries)
        .filter { it.txn.day in from..today && (it.txn.signedRial ?: 0) < 0 }
        .groupBy { it.txn.day }
        .map { (_, rows) -> rows.sumOf { -(it.txn.signedRial ?: 0L) } }
    if (daily.size < 14) return null
    val median = daily.sorted()[daily.size / 2]
    if (median <= 0) return null
    return (liquidRial.toDouble() / median).toInt().coerceAtMost(3650)
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
 */
fun narrate(
    now: MonthReport,
    before: MonthReport?,
    entries: List<LedgerEntry>,
    bufferDays: Int?,
): List<Insight> {
    val out = mutableListOf<Insight>()
    val monthRefs = spendable(entries)
        .filter { it.txn.day >= jalaliDay(now.year, now.month, 1) }
        .map { it.txn.ref }

    now.savingsRate?.let { rate ->
        val beforeRate = before?.savingsRate
        // A negative rate is not "minus twenty-nine percent remained" — nothing remained, she
        // spent more than came in. Saying it the first way is arithmetic read aloud; saying it
        // the second is what actually happened, and this app answers in the second.
        val text = when {
            rate < 0 -> "این ماه ${faPercent(-rate)}٪ بیشتر از درآمدت خرج کردی."
            beforeRate == null ->
                "این ماه ${faPercent(rate)}٪ از درآمدت مونده."
            rate > beforeRate + 0.01 ->
                "این ماه ${faPercent(rate)}٪ از درآمدت مونده؛ ماه قبل ${faPercent(beforeRate)}٪ بود."
            rate < beforeRate - 0.01 ->
                "این ماه ${faPercent(rate)}٪ از درآمدت مونده؛ ماه قبل ${faPercent(beforeRate)}٪ بود."
            else ->
                "پس‌اندازت مثل ماه قبل شد: ${faPercent(rate)}٪."
        }
        out += Insight(
            text = text,
            // A rate, never a Toman figure: with this inflation a smaller number of Toman is
            // not a smaller amount of money, and calling it progress would be a lie.
            why = "این عدد از درآمد و خرج این ماه به‌دست اومده؛ پولی که بین حساب‌های خودت جابه‌جا کردی، حساب نشده.",
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
        val nowShare = now.byCategory.associate { it.first to it.second.toDouble() / now.spentRial }
        val beforeShare = before.byCategory.associate { it.first to it.second.toDouble() / before.spentRial }
        val moved = nowShare
            .map { (name, share) -> Triple(name, share, share - (beforeShare[name] ?: 0.0)) }
            .filter { abs(it.third) >= 0.05 }
            .maxByOrNull { abs(it.third) }
        moved?.let { (name, share, delta) ->
            val refs = spendable(entries)
                .filter { it.categoryFa == name && it.txn.day >= jalaliDay(now.year, now.month, 1) }
                .map { it.txn.ref }
            out += Insight(
                text = if (delta > 0) {
                    "سهم «$name» از خرجت بیشتر شده: ${faPercent(share)}٪ از کل."
                } else {
                    "سهم «$name» از خرجت کمتر شده: ${faPercent(share)}٪ از کل."
                },
                why = "این دسته رو با ماه قبل مقایسه کردیم.",
                refs = refs,
                tone = Insight.Tone.NEUTRAL,
            )
        }
    }

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
                text = "${faPercent(share)}٪ از ${faNumber(now.transactions.toDouble())} تراکنش این ماه خودشون دسته‌بندی شدن.",
                why = "تراکنش‌هایی که بدون پرسیدن ازت دسته‌بندی شدن.",
                refs = monthRefs,
                tone = if (share >= 0.9) Insight.Tone.GOOD else Insight.Tone.NEUTRAL,
            )
        }
    }

    val waiting = entries.count { it.needsReview && !it.duplicate && !it.transfer }
    if (waiting > 0) {
        out += Insight(
            text = "${faNumber(waiting.toDouble())} تراکنش منتظر انتخاب توئه.",
            why = "تراکنش‌هایی که با اطمینان کافی دسته‌بندی نشدن.",
            refs = entries.filter { it.needsReview && !it.duplicate && !it.transfer }.map { it.txn.ref },
            tone = Insight.Tone.ATTENTION,
        )
    }

    return out
}

/**
 * Everything the home screen says, worked out in one pass.
 *
 * The screen itself renders this and computes nothing, which is the point: what she is told
 * about her money and what the tests check are the same function.
 */
data class HomeStory(
    val month: MonthReport,
    val previous: MonthReport,
    val insights: List<Insight>,
    val wins: List<Insight>,
    val bufferDays: Int?,
) {
    /** The one line worth leading with, and never more than one. */
    val headline: Insight? get() = wins.firstOrNull() ?: insights.firstOrNull { it.tone != Insight.Tone.ATTENTION }

    /** The single thing asking for her, if anything is. */
    val attention: Insight? get() = insights.firstOrNull { it.tone == Insight.Tone.ATTENTION }
}

fun buildStory(entries: List<LedgerEntry>, liquidRial: Long, today: Long): HomeStory {
    val here = jalaliOf(today)
    val month = monthReport(entries, here.year, here.month)
    val (py, pm) = previousMonth(here.year, here.month)
    val previous = monthReport(entries, py, pm)
    val buffer = bufferDays(entries, liquidRial, today)
    return HomeStory(
        month = month,
        previous = previous,
        insights = narrate(month, previous.takeIf { it.transactions > 0 }, entries, buffer),
        wins = quietWins(month, previous.takeIf { it.transactions > 0 }, buffer),
        bufferDays = buffer,
    )
}

/**
 * Wins worth marking, and nothing else.
 *
 * No points, no streaks, no confetti, and nothing that fires because she opened the app. Each
 * one is a real financial outcome, and each is stated once with the figure that earned it.
 */
fun quietWins(now: MonthReport, before: MonthReport?, bufferDays: Int?): List<Insight> {
    val wins = mutableListOf<Insight>()
    val rate = now.savingsRate
    if (rate != null && rate > 0 && (before?.savingsRate ?: -1.0) <= 0) {
        wins += Insight(
            "اولین ماهی که بیشتر از خرجت درآمد داشتی.",
            "درآمدت از خرجت بیشتر شد.",
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
    if (before != null && rate != null && before.savingsRate != null && rate > before.savingsRate!! + 0.05) {
        wins += Insight(
            "پس‌اندازت نسبت به ماه قبل بهتر شده.",
            "مهم اینه چند درصد از درآمدت مونده، نه چند تومان.",
            emptyList(),
            Insight.Tone.GOOD,
        )
    }
    return wins
}
