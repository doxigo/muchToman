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
 * Classification, which is a table of rules and nothing else.
 *
 * No model, no scoring, no learned weights. "Teach it once and it never asks again" is a row
 * being written, and the reason it can be that simple is that the alternative — anything that
 * infers — would be a thing that quietly changes its mind about money she already checked.
 */

// ─────────────────────────── categories ───────────────────────────

/** What a category does to a report. `transfer` is the one that must never count either way. */
object CategoryKind {
    const val EXPENSE = "expense"
    const val INCOME = "income"
    const val TRANSFER = "transfer"
}

@Entity(tableName = "category")
data class Category(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    @ColumnInfo(name = "name_fa") val nameFa: String,
    val kind: String,
    val sort: Int = 0,
    val builtin: Boolean = false,
    /** Never deleted, only archived: rows written last year still name it. */
    val archived: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)

const val CAT_UNCATEGORISED = "cat_uncategorised"
const val CAT_TRANSFER = "cat_transfer"
const val CAT_FEES = "cat_fees"
const val CAT_CASH = "cat_cash"
const val CAT_INCOME = "cat_income"

/**
 * The categories the app ships with.
 *
 * Setup does not ask her to build a taxonomy. The first block is what the parser can actually
 * tell apart from a bank SMS; the second is the household's own list, which no SMS will ever
 * name on its own — they are here so the choice exists the first time she files something by
 * hand, not because anything can guess them.
 *
 * The last four are the machine's: withdrawal, fee, income, transfer. They sort last because
 * they are what the app says about money, not what she spent it on.
 */
val BUILTIN_CATEGORIES: List<Category> = listOf(
    Category(CAT_UNCATEGORISED, nameFa = "دسته‌بندی نشده", kind = CategoryKind.EXPENSE, sort = 0, builtin = true),
    Category("cat_groceries", nameFa = "خواربار", kind = CategoryKind.EXPENSE, sort = 10, builtin = true),
    Category("cat_dining", nameFa = "رستوران و کافه", kind = CategoryKind.EXPENSE, sort = 20, builtin = true),
    Category("cat_transport", nameFa = "حمل و نقل", kind = CategoryKind.EXPENSE, sort = 30, builtin = true),
    Category("cat_bills", nameFa = "قبض‌ها", kind = CategoryKind.EXPENSE, sort = 40, builtin = true),
    Category("cat_health", nameFa = "سلامت", kind = CategoryKind.EXPENSE, sort = 50, builtin = true),
    Category("cat_shopping", nameFa = "خرید روزانه", kind = CategoryKind.EXPENSE, sort = 60, builtin = true),

    Category("cat_savings", nameFa = "پس‌انداز و سرمایه", kind = CategoryKind.EXPENSE, sort = 70, builtin = true),
    // EXPENSE, not TRANSFER: «انتقال بین حساب‌ها» is her own money moving and must not count,
    // while this is money that left for someone else — a کارت‌به‌کارت is spending with a nicer
    // name. TRANSFER would also hide it from the picker, which is the one place it is chosen.
    Category("cat_send", nameFa = "انتقال وجه", kind = CategoryKind.EXPENSE, sort = 80, builtin = true),
    Category("cat_gifts", nameFa = "هدیه و نیکوکاری", kind = CategoryKind.EXPENSE, sort = 90, builtin = true),
    Category("cat_beauty", nameFa = "زیبایی", kind = CategoryKind.EXPENSE, sort = 100, builtin = true),
    Category("cat_clothing", nameFa = "مد و پوشاک", kind = CategoryKind.EXPENSE, sort = 110, builtin = true),
    Category("cat_culture", nameFa = "فرهنگی و هنری", kind = CategoryKind.EXPENSE, sort = 120, builtin = true),
    Category("cat_home", nameFa = "خانه و کاشانه", kind = CategoryKind.EXPENSE, sort = 130, builtin = true),
    Category("cat_atina", nameFa = "خرج اتینا", kind = CategoryKind.EXPENSE, sort = 140, builtin = true),
    // The two halves of a قرض, which only balance out over time: neither is really spending or
    // really income, but EXPENSE and INCOME are the only kinds that stay visible — TRANSFER
    // means «not her decision, do not count», and money lent is very much a decision.
    Category("cat_loan", nameFa = "قرض", kind = CategoryKind.EXPENSE, sort = 150, builtin = true),
    // Sorted next to قرض and not next to قبض‌ها, though it is as fixed and as monthly as a bill.
    // A قسط is money going to a debt she already owes, and what she wants to see at the end of
    // the month is that number beside the قرض she has out — not buried in utilities.
    //
    // No shipped rule behind it. The builtin rules match on the channel a message came through,
    // and a قسط arrives as an ordinary برداشت on whichever channel the bank happened to use, so
    // there is nothing to key on that is not a guess. It earns a rule the first time she files
    // one and says «همیشه», which is the mechanism that already exists for exactly this.
    Category("cat_instalment", nameFa = "قسط و وام", kind = CategoryKind.EXPENSE, sort = 155, builtin = true),

    Category(CAT_CASH, nameFa = "برداشت نقدی", kind = CategoryKind.EXPENSE, sort = 160, builtin = true),
    Category(CAT_FEES, nameFa = "کارمزد", kind = CategoryKind.EXPENSE, sort = 170, builtin = true),
    // Last of the expenses rather than beside خرید, where it belongs by meaning. `sort` is grid
    // position, and the grid's colours are tuned against *which cells touch* — inserting this one
    // in the middle shifts every category after it into a new neighbourhood and lands two purples
    // side by side. Appending moves nothing, so every mark she has learned keeps its hue.
    Category("cat_tobacco", nameFa = "دخانیات", kind = CategoryKind.EXPENSE, sort = 175, builtin = true),

    Category(CAT_INCOME, nameFa = "درآمد", kind = CategoryKind.INCOME, sort = 180, builtin = true),
    // What درآمد is actually made of. درآمد stays, and stays first: it is what `rule_income` files
    // every incoming message as, so it is the answer she is confirming rather than one she has to
    // choose — these four are for when she wants to say more than «money came in».
    Category("cat_salary", nameFa = "حقوق", kind = CategoryKind.INCOME, sort = 182, builtin = true),
    Category("cat_bonus", nameFa = "پاداش", kind = CategoryKind.INCOME, sort = 184, builtin = true),
    Category("cat_sales", nameFa = "فروش", kind = CategoryKind.INCOME, sort = 186, builtin = true),
    Category("cat_invest_income", nameFa = "سود سرمایه‌گذاری", kind = CategoryKind.INCOME, sort = 188, builtin = true),
    // «پس‌گرفتن», not «پس‌دادن»: the ledger is hers, and on money coming in she is the one
    // getting it back — the other side is who gave it. Sorted beside درآمد because the picker
    // shows it on the same rows: the ones where money arrived.
    Category("cat_loan_back", nameFa = "پس‌گرفتن قرض", kind = CategoryKind.INCOME, sort = 190, builtin = true),
    // Last of the income side on purpose: «سایر» is where the eye goes after reading the others
    // and finding none of them, so it must not be the first thing read.
    Category("cat_income_other", nameFa = "سایر", kind = CategoryKind.INCOME, sort = 195, builtin = true),
    Category(CAT_TRANSFER, nameFa = "انتقال بین حساب‌ها", kind = CategoryKind.TRANSFER, sort = 200, builtin = true),
)

/**
 * A category she made herself, which is a row like any other — the only difference is that it
 * carries its own mark, because no build knows its name to look one up by.
 *
 * `sort = 500`, past every shipped category, and the same number [applyCategory] gives one that
 * arrives from another phone in the household: hers land after the app's in the picker, in the
 * order the database returns them, which is by name.
 */
fun customCategory(nameFa: String, kind: String, glyph: CategoryGlyph, now: Long): Category =
    Category(
        id = "cat_${uuid7(now)}",
        nameFa = nameFa.trim(),
        kind = kind,
        sort = 500,
        glyph = glyph.name,
        updatedAt = now,
    )

/**
 * The categories worth offering for one transaction.
 *
 * «دسته‌بندی نشده» is the absence of an answer, not one of them, and a transfer is something the
 * detector settles rather than something she files by hand.
 *
 * Direction does the rest, and it is what makes this list usable now that it is long: money that
 * arrived was never خواربار or مد و پوشاک, so offering them means reading past sixteen wrong
 * answers to reach the two right ones. A transaction the parser read no direction from gets
 * everything — that is the honest answer to «which way did this go», and guessing here would file
 * money on the wrong side of the report.
 */
fun categoryChoices(categories: List<Category>, direction: String?): List<Category> =
    categories.filter {
        it.id != CAT_UNCATEGORISED && when (direction) {
            "in" -> it.kind == CategoryKind.INCOME
            "out" -> it.kind == CategoryKind.EXPENSE
            else -> it.kind != CategoryKind.TRANSFER
        }
    }

// ─────────────────────────── rules ───────────────────────────

/**
 * One row is one rule, and one rule is a conjunction: every predicate that is not null must
 * match, and null means don't care.
 *
 * No OR, no NOT, no nesting. "Or" is two rows sharing a category and a priority, which covers
 * every real request without an expression tree to evaluate or debug.
 *
 * ponytail: conjunction only. Nobody can say "groceries unless it is over two million". Add a
 * mirrored set of `notP*` columns the first time somebody actually asks for that.
 */
@Entity(
    tableName = "rule",
    indices = [Index("enabled", "deleted", "priority"), Index("p_merchant_norm")],
)
data class Rule(
    @PrimaryKey val id: String,
    val priority: Int,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "p_merchant_norm") val pMerchantNorm: String? = null,
    @ColumnInfo(name = "p_merchant_like") val pMerchantLike: String? = null,
    @ColumnInfo(name = "p_addr_key") val pAddrKey: String? = null,
    @ColumnInfo(name = "p_bank") val pBank: String? = null,
    @ColumnInfo(name = "p_channel") val pChannel: String? = null,
    @ColumnInfo(name = "p_direction") val pDirection: String? = null,
    @ColumnInfo(name = "p_min_rial") val pMinRial: Long? = null,
    @ColumnInfo(name = "p_max_rial") val pMaxRial: Long? = null,
    @ColumnInfo(name = "p_instrument") val pInstrument: String? = null,
    val enabled: Boolean = true,
    val builtin: Boolean = false,
    /** The transaction she said «همیشه» on, so the rule can be undone and explained. */
    @ColumnInfo(name = "origin_ref") val originRef: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
    val deleted: Boolean = false,
) {
    /** How many predicates it names. More specific wins a tie on priority. */
    val specificity: Int
        get() = listOfNotNull(
            pMerchantNorm, pMerchantLike, pAddrKey, pBank, pChannel, pDirection,
            pMinRial, pMaxRial, pInstrument,
        ).size

    fun matches(txn: Txn): Boolean {
        if (!enabled || deleted) return false
        pMerchantNorm?.let { if (txn.merchantNorm != it) return false }
        pMerchantLike?.let { if (!txn.merchantNorm.contains(it)) return false }
        pBank?.let { if (txn.bank != it) return false }
        pChannel?.let { if (txn.channel != it) return false }
        pDirection?.let { if (txn.direction != it) return false }
        pInstrument?.let { if (txn.instrument != it) return false }
        pMinRial?.let { if ((txn.amountRial ?: return false) < it) return false }
        pMaxRial?.let { if ((txn.amountRial ?: return false) > it) return false }
        return true
    }
}

object Priority {
    /** She pinned this exact transaction. Not a rule at all — it short-circuits. */
    const val PINNED = 1000
    const val USER_EXACT_MERCHANT = 900
    const val USER_OTHER = 800
    const val SHIPPED = 500
}

/**
 * Confidence is an integer, not a probability, because nothing here estimates anything. It is
 * a label for *which kind of evidence matched*, and the review threshold is the whole learning
 * loop: a shipped guess lands under it and asks once; her answer writes a rule that lands over
 * it and never asks again.
 */
object Confidence {
    const val USER_PINNED = 100
    const val RULE_EXACT = 95
    const val RULE_NARROW = 85
    const val RULE_LIKE = 75
    const val BUILTIN_EXACT = 70
    const val BUILTIN_LIKE = 55
    const val CHANNEL_ONLY = 40
    const val NONE = 0
}

/** Below this a transaction goes to the deck and is asked about exactly once. */
const val REVIEW_BELOW = 70

/**
 * What the app ships knowing. Everything here is a channel the parser identified outright, so
 * it is right far more often than not — but it lands at [Confidence.CHANNEL_ONLY], under the
 * review threshold, so each shape is confirmed once and then never asked about again.
 */
val BUILTIN_RULES: List<Rule> = listOf(
    Rule("rule_atm", Priority.SHIPPED, CAT_CASH, pChannel = "atm", builtin = true),
    Rule("rule_fee", Priority.SHIPPED, CAT_FEES, pChannel = "fee", builtin = true),
    Rule("rule_bill", Priority.SHIPPED, CAT_BILLS_ID, pChannel = "bill", builtin = true),
    Rule("rule_pos", Priority.SHIPPED, CAT_SHOPPING_ID, pChannel = "pos", pDirection = "out", builtin = true),
    Rule("rule_income", Priority.SHIPPED, CAT_INCOME, pDirection = "in", builtin = true),
)

private const val CAT_BILLS_ID = "cat_bills"
private const val CAT_SHOPPING_ID = "cat_shopping"

/** How a transaction was filed, and whether she still has to look at it. */
@Entity(tableName = "txn_class", indices = [Index("needs_review", "ref")])
data class TxnClass(
    @PrimaryKey val ref: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    /** A durable rule's id, held as a value rather than a foreign key across the two files. */
    @ColumnInfo(name = "rule_id") val ruleId: String?,
    val confidence: Int,
    @ColumnInfo(name = "needs_review") val needsReview: Boolean,
)

private fun confidenceOf(rule: Rule): Int = when {
    rule.builtin && rule.pMerchantNorm != null -> Confidence.BUILTIN_EXACT
    rule.builtin && rule.pMerchantLike != null -> Confidence.BUILTIN_LIKE
    rule.builtin -> Confidence.CHANNEL_ONLY
    rule.pMerchantNorm != null -> Confidence.RULE_EXACT
    rule.pMerchantLike != null -> Confidence.RULE_LIKE
    rule.specificity >= 2 -> Confidence.RULE_NARROW
    else -> Confidence.RULE_LIKE
}

/**
 * File one transaction.
 *
 * [pinned] is a decision she made about this exact transaction and beats every rule. Otherwise
 * the highest-priority match wins, ties broken by specificity, then recency, then id — and that
 * last tiebreak is a sync requirement, not tidiness: without it two rules of equal standing
 * classify differently on two phones and the family ledger disagrees with itself for reasons
 * nobody can debug.
 */
fun classify(
    txn: Txn,
    rules: List<Rule>,
    pinned: String? = null,
    transferRefs: Set<String> = emptySet(),
): TxnClass {
    if (pinned != null) {
        return TxnClass(txn.ref, pinned, null, Confidence.USER_PINNED, needsReview = false)
    }
    // A confirmed transfer is not a spending decision to be classified; it is bookkeeping.
    if (txn.ref in transferRefs) {
        return TxnClass(txn.ref, CAT_TRANSFER, null, Confidence.USER_PINNED, needsReview = false)
    }
    val winner = rules
        .filter { it.matches(txn) }
        .maxWithOrNull(
            compareBy({ it.priority }, { it.specificity }, { it.updatedAt }, { it.id })
        )
        ?: return TxnClass(txn.ref, CAT_UNCATEGORISED, null, Confidence.NONE, needsReview = true)

    val confidence = confidenceOf(winner)
    return TxnClass(
        ref = txn.ref,
        categoryId = winner.categoryId,
        ruleId = winner.id,
        confidence = confidence,
        needsReview = confidence < REVIEW_BELOW,
    )
}

/**
 * «همیشه پرداخت‌هایی مثل این را خواربار حساب کن» — one row, applied to everything past and
 * future on the next derive, because classification is total and there is no backfill job.
 *
 * When the message named a merchant the rule keys on the merchant, which is the thing she
 * actually meant. When it did not, it keys on the sender and the channel together, which is the
 * closest the message comes to naming who was paid.
 */
fun ruleFrom(txn: Txn, categoryId: String, addrKey: String, now: Long, id: String = uuid7(now)): Rule {
    val hasMerchant = txn.merchantNorm.isNotEmpty()
    return Rule(
        id = id,
        priority = if (hasMerchant) Priority.USER_EXACT_MERCHANT else Priority.USER_OTHER,
        categoryId = categoryId,
        pMerchantNorm = txn.merchantNorm.takeIf { hasMerchant },
        pAddrKey = if (hasMerchant) null else addrKey,
        pBank = if (hasMerchant) null else txn.bank,
        pChannel = if (hasMerchant) null else txn.channel.takeIf { it != "unknown" },
        pDirection = txn.direction,
        originRef = txn.ref,
        createdAt = now,
        updatedAt = now,
    )
}

// ─────────────────────────── decisions she made ───────────────────────────

object DecisionKind {
    const val CATEGORY = "category"
    const val NOTE = "note"
    const val HIDE = "hide"
    const val WORTH_IT = "worth_it"
    const val ACCOUNT = "account"
    const val EXCLUDE = "exclude"
}

/**
 * One answer she gave about one transaction.
 *
 * «آیا ارزشش را داشت؟» gets no table of its own — it is `kind = worth_it`, and `createdAt`
 * already says which week she answered in. Every per-transaction answer arrives, syncs, replays
 * and undoes the same way, which is one mechanism instead of six.
 */
@Entity(
    tableName = "txn_decision",
    indices = [
        Index("ref", "kind", unique = true), Index("updated_at"),
        Index("family_ref", "kind"),
    ],
)
data class TxnDecision(
    @PrimaryKey val id: String,
    /** `s:<srcHash>:<seq>` or `m:<uuid>` — the message, never the row a parser made of it. */
    val ref: String,
    val kind: String,
    val value: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
    /** The family member who last made this decision. Blank on pre-family rows. */
    @ColumnInfo(name = "member_id", defaultValue = "''") val memberId: String = "",
    /** Stable encrypted-sync transaction id. Blank until the transaction enters a family. */
    @ColumnInfo(name = "family_ref", defaultValue = "''") val familyRef: String = "",
)

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<Category>)

    @Query("SELECT * FROM category WHERE archived = 0 ORDER BY sort, name_fa")
    suspend fun all(): List<Category>

    @Query("SELECT * FROM category WHERE id = :id LIMIT 1")
    suspend fun get(id: String): Category?

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int
}

@Dao
interface RuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: Rule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<Rule>)

    @Query("SELECT * FROM rule WHERE deleted = 0")
    suspend fun active(): List<Rule>

    @Query("UPDATE rule SET deleted = 1, updated_at = :now WHERE id = :id")
    suspend fun delete(id: String, now: Long)

    @Query("SELECT * FROM rule WHERE origin_ref = :ref AND deleted = 0")
    suspend fun madeFrom(ref: String): List<Rule>
}

@Dao
interface TxnDecisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: TxnDecision)

    @Query("SELECT * FROM txn_decision WHERE deleted = 0")
    suspend fun all(): List<TxnDecision>

    @Query("SELECT * FROM txn_decision WHERE ref = :ref AND deleted = 0")
    suspend fun forRef(ref: String): List<TxnDecision>

    @Query("SELECT * FROM txn_decision WHERE kind = :kind AND deleted = 0")
    suspend fun ofKind(kind: String): List<TxnDecision>
}

@Dao
interface TxnClassDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<TxnClass>)

    @Query("DELETE FROM txn_class")
    suspend fun deleteAll()

    @Query("SELECT * FROM txn_class")
    suspend fun all(): List<TxnClass>

    @Query("SELECT * FROM txn_class WHERE ref = :ref")
    suspend fun forRef(ref: String): TxnClass?

    @Query("SELECT COUNT(*) FROM txn_class WHERE needs_review = 1")
    suspend fun reviewCount(): Int
}
