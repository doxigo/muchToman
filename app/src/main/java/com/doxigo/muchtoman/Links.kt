package com.doxigo.muchtoman

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Transfers and duplicates — the two ways one movement of money shows up as more than one
 * transaction, and the two ways a total goes quietly wrong if nothing notices.
 *
 * Every detector here is a pure function of the transactions, and nothing guesses: a link is
 * applied to the totals only when nothing about it is ambiguous — [LinkCandidate.auto] — and her
 * verdict beats everything in both directions. A near miss is stored as a non-auto candidate,
 * and that is all it does: it counts in nothing, [LinkCandidateDao.touching] reads it beside a
 * row, and a verdict of hers has a pair to land on so the same question cannot come back.
 * Refund and fee detectors lived here once, computing links nothing consumed; a detector whose
 * output feeds no total and no screen is not caution, it is dead weight dressed as caution.
 */

object LinkKind {
    const val TRANSFER = "transfer"
    const val DUPLICATE = "duplicate"
}

object Verdict {
    const val CONFIRMED = "confirmed"
    const val REJECTED = "rejected"
}

/** A pair the detectors propose. Rebuilt on every derive, so it lives in `derived.db`. */
@Entity(
    tableName = "link_candidate",
    primaryKeys = ["a_ref", "b_ref", "kind"],
    indices = [Index("a_ref"), Index("b_ref")],
)
data class LinkCandidate(
    @ColumnInfo(name = "a_ref") val aRef: String,
    @ColumnInfo(name = "b_ref") val bRef: String,
    val kind: String,
    /** Why, in a word, so the deck can say what it noticed rather than just assert it. */
    val reason: String,
    /** True only when nothing about the match is ambiguous. False means: not certain enough to apply. */
    val auto: Boolean,
    /**
     * The leg that arrived second, which is the one [hiddenRefs] strikes out. [aRef]/[bRef]
     * order by ref, and a ref is a hash — its order against time is a coin flip — so time is
     * carried here rather than inferred. Blank only on a pair built from a verdict whose legs
     * the ledger no longer holds.
     */
    @ColumnInfo(name = "later_ref", defaultValue = "''") val laterRef: String = "",
)

/**
 * Her verdict on a pair, which always wins.
 *
 * [aRef] is always lexicographically less than [bRef]. That ordering is load-bearing rather
 * than tidy: without it `(a,b)` and `(b,a)` are two different rows and the same pair can be
 * confirmed and rejected at the same time.
 */
@Entity(
    tableName = "link_decision",
    indices = [Index("a_ref", "b_ref", "kind", unique = true), Index("updated_at")],
)
data class LinkDecision(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "a_ref") val aRef: String,
    @ColumnInfo(name = "b_ref") val bRef: String,
    val kind: String,
    val verdict: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
)

fun linkDecision(aRef: String, bRef: String, kind: String, verdict: String, now: Long): LinkDecision {
    val (a, b) = if (aRef <= bRef) aRef to bRef else bRef to aRef
    return LinkDecision(uuid7(now), a, b, kind, verdict, now, now)
}

/** A fee band that a bank may take out of a transfer without it stopping being the same money. */
const val TRANSFER_FEE_MAX_RIAL = 120_000L

/** Instant rails settle in seconds; anything past this is two separate movements. */
const val TRANSFER_WINDOW_MS = 15 * 60 * 1000L

/**
 * پایا settles in batches, genuinely next-business-day, and ساتنا in banking-hour cycles.
 * Neither is the seconds that [TRANSFER_WINDOW_MS] assumes. Too wide to ever trust silently —
 * a pair found this way is always a question for the deck, never an answer.
 */
const val SLOW_RAIL_WINDOW_MS = 40 * 60 * 60 * 1000L

/**
 * The rails that do not settle while she watches.
 *
 * Read off *either* leg, because the two banks describe one transfer differently: the receiving
 * bank is usually the one that writes «واریز پایا», while the sending bank says only «انتقال».
 * Taking the rail from the sender alone gave those transfers the fifteen-minute instant window,
 * and a leg that landed six hours later was never paired with anything.
 */
private val SLOW_RAILS = setOf("paya", "satna")

/** Two identical purchases this close together are a real thing; only she may decide. */
const val DUPLICATE_WINDOW_MS = 90 * 1000L

/**
 * A reference number is the bank's own idempotency key, but some banks re-print a contract or
 * instalment number where one belongs, so even this match carries a window. Seven days: wide
 * enough for a delayed second copy of a real message, far short of next month's instalment.
 */
const val DUPLICATE_REFNO_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Two legs sharing a post-transaction balance settle a duplicate only this close together.
 *
 * Unbounded, this match also caught a backup-restore that re-stamps DATE months later — and that
 * protection is deliberately traded away, because a fixed rent cycling an account back to the
 * same figure every month is normal use, and it was being auto-hidden from every report for
 * ever. A re-stamped restore now shows as two rows months apart, which is at least a wrongness
 * she can see and reject.
 */
const val DUPLICATE_BALANCE_WINDOW_MS = 48 * 60 * 60 * 1000L

private fun pair(a: Txn, b: Txn, kind: String, reason: String, auto: Boolean): LinkCandidate {
    val (x, y) = if (a.ref <= b.ref) a.ref to b.ref else b.ref to a.ref
    // The millisecond tie breaks on ref, so two devices deriving the same rows hide the same leg.
    val later = if (a.at > b.at || (a.at == b.at && a.ref > b.ref)) a else b
    return LinkCandidate(x, y, kind, reason, auto, later.ref)
}

/**
 * Duplicates, in this order, stopping at the first hit.
 *
 * The reference number is the bank's own idempotency key and settles it within its window. A
 * shared post-transaction balance settles it nearly as well: two genuinely separate identical
 * purchases cannot both leave the account holding the same figure — unless a month passes and
 * the same rent leaves the same account at the same figure again, which is why both matches
 * are time-bounded now. Anything weaker than that is never settled by the app, because two
 * identical taxi fares forty seconds apart is a thing that happens to people.
 */
fun findDuplicates(transactions: List<Txn>): List<LinkCandidate> {
    val ordered = transactions.sortedWith(compareBy({ it.at }, { it.ref }))

    val byRefNo = mutableListOf<LinkCandidate>()
    for (matches in ordered.filter { it.refNo.isNotEmpty() }.groupBy { it.bank to it.refNo }.values) {
        for (i in matches.indices) {
            for (j in i + 1 until matches.size) {
                if (matches[j].at - matches[i].at > DUPLICATE_REFNO_WINDOW_MS) break
                val a = matches[i]
                val b = matches[j]
                val compatible = a.accountId == b.accountId &&
                    a.direction != null && a.direction == b.direction &&
                    a.amountRial != null && a.amountRial == b.amountRial &&
                    a.signedRial != null && a.signedRial == b.signedRial &&
                    (a.mask.isBlank() || b.mask.isBlank() || a.mask == b.mask)
                byRefNo += pair(a, b, LinkKind.DUPLICATE, "refno", auto = compatible)
            }
        }
    }

    val byBalance = mutableListOf<LinkCandidate>()
    val balanceGroups = ordered
        .filter { it.signedRial != null && it.balanceRial != null }
        .groupBy { Triple(it.accountId, it.signedRial, it.balanceRial) }
    for (matches in balanceGroups.values) {
        for (i in matches.indices) {
            for (j in i + 1 until matches.size) {
                if (matches[j].at - matches[i].at > DUPLICATE_BALANCE_WINDOW_MS) break
                byBalance += pair(matches[i], matches[j], LinkKind.DUPLICATE, "balance", auto = true)
            }
        }
    }

    val nearby = mutableListOf<LinkCandidate>()
    for (i in ordered.indices) {
        val a = ordered[i]
        for (j in i + 1 until ordered.size) {
            val b = ordered[j]
            if (b.at - a.at > DUPLICATE_WINDOW_MS) break
            if (
                a.accountId == b.accountId &&
                a.signedRial != null &&
                a.signedRial == b.signedRial &&
                a.merchantNorm == b.merchantNorm
            ) {
                nearby += pair(a, b, LinkKind.DUPLICATE, "near", auto = false)
            }
        }
    }
    return (byRefNo + byBalance + nearby)
        .distinctBy { Triple(it.aRef, it.bRef, it.kind) }
}

/**
 * Transfers between two accounts she owns.
 *
 * Both legs must be in the ledger. A transfer to someone else's account is not a transfer, it
 * is an ordinary outflow, and treating it as one would make her spending disappear.
 *
 * Without this, moving fifty million between her own accounts reads as fifty million of income
 * and fifty million of spending — the same quietly-wrong total this app was built to avoid,
 * arriving from a new direction.
 */
private fun railOf(sent: Txn, received: Txn): String? = when {
    sent.channel in SLOW_RAILS -> sent.channel
    received.channel in SLOW_RAILS -> received.channel
    else -> null
}

private class TransferTimes(rows: List<Txn>) {
    private val ordered = rows.sortedBy { it.at }

    fun around(at: Long, window: Long): List<Txn> {
        val from = at.coerceAtLeast(Long.MIN_VALUE + window) - window
        val until = at.coerceAtMost(Long.MAX_VALUE - window) + window
        var low = 0
        var high = ordered.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ordered[middle].at < from) low = middle + 1 else high = middle
        }
        val start = low
        high = ordered.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ordered[middle].at <= until) low = middle + 1 else high = middle
        }
        return ordered.subList(start, low)
    }
}

fun findTransfers(transactions: List<Txn>): List<LinkCandidate> {
    val outgoing = transactions.filter { it.direction == "out" && it.amountRial != null }
    val incoming = transactions.filter { it.direction == "in" && it.amountRial != null }
    val allTimes = TransferTimes(incoming)
    val slowTimes = TransferTimes(incoming.filter { it.channel in SLOW_RAILS })
    val instantTimes = TransferTimes(incoming.filter { it.channel !in SLOW_RAILS })
    val candidates = outgoing.map { sent ->
        val nearby = if (sent.channel in SLOW_RAILS) {
            allTimes.around(sent.at, SLOW_RAIL_WINDOW_MS)
        } else {
            instantTimes.around(sent.at, TRANSFER_WINDOW_MS) +
                slowTimes.around(sent.at, SLOW_RAIL_WINDOW_MS)
        }
        sent to nearby.filter { received ->
            val slow = railOf(sent, received) != null
            received.accountId != sent.accountId &&
                kotlin.math.abs(received.at - sent.at) <=
                (if (slow) SLOW_RAIL_WINDOW_MS else TRANSFER_WINDOW_MS) &&
                if (slow) {
                    // پایا and ساتنا debit their fee separately, so the two legs are exactly
                    // equal or unrelated.
                    received.amountRial == sent.amountRial
                } else {
                    received.amountRial!! <= sent.amountRial!! &&
                        sent.amountRial - received.amountRial >= 0 &&
                        sent.amountRial - received.amountRial <= TRANSFER_FEE_MAX_RIAL
                }
        }
    }
    // One counter-leg settles at most one pair, so uniqueness is checked from the receiving
    // side too. Checked only from the sent side, two equal payments out inside the window of a
    // single equal leg in each looked unique from where they stood, both auto-paired with it,
    // and the one of them that had actually left the household vanished from every report.
    val claims = mutableMapOf<String, Int>()
    for ((_, matches) in candidates) {
        for (received in matches) claims[received.ref] = (claims[received.ref] ?: 0) + 1
    }
    val out = mutableListOf<LinkCandidate>()
    for ((sent, matches) in candidates) {
        if (matches.isEmpty()) continue
        // Ref breaks a tie in distance, so the pair does not depend on the order rows arrived.
        val nearest = matches.minWithOrNull(
            compareBy({ kotlin.math.abs(it.at - sent.at) }, { it.ref })
        )!!
        val rail = railOf(sent, nearest)
        val exact = nearest.amountRial == sent.amountRial
        val unique = matches.size == 1 && claims[nearest.ref] == 1
        // Automatic only when nothing about it is ambiguous: one candidate, claimed by no other
        // leg, exactly equal, and not on a rail whose forty-hour window makes coincidence
        // plausible.
        val auto = unique && exact && rail == null
        // The rail is its own reason, so the deck can say which one it noticed rather than
        // asserting a pairing she has no way to check.
        val reason = rail ?: if (exact) "exact" else "fee-band"
        out += pair(sent, nearest, LinkKind.TRANSFER, reason, auto)
    }
    return out
}

/**
 * Every candidate, with her verdicts applied last and winning outright.
 *
 * A rejection suppresses a pair for ever; a confirmation creates one the detectors missed.
 */
fun findLinks(
    transactions: List<Txn>,
    decisions: List<LinkDecision>,
): List<LinkCandidate> {
    val found = findDuplicates(transactions) + findTransfers(transactions)

    val rejected = decisions.filter { it.verdict == Verdict.REJECTED && !it.deleted }
        .map { Triple(it.aRef, it.bRef, it.kind) }.toSet()
    val confirmed = decisions.filter { it.verdict == Verdict.CONFIRMED && !it.deleted }

    val kept = found
        .distinctBy { Triple(it.aRef, it.bRef, it.kind) }
        .filterNot { Triple(it.aRef, it.bRef, it.kind) in rejected }

    // A confirmation may name legs the detectors never paired, so the later leg is looked up
    // rather than carried — and left blank when a leg is no longer in the ledger.
    val at = transactions.associate { it.ref to it.at }
    val hers = confirmed
        .filterNot { d -> kept.any { it.aRef == d.aRef && it.bRef == d.bRef && it.kind == d.kind } }
        .map { LinkCandidate(it.aRef, it.bRef, it.kind, "confirmed", auto = true, laterRef = laterOf(it.aRef, it.bRef, at)) }

    return kept.map { candidate ->
        // Her confirmation lifts a deck card into a settled fact.
        val settled = confirmed.any {
            it.aRef == candidate.aRef && it.bRef == candidate.bRef && it.kind == candidate.kind
        }
        if (settled) candidate.copy(auto = true, reason = "confirmed") else candidate
    } + hers
}

private fun laterOf(aRef: String, bRef: String, at: Map<String, Long>): String {
    val aAt = at[aRef] ?: return ""
    val bAt = at[bRef] ?: return ""
    return if (aAt > bAt || (aAt == bAt && aRef > bRef)) aRef else bRef
}

/** Both legs of every transfer that is settled, so reports can leave them out of both sides. */
fun transferRefs(links: List<LinkCandidate>): Set<String> = links
    .filter { it.kind == LinkKind.TRANSFER && it.auto }
    .flatMap { listOf(it.aRef, it.bRef) }
    .toSet()

/**
 * The later leg of every settled duplicate — hidden from reports, never deleted.
 *
 * [LinkCandidate.laterRef], not [LinkCandidate.bRef]: the pair is stored in ref order, a ref is
 * a hash, and hiding by hash order hid the original and kept the echo whenever the hashes
 * happened to sort against time. bRef remains the fallback for a confirmed pair whose legs the
 * ledger no longer holds, where "later" has no answer at all.
 */
fun hiddenRefs(links: List<LinkCandidate>): Set<String> = links
    .filter { it.kind == LinkKind.DUPLICATE && it.auto }
    .map { it.laterRef.ifEmpty { it.bRef } }
    .toSet()

@Dao
interface LinkCandidateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<LinkCandidate>)

    @Query("DELETE FROM link_candidate")
    suspend fun deleteAll()

    @Query("SELECT * FROM link_candidate")
    suspend fun all(): List<LinkCandidate>

    @Query("SELECT * FROM link_candidate WHERE a_ref = :ref OR b_ref = :ref")
    suspend fun touching(ref: String): List<LinkCandidate>
}

@Dao
interface LinkDecisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: LinkDecision)

    @Query("SELECT * FROM link_decision WHERE deleted = 0")
    suspend fun all(): List<LinkDecision>
}
