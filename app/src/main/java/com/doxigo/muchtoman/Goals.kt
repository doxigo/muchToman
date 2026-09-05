package com.doxigo.muchtoman

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Goals, and the one place this app is willing to encourage anybody.
 *
 * Concrete, monitored goals are the encouragement with actual evidence behind them, which is
 * why they get a table and streaks do not. Progress is **computed, never stored**: a stored
 * figure is one that can drift away from the transactions underneath it, and then the app is
 * congratulating her on a number it made up.
 *
 * Two shapes, and no more:
 *  - `save` — put this much aside by then. This file.
 *  - `cap`  — keep spending under this much, per week, month or فصل. That is a budget, and it
 *    lives in `Budget.kt`, which is one row of this same table read a different way. A cap names
 *    a category, or names none and is then the roof over all of them — see [Goal.total].
 *
 * They share a table because they share everything that matters — a target in Rial, a period, a
 * start, an end, and progress that is never written down — and they are two files because the
 * arithmetic runs in opposite directions: a goal is met by reaching its figure and a budget by
 * not reaching its own. Keeping both in one `when` is how the sign of that comparison gets
 * flipped by somebody who is reading the other half.
 *
 * Either shape is hers or the household's, and that is [Goal.shared] — one flag deciding both
 * who sees the figure and whose spending counts against it, because those are not two questions.
 */

object GoalKind {
    const val SAVE = "save"
    const val CAP = "cap"
}

/**
 * The `period` column's vocabulary. Values, not an enum, because these strings are on disk —
 * [BudgetPeriod] is the enum that carries the behaviour, and it names these as its ids.
 */
object GoalPeriod {
    /** A budget kept Saturday to Friday. */
    const val WEEK = "week"

    /** A Jalali month, which is what her salary, her rent and her bills already run on. */
    const val MONTH = "jmonth"

    /** A فصل — three Jalali months, which in Iran is exactly a season. */
    const val QUARTER = "jquarter"

    /** A savings goal, which has no period at all: it runs from its start to its deadline. */
    const val ONCE = "once"
}

@Entity(tableName = "goal")
data class Goal(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name_fa") val nameFa: String,
    @ColumnInfo(name = "target_rial") val targetRial: Long,
    val kind: String,
    /**
     * For a cap, which category it holds down — or **null for the whole month's spending**.
     *
     * Null carries two meanings and they never meet, because they live under different [kind]s: a
     * savings goal has no category to name, and a cap with none is the roof over all of them. See
     * [Goal.total], which is the only way this file asks the question.
     */
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    val period: String,
    @ColumnInfo(name = "starts_on") val startsOn: Long,
    @ColumnInfo(name = "ends_on") val endsOn: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
    /**
     * Whose figure this is: hers alone, or the household's. One flag, and it decides **both**
     * things that could have been two switches — who can see it and whose spending counts.
     *
     * That is not a shortcut, it is the whole question. A cap that syncs to her partner's phone
     * but only measures her own receipts is a number the two of them read differently while
     * looking at the same card; a cap that stays on this phone but counts his café run is one she
     * can go over without spending a Toman. Neither is a state anybody asked for. So there is one
     * question — «مال خودم» or «خانوادگی» — and everything follows from the answer.
     *
     * False on every row that predates the column, which is the safe direction on an upgrade:
     * nothing that was private starts leaving the phone because a build shipped. On a phone with
     * no household it is also the *only* direction, and it changes nothing — see [scopedTo].
     */
    val shared: Boolean = false,
    /**
     * Who set it, kept for the sentence a shared card carries rather than for permission.
     *
     * Anybody in the household may move a shared figure — it is theirs together, and a budget one
     * of them cannot adjust is a budget they cannot keep. What this field buys is attribution:
     * «سقفی که مریم گذاشت» is a fact about a card that appeared on her phone without her doing
     * anything, and a card that arrives unattributed reads as the app having invented it.
     *
     * Blank on a private row, and on every row written before the column: there is nobody to name
     * when a figure has only ever been on one phone.
     */
    @ColumnInfo(name = "owner_member_id") val ownerMemberId: String = "",
    /**
     * Who last moved the figure, and the tie-break that makes two phones agree about it.
     *
     * The same device [categoryUpdateWins] uses on a filed receipt, for the same reason: two
     * people editing one budget in the same millisecond is rare and must still converge, so the
     * later stamp wins and the higher member id breaks the draw. A losing edit is a figure that
     * reverts on the next pull — the named ceiling, and cheaper than a CRDT for a number two
     * people change a handful of times a year.
     */
    @ColumnInfo(name = "edited_by_member_id") val editedByMemberId: String = "",
) {
    /**
     * True when this is a cap on everything rather than on one category — «سقف کل خرج».
     *
     * Asked here and nowhere else, so «a cap with no category» never has to be spelled out at a
     * call site where somebody could read it as a savings goal instead.
     */
    val total: Boolean get() = kind == GoalKind.CAP && categoryId == null
}

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: Goal)

    @Query("SELECT * FROM goal WHERE deleted = 0 ORDER BY created_at")
    suspend fun active(): List<Goal>

    /**
     * Every row, buried ones included — what the sync reads, and the one query that must.
     *
     * A deleted goal is exactly the row the household still has to be told about: the tombstone
     * that takes it off the other phones is built from it. [active] would hide the only rows that
     * still have something to say.
     */
    @Query("SELECT * FROM goal ORDER BY created_at")
    suspend fun all(): List<Goal>

    /** One live row by its id — the edit sheet can only be open on one that still exists. */
    @Query("SELECT * FROM goal WHERE id = :id AND deleted = 0")
    suspend fun byId(id: String): Goal?

    /** One row whether or not it is buried, so an arriving record can be judged against it. */
    @Query("SELECT * FROM goal WHERE id = :id")
    suspend fun anyById(id: String): Goal?

    @Query("UPDATE goal SET deleted = 1, updated_at = :now, edited_by_member_id = :by WHERE id = :id")
    suspend fun delete(id: String, now: Long, by: String = "")
}

/**
 * The rows one figure is allowed to count: the whole household's, or only hers.
 *
 * The single place [Goal.shared] turns into arithmetic, used by both a cap and a savings goal so
 * the two cannot drift into meaning different things by the same word. A shared figure counts
 * everything the phone can see, which on a paired phone is both of them; a private one counts the
 * rows this phone read itself.
 *
 * [mineId] is `META_SYNC_MEMBER`, and it is blank on a phone that has never paired. That is not a
 * special case to guard — [LedgerEntry.ownerMemberId] is filled in with the same blank on every
 * local row, so «only mine» on a solo phone selects the whole ledger, which is what it means
 * there. The choice never appears on that phone and never has to.
 */
fun scopedTo(entries: List<LedgerEntry>, mineId: String, shared: Boolean): List<LedgerEntry> =
    if (shared) entries else entries.filter { it.ownerMemberId == mineId }

/**
 * How long a savings goal may run for, offered instead of a date picker.
 *
 * A deadline is what makes a goal something she can pace — [GoalProgress.perMonthRial] cannot
 * exist without one — and it was the field that made the difference between the goal card being
 * a progress bar and being a plan. A picker was the obvious way to ask for it and the wrong one:
 * four pills are one tap, and a Jalali date picker is a screen, a keyboard, and four ways to
 * choose a date in the past.
 *
 * Each lands on the **last day** of its own month, so «۶ ماه» chosen on the 28th is six whole
 * months and not five and two days — see [jalaliMonthsAheadEnd].
 */
enum class GoalHorizon(val months: Int?, val fa: String) {
    QUARTER(3, "۳ ماه"),
    HALF(6, "۶ ماه"),
    YEAR(12, "۱ سال"),

    /** No deadline at all, which is a real answer: «هر وقت شد». */
    OPEN(null, "بی‌مهلت"),
    ;

    /** The Tehran day this horizon ends on, or null for [OPEN]. */
    fun endsOn(today: Long): Long? = months?.let { jalaliMonthsAheadEnd(today, it) }
}

/** Where a savings goal stands, worked out from the transactions and nothing else. */
data class GoalProgress(
    val goal: Goal,
    val currentRial: Long,
    val targetRial: Long,
    /** What is still to be put aside. Zero once the goal is met. */
    val remainingRial: Long,
    /** 0..1, clamped. */
    val share: Float,
    /**
     * True when a savings goal is under water — she has spent more than came in since it
     * started, so nothing has been put aside.
     *
     * Kept as a flag rather than shown as a negative figure. "−۴۵ million of 50 million" is
     * arithmetic read aloud and lands as a scolding; nothing was set aside, and that is what
     * the screen says.
     */
    val underWater: Boolean,
    val done: Boolean,
    /** Days until the deadline, today counting as one. Null for a goal with no deadline. */
    val daysLeft: Int?,
    /**
     * What she would have to keep each month from here to land on the target on time.
     *
     * The one figure that makes a goal actionable rather than decorative: «۵۰ میلیون تا اسفند» is
     * an aspiration, «ماهی ۸ میلیون» is a decision about this month. Null when there is no
     * deadline, nothing left to save, or under a month to do it in — a per-month rate over eleven
     * days is a number with no month to spend it in, and the card says the days instead.
     */
    val perMonthRial: Long?,
    /** True when the deadline has passed with the target unmet. Never true for a met goal. */
    val expired: Boolean,
    /**
     * Who set it, when that was somebody else — «مریم». Blank on her own and on every private
     * goal, which is the same thing said twice: a private goal can only be hers.
     */
    val ownerName: String = "",
) {
    /** The household's, and therefore measured against what everybody keeps. */
    val shared: Boolean get() = goal.shared
}

/**
 * A savings goal counts what was kept: income minus spending since it started, with transfers and
 * duplicates left out of both sides.
 *
 * Net cash flow is a proxy and it is the honest one available — the app has no notion of a
 * separate savings pot, and inventing one would mean asking her to file every transaction twice.
 * What it costs is that two goals running at once read the same net and so advance together;
 * `startsOn` is what keeps that from being wrong as well as coarse, since each counts only from
 * the month it was set in.
 *
 * ponytail: one net for every goal. Allocating savings between goals needs a way for her to say
 * which pot a deposit went to, which is a whole screen and a decision nobody has asked for yet.
 *
 * A shared goal nets the household and a private one nets only her — [scopedTo], the same gate a
 * cap runs through. Which matters more here than on a cap: two people saving for one trip are
 * putting money aside out of one pot, and a goal that counted half of it would ask them to reach
 * the figure twice.
 */
fun goalProgress(
    goal: Goal,
    entries: List<LedgerEntry>,
    today: Long,
    /** This phone's member id, blank when it has never paired — see [scopedTo]. */
    mineId: String = "",
    /** Who set it, when that was somebody else. See [GoalProgress.ownerName]. */
    ownerName: String = "",
): GoalProgress {
    val net = scopedTo(spendable(entries), mineId, goal.shared)
        .filter { it.txn.day >= goal.startsOn }
        .sumOf { it.txn.signedRial ?: 0L }
    val current = net.coerceAtLeast(0L)
    val remaining = (goal.targetRial - current).coerceAtLeast(0L)
    // Today counts as a day she can still save in, for the reason [BudgetWindow.daysLeft] counts
    // it: a zero on the deadline itself would read as "the time is up" on a day it is not.
    val daysLeft = goal.endsOn?.let { (it - today + 1).toInt() }
    val done = current >= goal.targetRial
    return GoalProgress(
        goal = goal,
        currentRial = current,
        targetRial = goal.targetRial,
        remainingRial = remaining,
        share = if (goal.targetRial > 0) {
            (current.toDouble() / goal.targetRial).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        },
        underWater = net < 0,
        done = done,
        daysLeft = daysLeft,
        // Ceiling division on the months, so the rate is one she can actually finish on: floor
        // would hand her a figure that lands short in the final month, which is the one month
        // where being short is the whole outcome.
        perMonthRial = daysLeft
            ?.takeIf { !done && it >= JALALI_MONTH_DAYS && remaining > 0L }
            ?.let { days ->
                val months = days / JALALI_MONTH_DAYS
                (remaining + months - 1) / months
            },
        expired = daysLeft != null && daysLeft <= 0 && !done,
        ownerName = ownerName,
    )
}

/**
 * Days in an average Jalali month, as an integer, for turning a deadline into a monthly rate.
 *
 * Thirty rather than 30.44: the rate is rounded up anyway, so the shorter month is the one that
 * cannot leave her short, and integer division is what keeps this out of Double arithmetic.
 */
private const val JALALI_MONTH_DAYS = 30

// ─────────────────────── «آیا ارزشش را داشت؟» ───────────────────────

object WorthIt {
    const val YES = "yes"
    const val NO = "no"
    const val NEEDED = "needed"
}

/**
 * The question that separates spending she values from spending she regrets.
 *
 * It is asked about **at most two** discretionary purchases a week, and only large ones. The
 * point is not data collection: an app that asks about every coffee is one she stops answering,
 * and the answer is only worth having if it is considered.
 *
 * Rent, bills, fees and cash withdrawals are never asked about — "was it worth it" is not a
 * question about the electricity bill, and asking it would read as the app being smug.
 *
 * Only her own spending is asked about. A household ledger holds her partner's rows too, and
 * this question is not one anybody can answer for somebody else: in a family of two both phones
 * were handed the same two purchases, and the one who did not make them was being asked to have
 * an opinion about a card that is not hers. `txn.ownerMemberId` is blank exactly when the row was
 * read on this phone — see [LedgerEntry.ownerMemberId], which is filled in with the local member
 * and therefore never blank.
 */
private val NEVER_ASKED = setOf(CAT_TRANSFER, CAT_FEES, CAT_INCOME, "cat_bills", CAT_CASH)

const val WORTH_IT_PER_WEEK = 2

fun worthItCandidates(
    entries: List<LedgerEntry>,
    answered: Set<String>,
    today: Long,
    threshold: Long,
): List<LedgerEntry> {
    val weekFrom = weekStart(today)
    return spendable(entries)
        .filter {
            it.txn.day >= weekFrom &&
                it.txn.direction == "out" &&
                (it.txn.amountRial ?: 0L) >= threshold &&
                it.categoryId !in NEVER_ASKED &&
                it.txn.ownerMemberId.isBlank() &&
                it.txn.ref !in answered &&
                !it.needsReview // settle what it *is* before asking how she felt about it
        }
        .sortedByDescending { it.txn.amountRial ?: 0L }
        .take(WORTH_IT_PER_WEEK)
}

/**
 * The threshold: the 90th percentile of what she spends, floored so that a quiet month does not
 * start asking about bus fares.
 *
 * `sorted()[n * 9 / 10]` rather than a window function, because API 24 ships SQLite 3.9 and this
 * runs in Kotlin anyway.
 */
fun largeSpendThreshold(entries: List<LedgerEntry>, floorRial: Long = 20_000_000L): Long {
    val amounts = spendable(entries)
        .filter { it.txn.direction == "out" }
        .mapNotNull { it.txn.amountRial }
        .sorted()
    if (amounts.size < 10) return floorRial
    return maxOf(amounts[amounts.size * 9 / 10], floorRial)
}

/** What she has said about her own spending, once there is enough of it to say anything. */
data class WorthItSummary(val worth: Long, val notWorth: Long, val needed: Long) {
    val total: Long get() = worth + notWorth + needed
    /**
     * The only actionable half. "Spend less" is advice nobody can act on; "this is what you
     * later wished you had not" is a number attached to decisions she actually made.
     */
    val regretted: Long get() = notWorth
}

fun worthItSummary(entries: List<LedgerEntry>, answers: Map<String, String>): WorthItSummary {
    var worth = 0L
    var notWorth = 0L
    var needed = 0L
    for (entry in spendable(entries)) {
        val amount = entry.txn.amountRial ?: continue
        when (answers[entry.txn.ref]) {
            WorthIt.YES -> worth += amount
            WorthIt.NO -> notWorth += amount
            WorthIt.NEEDED -> needed += amount
        }
    }
    return WorthItSummary(worth, notWorth, needed)
}
