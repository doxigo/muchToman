package com.doxigo.muchtoman

import kotlinx.serialization.Serializable

/**
 * Budgets — «برای این ماه چقدر گذاشتم، و چقدرش رفته».
 *
 * A budget is a [GoalKind.CAP] goal with a period on it, and that is not a shortcut: `Goals.kt`
 * declared exactly two shapes from the day it was written — «put this much aside by then» and
 * «keep this category under this much» — and only the first was ever reachable from a screen. So
 * there is no new table, no migration, and no second definition of what a category cost this
 * month. What was missing was the period, the thresholds, and a way to say either out loud.
 *
 * Three rules, and they are the ones that keep this from becoming a scoreboard:
 *
 *  - **A window is closed-open and always the current one.** `startDay until endDay`, exactly as
 *    [ReportRange] is, and a budget only ever measures the week, month or فصل she is standing in.
 *    There is no history to browse: a budget is a decision about money she has not spent yet.
 *  - **Spending here is the same number the report shows.** «رستوران و کافه» on this card and
 *    «رستوران و کافه» in دخل و خرج are one figure read twice — see [budgetSpent] — because an app
 *    that gives two answers for one category has taught her to trust neither.
 *  - **It states facts, never verdicts.** «۸۳٪ بودجهٔ مرداد رفته» is a fact. Every phrasing below
 *    is a figure and a date; nothing here is allowed to say she did badly, and nothing celebrates
 *    spending fewer Toman — the rule the reports already run on.
 */

// ─────────────────────────── the period ───────────────────────────

/** «بهار», «تابستان», «پاییز», «زمستان» — the Jalali quarters, which are the seasons. */
internal val SEASONS = listOf("بهار", "تابستان", "پاییز", "زمستان")

/**
 * The three lengths a budget can be kept over, and what each is called.
 *
 * [id] is the string in the `period` column, so this enum can be renamed and reordered freely
 * while the database cannot. Anything longer than a فصل is not a budget: a year of «رستوران و
 * کافه» is a report, and by the time it could be over there is nothing left to decide.
 *
 * A week runs Saturday to Friday ([weekStart]); a month and a فصل are Jalali, because that is
 * what her salary, her rent and her bills run on.
 */
enum class BudgetPeriod(
    val id: String,
    /** «هفته», «ماه», «فصل» — the noun, for «۱۲ روز تا آخر ماه». */
    val fa: String,
    /** «هفتگی», «ماهانه», «فصلی» — the adjective, for the chip on the card. */
    val everyFa: String,
) {
    WEEK(GoalPeriod.WEEK, "هفته", "هفتگی"),
    MONTH(GoalPeriod.MONTH, "ماه", "ماهانه"),
    QUARTER(GoalPeriod.QUARTER, "فصل", "فصلی"),
    ;

    companion object {
        /**
         * Falls back to a month rather than throwing. A `period` this build does not know is a row
         * written by a later one — a database restored onto an older APK — and a budget that cannot
         * be read must still draw as *something* she can delete, not crash the screen it is on.
         */
        fun of(id: String): BudgetPeriod = entries.firstOrNull { it.id == id } ?: MONTH
    }
}

/**
 * One period of one budget: the days it covers, and nothing about money.
 *
 * Closed-open on Tehran days. [endDay] is the first day *after* the window, so two consecutive
 * windows share a boundary without sharing a day — which is the only arrangement in which a
 * transaction cannot land in both or in neither.
 */
data class BudgetWindow(
    val period: BudgetPeriod,
    val startDay: Long,
    val endDay: Long,
) {
    /** How many days it holds: 7, 29–31, or 90–92. */
    val days: Int get() = (endDay - startDay).toInt()

    operator fun contains(day: Long): Boolean = day in startDay until endDay

    /**
     * What the card calls it: «این هفته», «مرداد», «تابستان».
     *
     * A month and a فصل have names and are said by them, because «بودجهٔ مرداد» is a claim she can
     * check against a calendar. A week has no name in Persian, so «این هفته» *is* its name — and
     * a budget only ever measures the current window, so the deixis is never a lie.
     */
    val fa: String
        get() {
            val at = jalaliOf(startDay)
            return when (period) {
                BudgetPeriod.WEEK -> "این هفته"
                BudgetPeriod.MONTH -> MONTHS[at.month - 1]
                BudgetPeriod.QUARTER -> SEASONS[jalaliQuarter(at.month) - 1]
            }
        }

    /**
     * How many days of it are still to come, [today] included, floored at zero.
     *
     * Today counts as left because she can still spend it — «۱ روز مونده» on the last day is the
     * honest number, and a zero there would read as "the window is over" on a day it is not.
     */
    fun daysLeft(today: Long): Int = (endDay - today).coerceIn(0L, days.toLong()).toInt()

    /** How many days of it are gone, [today] included, so the first day is 1 and never 0. */
    fun daysGone(today: Long): Int = (today - startDay + 1).coerceIn(1L, days.toLong()).toInt()
}

/** The window of [period] that [day] falls in. */
fun budgetWindow(period: BudgetPeriod, day: Long): BudgetWindow = when (period) {
    BudgetPeriod.WEEK -> BudgetWindow(period, weekStart(day), weekEnd(day))
    BudgetPeriod.MONTH -> BudgetWindow(period, jalaliMonthStart(day), jalaliMonthEnd(day))
    BudgetPeriod.QUARTER -> BudgetWindow(period, jalaliQuarterStart(day), jalaliQuarterEnd(day))
}

// ─────────────────────────── where it stands ───────────────────────────

/**
 * The two shares of the cap at which a budget stops being silent, as whole percents.
 *
 * Eighty is early enough to change the rest of the month and late enough not to be noise; the
 * second one is nearly the end, and the sentence it carries is about what is left rather than
 * about what is gone. Past the cap is the third thing it says, and it is not a threshold — it is
 * a different fact.
 */
const val BUDGET_NEAR_PERCENT = 80
const val BUDGET_CLOSE_PERCENT = 95

/**
 * How loud one budget currently is. Ordered, so «has this crossed a line nobody mentioned yet»
 * is a comparison rather than a table — see [budgetNews].
 */
object BudgetLevel {
    const val OK = 0
    const val NEAR = 1
    const val CLOSE = 2
    const val OVER = 3
}

/**
 * Which of the four [BudgetLevel]s a spend against a cap is.
 *
 * Integer arithmetic on Rial, never a Double: at exactly 80% of the cap a float comparison is a
 * coin toss on the last bit, and the whole point of the threshold is that she is told once, at a
 * boundary she could work out herself.
 *
 * Landing exactly on the cap is [BudgetLevel.CLOSE] and not [BudgetLevel.OVER] — spending exactly
 * what she set aside is the budget being met, which is the same reading `goalProgress` has always
 * had for a cap. The card says «چیزی نمونده» there; only the next Toman is over.
 */
fun budgetLevel(spentRial: Long, capRial: Long): Int = when {
    capRial <= 0L -> if (spentRial > 0L) BudgetLevel.OVER else BudgetLevel.OK
    spentRial > capRial -> BudgetLevel.OVER
    spentRial * 100L >= capRial * BUDGET_CLOSE_PERCENT -> BudgetLevel.CLOSE
    spentRial * 100L >= capRial * BUDGET_NEAR_PERCENT -> BudgetLevel.NEAR
    else -> BudgetLevel.OK
}

/**
 * What one category cost inside one window, by the same rules `periodReport` counts spending by.
 *
 * Only the negative side, which is what that report does when it builds `spendingByCategory`: money
 * arriving under a spending category is a refund and lands on the income side there. Netting it off
 * here would give «رستوران و کافه» one value on the budget card and another in دخل و خرج, and there
 * is no version of that she should have to reconcile. Duplicates and transfer legs fall out through
 * [spendable], as they do everywhere else.
 *
 * The one deliberate divergence: money that only passes through — قرض, همسر — is counted here even
 * though دخل و خرج holds it apart by default. That gate is a way of *reading a report*, and a cap on
 * a category is her saying in as many words that she wants that category measured. A budget on همسر
 * that always read zero would be worse than no budget at all.
 */
fun budgetSpent(entries: List<LedgerEntry>, categoryId: String?, window: BudgetWindow): Long =
    spendable(entries)
        .filter { it.txn.day in window && it.categoryId == categoryId }
        .sumOf { -(it.txn.signedRial ?: 0L).coerceAtMost(0L) }

/**
 * Where a budget stands, worked out from the transactions and nothing else.
 *
 * Nothing here is stored. A stored figure is one that can drift away from the ledger underneath
 * it, and then the app is warning her about a number it made up — the same reason
 * [GoalProgress] is computed, and the reason recategorising one receipt moves this card the
 * instant she does it.
 */
data class BudgetProgress(
    val goal: Goal,
    val window: BudgetWindow,
    /** What she chose. Whole Rial, like every figure in the ledger. */
    val capRial: Long,
    val spentRial: Long,
    /** What is left of the cap, floored at zero. Zero exactly when [overRial] is not. */
    val leftRial: Long,
    /** What was spent past the cap, or zero. */
    val overRial: Long,
    /** 0..1, clamped, for the bar. Being over is [overRial]'s job — a bar cannot overflow. */
    val share: Float,
    /** Whole percent of the cap that is gone. Unclamped: «۱۳۰٪» is a thing she needs to see. */
    val percent: Int,
    val level: Int,
    /**
     * Days of the window still to come, today included, and therefore **never zero**.
     *
     * The window is derived from the same `today` this is measured against, so today is always
     * inside it and there is always at least today left. That invariant is why nothing below has to
     * phrase «۰ روز مونده», which is a sentence with no meaning.
     */
    val daysLeft: Int,
    /**
     * What is left divided by the days that are left — «روزی ۲ میلیون» — or null when there is
     * nothing left to divide, or no day left to spend it on.
     */
    val perDayRial: Long?,
    /**
     * True when more of the cap is gone than of the window.
     *
     * The one comparison that turns a budget from a scoreboard into something she can act on: 70%
     * of the cap on the 10th of the month is a different situation from 70% on the 25th, and the
     * bare share cannot tell them apart. A fact about two ratios, stated as one.
     */
    val outpacing: Boolean,
    /** The live category name, or the one snapshotted when she set the budget. Never blank. */
    val categoryFa: String,
    /**
     * True when she set this budget part-way into the window it is currently reporting on.
     *
     * The window is the whole month either way — anything else would disagree with the report —
     * so the card owes her the sentence that says the days before she decided are in the figure.
     */
    val partWindow: Boolean,
) {
    val period: BudgetPeriod get() = window.period
    val over: Boolean get() = level == BudgetLevel.OVER

    /** Whether the budget has anything left to say beyond its own bar. */
    val loud: Boolean get() = level >= BudgetLevel.NEAR
}

/**
 * One budget, against the window it is in right now.
 *
 * [today] is passed rather than read off a clock so that the same ledger reads the same way in a
 * test as on the phone — and so that the worker that posts a notification at 3am and the screen
 * she opens at 9am cannot disagree about which month it is.
 */
fun budgetProgress(
    goal: Goal,
    entries: List<LedgerEntry>,
    today: Long,
    categoryFa: String = goal.nameFa,
): BudgetProgress {
    val window = budgetWindow(BudgetPeriod.of(goal.period), today)
    val cap = goal.targetRial
    val spent = budgetSpent(entries, goal.categoryId, window)
    val left = (cap - spent).coerceAtLeast(0L)
    val over = (spent - cap).coerceAtLeast(0L)
    val daysLeft = window.daysLeft(today)
    return BudgetProgress(
        goal = goal,
        window = window,
        capRial = cap,
        spentRial = spent,
        leftRial = left,
        overRial = over,
        share = if (cap > 0L) (spent.toDouble() / cap).coerceIn(0.0, 1.0).toFloat() else 1f,
        percent = if (cap > 0L) (spent * 100L / cap).toInt() else if (spent > 0L) 100 else 0,
        level = budgetLevel(spent, cap),
        daysLeft = daysLeft,
        perDayRial = (left / daysLeft).takeIf { daysLeft > 0 && left > 0L },
        // Long arithmetic on both sides rather than two shares as Doubles: `days` is at most 92
        // and a cap at most a few trillion Rial, so neither product can overflow, and there is no
        // rounding to argue with at the moment the two ratios are equal.
        outpacing = cap > 0L && spent * window.days > cap * window.daysGone(today),
        categoryFa = categoryFa.ifBlank { goal.nameFa },
        partWindow = goal.startsOn > window.startDay,
    )
}

/**
 * Every budget she keeps, the ones closest to trouble first.
 *
 * Ordered by how much of the cap is gone rather than by when she made them, because the whole
 * reason to open this screen is «چقدر جا دارم؟» and the answer she needs is the one at the top.
 * A stable secondary sort on the id keeps two budgets at identical shares from swapping places
 * between recompositions.
 */
fun budgetsOf(
    goals: List<Goal>,
    entries: List<LedgerEntry>,
    today: Long,
    names: Map<String, String> = emptyMap(),
): List<BudgetProgress> =
    goals.filter { it.kind == GoalKind.CAP }
        .map { budgetProgress(it, entries, today, names[it.categoryId] ?: it.nameFa) }
        .sortedWith(compareByDescending<BudgetProgress> { it.percent }.thenBy { it.goal.id })

// ─────────────────────────── saying it once ───────────────────────────

/**
 * What this device has already said about one budget, and for which window.
 *
 * Kept in [Store] rather than in `durable.db`, and that placement is the decision: this is not
 * something she decided and not something a parser computed — it is a note about a notification
 * *this phone* posted. Her husband's phone has its own, and neither owes the other one.
 *
 * [windowStart] is what makes it expire on its own. A new month is a new key, so nothing has to
 * remember to reset anything on the first of the month — the mark for مرداد simply stops matching
 * once شهریور starts, and [budgetNews] drops it.
 */
@Serializable
data class BudgetMark(val goalId: String, val windowStart: Long, val level: Int)

/**
 * What is worth interrupting her for, and what to remember having said.
 *
 * [alerts] is every budget that has crossed a line higher than anything mentioned for its current
 * window. [marks] is what to store afterwards: one per live budget, at the highest level it has
 * ever reached in this window, and nothing else — which is also how the list is pruned, since a
 * budget she deleted and a window that has ended both simply fail to produce one.
 *
 * The level is remembered as a **high-water mark**, never lowered. Recategorising one receipt can
 * drop a budget from 82% back to 60%, and re-crossing 80% a week later must not say the same thing
 * a second time: she was told about مرداد, and مرداد is one conversation.
 */
data class BudgetNews(val alerts: List<BudgetProgress>, val marks: List<BudgetMark>)

fun budgetNews(budgets: List<BudgetProgress>, said: List<BudgetMark>): BudgetNews {
    val alerts = mutableListOf<BudgetProgress>()
    val marks = mutableListOf<BudgetMark>()
    for (budget in budgets) {
        val start = budget.window.startDay
        val before = said
            .firstOrNull { it.goalId == budget.goal.id && it.windowStart == start }
            ?.level
            ?: BudgetLevel.OK
        if (budget.level > before && budget.level >= BudgetLevel.NEAR) alerts += budget
        marks += BudgetMark(budget.goal.id, start, maxOf(before, budget.level))
    }
    return BudgetNews(alerts, marks)
}

// ─────────────────────────── how it is said ───────────────────────────

/**
 * The one line a notification leads with: which category, and what has happened to its budget.
 *
 * The real percent, not the threshold it crossed. She is being told about her own money and «۸۳٪»
 * is what is true; «۸۰٪» would be the app quoting its own rule back at her.
 */
fun budgetAlertTitle(budget: BudgetProgress): String = when {
    budget.over -> "${budget.categoryFa}: از بودجهٔ ${budget.window.fa} گذشتی"
    else -> "${budget.categoryFa}: ${faNumber(budget.percent.toDouble())}٪ بودجهٔ ${budget.window.fa} رفته"
}

/**
 * The line under it: what is left, or how far past, and how long the window still has to run.
 *
 * Both halves are needed and neither is enough. «۸ میلیون مونده» without the days is a number
 * with nothing to measure it against, and the days without the figure are a calendar.
 */
fun budgetAlertBody(budget: BudgetProgress): String {
    val head = when {
        budget.over ->
            "${faCompact(tomanOf(budget.overRial))} بیشتر از ${faCompact(tomanOf(budget.capRial))} تومان"
        budget.leftRial <= 0L -> "چیزی از بودجه نمونده"
        else ->
            "${faCompact(tomanOf(budget.leftRial))} از ${faCompact(tomanOf(budget.capRial))} تومان مونده"
    }
    return "$head • ${budgetDaysLeftFa(budget)}"
}

/**
 * The one budget worth a line on the home screen, if any is.
 *
 * At most one, and only from [BudgetLevel.CLOSE] up. Home is the glance surface with room for
 * exactly one thing asking for her; a budget at 82% is worth knowing when she opens بودجه and is not
 * worth being met with on the front page. The worst one wins, by share rather than by amount,
 * because a cap is a decision about a proportion.
 *
 * It outranks the review backlog for that slot on purpose — «۱۲ تراکنش منتظر توئه» is homework, and
 * a category that has run past what she set aside is money. Nothing is lost by the demotion: the
 * backlog is also the badge on the دفتر tab and the pill at the top of the ledger.
 *
 * One function for the choice, used by both the sentence and the card's destination, so those two
 * cannot end up describing different budgets.
 */
fun pressingBudget(budgets: List<BudgetProgress>): BudgetProgress? =
    budgets.filter { it.level >= BudgetLevel.CLOSE }.maxByOrNull { it.percent }

/**
 * That budget as a line for home, carrying its own evidence like every other [Insight]: the rows
 * behind the figure, in the window that produced it.
 */
fun budgetInsight(budget: BudgetProgress, entries: List<LedgerEntry>): Insight = Insight(
    text = if (budget.over) {
        "از بودجهٔ «${budget.categoryFa}» گذشتی."
    } else {
        "${faNumber(budget.percent.toDouble())}٪ بودجهٔ «${budget.categoryFa}» رفته."
    },
    why = "سقفی که خودت برای این دسته گذاشتی، در برابر خرج ${budget.window.fa}.",
    refs = spendable(entries)
        .filter { it.txn.day in budget.window && it.categoryId == budget.goal.categoryId }
        .map { it.txn.ref },
    tone = Insight.Tone.ATTENTION,
)

/**
 * «۱۲ روز تا آخر ماه», and on the last day the thing that is actually worth knowing.
 *
 * `<= 1` rather than `== 1`: one is the floor by construction — see [BudgetProgress.daysLeft] — and
 * the comparison says so rather than leaving a zero to fall through into «۰ روز تا آخر ماه».
 */
fun budgetDaysLeftFa(budget: BudgetProgress): String =
    if (budget.daysLeft <= 1) "امروز آخرین روزه"
    else "${faNumber(budget.daysLeft.toDouble())} روز تا آخر ${budget.period.fa}"

/**
 * The sentence on the card, under the bar — the one place a budget is allowed more than a figure.
 *
 * Ordered by what she can still do about it. Being over is the only fact that outranks the
 * calendar; after that comes running ahead of the window, which is the only line here that is
 * about a rate rather than an amount, and it is only offered while there is still a window left
 * to change. Everything else is «this much, this long», and a budget comfortably on pace says
 * nothing at all beyond its own bar.
 */
fun budgetNoteFa(budget: BudgetProgress): String = when {
    budget.over ->
        "${faCompact(tomanOf(budget.overRial))} بیشتر از بودجه‌ات خرج شده."
    budget.leftRial <= 0L -> "بودجهٔ ${budget.window.fa} تموم شد."
    budget.daysLeft <= 1 -> budgetDaysLeftFa(budget) + "."
    budget.outpacing && budget.perDayRial != null ->
        "تا آخر ${budget.period.fa} روزی ${faCompact(tomanOf(budget.perDayRial))} تومان جا داری."
    budget.level >= BudgetLevel.NEAR && budget.perDayRial != null ->
        "${faCompact(tomanOf(budget.leftRial))} تومان مونده، روزی ${faCompact(tomanOf(budget.perDayRial))} تومان."
    else -> ""
}
