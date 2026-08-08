package com.doxigo.muchtoman

/**
 * A household's year, invented — the only money in this app that nobody earned.
 *
 * It exists because the reports cannot be *looked at* until the ledger has months in them. «۶ ماه»
 * and «۱ سال» on دخل و خرج are dimmed until the ledger reaches back that far, which is right on a
 * phone and useless on a bench: the way to see the year-long window before shipping it was to
 * hand-write rows into `durable.db` over adb, one `sqlite3` line at a time, and that is a test
 * nobody runs twice.
 *
 * Three rules keep it from ever being mistaken for hers:
 *
 *  - **Only the `dev` build offers it.** `BuildConfig.DEMO` is a constant, so R8 removes all of
 *    this from the release APK rather than shipping a switched-off way to fabricate transactions.
 *  - **Every row is marked.** Ids start with [DEMO_PREFIX], which is what makes clearing them
 *    exact — it takes what this file wrote and nothing typed in beside it.
 *  - **It goes in through the front door.** These are ordinary [ManualTxn] rows with ordinary
 *    [TxnDecision] filings on top, so they parse, classify, link, report and sync exactly as a
 *    transaction typed in by hand does. Nothing downstream has a demo branch, which is the only
 *    way the thing being looked at is the thing that ships.
 *
 * Deterministic, seed and all: the same [today] gives the same ledger, so a screenshot can be
 * compared with yesterday's and a figure that looks wrong can be found again.
 */

/** What marks a row as invented. The ledger reference is `m:demo-…` — see [manualRef]. */
const val DEMO_PREFIX = "demo-"

/** How far back the demo ledger reaches: a year, plus the two months a year is read against. */
const val DEMO_MONTHS = 14

/** The transactions, and how each one is filed. Written together or not at all. */
data class DemoLedger(val transactions: List<ManualTxn>, val filings: List<TxnDecision>)

/**
 * A tiny, deliberately boring pseudo-random source.
 *
 * `kotlin.random.Random` would do, but this is seeded off the day rather than the clock and its
 * sequence must not change under a Kotlin upgrade — a demo ledger that reshuffles itself between
 * builds makes every before-and-after screenshot a comparison of two different households.
 */
private class Dice(private var state: Long) {
    private fun next(): Long {
        state = state * 6364136223846793005L + 1442695040888963407L
        // Unsigned shift, so the value is never negative and the caller needs no absolute value.
        return state ushr 16
    }

    /** Inclusive at both ends, which is how every range below reads. */
    fun int(from: Int, to: Int): Int = from + (next() % (to - from + 1)).toInt()

    fun long(from: Long, to: Long): Long = from + next() % (to - from + 1)

    fun <T> pick(items: List<T>): T = items[(next() % items.size).toInt()]

    /** True [percent] times in a hundred. */
    fun chance(percent: Int): Boolean = int(1, 100) <= percent
}

/**
 * One line of the household's month: what it is called, who it is paid to, how often, how much.
 *
 * Amounts are in **Toman**, because that is the unit the numbers were chosen in and a range
 * written in Rial is a range nobody can sanity-check by eye.
 */
private class Line(
    val categoryId: String,
    val merchants: List<String>,
    val times: IntRange,
    val toman: LongRange,
)

/**
 * What goes out in an ordinary month, roughly in the order a household pays it.
 *
 * The figures are a Tehran household living on a salary rather than an interesting one: rent and
 * the قسط take most of it, خواربار is the biggest thing she actually decides, and the tail is
 * small and frequent. They add up to a little under what comes in, so most months keep something
 * and the ones with a repair or a doctor in them do not — which is the only reason the report has
 * anything to say. See the totals asserted in DemoTest.
 */
private val OUTGOINGS = listOf(
    Line("cat_home", listOf("اجاره خانه"), 1..1, 11_000_000L..13_000_000L),
    Line("cat_instalment", listOf("قسط وام مسکن"), 1..1, 3_200_000L..3_600_000L),
    Line("cat_bills", listOf("قبض برق", "قبض گاز", "قبض آب", "شارژ ساختمان"), 1..3, 150_000L..900_000L),
    Line("cat_internet", listOf("ایرانسل", "همراه اول", "شاتل"), 1..1, 250_000L..500_000L),
    Line(
        "cat_groceries",
        listOf("هایپراستار", "افق کوروش", "جانبو", "میوه‌فروشی محله", "نانوایی", "قصابی"),
        5..8,
        400_000L..1_600_000L,
    ),
    Line("cat_dining", listOf("کافه نادری", "رستوران شاندیز", "اسنپ‌فود", "فست‌فود سیب"), 2..5, 150_000L..900_000L),
    Line("cat_transport", listOf("اسنپ", "تپسی", "مترو", "اتوبوس"), 3..7, 50_000L..250_000L),
    Line("cat_shopping", listOf("دیجی‌کالا", "بازار روز", "لوازم خانگی"), 1..3, 200_000L..1_200_000L),
    Line("cat_atina", listOf("مهدکودک آتینا", "اسباب‌بازی‌فروشی", "کتاب کودک"), 1..3, 200_000L..1_200_000L),
    Line("cat_car", listOf("پمپ بنزین", "تعمیرگاه", "بیمه ایران", "پارکینگ"), 0..2, 300_000L..2_500_000L),
    Line("cat_health", listOf("داروخانه هلال", "مطب دکتر مهدوی", "آزمایشگاه پارس"), 0..2, 200_000L..3_000_000L),
    Line("cat_clothing", listOf("پوشاک ال‌سی", "کفش ملی"), 0..1, 500_000L..4_000_000L),
    Line("cat_beauty", listOf("آرایشگاه", "لوازم آرایشی"), 0..1, 200_000L..1_200_000L),
    Line("cat_culture", listOf("سینما کورش", "کتاب‌فروشی ققنوس"), 0..1, 100_000L..600_000L),
    Line("cat_gifts", listOf("هدیه تولد", "کمک به خیریه"), 0..1, 200_000L..2_000_000L),
    Line("cat_spouse", listOf("کارت به کارت به همسر"), 0..1, 1_000_000L..4_000_000L),
    Line("cat_savings", listOf("خرید طلا", "صندوق سرمایه‌گذاری"), 0..1, 2_000_000L..6_000_000L),
)

/** What comes in besides the salary — none of it every month. */
private val WINDFALLS = listOf(
    Line("cat_sales", listOf("فروش در دیوار"), 0..1, 1_000_000L..8_000_000L),
    Line("cat_loan_back", listOf("پس‌گرفتن قرض"), 0..1, 500_000L..4_000_000L),
)

/** Iranian prices do not stand still, and a year of flat figures is the one thing this cannot be. */
private const val MONTHLY_INFLATION = 1.022

/**
 * The invented ledger, ending on [today].
 *
 * The current month is written only as far as [today]: a demo that fills مرداد to its last day
 * while the phone says it is the eighth would put the report's «تا امروز» over a finished month
 * and make every partial-month figure on the screen wrong in the same direction.
 */
fun demoLedger(today: Long, now: Long, months: Int = DEMO_MONTHS): DemoLedger {
    val here = reportMonthOf(today)
    val dice = Dice(here.startDay * 1_000_003L + months)
    val rows = mutableListOf<ManualTxn>()
    val filings = mutableListOf<TxnDecision>()
    var n = 0

    /** One transaction, filed where [categoryId] says — or left for her when it is null. */
    fun add(day: Long, toman: Long, categoryId: String?, merchant: String) {
        n++
        val id = "$DEMO_PREFIX%03d".format(n)
        // Spread across the waking day, and never two at the same minute: a shared timestamp is
        // what the duplicate detector looks at, and an invented pair of identical taxi fares
        // would arrive on her deck as a question about money that does not exist.
        // Never later than the moment it was invented, or today's rows carry a time that has not
        // happened yet; still a second apart from each other, so no two share a timestamp.
        val at = minOf(
            tehranDayStart(day) + (8 + n % 13) * 3_600_000L + (n * 7 % 60) * 60_000L,
            now - n * 1_000L,
        )
        rows += ManualTxn(
            id = id,
            at = at,
            day = day,
            amountRial = toman * 10,
            accountId = null,
            categoryId = categoryId,
            merchant = merchant,
            note = "",
            createdAt = now,
            updatedAt = now,
        )
        // The filing is a decision on the reference, exactly as tapping a category in the deck
        // writes one. Without it the row would be classified by the shipped rules instead, which
        // for a manual outflow means «دسته‌بندی نشده» — see [classify].
        categoryId?.let {
            filings += TxnDecision(
                id = "$DEMO_PREFIX${manualRef(id)}",
                ref = manualRef(id),
                kind = DecisionKind.CATEGORY,
                value = it,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    for (back in months - 1 downTo 0) {
        val month = here.back(back)
        val length = jalaliMonthLength(month.year, month.month)
        // The month she is standing in stops at today; every other one runs its full length.
        val last = if (back == 0) (today - month.startDay + 1).toInt().coerceIn(1, length) else length
        // Prices in the oldest month, walked forward: the newest month is roughly a third dearer
        // than the first, which is what the year-long chart is there to show.
        val drift = Math.pow(MONTHLY_INFLATION, (months - 1 - back).toDouble())
        fun scaled(range: LongRange): Long = (dice.long(range.first, range.last) * drift).toLong()
        fun day(from: Int, to: Int): Long = month.startDay + dice.int(from, to).coerceAtMost(last) - 1

        // Salary lands first, because everything below is spent out of it.
        add(day(1, 3), (dice.long(36_000_000L, 41_000_000L) * drift).toLong(), "cat_salary", "واریز حقوق")
        // عیدی, in the month it actually arrives.
        if (month.month == 12) {
            add(day(20, 27), (dice.long(30_000_000L, 45_000_000L) * drift).toLong(), "cat_bonus", "عیدی")
        }
        for (line in WINDFALLS) {
            repeat(dice.int(line.times.first, line.times.last)) {
                add(day(5, 26), scaled(line.toman), line.categoryId, dice.pick(line.merchants))
            }
        }

        for (line in OUTGOINGS) {
            repeat(dice.int(line.times.first, line.times.last)) {
                // One in fourteen is left unfiled, which is what the review deck and the badge on
                // the ledger tab are for. Only ever an outflow: an unfiled *incoming* row is
                // caught by `rule_income` and filed anyway, so it would prove nothing.
                val unfiled = dice.chance(7)
                add(
                    day(2, length),
                    -scaled(line.toman),
                    if (unfiled) null else line.categoryId,
                    dice.pick(line.merchants),
                )
            }
        }

        // Her own money moving between her own accounts, both legs, every other month. It counts
        // as neither income nor spending, and a demo ledger with nothing to leave out cannot
        // show that the report leaves it out.
        if (back % 2 == 0) {
            val moved = (dice.long(10_000_000L, 30_000_000L) * drift).toLong()
            val on = day(12, 20)
            add(on, -moved, CAT_TRANSFER, "انتقال به حساب پس‌انداز")
            add(on, moved, CAT_TRANSFER, "انتقال از حساب جاری")
        }
    }
    return DemoLedger(rows.sortedBy { it.at }, filings)
}

/**
 * Bank balances to sit under the invented ledger.
 *
 * Anchored, because [bankTotal] deliberately ignores an account no bank has ever stated a figure
 * for — and without a figure there is no «پول نقدت برای … روز می‌رسه» on the report, which is one
 * of the lines worth looking at. Marked by [sender] so clearing takes exactly these two back out
 * and leaves anything her messages built.
 */
fun demoBankAccounts(now: Long): List<BankAccount> = listOf(
    BankAccount(
        bank = Bank.SAMAN.name,
        balance = 84_500_000.0,
        updatedAt = now,
        anchored = true,
        sender = DEMO_PREFIX,
    ),
    BankAccount(
        bank = Bank.MELLAT.name,
        balance = 23_900_000.0,
        updatedAt = now,
        anchored = true,
        sender = DEMO_PREFIX,
    ),
)

/** Holdings to give گزارش دارایی something to be a report about. Marked in [Holding.id]. */
fun demoHoldings(): List<Holding> = listOf(
    Holding(typeId = "gold18", amount = 38.5, id = "${DEMO_PREFIX}gold18"),
    Holding(typeId = "coin_emami", amount = 4.0, id = "${DEMO_PREFIX}coin_emami"),
    Holding(typeId = "usd", amount = 2_400.0, id = "${DEMO_PREFIX}usd"),
    Holding(typeId = TOMAN_ID, amount = 12_000_000.0, id = "${DEMO_PREFIX}toman"),
)

/**
 * A year and a bit of daily totals, ending at [total] today.
 *
 * Built backwards from what she is worth now rather than forwards from a guess, so the chart's
 * last point is the real one the app would have recorded anyway and only the past is invented.
 * The walk drifts down into the past at roughly the rate the prices above drift up, with a
 * day-to-day wobble — a chart that is a straight line is not a chart anybody would notice a bug in.
 */
fun demoHistory(now: Long, total: Double, days: Int = HISTORY_KEEP_DAYS - 5): Map<Long, Double> {
    if (total <= 0) return emptyMap()
    val dice = Dice(now / DAY_MS * 7_919L)
    val out = sortedMapOf<Long, Double>()
    var value = total
    // The UTC epoch day [recordDay] keys by, worked out exactly as it works it out — deliberately
    // not the Tehran day the ledger uses. See [tehranDay].
    val end = now / DAY_MS
    for (i in 0 until days) {
        out[end - i] = value
        // Backwards, so this is the *reverse* of a day's growth: a little less each step, plus
        // noise that is allowed to go either way.
        value = (value / 1.0018) * (1 + dice.int(-4, 4) / 1000.0)
    }
    return out
}
