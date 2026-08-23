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
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * `durable.db` — the messages themselves, and later everything she decides about them.
 *
 * Nothing a parser computed lives here. That is the whole point of the split: a parser fix must
 * be shippable for ever without destroying a year of her corrections, and one database with a
 * naming convention makes that a discipline while two files make it something SQLite enforces.
 * `DELETE FROM txn` in the derived database cannot reach across a file boundary.
 *
 * The inbox is the source for *ingest* and nothing else. It stopped being a trustworthy source
 * of truth the moment the horizon became thirteen months of itemised history: SMS apps prune,
 * people clear bank threads, and a phone migration loses messages routinely. Once a message is
 * here, re-deriving the ledger from it is offline, instant, needs no permission, and still works
 * after she revokes READ_SMS.
 */
@Database(
    entities = [
        SmsSource::class, DurableMeta::class, BalanceAnchor::class,
        Category::class, Rule::class, TxnDecision::class, LinkDecision::class,
        ManualTxn::class, Goal::class, FamilyMember::class, FamilyTxn::class,
        SyncPublication::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class DurableDb : RoomDatabase() {
    abstract fun smsSource(): SmsSourceDao
    abstract fun meta(): DurableMetaDao
    abstract fun anchors(): BalanceAnchorDao
    abstract fun categories(): CategoryDao
    abstract fun rules(): RuleDao
    abstract fun decisions(): TxnDecisionDao
    abstract fun linkDecisions(): LinkDecisionDao
    abstract fun manual(): ManualTxnDao
    abstract fun goals(): GoalDao
    abstract fun familyMembers(): FamilyMemberDao
    abstract fun familyTxns(): FamilyTxnDao
    abstract fun syncPublications(): SyncPublicationDao

    companion object {
        @Volatile private var instance: DurableDb? = null

        /** Balances she typed in herself, which no rescan can ever reconstruct. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `balance_anchor` (" +
                        "`id` TEXT NOT NULL, `account_id` TEXT NOT NULL, `at` INTEGER NOT NULL, " +
                        "`balance_rial` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_balance_anchor_account_id_at` " +
                        "ON `balance_anchor` (`account_id`, `at`)"
                )
            }
        }

        /** Categories, rules, and every answer she gives about a transaction. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `category` (`id` TEXT NOT NULL, " +
                        "`parent_id` TEXT, `name_fa` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`sort` INTEGER NOT NULL, `builtin` INTEGER NOT NULL, " +
                        "`archived` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rule` (`id` TEXT NOT NULL, " +
                        "`priority` INTEGER NOT NULL, `category_id` TEXT NOT NULL, " +
                        "`p_merchant_norm` TEXT, `p_merchant_like` TEXT, `p_addr_key` TEXT, " +
                        "`p_bank` TEXT, `p_channel` TEXT, `p_direction` TEXT, " +
                        "`p_min_rial` INTEGER, `p_max_rial` INTEGER, `p_instrument` TEXT, " +
                        "`enabled` INTEGER NOT NULL, `builtin` INTEGER NOT NULL, " +
                        "`origin_ref` TEXT, `created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rule_enabled_deleted_priority` " +
                        "ON `rule` (`enabled`, `deleted`, `priority`)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rule_p_merchant_norm` ON `rule` (`p_merchant_norm`)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `txn_decision` (`id` TEXT NOT NULL, " +
                        "`ref` TEXT NOT NULL, `kind` TEXT NOT NULL, `value` TEXT, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_txn_decision_ref_kind` " +
                        "ON `txn_decision` (`ref`, `kind`)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_txn_decision_updated_at` " +
                        "ON `txn_decision` (`updated_at`)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `link_decision` (`id` TEXT NOT NULL, " +
                        "`a_ref` TEXT NOT NULL, `b_ref` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`verdict` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_link_decision_a_ref_b_ref_kind` " +
                        "ON `link_decision` (`a_ref`, `b_ref`, `kind`)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_link_decision_updated_at` " +
                        "ON `link_decision` (`updated_at`)"
                )
            }
        }

        /** Transactions somebody typed in, here or on another device in the household. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `manual_txn` (`id` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `day` INTEGER NOT NULL, " +
                        "`amount_rial` INTEGER NOT NULL, `account_id` TEXT, " +
                        "`category_id` TEXT, `merchant` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_manual_txn_day` ON `manual_txn` (`day`)"
                )
            }
        }

        /** Goals, which are the one thing this app is willing to encourage anybody about. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goal` (`id` TEXT NOT NULL, " +
                        "`name_fa` TEXT NOT NULL, `target_rial` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, `category_id` TEXT, `period` TEXT NOT NULL, " +
                        "`starts_on` INTEGER NOT NULL, `ends_on` INTEGER, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        /** Member identities and parsed family transactions. Raw SMS stays in sms_source. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `txn_decision` ADD COLUMN `member_id` TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE `txn_decision` ADD COLUMN `family_ref` TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_txn_decision_family_ref_kind` " +
                        "ON `txn_decision` (`family_ref`, `kind`)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `family_member` (`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `shares_sms` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `family_txn` (`id` TEXT NOT NULL, " +
                        "`owner_member_id` TEXT NOT NULL, `source_kind` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `day` INTEGER NOT NULL, `amount_rial` INTEGER NOT NULL, " +
                        "`bank` TEXT NOT NULL, `merchant` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_family_txn_day` ON `family_txn` (`day`)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_family_txn_owner_member_id` " +
                        "ON `family_txn` (`owner_member_id`)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_publication` (`id` TEXT NOT NULL, " +
                        "`source_kind` TEXT NOT NULL, `content_hash` TEXT NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** A mark of her own on a category she made. Blank on everything that shipped. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `category` ADD COLUMN `glyph` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun get(context: Context): DurableDb = instance ?: synchronized(this) {
            instance ?: Room
                .databaseBuilder(context.applicationContext, DurableDb::class.java, "durable.db")
                // No fallbackToDestructiveMigration, now or ever. Every migration on this
                // database is written by hand and tested. Losing this file is losing the only
                // copy of messages her inbox may no longer have.
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7,
                )
                .build()
                .also { instance = it }
        }
    }
}

/**
 * A balance she typed in herself.
 *
 * Only hers. A bank's own stated مانده is something a parser read, so it is derived data and
 * lives on the transaction row. These two are conflated today in `BankAccount.anchored`, which
 * is set both by a stated balance and by her typing one, and that is why the migration treats
 * every inherited anchor as hers: a figure of hers wrongly discarded is unrecoverable and
 * silent, while a bank's wrongly kept is superseded by the bank's next message.
 */
@Entity(tableName = "balance_anchor", indices = [Index("account_id", "at")])
data class BalanceAnchor(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    /** The instant the balance was true, not when she typed it. */
    val at: Long,
    @ColumnInfo(name = "balance_rial") val balanceRial: Long,
    /** `user` or `migrated`. */
    val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * A transaction somebody typed in — here, or on the iPhone, which has no other way to add one.
 *
 * The id *is* the ledger reference: `ref = "m:" + id`. That keeps a manual entry in the same
 * namespace as an SMS-derived one without ever colliding with it, so every decision, link and
 * report treats the two identically.
 */
@Entity(tableName = "manual_txn", indices = [Index("day")])
data class ManualTxn(
    @PrimaryKey val id: String,
    val at: Long,
    val day: Long,
    /** Signed: negative is money out. Whole Rial, like everything else in the ledger. */
    @ColumnInfo(name = "amount_rial") val amountRial: Long,
    @ColumnInfo(name = "account_id") val accountId: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    val merchant: String = "",
    val note: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
)

/** A person in the family. Names and sharing status are encrypted before synchronization. */
@Entity(tableName = "family_member")
data class FamilyMember(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "shares_sms") val sharesSms: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
)

/** A parsed transaction shared by another member. It never contains the original SMS body. */
@Entity(
    tableName = "family_txn",
    indices = [Index("day"), Index("owner_member_id")],
)
data class FamilyTxn(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_member_id") val ownerMemberId: String,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    val at: Long,
    val day: Long,
    /** Signed whole Rial. */
    @ColumnInfo(name = "amount_rial") val amountRial: Long,
    val bank: String = "MANUAL",
    val merchant: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
)

/** What this device has successfully published, used to send reliable unshare tombstones. */
@Entity(tableName = "sync_publication")
data class SyncPublication(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
)

@Dao
interface ManualTxnDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: ManualTxn)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<ManualTxn>)

    @Query("SELECT * FROM manual_txn WHERE deleted = 0 ORDER BY at DESC")
    suspend fun all(): List<ManualTxn>

    @Query("SELECT COUNT(*) FROM manual_txn WHERE deleted = 0")
    suspend fun count(): Int

    /**
     * Erased outright, not tombstoned — the one place in this file that is allowed to.
     *
     * `deleted = 1` is how a transaction *she* entered goes away: the row stays so the other
     * phones in the household learn it is gone. Nothing here was ever hers, so there is nobody to
     * tell. See [DEMO_PREFIX].
     */
    @Query("DELETE FROM manual_txn WHERE id LIKE :prefix || '%'")
    suspend fun deleteWithIdPrefix(prefix: String)
}

@Dao
interface FamilyMemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: FamilyMember)

    @Query("SELECT * FROM family_member WHERE deleted = 0 ORDER BY name, id")
    suspend fun all(): List<FamilyMember>

    @Query("SELECT * FROM family_member WHERE id = :id LIMIT 1")
    suspend fun get(id: String): FamilyMember?
}

@Dao
interface FamilyTxnDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: FamilyTxn)

    @Query("SELECT * FROM family_txn WHERE deleted = 0 ORDER BY at DESC")
    suspend fun all(): List<FamilyTxn>

    @Query("SELECT * FROM family_txn WHERE id = :id LIMIT 1")
    suspend fun get(id: String): FamilyTxn?
}

@Dao
interface SyncPublicationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<SyncPublication>)

    @Query("SELECT * FROM sync_publication")
    suspend fun all(): List<SyncPublication>
}

@Dao
interface BalanceAnchorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: BalanceAnchor)

    @Query(
        "SELECT * FROM balance_anchor WHERE account_id = :accountId AND deleted = 0 " +
            "ORDER BY at DESC LIMIT 1"
    )
    suspend fun newest(accountId: String): BalanceAnchor?

    @Query("SELECT * FROM balance_anchor WHERE deleted = 0")
    suspend fun all(): List<BalanceAnchor>

    @Query("SELECT COUNT(*) FROM balance_anchor WHERE deleted = 0")
    suspend fun count(): Int
}

/**
 * One inbox row, kept verbatim.
 *
 * [sender], [addrKey], [body] and [at] are stored rather than only hashed, which is what makes
 * [srcHash] reversible: if a v2 of the hash is ever forced, a migration recomputes it from these
 * columns and rewrites every decision that points at it, in one transaction. A hash whose inputs
 * you did not keep is a trapdoor. The one exception is a row whose stamp [clampAt] had to move,
 * where the hash keeps the raw stamp the columns no longer hold — see [ingestBankSms].
 */
@Entity(
    tableName = "sms_source",
    indices = [Index("at"), Index("addr_key", "at")],
)
data class SmsSource(
    @PrimaryKey @ColumnInfo(name = "src_hash") val srcHash: String,
    /** Exactly as the provider gave it, spacing and all. */
    val sender: String,
    /** [srcAddrKeyV1] of [sender] — the hash input, stored so the hash can be recomputed. */
    @ColumnInfo(name = "addr_key") val addrKey: String,
    /** Verbatim. Never normalised on the way in; normalising is the parser's job, downstream. */
    val body: String,
    /** `Telephony.Sms.DATE`, epoch milliseconds — [clampAt]-ed at ingest, see [ingestBankSms]. */
    val at: Long,
    @ColumnInfo(name = "ingested_at") val ingestedAt: Long,
    /** Which generation of [srcHash] produced the key. Bumped only by a hash migration. */
    @ColumnInfo(name = "key_gen") val keyGen: Int = 1,
)

@Entity(tableName = "durable_meta")
data class DurableMeta(@PrimaryKey val k: String, val v: String)

@Dao
interface SmsSourceDao {
    /**
     * IGNORE, not REPLACE: re-reading a stretch of inbox must be free and must not rewrite
     * [SmsSource.ingestedAt] on rows that were already here. The primary key is the whole
     * deduplication mechanism now — there is no seen-set and no cap on how many keys it holds.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<SmsSource>): List<Long>

    /** Oldest first — the order a rebuild wants, so a stated balance is never re-applied late. */
    @Query("SELECT * FROM sms_source ORDER BY at ASC")
    suspend fun allOldestFirst(): List<SmsSource>

    @Query("SELECT COUNT(*) FROM sms_source")
    suspend fun count(): Int

    @Query("SELECT MAX(at) FROM sms_source")
    suspend fun newestAt(): Long?

    /** When something last actually arrived, which is what [clockRunsAhead] holds `now` against. */
    @Query("SELECT MAX(ingested_at) FROM sms_source")
    suspend fun newestIngestedAt(): Long?

    @Query("SELECT addr_key FROM sms_source WHERE src_hash = :srcHash")
    suspend fun addrKeyOf(srcHash: String): String?

    @Query("SELECT body FROM sms_source WHERE src_hash = :srcHash")
    suspend fun bodyOf(srcHash: String): String?

    @Query("DELETE FROM sms_source WHERE at < :before")
    suspend fun deleteBefore(before: Long): Int

    /**
     * The backstop against a pathological inbox. `LIMIT -1 OFFSET n` is the only way to say
     * "everything past the first n" on the SQLite that API 24 ships (3.9.2 — no window
     * functions, no UPSERT, no generated columns).
     */
    @Query(
        "DELETE FROM sms_source WHERE src_hash IN " +
            "(SELECT src_hash FROM sms_source ORDER BY at DESC LIMIT -1 OFFSET :keep)"
    )
    suspend fun trimToNewest(keep: Int): Int
}

@Dao
interface DurableMetaDao {
    @Query("SELECT v FROM durable_meta WHERE k = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: DurableMeta)
}

/** How far back the ledger keeps messages it has read, in Jalali months. */
const val SOURCE_HORIZON_MONTHS = 13

/**
 * Two months of slack below the horizon, so a horizon rolling forward on the first of the month
 * does not throw away a month she has not finished reviewing.
 */
const val SOURCE_GRACE_DAYS = 62L

/**
 * ponytail: a flat row cap as a backstop against a pathological inbox, not a design goal.
 * Thirteen months at a heavy twenty messages a day is about 8,000 rows and 3 MB.
 */
const val SOURCE_HARD_CAP = 50_000

private const val SOURCE_SCANNED_TO = "source_scanned_to"

/**
 * The oldest message the ledger will hold on to, as an epoch millisecond.
 *
 * Retention, not reach. It stays a year deep so a ledger that has already been backfilled — or
 * one filled month by month as she uses the app — keeps its year-over-year comparisons.
 */
fun sourceHorizon(now: Long): Long =
    tehranDayStart(jalaliMonthsBack(tehranDay(now), SOURCE_HORIZON_MONTHS))

/** Below this, a stored message is past the horizon plus its grace and can go. */
fun sourcePruneFloor(now: Long): Long = sourceHorizon(now) - SOURCE_GRACE_DAYS * DAY_MS

/** Slack for a bank or carrier clock running a little fast; further out than this is not a time. */
const val AT_FUTURE_SLACK_MS = 48 * 60 * 60 * 1000L

/**
 * A timestamp forced into the span the ledger can live with.
 *
 * A restore tool that writes `DATE` in microseconds stamps a message fifty thousand years out;
 * a negative one stamps it before time. Either way the figure in the body is real money and only
 * its stamp is nonsense — so the money is kept and the day approximated, never the other way
 * round. Left unclamped, the row sorts newest for ever, sits under [SmsSourceDao.trimToNewest]'s
 * protection while real months fall off, and derives a day the Jalali arithmetic cannot even
 * represent (jalCal throws outside years −61..3177).
 */
fun clampAt(at: Long, now: Long): Long = at.coerceIn(0L, now + AT_FUTURE_SLACK_MS)

/** Longer than any real gap between a scan and the newest thing it ever stored. */
const val INGEST_CLOCK_SKEW_MAX_MS = 30L * DAY_MS

/**
 * Whether the wall clock has run away from reality.
 *
 * A clock more than a month past everything ever ingested is a broken clock, not a year of
 * silence: real silence ends the moment a message arrives, and ingesting it moves
 * `MAX(ingested_at)` forward with it. Null means an empty archive, which has nothing to protect.
 */
fun clockRunsAhead(now: Long, lastIngestedAt: Long?): Boolean =
    lastIngestedAt != null && now - lastIngestedAt > INGEST_CLOCK_SKEW_MAX_MS

/**
 * The identity of one inbox row, frozen for ever.
 *
 * This is a **copy** of [senderKey] as it stands today, and it must never be changed again even
 * when [senderKey] itself changes. That is not duplication by accident.
 *
 * [senderKey] is a live function. It has already moved once — the whitespace collapse noted in
 * the scan loop — and it will move again the next time a carrier delivers a header in a shape
 * nobody expected. That is exactly right for "which bank is this", where the answer should
 * improve. It is exactly wrong for a key that a year of her corrections hangs off: the day it
 * changes, every hash changes, and every correction orphans at once.
 *
 * So the two are allowed to drift apart, on purpose. If this one ever has to change, it becomes
 * `srcAddrKeyV2`, [SmsSource.keyGen] goes to 2, and a migration rewrites the stored keys and
 * every reference to them together.
 */
fun srcAddrKeyV1(sender: String): String {
    val trimmed = sender.trim()
    if (trimmed.any { it.isLetter() }) return trimmed.replace(FROZEN_WHITESPACE, " ").lowercase()
    val digits = buildString {
        for (c in trimmed) when (c) {
            in '0'..'9' -> append(c)
            in '۰'..'۹' -> append('0' + (c - '۰'))
            in '٠'..'٩' -> append('0' + (c - '٠'))
            else -> Unit
        }
    }
    return when {
        digits.length == 12 && digits.startsWith("98") -> digits.drop(2)
        digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
        else -> digits
    }
}

private val FROZEN_WHITESPACE = Regex("[\\s\\p{Z}]+")

/**
 * NUL, which cannot occur in an SMS body, so no message can be shaped to forge another's hash by
 * moving the boundary between its fields.
 */
private val FIELD_SEP = Char(0).toString()

/**
 * The identity of one message: a function of the **input** rather than of any interpretation of
 * it, which is the entire reason it survives a parser change. Every alternative — the amount,
 * the category, the account, a row id — is a function of the interpretation, and so changes at
 * precisely the moment the pointer has to hold.
 */
fun srcHash(sender: String, body: String, at: Long): String =
    sha256Hex(srcAddrKeyV1(sender) + FIELD_SEP + at + FIELD_SEP + body.trim())

/** How many transactions one message produced. Zero for nearly every message there is. */
fun refOf(srcHash: String, seq: Int = 0): String = "s:$srcHash:$seq"

/** The reference for a transaction she entered herself. */
fun manualRef(id: String): String = "m:$id"

/** The local, compact reference for a parsed transaction received from another family member. */
fun familyLocalRef(familyRef: String): String = "f:${sha256Hex(familyRef)}"

/**
 * Copy every recognised message the inbox has that we do not already hold.
 *
 * The privacy gate is the point of the [bankOf] check, and it sits one layer earlier than the
 * parser's own: a body is stored **only** when the sender is a bank we read. No one-time code,
 * no advert, no message from her family ever reaches this table — and the manifest's
 * `allowBackup="false"` keeps what does off Google's servers.
 *
 * Returns how many new rows were stored.
 */
suspend fun ingestBankSms(
    context: Context,
    db: DurableDb,
    extra: Map<String, Bank>,
    now: Long = System.currentTimeMillis(),
): Int {
    if (!canReadSms(context)) return 0
    // A first ingest starts here, at `now`: nothing already sitting in the inbox is read. This
    // used to start at the first of the current Jalali month, which let the calendar decide how
    // much homework a new ledger opened on — four weeks of it on the 29th, none on the 1st.
    // Reaching back is [rewindIngest]'s job, and that one is asked for. A watermark, once
    // written, always wins, so this is only ever consulted when nothing has been read yet.
    val since = db.meta().get(SOURCE_SCANNED_TO)?.toLongOrNull() ?: now
    val messages = readSmsInbox(context, since)
    if (messages.isEmpty()) return 0

    // Read before anything is written, so it describes the archive and not this scan. One scan
    // with the phone at 2035 used to run the prune below against 2035's floor — the whole
    // archive, gone — and park the watermark ten years out, freezing ingest long after the
    // clock was fixed. When the clock is that far past everything ever received, nothing
    // destructive and nothing irreversible happens on its say-so.
    val lastIngest = db.smsSource().newestIngestedAt()
    val brokenClock = clockRunsAhead(now, lastIngest)
    val ceiling = if (brokenClock) lastIngest!! + INGEST_CLOCK_SKEW_MAX_MS else now

    var stored = 0
    // Chunked so a kill part way through resumes at the last chunk boundary rather than starting
    // again: the rows and the watermark move in one transaction, so they can never disagree about
    // what has been read. The next read is `DATE >=` the watermark — see [readSmsInbox] — so the
    // boundary row is read twice and the primary key absorbs it; strictly-after lost the second
    // of two same-millisecond rows straddling a chunk cut for ever.
    for (chunk in messages.chunked(500)) {
        val rows = chunk.mapNotNull { m ->
            if (bankOf(m.from, extra) == null) return@mapNotNull null
            SmsSource(
                // The hash keeps the raw stamp: identity must be whatever every future re-read
                // of the same inbox row computes, and the clamp below moves with `now`. The one
                // cost is that a clamped row's stored columns no longer recompute its own hash —
                // accepted, because that stamp was never a real time to begin with.
                srcHash = srcHash(m.from, m.body, m.at),
                sender = m.from,
                addrKey = srcAddrKeyV1(m.from),
                body = m.body,
                // The money is never wrong, only its day — see [clampAt].
                at = clampAt(m.at, now),
                ingestedAt = now,
            )
        }
        // Never past now, or past what a broken clock may claim: one inbox row stamped in 2030
        // by a restored backup or a skewed carrier clock would otherwise park the watermark
        // there and freeze ingest for ever.
        val watermark = minOf(chunk.maxOf { it.at }, ceiling)
        db.withTransaction {
            stored += db.smsSource().insertAll(rows).count { it != -1L }
            db.meta().put(DurableMeta(SOURCE_SCANNED_TO, watermark.toString()))
        }
    }
    if (!brokenClock) {
        db.withTransaction {
            db.smsSource().deleteBefore(sourcePruneFloor(now))
            db.smsSource().trimToNewest(SOURCE_HARD_CAP)
        }
    }
    return stored
}

private val RANDOM by lazy { java.security.SecureRandom() }

/**
 * A time-ordered UUID. The first six bytes are the millisecond, so ids sort by creation and an
 * index over them stays local — which matters once these rows are the unit the sync layer moves
 * and orders. The JDK has no v7 of its own.
 */
fun uuid7(now: Long = System.currentTimeMillis()): String {
    val b = ByteArray(16)
    RANDOM.nextBytes(b)
    for (i in 0..5) b[i] = (now shr (40 - 8 * i)).toByte()
    b[6] = ((b[6].toInt() and 0x0f) or 0x70).toByte() // version 7
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte() // RFC variant
    val hex = "0123456789abcdef"
    return buildString(36) {
        for (i in b.indices) {
            if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
            val v = b[i].toInt() and 0xff
            append(hex[v ushr 4]); append(hex[v and 0x0f])
        }
    }
}

private const val ANCHORS_MIGRATED = "anchors_migrated"

/**
 * Carry the balances she anchored by hand into the ledger, once.
 *
 * `BankAccount.anchored` is set both by a bank stating a مانده and by her typing a figure in,
 * and nothing stored today can tell those apart. **Every one of them is treated as hers.** The
 * risk is asymmetric and only points one way: a figure of hers wrongly discarded is
 * unrecoverable and silent, while a bank's wrongly preserved is superseded the moment that bank
 * states another balance at a later time. Rows written from here on carry the distinction.
 *
 * Her total must not move on the launch that introduces the ledger. Anything else reads as the
 * app losing her money.
 */
suspend fun migrateAnchorsFromPrefs(accounts: List<BankAccount>, db: DurableDb, now: Long) {
    if (db.meta().get(ANCHORS_MIGRATED) != null) return
    db.withTransaction {
        for (account in accounts) {
            if (!account.anchored) continue
            db.anchors().put(
                BalanceAnchor(
                    id = uuid7(now),
                    accountId = account.bank,
                    at = account.updatedAt,
                    balanceRial = Math.round(account.balance * 10.0),
                    source = "migrated",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        db.meta().put(DurableMeta(ANCHORS_MIGRATED, now.toString()))
    }
}

/**
 * Reach back to the horizon again on the next ingest.
 *
 * Needed when a sender becomes readable that was not before — she confirms a new shortcode, or a
 * bank is added to the enum. Those messages were skipped by the gate above, so the watermark has
 * long since passed them and only a walk back to the horizon finds them. Idempotent: re-reading
 * costs time and nothing else, because the primary key absorbs everything already held.
 *
 * The horizon is written out rather than the watermark cleared. Clearing it would fall through to
 * `now` — reading nothing at all — and a sender she has just confirmed is one whose *history* she
 * is asking for. Retention reaches a year back, so nothing read here is pruned on the way out.
 */
suspend fun rewindIngest(db: DurableDb, now: Long = System.currentTimeMillis()) {
    db.meta().put(DurableMeta(SOURCE_SCANNED_TO, sourceHorizon(now).toString()))
}

