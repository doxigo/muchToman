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
 * Transfers, refunds, fees and duplicates — the four ways one movement of money shows up as
 * more than one transaction, and the four ways a total goes quietly wrong if nothing notices.
 *
 * Every detector here is a pure function of the transactions. Nothing guesses, nothing scores,
 * and anything short of certain goes to the deck rather than being applied silently.
 */

object LinkKind {
    const val TRANSFER = "transfer"
    const val REFUND = "refund"
    const val FEE_OF = "fee_of"
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
    /** True only when nothing about the match is ambiguous. False means: ask her. */
    val auto: Boolean,
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

/** Two identical purchases this close together are a real thing; only the deck may decide. */
const val DUPLICATE_WINDOW_MS = 90 * 1000L

const val REFUND_KEYWORD_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
const val REFUND_QUIET_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/** An unlabelled outflow at most this large, just after another, may be its fee. */
const val LOOSE_FEE_MAX_RIAL = 200_000L
const val LOOSE_FEE_WINDOW_MS = 120 * 1000L

private val REFUND_WORDS = listOf("برگشت", "عودت", "ابطال", "استرداد", "کنسل")

private fun pair(a: Txn, b: Txn, kind: String, reason: String, auto: Boolean): LinkCandidate {
    val (x, y) = if (a.ref <= b.ref) a.ref to b.ref else b.ref to a.ref
    return LinkCandidate(x, y, kind, reason, auto)
}

/**
 * Duplicates, in this order, stopping at the first hit.
 *
 * The reference number is the bank's own idempotency key and settles it outright. A shared
 * post-transaction balance settles it nearly as well: two genuinely separate identical
 * purchases cannot both leave the account holding the same figure. Anything weaker than that
 * goes to the deck, because two identical taxi fares forty seconds apart is a thing that
 * happens to people.
 */
fun findDuplicates(transactions: List<Txn>): List<LinkCandidate> {
    val ordered = transactions.sortedWith(compareBy({ it.at }, { it.ref }))

    val byRefNo = mutableListOf<LinkCandidate>()
    for (matches in ordered.filter { it.refNo.isNotEmpty() }.groupBy { it.bank to it.refNo }.values) {
        for (i in matches.indices) {
            for (j in i + 1 until matches.size) {
                byRefNo += pair(matches[i], matches[j], LinkKind.DUPLICATE, "refno", auto = true)
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
private fun railOf(sent: Txn, received: Txn): String? =
    listOf(sent.channel, received.channel).firstOrNull { it in SLOW_RAILS }

fun findTransfers(transactions: List<Txn>): List<LinkCandidate> {
    val out = mutableListOf<LinkCandidate>()
    val outgoing = transactions.filter { it.direction == "out" && it.amountRial != null }
    val incoming = transactions.filter { it.direction == "in" && it.amountRial != null }
    for (sent in outgoing) {
        val matches = incoming.filter { received ->
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
        if (matches.isEmpty()) continue
        val nearest = matches.minByOrNull { kotlin.math.abs(it.at - sent.at) }!!
        val rail = railOf(sent, nearest)
        val exact = nearest.amountRial == sent.amountRial
        val unique = matches.size == 1
        // Automatic only when nothing about it is ambiguous: one candidate, exactly equal, and
        // not on a rail whose forty-hour window makes coincidence plausible.
        val auto = unique && exact && rail == null
        // The rail is its own reason, so the deck can say which one it noticed rather than
        // asserting a pairing she has no way to check.
        val reason = rail ?: if (exact) "exact" else "fee-band"
        out += pair(sent, nearest, LinkKind.TRANSFER, reason, auto)
    }
    return out
}

/** Money coming back. A keyword makes it certain; matching amounts alone only make it likely. */
fun findRefunds(transactions: List<Txn>, bodyOf: (Txn) -> String): List<LinkCandidate> {
    val out = mutableListOf<LinkCandidate>()
    val ordered = transactions.sortedWith(compareBy({ it.at }, { it.ref }))
    val outgoing = ordered
        .filter { it.direction == "out" && it.amountRial != null }
        .groupBy { it.accountId to it.amountRial }
    for (back in ordered) {
        if (back.direction != "in" || back.amountRial == null) continue
        val labelled = REFUND_WORDS.any { bodyOf(back).contains(it) }
        val window = if (labelled) REFUND_KEYWORD_WINDOW_MS else REFUND_QUIET_WINDOW_MS
        val candidates = outgoing[back.accountId to back.amountRial].orEmpty().filter { spent ->
            spent.at < back.at &&
                back.at - spent.at <= window &&
                (labelled || (spent.merchantNorm.isNotEmpty() && spent.merchantNorm == back.merchantNorm))
        }
        val original = candidates.maxByOrNull { it.at } ?: continue
        out += pair(original, back, LinkKind.REFUND, if (labelled) "keyword" else "merchant", labelled)
    }
    return out
}

/**
 * A small unlabelled outflow just after another is very often that one's fee. Never automatic:
 * it is also very often a second small purchase.
 */
fun findLooseFees(transactions: List<Txn>): List<LinkCandidate> {
    val out = mutableListOf<LinkCandidate>()
    val spends = transactions
        .filter { it.direction == "out" && it.amountRial != null }
        .sortedWith(compareBy({ it.at }, { it.ref }))
    for (i in spends.indices) {
        val fee = spends[i]
        if (fee.amountRial!! > LOOSE_FEE_MAX_RIAL) continue
        if (fee.channel == "fee") continue // already labelled; it needs no guess
        val parent = spends.lastOrNull {
            it.ref != fee.ref &&
                it.accountId == fee.accountId &&
                it.at <= fee.at &&
                fee.at - it.at <= LOOSE_FEE_WINDOW_MS &&
                it.amountRial!! > LOOSE_FEE_MAX_RIAL
        } ?: continue
        out += pair(parent, fee, LinkKind.FEE_OF, "near", auto = false)
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
    bodyOf: (Txn) -> String = { "" },
): List<LinkCandidate> {
    val found = findDuplicates(transactions) +
        findTransfers(transactions) +
        findRefunds(transactions, bodyOf) +
        findLooseFees(transactions)

    val rejected = decisions.filter { it.verdict == Verdict.REJECTED && !it.deleted }
        .map { Triple(it.aRef, it.bRef, it.kind) }.toSet()
    val confirmed = decisions.filter { it.verdict == Verdict.CONFIRMED && !it.deleted }

    val kept = found
        .distinctBy { Triple(it.aRef, it.bRef, it.kind) }
        .filterNot { Triple(it.aRef, it.bRef, it.kind) in rejected }

    val hers = confirmed
        .filterNot { d -> kept.any { it.aRef == d.aRef && it.bRef == d.bRef && it.kind == d.kind } }
        .map { LinkCandidate(it.aRef, it.bRef, it.kind, "confirmed", auto = true) }

    return kept.map { candidate ->
        // Her confirmation lifts a deck card into a settled fact.
        val settled = confirmed.any {
            it.aRef == candidate.aRef && it.bRef == candidate.bRef && it.kind == candidate.kind
        }
        if (settled) candidate.copy(auto = true, reason = "confirmed") else candidate
    } + hers
}

/** Both legs of every transfer that is settled, so reports can leave them out of both sides. */
fun transferRefs(links: List<LinkCandidate>): Set<String> = links
    .filter { it.kind == LinkKind.TRANSFER && it.auto }
    .flatMap { listOf(it.aRef, it.bRef) }
    .toSet()

/** The later leg of every settled duplicate — hidden from reports, never deleted. */
fun hiddenRefs(links: List<LinkCandidate>): Set<String> = links
    .filter { it.kind == LinkKind.DUPLICATE && it.auto }
    .map { it.bRef }
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

    @Query("SELECT COUNT(*) FROM link_candidate WHERE auto = 0")
    suspend fun askCount(): Int
}

@Dao
interface LinkDecisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: LinkDecision)

    @Query("SELECT * FROM link_decision WHERE deleted = 0")
    suspend fun all(): List<LinkDecision>
}
