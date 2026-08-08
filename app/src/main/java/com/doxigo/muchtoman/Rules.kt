package com.doxigo.muchtoman

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlin.math.pow

/**
 * Classification, which is a table of rules and nothing else.
 *
 * No model, no scoring, no learned weights. "Teach it once and it never asks again" is a row
 * being written, and the reason it can be that simple is that the alternative — anything that
 * infers — would be a thing that quietly changes its mind about money she already checked.
 *
 * [categoryUseOf] is the one thing here that learns, and it is allowed to because of what it is
 * not allowed to touch: it orders the cells of the picker and files nothing. Getting it wrong
 * costs her a glance, never a wrong figure in a report, and it decides nothing that is not
 * already visible on the screen she is looking at.
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
    /**
     * The mark she picked for a category she made, as a [CategoryGlyph] name. Blank on everything
     * that ships, which is looked up by name instead — a shipped category's mark is not a thing
     * stored per install, or renaming one in a build would leave the old mark in the database.
     */
    @ColumnInfo(defaultValue = "''") val glyph: String = "",
)

const val CAT_UNCATEGORISED = "cat_uncategorised"
const val CAT_TRANSFER = "cat_transfer"
const val CAT_SPOUSE = "cat_spouse"

/**
 * «سایر», which is offered on both sides of the ledger.
 *
 * The id still says income because that is the only side it was offered on when it shipped, and
 * an id is what every stored row, rule and decision names — renaming it would orphan them all.
 */
const val CAT_OTHER = "cat_income_other"
const val CAT_FEES = "cat_fees"
const val CAT_CASH = "cat_cash"
const val CAT_INCOME = "cat_income"

/**
 * The categories the app ships with, in the order a household generally reaches for them.
 *
 * Setup does not ask her to build a taxonomy. Everything the parser can tell apart from a bank
 * SMS is here, and so is the household's own list, which no SMS will ever name on its own —
 * those are here so the choice exists the first time she files something by hand, not because
 * anything can guess them.
 *
 * `sort` is the *opening* position of a cell in the picker and no longer its permanent one:
 * [categoryChoices] floats what she actually uses to the front, so this list is the order the
 * app assumes before it knows anything about her, and the order the seldom-used tail keeps
 * forever. Grouped roughly weekly-then-monthly-then-rarely: the four cells of the first row are
 * the ones most spending in a month lands in, and what the app says about money rather than what
 * she spent it on — کارمزد, برداشت نقدی — sorts to the back, because it is filed for her.
 *
 * The three at the end are the ones both grids share, and they stay at the end of both.
 */
val BUILTIN_CATEGORIES: List<Category> = listOf(
    Category(CAT_UNCATEGORISED, nameFa = "دسته‌بندی نشده", kind = CategoryKind.EXPENSE, sort = 0, builtin = true),

    // ── the weekly ones: the first row of the spending grid ──
    Category("cat_groceries", nameFa = "خواربار", kind = CategoryKind.EXPENSE, sort = 10, builtin = true),
    Category("cat_dining", nameFa = "رستوران و کافه", kind = CategoryKind.EXPENSE, sort = 20, builtin = true),
    Category("cat_transport", nameFa = "حمل و نقل", kind = CategoryKind.EXPENSE, sort = 30, builtin = true),
    Category("cat_shopping", nameFa = "خرید روزانه", kind = CategoryKind.EXPENSE, sort = 40, builtin = true),

    // ── the monthly ones ──
    // خودرو is the car itself — بنزین, سرویس, بیمه, جریمه — and حمل و نقل stays what it was, the
    // fare she pays somebody else. اینترنت is out of قبض‌ها for the same reason قسط و وام is: it is
    // the one line of the utilities she actually decides the size of, and it is topped up far more
    // often than a quarterly gas bill arrives, which is why it sorts above one.
    Category("cat_bills", nameFa = "قبض‌ها", kind = CategoryKind.EXPENSE, sort = 50, builtin = true),
    Category("cat_internet", nameFa = "اینترنت", kind = CategoryKind.EXPENSE, sort = 60, builtin = true),
    Category("cat_car", nameFa = "خودرو", kind = CategoryKind.EXPENSE, sort = 70, builtin = true),
    Category("cat_health", nameFa = "سلامت", kind = CategoryKind.EXPENSE, sort = 80, builtin = true),
    Category("cat_home", nameFa = "خانه و کاشانه", kind = CategoryKind.EXPENSE, sort = 90, builtin = true),
    Category("cat_clothing", nameFa = "مد و پوشاک", kind = CategoryKind.EXPENSE, sort = 100, builtin = true),
    // A قسط is money going to a debt she already owes, and what she wants to see at the end of
    // the month is that number beside the قرض she has out — not buried in utilities.
    //
    // No shipped rule behind it. The builtin rules match on the channel a message came through,
    // and a قسط arrives as an ordinary برداشت on whichever channel the bank happened to use, so
    // there is nothing to key on that is not a guess. It earns a rule the first time she files
    // one and says «همیشه», which is the mechanism that already exists for exactly this.
    Category("cat_instalment", nameFa = "قسط و وام", kind = CategoryKind.EXPENSE, sort = 110, builtin = true),
    Category("cat_atina", nameFa = "خرج اتینا", kind = CategoryKind.EXPENSE, sort = 120, builtin = true),

    // ── the occasional ones ──
    Category("cat_savings", nameFa = "پس‌انداز و سرمایه", kind = CategoryKind.EXPENSE, sort = 130, builtin = true),
    Category("cat_gifts", nameFa = "هدیه و نیکوکاری", kind = CategoryKind.EXPENSE, sort = 140, builtin = true),
    Category("cat_beauty", nameFa = "زیبایی", kind = CategoryKind.EXPENSE, sort = 150, builtin = true),
    Category("cat_culture", nameFa = "فرهنگی و هنری", kind = CategoryKind.EXPENSE, sort = 160, builtin = true),
    Category("cat_tobacco", nameFa = "دخانیات", kind = CategoryKind.EXPENSE, sort = 170, builtin = true),
    // The two halves of a قرض, which only balance out over time: neither is really spending or
    // really income, but EXPENSE and INCOME are the only kinds that stay visible — TRANSFER
    // means «not her decision, do not count», and money lent is very much a decision.
    Category("cat_loan", nameFa = "قرض", kind = CategoryKind.EXPENSE, sort = 180, builtin = true),

    // ── what the app files for her, which she rarely has to pick ──
    Category(CAT_CASH, nameFa = "برداشت نقدی", kind = CategoryKind.EXPENSE, sort = 190, builtin = true),
    Category(CAT_FEES, nameFa = "کارمزد", kind = CategoryKind.EXPENSE, sort = 200, builtin = true),

    // Retired, and archived rather than deleted so that every row filed under it still says what
    // it was filed as. «انتقال وجه» named the mechanism and not the reason: a کارت‌به‌کارت is how
    // money left, never why, and the why — a gift, a loan, the rent, her husband — is what every
    // other category on this list is for. Offering it invited her to throw that answer away at
    // the one moment she still knew it, and left a line in the month's report that says only
    // «money went somewhere».
    Category("cat_send", nameFa = "انتقال وجه", kind = CategoryKind.EXPENSE, sort = 205, builtin = true, archived = true),

    // ── the income grid ──
    // درآمد stays first: it is what `rule_income` files every incoming message as, so it is the
    // answer she is confirming rather than one she has to choose. The rest are for when she wants
    // to say more than «money came in», in the order a household earns them.
    Category(CAT_INCOME, nameFa = "درآمد", kind = CategoryKind.INCOME, sort = 210, builtin = true),
    Category("cat_salary", nameFa = "حقوق", kind = CategoryKind.INCOME, sort = 220, builtin = true),
    Category("cat_sales", nameFa = "فروش", kind = CategoryKind.INCOME, sort = 230, builtin = true),
    Category("cat_bonus", nameFa = "پاداش", kind = CategoryKind.INCOME, sort = 240, builtin = true),
    Category("cat_invest_income", nameFa = "سود سرمایه‌گذاری", kind = CategoryKind.INCOME, sort = 250, builtin = true),
    // «پس‌گرفتن», not «پس‌دادن»: the ledger is hers, and on money coming in she is the one
    // getting it back — the other side is who gave it.
    Category("cat_loan_back", nameFa = "پس‌گرفتن قرض", kind = CategoryKind.INCOME, sort = 260, builtin = true),

    // ── the three both grids end with ──
    // Money between the two of them, and it runs both ways: what she pays for him is spending,
    // what he sends her is income, and both belong under the same word. One row and not a pair —
    // the month's report totals by name, so two rows called همسر would be one line anyway, and
    // one name is one mark to learn. [categoryChoices] offers it in both directions, the way
    // انتقال بین حساب‌ها already is.
    //
    // EXPENSE by kind for the reason قرض is: EXPENSE and INCOME are the only kinds that stay
    // visible, and what a row counts as is read off the amount's sign, never off this field.
    Category(CAT_SPOUSE, nameFa = "همسر", kind = CategoryKind.EXPENSE, sort = 270, builtin = true),
    // «none of the others», which is a thing money going out needs as often as money coming in.
    // One row offered both ways rather than a pair, for the reason همسر is one row. INCOME by
    // kind is only where it shipped; the sign decides what a row counts as.
    //
    // Last of the answers on purpose: «سایر» is where the eye goes after reading the others and
    // finding none of them, so it must not be the first thing read — and [categoryChoices] holds
    // it here however often it is used, or the picker would teach her to file everything as it.
    Category(CAT_OTHER, nameFa = "سایر", kind = CategoryKind.INCOME, sort = 280, builtin = true),
    Category(CAT_TRANSFER, nameFa = "انتقال بین حساب‌ها", kind = CategoryKind.TRANSFER, sort = 290, builtin = true),
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
 * Half of a category's weight is gone this many days later.
 *
 * Six weeks, which is a month's habits plus the fortnight it takes for a change of them to look
 * like more than one odd week. Shorter and a single holiday reorders the grid; much longer and
 * the app is still offering her what she was buying at the start of last year.
 */
private const val USE_HALF_LIFE = 45.0

/**
 * Weight below which a category has not earned the front of the grid.
 *
 * Two recent transactions, roughly. A grid that reshuffles the moment she files one thing is a
 * grid she cannot learn the shape of, and the cost of promoting too eagerly is paid every time
 * she looks for a cell where it was yesterday.
 */
private const val USE_PROMOTES = 2.0

/**
 * How much each category is actually being used, with the recent past counting for more.
 *
 * Every filed row is a data point, not just the ones she corrected by hand: what the picker
 * should offer first is where her money goes, and a category the rules file for her twenty times
 * a month is one she reaches for when they get it wrong too. Duplicates and transfer legs are
 * left out for the reason every total leaves them out — they are not money moving — and
 * «دسته‌بندی نشده» is the absence of an answer rather than a popular one.
 *
 * Weighting is a plain exponential decay on the Tehran day, so a transaction today counts twice
 * what one six weeks ago does. No storage and no counters to keep in step: this is read off the
 * ledger she already has in memory, which means it can never disagree with it.
 */
fun categoryUseOf(
    entries: List<LedgerEntry>,
    today: Long,
    halfLife: Double = USE_HALF_LIFE,
): Map<String, Double> {
    val use = mutableMapOf<String, Double>()
    for (entry in entries) {
        if (entry.duplicate || entry.transfer) continue
        val id = entry.categoryId
        if (id.isBlank() || id == CAT_UNCATEGORISED) continue
        // A row dated in the future — a bank clock running fast — counts as today rather than
        // as more than today, or one of them would outweigh a fortnight of real ones.
        val age = (today - entry.txn.day).coerceAtLeast(0L)
        use[id] = (use[id] ?: 0.0) + 2.0.pow(-age / halfLife)
    }
    return use
}

/**
 * The categories worth offering for one transaction, the ones she actually uses first.
 *
 * «دسته‌بندی نشده» is the absence of an answer, not one of them, and an archived category is one
 * she has retired — it still names the rows filed under it and is never offered again.
 *
 * «انتقال بین حساب‌ها» is offered in both directions, because it is the only way back from a
 * rejected transfer link. Filing one leg of a real transfer under a spending category rejects
 * that link for ever and hands the other leg to the income rule — the same money counted twice,
 * with no undo anywhere in the app. The detector settling most transfers is not a reason to make
 * the correction unreachable.
 *
 * Direction does the rest, and it is what makes this list usable now that it is long: money that
 * arrived was never خواربار or مد و پوشاک, so offering them means reading past sixteen wrong
 * answers to reach the two right ones. A transaction the parser read no direction from gets
 * everything — that is the honest answer to «which way did this go», and guessing here would file
 * money on the wrong side of the report.
 *
 * [use] then reorders what is left, and it is the only thing that overrides `sort`. Ranking is by
 * weight and nothing else: a category she files four things a week under belongs where her thumb
 * already is, and the shipped order is only the app's guess at that until it has hers. Two rules
 * keep it from being a shuffle rather than an order — a category has to clear [USE_PROMOTES]
 * before it moves at all, and everything below that keeps the shipped order exactly, so the tail
 * of the grid stays where she last saw it while the head follows her.
 *
 * سایر and انتقال never move. Both are ways out rather than answers: a picker that learns to put
 * «none of the others» under her thumb is one that has taught her to stop filing, and a grid that
 * opens with the transfer escape hatch is offering a correction to someone who has nothing to
 * correct.
 */
fun categoryChoices(
    categories: List<Category>,
    direction: String?,
    use: Map<String, Double> = emptyMap(),
): List<Category> =
    categories.filter {
        it.id != CAT_UNCATEGORISED && !it.archived && when {
            // The three that ignore direction: انتقال because it is the only way back from a
            // rejected transfer link, همسر because money between the two of them is one category
            // whichever way it went, and سایر because «none of the others» is an answer either
            // side of the ledger can need. All three are carried by id — their `kind` names the
            // side they shipped on and nothing more.
            it.id == CAT_TRANSFER || it.id == CAT_SPOUSE || it.id == CAT_OTHER -> true
            direction == "in" -> it.kind == CategoryKind.INCOME
            direction == "out" -> it.kind == CategoryKind.EXPENSE
            else -> it.kind != CategoryKind.TRANSFER
        }
        // Stable, so everything that has not earned a promotion holds the shipped order.
    }.sortedByDescending { promotionOf(it, use) }

/** What a category has earned toward the front of the grid, and zero is «leave it where it is». */
private fun promotionOf(category: Category, use: Map<String, Double>): Double =
    if (category.id == CAT_OTHER || category.id == CAT_TRANSFER) 0.0
    else (use[category.id] ?: 0.0).let { if (it >= USE_PROMOTES) it else 0.0 }

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

    /**
     * Including the retired ones, which is what naming a transaction needs.
     *
     * Archiving takes a category out of the picker and nothing else — «rows written last year
     * still name it» is only true if the row's name can still be found. Reading [all] here is
     * what made an archived category quietly turn every transaction under it into
     * «دسته‌بندی نشده» on the timeline and drop it out of the month's report.
     */
    @Query("SELECT * FROM category ORDER BY sort, name_fa")
    suspend fun withArchived(): List<Category>

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
