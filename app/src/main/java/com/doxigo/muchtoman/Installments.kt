package com.doxigo.muchtoman

/**
 * Installments — «قسط», the monthly payment on a phone, a fridge or a loan.
 *
 * A plan is one more row of the `goal` table, as a budget is: `kind = installment`, one payment in
 * [Goal.targetRial], the first due day in [Goal.startsOn] and the last in [Goal.endsOn], one Jalali
 * month apart. No table and no migration, for the reason `Budget.kt` gives — the columns were
 * already the right ones, and the count is simply the months between the two ends.
 *
 * A payment is not a figure she types here. It is a transaction the ledger already holds — the
 * bank's message, or a row she entered by hand for cash — and a [DecisionKind.INSTALLMENT] decision
 * on it says which plan it paid. Typing the amount in here as well would count the same money
 * twice: once as spending in دخل و خرج and once as a payment, with nothing to say which was real.
 *
 * Always private: [Goal.shared] is false on these rows, so the sync never publishes them, and the
 * link decisions are a kind the sync never reads.
 *
 * ponytail: private only. A household plan needs its links to travel with it, which is a new sync
 * record kind and so a server deploy before any client; add it when two people pay one plan.
 * Editing a plan is delete-and-add for the same reason budgets keep their category — a plan with a
 * different amount or length is a different plan — until somebody's loan actually changes terms.
 */

/** Ten years of monthly payments. Past that it is a mortgage, and a typo is likelier. */
const val MAX_INSTALLMENTS = 120

/** How many possible payments the sheet offers at once. The exact-amount ones come first. */
private const val INSTALLMENT_CANDIDATES = 20

/**
 * What a [DecisionKind.INSTALLMENT] decision's value holds: the plan it paid, and how much.
 *
 * The amount is copied onto the link when she makes it, and progress is summed from these rather
 * than from the transactions, because the ledger forgets: sources older than
 * [SOURCE_HORIZON_MONTHS] are pruned, and a two-year loan counted off live rows would start reading
 * as overdue in its fourteenth month, for payments the app itself watched her make.
 *
 * ponytail: the copy's ceiling is that a parser fix which changes a linked row's amount does not
 * reach the link. Unlinking and linking again refreshes it.
 */
data class InstallmentLink(val planId: String, val rial: Long) {
    /** `<planId>:<rial>`. A plan id is a [uuid7], which has no colon in it. */
    fun encode(): String = "$planId:$rial"

    companion object {
        /** Null for anything this build did not write, so one bad row cannot poison a total. */
        fun decode(value: String?): InstallmentLink? {
            val raw = value ?: return null
            val planId = raw.substringBeforeLast(':', "")
            val rial = raw.substringAfterLast(':').toLongOrNull()
            if (planId.isEmpty() || rial == null || rial <= 0L || rial > MAX_PLAUSIBLE_RIAL) return null
            return InstallmentLink(planId, rial)
        }
    }
}

/**
 * Every live link, by the transaction it is on.
 *
 * Only links to a plan that still exists: a deleted plan leaves its decisions behind, and a
 * transaction that paid something she has since deleted is free to be linked again.
 */
fun installmentLinks(decisions: List<TxnDecision>, plans: List<Goal>): Map<String, InstallmentLink> {
    val live = plans.filter { it.kind == GoalKind.INSTALLMENT && !it.deleted }.mapTo(HashSet()) { it.id }
    return decisions
        .filter { it.kind == DecisionKind.INSTALLMENT && !it.deleted }
        .mapNotNull { d -> InstallmentLink.decode(d.value)?.takeIf { it.planId in live }?.let { d.ref to it } }
        .toMap()
}

/**
 * A new plan, or null when the answers are not one.
 *
 * [count] is what is **left** to pay, and the first due is the next [dayOfMonth] from today, today
 * included — so a plan already half paid is entered as its remainder, with nothing to reconstruct.
 *
 * [firstDue] overrides that, for a plan made from a payment she has just filed: the payment is the
 * first installment, so the plan starts on its day — past or not — and [count] includes it.
 */
fun newInstallment(
    id: String,
    nameFa: String,
    paymentRial: Long,
    count: Int,
    dayOfMonth: Int,
    now: Long,
    mineId: String = "",
    firstDue: Long? = null,
): Goal? {
    val name = nameFa.trim().take(40)
    if (name.isEmpty() || paymentRial <= 0L || paymentRial > MAX_PLAUSIBLE_RIAL) return null
    if (count !in 1..MAX_INSTALLMENTS || dayOfMonth !in 1..31) return null
    val first = firstDue ?: firstInstallmentDue(dayOfMonth, tehranDay(now))
    return Goal(
        id = id,
        nameFa = name,
        targetRial = paymentRial,
        kind = GoalKind.INSTALLMENT,
        period = GoalPeriod.MONTH,
        startsOn = first,
        endsOn = jalaliMonthsAfter(first, count - 1),
        createdAt = now,
        updatedAt = now,
        ownerMemberId = mineId,
        editedByMemberId = mineId,
    )
}

/**
 * The next [dayOfMonth] on or after [today], clamped to the month's length.
 *
 * ponytail: the day is kept by the first due date and nowhere else, so a «۳۱ام» whose first due
 * lands in a thirty-day month is the 30th from then on — one day early, never late, in the six
 * months that have a 31st. Keeping the day she typed needs a column; add one if a lender minds.
 */
fun firstInstallmentDue(dayOfMonth: Int, today: Long): Long {
    val t = jalaliOf(today)
    val thisMonth = jalaliDay(t.year, t.month, minOf(dayOfMonth, jalaliMonthLength(t.year, t.month)))
    if (thisMonth >= today) return thisMonth
    val next = jalaliOf(jalaliMonthsAheadEnd(today, 1))
    return jalaliDay(next.year, next.month, minOf(dayOfMonth, next.day))
}

/**
 * Whether this row could be a payment of one of her plans: money out, of a known amount, hers, and
 * neither a duplicate nor a move between her own accounts — [spendable] under [scopedTo]'s private
 * rule. One test for both directions of the link: the rows a plan's sheet offers, and the rows whose
 * own page offers a plan, so the two can never disagree about what counts.
 */
fun installmentPayable(entry: LedgerEntry, mineId: String): Boolean =
    entry.txn.direction == "out" && entry.txn.amountRial != null && !entry.duplicate &&
        !entry.transfer && entry.ownerMemberId == mineId

/** The plan this transaction paid, if she linked it to one the ledger still shows. */
fun installmentPaidBy(ref: String, installments: List<InstallmentProgress>): InstallmentProgress? =
    installments.firstOrNull { plan -> plan.payments.any { it.txn.ref == ref } }

/** How many payments the plan has — the months from its first due to its last, both counted. */
fun installmentCount(plan: Goal): Int {
    val first = jalaliOf(plan.startsOn)
    val last = jalaliOf(plan.endsOn ?: plan.startsOn)
    return ((last.year - first.year) * 12 + (last.month - first.month) + 1).coerceIn(1, MAX_INSTALLMENTS)
}

/** The day payment [index] (from 0) falls due. */
fun installmentDueOn(plan: Goal, index: Int): Long = jalaliMonthsAfter(plan.startsOn, index)

/** Where one plan stands, worked out from her links and nothing else. */
data class InstallmentProgress(
    val plan: Goal,
    val count: Int,
    val totalRial: Long,
    /** What her links add up to, never past [totalRial]: overpaying one plan pays no other. */
    val paidRial: Long,
    /** Payments covered in full — the «۳» of «۳ از ۱۲». */
    val paidCount: Int,
    /**
     * What fell due **before** today and has not been paid. Zero when she is ahead or on time — and
     * on a due day itself, which is still a day she can pay it on.
     */
    val overdueRial: Long,
    /** When the first payment not yet covered falls due. Null once the plan is paid off. */
    val nextDue: Long?,
    val done: Boolean,
    /** 0..1. */
    val share: Float,
    /** Her linked transactions the ledger still holds, newest first. */
    val payments: List<LedgerEntry>,
    /**
     * What links to transactions the ledger has since forgotten still count for. Said on the sheet,
     * or the list above it would not add up to the figure on the card.
     */
    val olderRial: Long,
    /**
     * Her own outgoing rows that could be a payment of this plan, the ones of exactly its amount
     * first and then the newest. Empty once the plan is paid off.
     */
    val candidates: List<LedgerEntry>,
)

fun installmentProgress(
    plan: Goal,
    links: Map<String, InstallmentLink>,
    entries: List<LedgerEntry>,
    today: Long,
    /** This phone's member id — a payment she can link is one of her own rows. See [scopedTo]. */
    mineId: String = "",
): InstallmentProgress {
    val count = installmentCount(plan)
    // Cannot overflow: a payment is at most MAX_PLAUSIBLE_RIAL and there are at most 120 of them.
    val total = plan.targetRial * count
    val mine = links.filterValues { it.planId == plan.id }
    // Clamped as it goes, so a link too large for the plan reads as «paid off» and never wraps.
    var paid = 0L
    for (link in mine.values) paid = minOf(total, paid + link.rial)
    val done = paid >= total
    val paidCount = if (plan.targetRial > 0L) (paid / plan.targetRial).toInt() else 0
    val lateCount = (0 until count).count { installmentDueOn(plan, it) < today }
    val payments = entries.filter { it.txn.ref in mine }.sortedByDescending { it.txn.day }
    val held = payments.mapTo(HashSet()) { it.txn.ref }
    // A payment for the first installment is one made after the one before it would have fallen
    // due. Any earlier and it is last month's payment on a plan she entered as its remainder.
    val after = jalaliMonthsAfter(plan.startsOn, -1)
    return InstallmentProgress(
        plan = plan,
        count = count,
        totalRial = total,
        paidRial = paid,
        paidCount = paidCount,
        overdueRial = (plan.targetRial * lateCount - paid).coerceAtLeast(0L),
        nextDue = if (done) null else installmentDueOn(plan, paidCount),
        done = done,
        share = if (total > 0L) (paid.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f,
        payments = payments,
        olderRial = minOf(total, mine.filterKeys { it !in held }.values.sumOf { it.rial }),
        candidates = if (done) emptyList() else entries
            .filter { installmentPayable(it, mineId) && it.txn.ref !in links && it.txn.day > after }
            .sortedWith(
                compareBy<LedgerEntry> { it.txn.amountRial != plan.targetRial }
                    .thenByDescending { it.txn.day },
            )
            .take(INSTALLMENT_CANDIDATES),
    )
}

// ─────────────────────────── the reminder ───────────────────────────

/**
 * How many days before a due date the reminder comes — what تنظیمات offers, `-1` for never.
 *
 * On by default, a day ahead: the plan is one she made, as a budget is, and a budget speaks without
 * being asked to as well.
 */
val INSTALLMENT_REMINDER_DAYS = listOf(-1, 0, 1, 3)
const val INSTALLMENT_REMINDER_DEFAULT = 1

/**
 * The Tehran hours a reminder may be posted in. The watch sweeps every six hours round the clock,
 * and a reminder is the one note that is not answering something that just happened — ungated, it
 * would ring at whatever hour past midnight the day's first sweep landed. Thirteen hours is two
 * sweeps wide, so a day whose first chance is lost to Doze still has another.
 */
private val REMIND_HOURS = 9..21

/**
 * The next payment she has not covered that falls due today or later.
 *
 * Not [InstallmentProgress.nextDue], which stops at the first unpaid payment: a plan she pays but
 * never links would be reminded once, of a date already gone, and then never again. A reminder
 * follows the calendar, and only a payment linked ahead holds it back.
 */
fun installmentUpcoming(progress: InstallmentProgress, today: Long): Long? =
    (progress.paidCount until progress.count).asSequence()
        .map { installmentDueOn(progress.plan, it) }
        .firstOrNull { it >= today }

/**
 * Which plans are worth a reminder now, and what to remember having said: plan id → the due day it
 * was last reminded of.
 *
 * The mark is the date, so next month's payment is a new date and a new reminder with nothing to
 * reset, and a deleted plan simply stops producing one — [BudgetMark]'s arrangement.
 */
data class InstallmentNews(val due: List<Pair<InstallmentProgress, Long>>, val marks: Map<String, Long>)

fun installmentNews(
    installments: List<InstallmentProgress>,
    daysBefore: Int,
    said: Map<String, Long>,
    now: Long,
): InstallmentNews {
    val today = tehranDay(now)
    val awake = (now + TEHRAN_OFFSET_MS).mod(DAY_MS) / 3_600_000L in REMIND_HOURS
    val due = mutableListOf<Pair<InstallmentProgress, Long>>()
    val marks = HashMap<String, Long>()
    for (progress in installments) {
        val id = progress.plan.id
        val next = installmentUpcoming(progress, today)
        if (next != null && daysBefore >= 0 && awake && next - today <= daysBefore && said[id] != next) {
            due += progress to next
            marks[id] = next
        } else {
            said[id]?.let { marks[id] = it }
        }
    }
    return InstallmentNews(due, marks)
}

/** «گوشی: سررسید قسط فرداست» — the card's own «سررسید قسط بعدی امروزه», said ahead. */
fun installmentReminderTitle(plan: Goal, due: Long, today: Long): String {
    val whenFa = when (val days = due - today) {
        0L -> "امروزه"
        1L -> "فرداست"
        2L -> "پس‌فرداست"
        else -> "${faNumber(days.toDouble())} روز دیگه‌ست"
    }
    return "${plan.nameFa}: سررسید قسط $whenFa"
}

/** How much, and on which date — what she needs in the banking app. */
fun installmentReminderBody(plan: Goal, due: Long): String =
    "${faCompact(tomanOf(plan.targetRial))} تومان • ${faDate(due)}"

/** The setting's answer, on the تنظیمات row and in its sheet. */
fun installmentReminderFa(days: Int): String = when {
    days < 0 -> "خاموش"
    days == 0 -> "همون روز"
    else -> "${faNumber(days.toDouble())} روز قبل"
}
