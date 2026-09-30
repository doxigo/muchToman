package com.doxigo.muchtoman

import androidx.room.withTransaction

/**
 * Her corrections to a row — its figure, its day, whose it was — and a row broken into parts.
 *
 * All four are [TxnDecision]s, so they live in `durable.db`, survive every re-derive and back up
 * with everything else. None of them touches `derived.db`: they are laid over the row where the
 * ledger is *read* ([ledgerEntries]), never where it is derived. That is the whole design, and it
 * is what makes a message's day editable after all — the bank's own stamp still orders the walk
 * [deriveBalance] anchors on, and a مانده the bank printed is never moved by her saying the coffee
 * was yesterday. Reports, budgets, the timeline and the family's copy read the corrected row; the
 * account's balance reads the bank.
 */

/** One part of a split row: what it was filed as, and how much of the row it was. */
data class SplitPart(val categoryId: String, val categoryFa: String, val rial: Long)

/** The most parts a row is broken into. A receipt, not a ledger inside a ledger. */
const val MAX_SPLIT_PARTS = 8

/**
 * A [DecisionKind.SPLIT] value read back: `cat_a:1200000,cat_b:300000`, Rial per part.
 *
 * Refused whole — an empty list, so the row reads as one — for anything this app would not have
 * written: fewer than two parts, a part of nothing, one category twice, or انتقال, which is not a
 * spending answer and would take the row out of every total. The sum is checked against the row
 * by [splitOf], because the row's figure can move after the split was made.
 */
fun parseSplit(value: String?): List<Pair<String, Long>> {
    if (value.isNullOrBlank()) return emptyList()
    val parts = value.split(',').map { piece ->
        val cut = piece.lastIndexOf(':')
        if (cut <= 0) return emptyList()
        val rial = piece.substring(cut + 1).toLongOrNull() ?: return emptyList()
        piece.substring(0, cut) to rial
    }
    val valid = parts.size in 2..MAX_SPLIT_PARTS &&
        parts.all { (id, rial) -> id.isNotBlank() && id != CAT_TRANSFER && rial in 1..MAX_PLAUSIBLE_RIAL } &&
        parts.map { it.first }.toSet().size == parts.size
    return if (valid) parts else emptyList()
}

fun splitValue(parts: List<Pair<String, Long>>): String = parts.joinToString(",") { (id, rial) -> "${id}:$rial" }

/** The biggest part, which is what the row is filed as for everything that reads one category. */
fun splitLead(parts: List<Pair<String, Long>>): String? = parts.maxByOrNull { it.second }?.first

/**
 * The parts a row is split into, named — or none, when the split no longer adds up to the row.
 * A figure edited after the split is the likeliest way there; a split that silently counted money
 * the row no longer has would be a wrong total, so the row reads as one until it is split again.
 */
fun splitOf(value: String?, amountRial: Long?, names: Map<String, String>): List<SplitPart> {
    val parts = parseSplit(value)
    if (parts.isEmpty() || amountRial == null || parts.sumOf { it.second } != amountRial) return emptyList()
    return parts.map { (id, rial) -> SplitPart(id, names[id] ?: "دسته‌بندی نشده", rial) }
}

/**
 * The row as she corrected it: her figure in place of the one read, her day in place of the
 * stamp's — keeping the minute, so a row from ۱۴:۰۳ is still from ۱۴:۰۳. The direction never
 * changes: money that came in is not made to have gone out by a typo in its figure.
 */
fun editedTxn(txn: Txn, amountRial: Long?, day: Long?): Txn {
    var edited = txn
    if (amountRial != null && amountRial > 0 && txn.amountRial != null) {
        edited = edited.copy(
            amountRial = amountRial,
            signedRial = txn.signedRial?.let { if (it < 0) -amountRial else amountRial },
        )
    }
    if (day != null && day != txn.day) {
        val minute = (txn.at - tehranDayStart(txn.day)).coerceIn(0L, DAY_MS - 1)
        edited = edited.copy(day = day, at = tehranDayStart(day) + minute)
    }
    return edited
}

/**
 * One entry per part, each filed and sized as its part — what [spendable] hands every total, so
 * a split row counts under each of its categories and nowhere twice. An unsplit row is itself.
 */
fun splitParts(entry: LedgerEntry): List<LedgerEntry> =
    if (entry.split.isEmpty()) listOf(entry)
    else entry.split.map { part ->
        entry.copy(
            categoryId = part.categoryId,
            categoryFa = part.categoryFa,
            txn = entry.txn.copy(
                amountRial = part.rial,
                signedRial = entry.txn.signedRial?.let { if (it < 0) -part.rial else part.rial },
            ),
            split = emptyList(),
        )
    }

/** Whether this phone may correct the row: its own, typed or read. Another member's is theirs. */
fun editable(entry: LedgerEntry): Boolean =
    (entry.txn.ref.startsWith("m:") || entry.txn.ref.startsWith("s:")) && entry.txn.amountRial != null

/** Whether the row can be broken into parts: any row that moved money and is not a transfer. */
fun splittable(entry: LedgerEntry): Boolean =
    !entry.duplicate && !entry.transfer && (entry.txn.amountRial ?: 0L) > 1L

/**
 * One answer about one row, stamped past the one it replaces — null takes it back. `ref` and
 * `kind` are unique together, so a retracted answer is the row re-answered, never a second one.
 * [member] is this phone's in its household, blank when it has none.
 */
internal suspend fun putAnswer(
    durable: DurableDb,
    txn: Txn,
    kind: String,
    value: String?,
    member: String,
    now: Long = System.currentTimeMillis(),
) {
    val previous = durable.decisions().answerFor(txn.ref, kind)
    if (value == null && (previous == null || previous.deleted)) return
    val at = maxOf(now, (previous?.updatedAt ?: 0L) + 1L)
    durable.decisions().put(
        TxnDecision(
            id = previous?.id ?: uuid7(at),
            ref = txn.ref,
            kind = kind,
            value = value,
            createdAt = previous?.createdAt ?: at,
            updatedAt = at,
            deleted = value == null,
            memberId = member,
            familyRef = txn.familyRef.ifBlank { member.takeIf { it.isNotBlank() }?.let { familyTxnId(it, txn.ref) }.orEmpty() },
        )
    )
}

/**
 * Her corrections to one of her own rows; null leaves that one as it stands. The day stops at
 * today — the ledger records what happened — and a row put back to its own member takes the
 * attribution back rather than storing it, so the published row is the one it always was.
 */
internal suspend fun editRow(
    durable: DurableDb,
    entry: LedgerEntry,
    rial: Long?,
    day: Long?,
    memberId: String?,
    member: String,
    now: Long = System.currentTimeMillis(),
) {
    if (!editable(entry)) return
    durable.withTransaction {
        rial?.takeIf { it in 1..MAX_PLAUSIBLE_RIAL && it != entry.txn.amountRial }
            ?.let { putAnswer(durable, entry.txn, DecisionKind.AMOUNT, it.toString(), member, now) }
        day?.coerceAtMost(tehranDay(now))?.takeIf { it != entry.txn.day }
            ?.let { putAnswer(durable, entry.txn, DecisionKind.DAY, it.toString(), member, now) }
        memberId?.takeIf { it != entry.ownerMemberId }?.let {
            val own = it == entry.txn.ownerMemberId.ifBlank { member }
            putAnswer(durable, entry.txn, DecisionKind.MEMBER, it.takeUnless { own }, member, now)
        }
    }
}

/** Back to the figure and day the row was read or typed with. Whose it was stays. */
internal suspend fun revertRow(durable: DurableDb, entry: LedgerEntry, member: String) {
    durable.withTransaction {
        putAnswer(durable, entry.txn, DecisionKind.AMOUNT, null, member)
        putAnswer(durable, entry.txn, DecisionKind.DAY, null, member)
    }
}

/**
 * The row in parts — or, with none, whole again. Either way it is filed under its biggest part as
 * an ordinary pinned category: what every reader of one category sees, and what a build from
 * before splits files it as. Returns false, writing nothing, for parts that do not add up to it.
 */
internal suspend fun splitRow(
    durable: DurableDb,
    entry: LedgerEntry,
    parts: List<Pair<String, Long>>,
    member: String,
): Boolean {
    val split = parseSplit(splitValue(parts)).takeIf { it.sumOf { p -> p.second } == entry.txn.amountRial }.orEmpty()
    if (parts.isNotEmpty() && split.isEmpty()) return false
    durable.withTransaction {
        putAnswer(durable, entry.txn, DecisionKind.CATEGORY, splitLead(split) ?: entry.categoryId, member)
        putAnswer(durable, entry.txn, DecisionKind.SPLIT, split.takeIf { it.isNotEmpty() }?.let(::splitValue), member)
    }
    return true
}
