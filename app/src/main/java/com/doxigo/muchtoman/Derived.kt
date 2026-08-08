package com.doxigo.muchtoman

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * Which reading produced a row. Bump it whenever [parseBankSms] changes what it makes of a
 * message, and the next launch rebuilds every transaction from the stored messages.
 *
 * This is what `SMS_SCHEMA` used to be, with its teeth pulled. It no longer deletes anything:
 * a rebuild is a local recompute of a few thousand rows, offline, needing no permission, and
 * leaving every anchor and every correction exactly where she left them.
 */
// 2: implausible figures are dropped rather than saturating Long. Bumping this is the whole
// mechanism — the next launch rebuilds every transaction from the stored messages, offline,
// with no inbox read and no permission, and every correction and anchor stays exactly put.
// 3: an amount is no longer read across «موجودی», and may sit in front of the word that gives
// it a direction. Every Blu deposit headed «دریافت پل» was stored at the account's balance
// rather than what arrived, and only a rebuild puts those rows right. The same rebuild reruns
// [findLinks], which is what this bump also buys: the transfer detector now reads the rail off
// either leg, and the pairs it previously missed are only found by looking again.
const val PARSER_VERSION = 3

private const val META_PARSER_VER = "parser_ver"
private const val META_DERIVED_AT = "derived_at"
private const val META_DERIVE_MS = "derive_ms"

/**
 * `derived.db` — everything a parser computed, and nothing else.
 *
 * Every table here is disposable by design. It is dropped and rebuilt from `sms_source` whenever
 * the reading changes, which is why it may carry a destructive fallback and why `durable.db`
 * never may. Nothing in here is referenced by a foreign key from outside this file, because
 * nothing outside this file is allowed to depend on a row that a rebuild will delete.
 */
@Database(
    entities = [Txn::class, TxnClass::class, LinkCandidate::class, LedgerMeta::class],
    // Bump this whenever anything in this file's schema changes. There are no migrations here
    // on purpose — the version going up drops every table, and the marker that says which
    // parser built these rows goes with them, so the next launch rebuilds. That is the intended
    // behaviour and not a shortcut: it costs under a second and nothing here is irreplaceable.
    version = 5,
    exportSchema = true,
)
abstract class DerivedDb : RoomDatabase() {
    abstract fun txn(): TxnDao
    abstract fun classes(): TxnClassDao
    abstract fun links(): LinkCandidateDao
    abstract fun meta(): LedgerMetaDao

    companion object {
        @Volatile private var instance: DerivedDb? = null

        fun get(context: Context): DerivedDb = instance ?: synchronized(this) {
            instance ?: Room
                .databaseBuilder(context.applicationContext, DerivedDb::class.java, "derived.db")
                // Allowed here and nowhere else: everything in this file can be rebuilt from
                // durable.db in under a second.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}

/**
 * One transaction, as read from one message.
 *
 * [ref] is `s:<srcHash>:<seq>` — a function of the message, never of what was made of it. That
 * is what lets a correction she made a year ago survive a parser fix: the pointer is to the
 * thing that did not change.
 */
@Entity(
    tableName = "txn",
    indices = [
        Index("at"),
        Index("account_id", "at"),
    ],
)
data class Txn(
    @PrimaryKey val ref: String,
    @ColumnInfo(name = "src_hash") val srcHash: String,
    val seq: Int,
    /** When the network stamped the message. */
    val at: Long,
    /** Tehran day, precomputed — API 24's SQLite has no generated columns. */
    val day: Long,
    val bank: String,
    /**
     * The account this belongs to, which is the bank and only the bank.
     *
     * Not the printed identifier, and per-transaction rows do not change why. One real Saman
     * account prints its number on a transfer, a card mask on a purchase, and nothing at all on
     * some alerts; keying on what was printed forked it into three accounts, each anchored at
     * the same full balance, and the total added all three. [mask] and [instrument] live on the
     * row where they group card spend and key nothing.
     */
    @ColumnInfo(name = "account_id") val accountId: String,
    /** "in", "out", or null when the message did not say. */
    val direction: String?,
    /** Magnitude in whole Rial. Present even when [direction] is not. */
    @ColumnInfo(name = "amount_rial") val amountRial: Long?,
    /** [amountRial] with its sign, or null when the direction is unknown. */
    @ColumnInfo(name = "signed_rial") val signedRial: Long?,
    /** The bank's own stated مانده, which is what anchors a derived balance. */
    @ColumnInfo(name = "balance_rial") val balanceRial: Long?,
    @ColumnInfo(name = "fee_rial") val feeRial: Long?,
    val mask: String,
    val instrument: String,
    val merchant: String,
    @ColumnInfo(name = "merchant_norm") val merchantNorm: String,
    @ColumnInfo(name = "ref_no") val refNo: String,
    @ColumnInfo(name = "printed_at") val printedAt: String,
    val channel: String,
    @ColumnInfo(name = "unit_printed") val unitPrinted: String,
    val inferred: Boolean,
    @ColumnInfo(name = "parser_ver") val parserVer: Int,
    /** Stable family sync id. Blank for a transaction created on this device. */
    @ColumnInfo(name = "family_ref") val familyRef: String = "",
    /** Immutable person identity. Blank means the current local member, or no family yet. */
    @ColumnInfo(name = "owner_member_id") val ownerMemberId: String = "",
    /** `sms` or `manual`; independent from which device currently renders the row. */
    @ColumnInfo(name = "source_kind") val sourceKind: String = "sms",
)

/**
 * Notes about the derivation, kept **in `derived.db`** rather than beside the messages.
 *
 * That placement is the whole point. The marker saying "these rows came from parser version N"
 * has to be destroyed by the same thing that destroys the rows, or the two disagree — and they
 * did: bumping this database's version dropped every table while a marker over in `durable.db`
 * went on claiming the work was done, so nothing rebuilt and the ledger sat empty. Keeping the
 * marker with the thing it describes makes that unrepresentable.
 */
@Entity(tableName = "ledger_meta")
data class LedgerMeta(@PrimaryKey val k: String, val v: String)

@Dao
interface LedgerMetaDao {
    @Query("SELECT v FROM ledger_meta WHERE k = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: LedgerMeta)
}

/** An account and what it holds, as the ledger works it out. */
data class DerivedBalance(
    val accountId: String,
    val rial: Long,
    val at: Long,
    /** False when nothing has ever stated a balance, so it is a running sum and not a figure. */
    val anchored: Boolean,
)

private class Anchor(val rial: Long, val at: Long, val ref: String)

@Dao
interface TxnDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<Txn>)

    @Query("DELETE FROM txn")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM txn")
    suspend fun count(): Int

    @Query("SELECT * FROM txn ORDER BY at DESC LIMIT :limit")
    suspend fun newest(limit: Int): List<Txn>

    @Query("SELECT DISTINCT account_id FROM txn")
    suspend fun accountIds(): List<String>

    /**
     * One account's transactions, oldest first, `ref` breaking a tie on the millisecond.
     *
     * The whole account rather than an aggregate: thirteen months of one account is a few
     * hundred rows, and the arithmetic being an ordinary Kotlin function is what lets
     * [deriveBalance] be tested on a JVM instead of only on a phone.
     *
     * ponytail: loads the account. Push the sum back into SQL if an account ever holds enough
     * rows for this to show up in a frame.
     */
    @Query("SELECT * FROM txn WHERE account_id = :accountId ORDER BY at ASC, ref ASC")
    suspend fun forAccount(accountId: String): List<Txn>
}

/** Whitespace-collapsed and trimmed. The body was already letter-folded by the parser. */
fun merchantNorm(merchant: String): String =
    merchant.trim().replace(WHITESPACE, " ")

/**
 * One stored message, read into transaction rows.
 *
 * A list because a single message can in principle describe more than one movement; today it
 * never does, so [Txn.seq] is 0 on every row there is. The shape is here so that the day one
 * bank starts batching, nothing above has to change.
 */
/**
 * Beyond this, a figure is a parse gone wrong rather than money.
 *
 * Ten trillion Toman is orders of magnitude past any personal transaction in Iran, and the
 * failure it catches is real: a twenty-one-digit run parses into a `Double` that has already
 * lost precision and then saturates `Long` at 9.2 × 10¹⁸ on the way in. A confident wrong total
 * is the one thing this app must never produce, so an implausible figure is dropped exactly the
 * way an asset with no rate is — left out, not guessed at.
 */
const val MAX_PLAUSIBLE_RIAL = 100_000_000_000_000L // 10 trillion Toman

private fun plausible(rial: Long?): Boolean =
    rial == null || (rial in -MAX_PLAUSIBLE_RIAL..MAX_PLAUSIBLE_RIAL)

fun parseToRows(source: SmsSource, extra: Map<String, Bank>): List<Txn> {
    val read = parseBankSms(source.sender, source.body, source.at, extra) ?: return emptyList()
    if (!plausible(read.amountRial) || !plausible(read.balanceRial)) return emptyList()
    val direction = read.delta?.let { if (it > 0) "in" else "out" }
    return listOf(
        Txn(
            ref = refOf(source.srcHash, 0),
            srcHash = source.srcHash,
            seq = 0,
            at = source.at,
            day = tehranDay(source.at),
            bank = read.bank.name,
            accountId = read.bank.name,
            direction = direction,
            amountRial = read.amountRial,
            signedRial = read.amountRial?.let { a -> direction?.let { if (it == "in") a else -a } },
            balanceRial = read.balanceRial,
            feeRial = read.feeRial,
            mask = read.mask,
            instrument = read.instrument.name.lowercase(),
            merchant = read.merchant,
            merchantNorm = merchantNorm(read.merchant),
            refNo = read.refNo,
            printedAt = read.printedAt,
            channel = read.channel.name.lowercase(),
            unitPrinted = read.unitPrinted.name.lowercase(),
            inferred = read.inferred,
            parserVer = PARSER_VERSION,
        )
    )
}

/** A hand-entered transaction, in the same shape as one read from a message. */
fun manualToRow(row: ManualTxn): Txn = Txn(
    ref = manualRef(row.id),
    // There is no message behind it, so the id stands in — nothing downstream reads this except
    // to look a body up, and there is no body to find.
    srcHash = row.id,
    seq = 0,
    at = row.at,
    day = row.day,
    bank = "MANUAL",
    accountId = row.accountId ?: "MANUAL",
    direction = if (row.amountRial > 0) "in" else "out",
    amountRial = kotlin.math.abs(row.amountRial),
    signedRial = row.amountRial,
    balanceRial = null,
    feeRial = null,
    mask = "",
    instrument = "unknown",
    merchant = row.merchant,
    merchantNorm = merchantNorm(row.merchant),
    refNo = "",
    printedAt = "",
    channel = "unknown",
    unitPrinted = "none",
    inferred = false,
    parserVer = PARSER_VERSION,
    sourceKind = "manual",
)

/** A remote parsed transaction, without pretending it was entered manually on this phone. */
fun familyToRow(row: FamilyTxn): Txn = Txn(
    ref = familyLocalRef(row.id),
    srcHash = row.id,
    seq = 0,
    at = row.at,
    day = row.day,
    bank = row.bank,
    accountId = "family:${row.ownerMemberId}:${row.bank}",
    direction = if (row.amountRial > 0) "in" else "out",
    amountRial = kotlin.math.abs(row.amountRial),
    signedRial = row.amountRial,
    balanceRial = null,
    feeRial = null,
    mask = "",
    instrument = "unknown",
    merchant = row.merchant,
    merchantNorm = merchantNorm(row.merchant),
    refNo = "",
    printedAt = "",
    channel = "unknown",
    unitPrinted = "none",
    inferred = false,
    parserVer = PARSER_VERSION,
    familyRef = row.id,
    ownerMemberId = row.ownerMemberId,
    sourceKind = row.sourceKind,
)

/**
 * Rebuild every transaction from the stored messages.
 *
 * Total and idempotent, with no incremental path and no watermark: running it twice over the
 * same `sms_source` must produce byte-identical rows, and that property is what makes shipping
 * a parser fix a thing you can do on a Tuesday. About 8,000 rows land in well under a second.
 *
 * Cancelled half way it simply never writes [META_PARSER_VER], so the next launch derives again.
 * Correct by construction rather than by bookkeeping.
 *
 * ponytail: full rebuild every time. Add an incremental path when sms_source passes ~50k rows
 * or measured rebuild time passes ~2s — [META_DERIVE_MS] records the real trigger.
 */
suspend fun derive(
    durable: DurableDb,
    derived: DerivedDb,
    extra: Map<String, Bank>,
    now: Long = System.currentTimeMillis(),
): Int {
    val started = System.currentTimeMillis()
    val sources = durable.smsSource().allOldestFirst()
    val bodies = sources.associate { it.srcHash to it.body }

    // Everything she has decided, loaded once. The two databases cannot be joined — that is the
    // point of them being two files — so the join happens here, in a map, keyed by the message.
    val pinned = durable.decisions().ofKind(DecisionKind.CATEGORY).associate { it.ref to it.value }
    val rules = durable.rules().active()
    val verdicts = durable.linkDecisions().all()

    var written = 0
    derived.withTransaction {
        derived.txn().deleteAll()
        derived.classes().deleteAll()
        derived.links().deleteAll()

        val all = mutableListOf<Txn>()
        for (chunk in sources.chunked(500)) {
            val rows = chunk.flatMap { parseToRows(it, extra) }
            derived.txn().insertAll(rows)
            all += rows
            written += rows.size
        }
        // Typed in here, or on the iPhone and synced across. From this line down nothing
        // distinguishes them from a message — they classify, link and report identically.
        val manual = durable.manual().all().map(::manualToRow)
        derived.txn().insertAll(manual)
        all += manual
        written += manual.size

        val family = durable.familyTxns().all().map(::familyToRow)
        derived.txn().insertAll(family)
        all += family
        written += family.size

        val links = findLinks(all, verdicts) { bodies[it.srcHash].orEmpty() }
        derived.links().putAll(links)

        val transfers = transferRefs(links)
        derived.classes().putAll(
            all.map { classify(it, rules, pinned[it.ref], transfers) }
        )
    }
    derived.meta().put(LedgerMeta(META_PARSER_VER, PARSER_VERSION.toString()))
    derived.meta().put(LedgerMeta(META_DERIVED_AT, now.toString()))
    derived.meta().put(LedgerMeta(META_DERIVE_MS, (System.currentTimeMillis() - started).toString()))
    return written
}

/**
 * Whether the stored transactions were produced by the reading this build ships.
 *
 * True when the parser has moved on, and true when this database was rebuilt from scratch —
 * which is the same question, because the marker lives here and dies with the rows.
 */
suspend fun needsDerive(derived: DerivedDb): Boolean =
    derived.meta().get(META_PARSER_VER)?.toIntOrNull() != PARSER_VERSION

/**
 * Put the shipped categories and rules in place, and keep them current.
 *
 * REPLACE by id, so a build that renames a category or retunes a shipped rule takes effect —
 * while anything she made, which carries an id of its own, is untouched. Archiving is how a
 * builtin retires; deleting one would orphan every row that named it.
 */
suspend fun seedBuiltins(durable: DurableDb, now: Long = System.currentTimeMillis()) {
    durable.categories().putAll(BUILTIN_CATEGORIES.map { it.copy(updatedAt = now) })
    durable.rules().putAll(BUILTIN_RULES.map { it.copy(createdAt = now, updatedAt = now) })
}

/** How many transactions are waiting for her, which is the only number the deck needs. */
suspend fun reviewCount(derived: DerivedDb): Int =
    derived.classes().reviewCount() + derived.links().askCount()

/** One line of the timeline: what happened, what it was filed as, and whether that is settled. */
data class LedgerEntry(
    val txn: Txn,
    val categoryId: String,
    val categoryFa: String,
    val confidence: Int,
    val needsReview: Boolean,
    /** The later leg of a duplicate. Shown struck through, never deleted, always undoable. */
    val duplicate: Boolean,
    /** Both legs of a settled transfer. Counted in neither income nor spending. */
    val transfer: Boolean,
    val ownerMemberId: String = "",
    val ownerName: String = "",
    val categoryEditorName: String = "",
)

/** Everything the ledger screens need, read in one pass. */
data class LedgerView(
    val entries: List<LedgerEntry> = emptyList(),
    val categories: List<Category> = emptyList(),
    val goals: List<GoalProgress> = emptyList(),
    /** ref → yes | no | needed, for the ones she has answered. */
    val worthIt: Map<String, String> = emptyMap(),
    /**
     * The marks she picked, by category name — [LocalCustomGlyphs]'s contents.
     *
     * Read off every category rather than off [categories], archived ones included, for the same
     * reason the names are: a row filed under a category she has retired keeps saying what it was
     * filed as, and it must keep drawing it too.
     */
    val marks: Map<String, CategoryGlyph> = emptyMap(),
    val ready: Boolean = false,
) {
    /**
     * Everything she actually has to answer, biggest first.
     *
     * Uncapped on purpose: this list is both the deck and the number on the tab badge, and a
     * badge that reads 12 against a backlog of 67 is telling her something false about her own
     * ledger. The deck is finishable because she can close it at any card, not because the
     * count was trimmed to look finishable. The only ceiling left is the [ledgerEntries] window
     * — anything older than the newest 300 transactions is not loaded, so it cannot be counted.
     */
    val review: List<LedgerEntry> by lazy {
        entries.filter { it.needsReview && !it.duplicate && !it.transfer }
            .sortedByDescending { it.txn.amountRial ?: 0L }
    }
}

data class LedgerEntries(
    val entries: List<LedgerEntry>,
    val categories: List<Category>,
    val categoryDecisions: Map<String, TxnDecision>,
    val marks: Map<String, CategoryGlyph> = emptyMap(),
)

suspend fun ledgerEntries(derived: DerivedDb, durable: DurableDb, limit: Int = 300): LedgerEntries {
    val transactions = derived.txn().newest(limit)
    val categories = durable.categories().all()
    // Every category ever, for the names — a transaction filed under one she has since retired
    // still says what she filed it as. `categories` stays the live list: that one is the picker.
    val everyCategory = durable.categories().withArchived()
    val names = everyCategory.associate { it.id to it.nameFa }
    val members = durable.familyMembers().all().associateBy { it.id }
    val currentMemberId = durable.meta().get(META_SYNC_MEMBER).orEmpty()
    val categoryDecisions = durable.decisions().ofKind(DecisionKind.CATEGORY).associateBy { it.ref }
    val links = derived.links().all()
    val classes = derived.classes().all().associateBy { it.ref }
    val hidden = hiddenRefs(links)
    val transfers = transferRefs(links)
    val entries = transactions.map { txn ->
        val filed = classes[txn.ref]
        val ownerMemberId = txn.ownerMemberId.ifBlank { currentMemberId }
        val categoryEditorId = categoryDecisions[txn.ref]?.memberId.orEmpty()
        LedgerEntry(
            txn = txn,
            categoryId = filed?.categoryId ?: CAT_UNCATEGORISED,
            categoryFa = names[filed?.categoryId] ?: "دسته‌بندی نشده",
            confidence = filed?.confidence ?: Confidence.NONE,
            needsReview = filed?.needsReview ?: true,
            duplicate = txn.ref in hidden,
            // A detector's pair, or her own answer. Both mean the same thing to every total, so
            // they are the same flag: `spendable`, the day's net and the deck all read this one
            // field, and only this field keeps a transfer out of income and out of spending.
            transfer = txn.ref in transfers || filed?.categoryId == CAT_TRANSFER,
            ownerMemberId = ownerMemberId,
            ownerName = members[ownerMemberId]?.name.orEmpty(),
            categoryEditorName = members[categoryEditorId]?.name.orEmpty(),
        )
    }
    return LedgerEntries(entries, categories, categoryDecisions, customGlyphs(everyCategory))
}

suspend fun ledgerView(derived: DerivedDb, durable: DurableDb, limit: Int = 300): LedgerView {
    val ledger = ledgerEntries(derived, durable, limit)
    val answers = durable.decisions().ofKind(DecisionKind.WORTH_IT)
        .mapNotNull { d -> d.value?.let { d.ref to it } }
        .toMap()
    val today = tehranDay(System.currentTimeMillis())
    val goals = durable.goals().active().map { goalProgress(it, ledger.entries, today) }
    return LedgerView(ledger.entries, ledger.categories, goals, answers, ledger.marks, ready = true)
}

/**
 * What an account holds, worked out rather than accumulated.
 *
 * The newest evidence wins, from either source: a balance the bank stated and a figure she
 * typed in compete on time alone. The bank takes a tie, because a stated balance is the bank's
 * own arithmetic and hers is a memory.
 *
 * Everything the old left fold guaranteed falls out of this more cleanly. A stated balance still
 * beats a computed delta — it *is* the anchor. An out-of-order message needs no guard, because
 * it is just a row with a timestamp. And a parser fix can repair a balance, which a fold could
 * only ever do by wiping and re-folding in order.
 */
fun deriveBalance(
    accountId: String,
    transactions: List<Txn>,
    typed: BalanceAnchor?,
): DerivedBalance? {
    if (transactions.isEmpty() && typed == null) return null
    val ordered = transactions.sortedWith(compareBy({ it.at }, { it.ref }))
    val newestAt = maxOf(ordered.lastOrNull()?.at ?: 0L, typed?.at ?: 0L)

    val stated = ordered.lastOrNull { it.balanceRial != null }
        ?.let { Anchor(it.balanceRial!!, it.at, it.ref) }
    val hers = typed?.let { Anchor(it.balanceRial, it.at, "") }
    // The bank takes a tie: a stated balance is the bank's own arithmetic, hers is a memory.
    val anchor = when {
        stated != null && hers != null -> if (hers.at > stated.at) hers else stated
        else -> stated ?: hers
    }
        ?: return DerivedBalance(
            accountId,
            // Nothing has ever stated a figure, so this is the change since we started reading
            // and not a balance at all. Reported, flagged, and kept out of every total —
            // exactly as an asset with no rate already is. Start on a withdrawal and it is
            // negative, and counting that would take money off her total that never existed.
            ordered.sumOf { it.signedRial ?: 0L },
            newestAt,
            anchored = false,
        )

    // Strictly after: a message that states a balance *and* a delta states the balance after
    // the transaction, so adding its own delta on top would count it twice.
    val since = ordered
        .filter { it.at > anchor.at || (it.at == anchor.at && it.ref > anchor.ref) }
        .sumOf { it.signedRial ?: 0L }
    return DerivedBalance(accountId, anchor.rial + since, newestAt, anchored = true)
}

suspend fun balanceOf(derived: DerivedDb, durable: DurableDb, accountId: String): DerivedBalance? =
    deriveBalance(
        accountId,
        derived.txn().forAccount(accountId),
        durable.anchors().newest(accountId),
    )

/** Every account the ledger knows about, with what it holds. */
suspend fun allBalances(derived: DerivedDb, durable: DurableDb): List<DerivedBalance> {
    val fromTransactions = derived.txn().accountIds()
    val anchors = durable.anchors().all()
        .groupBy { it.accountId }
        .mapValues { (_, rows) -> rows.maxByOrNull { it.at }!! }
    val fromAnchors = anchors.keys
    return (fromTransactions + fromAnchors).distinct()
        .mapNotNull { accountId ->
            deriveBalance(accountId, derived.txn().forAccount(accountId), anchors[accountId])
        }
}
