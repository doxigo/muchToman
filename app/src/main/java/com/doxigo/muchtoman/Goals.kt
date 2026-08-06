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
 *  - `save` — put this much aside by then.
 *  - `cap`  — keep this category under this much, this month.
 */

object GoalKind {
    const val SAVE = "save"
    const val CAP = "cap"
}

object GoalPeriod {
    const val MONTH = "jmonth"
    const val ONCE = "once"
}

@Entity(tableName = "goal")
data class Goal(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name_fa") val nameFa: String,
    @ColumnInfo(name = "target_rial") val targetRial: Long,
    val kind: String,
    /** For a cap, which category it holds down. Null on a savings goal. */
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    val period: String,
    @ColumnInfo(name = "starts_on") val startsOn: Long,
    @ColumnInfo(name = "ends_on") val endsOn: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
)

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: Goal)

    @Query("SELECT * FROM goal WHERE deleted = 0 ORDER BY created_at")
    suspend fun active(): List<Goal>

    @Query("UPDATE goal SET deleted = 1, updated_at = :now WHERE id = :id")
    suspend fun delete(id: String, now: Long)
}

/** Where a goal stands, worked out from the transactions and nothing else. */
data class GoalProgress(
    val goal: Goal,
    val currentRial: Long,
    val targetRial: Long,
    /** 0..1, clamped. A cap counts down: 1.0 means the cap is used up, not met. */
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
    /** Null for a goal with no end. */
    val daysLeft: Int?,
) {
    val cap: Boolean get() = goal.kind == GoalKind.CAP
}

/**
 * A savings goal counts what was kept — income minus spending since it started, transfers left
 * out of both. A cap counts what has been spent in its category this Jalali month.
 */
fun goalProgress(goal: Goal, entries: List<LedgerEntry>, today: Long): GoalProgress {
    val rows = spendable(entries)
    val current = when (goal.kind) {
        GoalKind.CAP -> {
            val from = jalaliMonthStart(today)
            rows.filter { it.txn.day >= from && it.categoryId == goal.categoryId }
                .sumOf { -(it.txn.signedRial ?: 0L).coerceAtMost(0L) }
        }
        else -> rows.filter { it.txn.day >= goal.startsOn }.sumOf { it.txn.signedRial ?: 0L }
    }
    val share = if (goal.targetRial > 0) {
        (current.toDouble() / goal.targetRial).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }
    return GoalProgress(
        goal = goal,
        currentRial = if (goal.kind == GoalKind.CAP) current else current.coerceAtLeast(0L),
        targetRial = goal.targetRial,
        underWater = goal.kind != GoalKind.CAP && current < 0,
        share = share,
        // A cap is met by NOT reaching it, which is the opposite test and easy to get backwards.
        done = if (goal.kind == GoalKind.CAP) current <= goal.targetRial else current >= goal.targetRial,
        daysLeft = goal.endsOn?.let { (it - today).toInt() },
    )
}

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
