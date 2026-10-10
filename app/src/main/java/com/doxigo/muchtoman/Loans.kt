package com.doxigo.muchtoman

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * طلب و بدهی — who she lent money to, and who she owes.
 *
 * Three stores, each already the right one for its part:
 *  - **People and what she typed** live in one prefs blob, [LoanBook], beside the holdings: a
 *    person is a name and a promise date, and a [LoanMove] is the one thing no bank reports —
 *    cash handed over, two coins lent out of the drawer, a balance from before the app.
 *  - **What the bank reported** is a [DecisionKind.LOAN] on the transaction, exactly as a قسط
 *    payment is a [DecisionKind.INSTALLMENT]: the card-to-card to Mahdi is already in the ledger,
 *    and typing it here as well would count it twice with nothing to say which was real.
 *
 * A balance is owed **in what was lent**. Two coins lent are two coins owed, whatever a coin fetches
 * the day he returns them, so the units are kept apart and Toman is only ever today's valuation of
 * them — labelled as such, never the debt itself. The sign is one convention everywhere: positive
 * is money that went to them (they owe her), negative is money that came from them (she owes, or
 * he paid some back). That is why filing never asks «lent or repaid?» — money out to Hossein
 * reduces what she owes him and, past zero, becomes what he owes her, by arithmetic alone.
 *
 * Beside the total, never in it (the product decision of 2026-09-27): what is lent may not come
 * back, and «چقدر تومن دارم» is money she holds. Lending two coins takes them out of دارایی — see
 * [loanHoldings] — so the hero drops by what left the drawer and the loan appears next to it.
 *
 * Always private: the blob is a pref the sync never reads, and the link decisions are a kind it
 * never reads either (it reads [DecisionKind.CATEGORY] and [DecisionKind.NOTE] by name). The
 * household does not see who owes whom.
 *
 * ponytail: a person whose parts pull in opposite directions — he owes her two coins, she owes him
 * a million — is placed by the net of today's value and each part is read in that one direction.
 * Per-part words are the upgrade if a real ledger ever mixes them.
 */

/** A name that fits on a row. */
const val MAX_LOAN_NAME = 32

/** Below this a unit balance is float dust from adding and taking the same amount back. */
private const val UNIT_EPSILON = 1e-9

@Serializable
data class LoanPerson(
    val id: String,
    val name: String,
    /** The Tehran day he said he would settle by, or null. */
    val promise: Long? = null,
    val createdAt: Long = 0L,
)

/**
 * One thing she wrote down that no bank will report. Exactly one of [rial] and [amount] is used:
 * [rial] when [typeId] is blank (cash, or a balance from before the app), [amount] otherwise.
 */
@Serializable
data class LoanMove(
    val id: String,
    val personId: String,
    /** Blank for Rial; otherwise the asset it was in — `coin_emami`, `usd`. */
    val typeId: String = "",
    /** Signed whole Rial. Positive: it went to them. */
    val rial: Long = 0L,
    /** Signed units of [typeId]. Positive: it went to them. */
    val amount: Double = 0.0,
    val day: Long,
    /**
     * The holding this came out of or went into, when it moved one — see [loanHoldings]. Kept so
     * taking the move back can put the coins back where they were.
     */
    val holdingKey: String = "",
    /** A balance she carried in when she added the person: «از قبل», not something that happened. */
    val opening: Boolean = false,
)

@Serializable
data class LoanBook(
    val people: List<LoanPerson> = emptyList(),
    val moves: List<LoanMove> = emptyList(),
)

/**
 * What a [DecisionKind.LOAN] decision's value holds: whose, and how much, signed.
 *
 * The amount is copied onto the link for [InstallmentLink]'s reason: the ledger forgets pruned
 * sources, and a loan summed off live rows would quietly shrink as the months went by. The sign
 * is copied too, from the row's direction at the moment she linked it.
 */
data class LoanLink(val personId: String, val rial: Long) {
    /** `<personId>:<signed rial>`. A person id is a [uuid7], which has no colon in it. */
    fun encode(): String = "$personId:$rial"

    companion object {
        /** Null for anything this build did not write, so one bad row cannot poison a balance. */
        fun decode(value: String?): LoanLink? {
            val raw = value ?: return null
            val personId = raw.substringBeforeLast(':', "")
            val rial = raw.substringAfterLast(':').toLongOrNull()
            // A range, not abs(): abs(Long.MIN_VALUE) is itself negative and passed the bound.
            if (personId.isEmpty() || rial == null || rial == 0L || rial !in -MAX_PLAUSIBLE_RIAL..MAX_PLAUSIBLE_RIAL) return null
            return LoanLink(personId, rial)
        }
    }
}

/** Every link, by the transaction it is on. Links to a deleted person are dropped by [loanViews]. */
fun loanLinks(decisions: List<TxnDecision>): Map<String, LoanLink> =
    decisions
        .filter { it.kind == DecisionKind.LOAN && !it.deleted }
        .mapNotNull { d -> LoanLink.decode(d.value)?.let { d.ref to it } }
        .toMap()

/** The link this row would make: its amount, signed by which way it went. Null with no direction. */
fun loanLinkRial(entry: LedgerEntry): Long? {
    val amount = entry.txn.amountRial?.takeIf { it > 0L } ?: return null
    return when (entry.txn.direction) {
        "out" -> amount
        "in" -> -amount
        else -> null
    }
}

/**
 * Whether this row can be linked to a person: hers, with an amount and a direction, and neither a
 * duplicate nor a move between her own accounts. Both directions — money in is repayment or a
 * loan to her, money out is a loan or repayment by her.
 */
fun loanLinkable(entry: LedgerEntry, mineId: String): Boolean =
    loanLinkRial(entry) != null && !entry.duplicate && !entry.transfer && entry.ownerMemberId == mineId

/** Filed under one of the two قرض categories, which is what raises «به کی؟». */
fun isLoanCategory(categoryId: String): Boolean = categoryId == CAT_LOAN || categoryId == CAT_LOAN_BACK

// ─────────────────────────── balances ───────────────────────────

enum class LoanSide { OWED, OWE, SETTLED }

/** One line of a person's history: a linked bank row, or a move she wrote down. */
data class LoanEvent(
    val day: Long,
    /** Blank for Rial. */
    val typeId: String,
    val rial: Long,
    val amount: Double,
    val entry: LedgerEntry? = null,
    val move: LoanMove? = null,
)

data class LoanView(
    val person: LoanPerson,
    /** Net Rial across links and cash moves. */
    val rial: Long,
    /** Net units by asset, zeros dropped. */
    val units: Map<String, Double>,
    /** Today's Toman value of what could be priced, signed. */
    val toman: Double,
    /** Assets in [units] with no rate — left out of [toman] and named, never zero. */
    val missing: List<String>,
    val side: LoanSide,
    /** Newest first: the linked rows the ledger still holds, and every move. */
    val events: List<LoanEvent>,
    /** What links to rows the ledger has since forgotten still count for. */
    val olderRial: Long,
)

/** Which way an account leans. Only unpriced units left: their own sign says which way. */
private fun sideOf(rial: Long, units: Map<String, Double>, toman: Double): LoanSide = when {
    rial == 0L && units.isEmpty() -> LoanSide.SETTLED
    toman > 0.0 -> LoanSide.OWED
    toman < 0.0 -> LoanSide.OWE
    else -> if (units.values.sum() >= 0.0) LoanSide.OWED else LoanSide.OWE
}

fun loanView(
    person: LoanPerson,
    book: LoanBook,
    links: Map<String, LoanLink>,
    entries: List<LedgerEntry>,
    rates: Map<String, Double>,
): LoanView {
    val mine = links.filterValues { it.personId == person.id }
    val moves = book.moves.filter { it.personId == person.id }
    val held = entries.filter { it.txn.ref in mine }
    val heldRefs = held.mapTo(HashSet()) { it.txn.ref }
    // Clamped as it adds, so a pathological pile of links saturates rather than wraps.
    var rial = 0L
    for (r in mine.values.map { it.rial } + moves.filter { it.typeId.isBlank() }.map { it.rial }) {
        rial = (rial + r).coerceIn(-MAX_PLAUSIBLE_RIAL * 1000, MAX_PLAUSIBLE_RIAL * 1000)
    }
    val units = moves.filter { it.typeId.isNotBlank() }
        .groupBy { it.typeId }
        .mapValues { (_, m) -> m.sumOf { it.amount } }
        .filterValues { abs(it) > UNIT_EPSILON }
    var toman = tomanOf(rial)
    val missing = mutableListOf<String>()
    for ((type, amount) in units) {
        val rate = rates[type]?.takeIf { it > 0.0 && it.isFinite() }
        if (rate == null) missing += type else toman += amount * rate
    }
    // Moves are kept in the order she wrote them; reversed, a day's newest is its first line too.
    val events = held.map { LoanEvent(it.txn.day, "", mine.getValue(it.txn.ref).rial, 0.0, entry = it) } +
        moves.asReversed().map { LoanEvent(it.day, it.typeId, it.rial, it.amount, move = it) }
    return LoanView(
        person = person,
        rial = rial,
        units = units,
        toman = toman,
        missing = missing,
        side = sideOf(rial, units, toman),
        events = events.sortedByDescending { it.day },
        olderRial = mine.filterKeys { it !in heldRefs }.values.sumOf { it.rial },
    )
}

/** Every person, the biggest balance first; settled ones last, newest-made first among them. */
fun loanViews(
    book: LoanBook,
    links: Map<String, LoanLink>,
    entries: List<LedgerEntry>,
    rates: Map<String, Double>,
): List<LoanView> =
    book.people.map { loanView(it, book, links, entries, rates) }
        .sortedWith(
            compareBy<LoanView> { it.side == LoanSide.SETTLED }
                .thenByDescending { abs(it.toman) }
                .thenByDescending { it.person.createdAt },
        )

/** «طلبت» and «بدهیت»: what the two sides come to today, and how many people on each. */
data class LoanTotals(
    val owedToman: Double,
    val oweToman: Double,
    val owedPeople: Int,
    val owePeople: Int,
    /** Assets somebody owes in that have no rate, so neither figure counts them. */
    val missing: List<String>,
) {
    val isEmpty: Boolean get() = owedPeople == 0 && owePeople == 0
}

fun loanTotals(views: List<LoanView>): LoanTotals {
    val owed = views.filter { it.side == LoanSide.OWED }
    val owe = views.filter { it.side == LoanSide.OWE }
    return LoanTotals(
        owedToman = owed.sumOf { it.toman.coerceAtLeast(0.0) },
        oweToman = owe.sumOf { (-it.toman).coerceAtLeast(0.0) },
        owedPeople = owed.size,
        owePeople = owe.size,
        missing = views.flatMap { it.missing }.distinct(),
    )
}

// ─────────────────────────── making and changing ───────────────────────────

/** A new person, or null when the name is empty. */
fun newLoanPerson(id: String, name: String, promise: Long?, now: Long): LoanPerson? {
    val clean = name.trim().take(MAX_LOAN_NAME)
    if (clean.isEmpty()) return null
    return LoanPerson(id = id, name = clean, promise = promise, createdAt = now)
}

/**
 * Her holdings after [amount] of [typeId] left them (positive: lent out) or came into them
 * (negative: borrowed, or paid back) — and the key of the holding that moved, for [LoanMove.holdingKey].
 *
 * Only hand-typed holdings move: one tracked from a wallet is the chain's figure, not hers to
 * adjust. Lending takes from the first of them that holds enough; coming in lands on the first
 * of them, or on a new one when she has none. Null when she is lending more than any one of them
 * holds — the coins she hands over have to be coins the app thinks she has.
 *
 * ponytail: lending from one holding at a time. Two coin holdings each too small to cover the loan
 * alone refuse it; spread the loan across them when somebody actually keeps coins in two places.
 */
fun loanHoldings(
    holdings: List<Holding>,
    typeId: String,
    amount: Double,
    newId: () -> String,
): Pair<List<Holding>, String>? {
    if (!amount.isFinite() || amount == 0.0) return null
    val manual = holdings.filter { it.typeId == typeId && it.wallet == null }
    if (amount > 0.0) {
        val from = manual.firstOrNull { it.amount + UNIT_EPSILON >= amount } ?: return null
        return holdings.map {
            if (it.key == from.key) it.copy(amount = (it.amount - amount).coerceAtLeast(0.0)) else it
        } to from.key
    }
    val into = manual.firstOrNull()
    if (into == null) {
        val fresh = Holding(typeId, -amount, id = newId())
        return holdings + fresh to fresh.key
    }
    return holdings.map { if (it.key == into.key) it.copy(amount = it.amount - amount) else it } to into.key
}

/**
 * Her holdings with [move]'s change taken back, for when she deletes the move. Never below zero:
 * coins she has since sold are gone, and taking back a loan cannot conjure them.
 */
fun loanHoldingsUndo(holdings: List<Holding>, move: LoanMove): List<Holding> {
    if (move.holdingKey.isBlank()) return holdings
    val delta = if (move.typeId.isBlank()) tomanOf(move.rial) else move.amount
    return holdings.map {
        if (it.key == move.holdingKey) it.copy(amount = (it.amount + delta).coerceAtLeast(0.0)) else it
    }
}

/** [loanHoldingsUndo] undone: the move's change applied again, when she brings it back. */
fun loanHoldingsRedo(holdings: List<Holding>, move: LoanMove): List<Holding> {
    if (move.holdingKey.isBlank()) return holdings
    val delta = if (move.typeId.isBlank()) tomanOf(move.rial) else move.amount
    return holdings.map {
        if (it.key == move.holdingKey) it.copy(amount = (it.amount - delta).coerceAtLeast(0.0)) else it
    }
}

/**
 * [move] at a new size, in its own direction and unit — what editing a trail line writes. [rial]
 * and [amount] are magnitudes as she typed them; the sign stays the move's own, so a line written
 * as money out stays money out.
 */
fun loanMoveResized(move: LoanMove, rial: Long, amount: Double): LoanMove =
    if (move.typeId.isBlank()) move.copy(rial = if (move.rial < 0L) -rial else rial)
    else move.copy(amount = if (move.amount < 0.0) -amount else amount)

/**
 * Her holdings with [move] changed to [edited]: the old change taken back and the new one made, on
 * the same holding. Null when the new size takes out more than that holding has once the old one is
 * back — the coins she hands over have to be coins the app thinks she has, as when lending.
 */
fun loanHoldingsEdit(holdings: List<Holding>, move: LoanMove, edited: LoanMove): List<Holding>? {
    if (move.holdingKey.isBlank()) return holdings
    val back = loanHoldingsUndo(holdings, move)
    // The holding is gone since: there is nothing left for the move to change.
    val held = back.firstOrNull { it.key == move.holdingKey } ?: return back
    val out = if (edited.typeId.isBlank()) tomanOf(edited.rial) else edited.amount
    if (out > held.amount + UNIT_EPSILON) return null
    return loanHoldingsRedo(back, edited)
}

/**
 * Which holding a move in this unit would change: cash moves the «پول نقد» holding, anything
 * else its own asset's. Null when there is none to change and nothing should be made — lending
 * coins she never entered, or cash when she does not count her cash here.
 *
 * Lending names the fullest of them, which is the one [loanHoldings] will manage to take from if
 * any can — so «فقط ۱ سکه داری» is never said while a second holding has three.
 */
fun loanHoldingFor(holdings: List<Holding>, typeId: String, giving: Boolean): Holding? {
    val asset = typeId.ifBlank { TOMAN_ID }
    val manual = holdings.filter { it.typeId == asset && it.wallet == null }
    return if (giving) manual.maxByOrNull { it.amount }?.takeIf { it.amount > 0.0 } else manual.firstOrNull()
}

// ─────────────────────────── words ───────────────────────────

/** «بهت بدهکاره», «بهش بدهکاری», «تسویه» — the side in her words, never in colour alone. */
fun loanSideFa(side: LoanSide): String = when (side) {
    LoanSide.OWED -> "بهت بدهکاره"
    LoanSide.OWE -> "بهش بدهکاری"
    LoanSide.SETTLED -> "حسابتون صافه"
}

/**
 * One asset's amount in words: «۲ سکه امامی», «۳۸٫۵ گرم طلای ۱۸ عیار», «۱۰۰ دلار آمریکا».
 * A counted thing takes its own name, a fiat whose name starts with its unit says it once, and
 * everything else is the amount, the unit and the name.
 */
fun loanAmountFa(type: AssetType, amount: Double): String {
    val n = faHeld(abs(amount), type.dec)
    return when {
        type.unitFa == "عدد" || type.fa.startsWith(type.unitFa) -> "$n ${type.fa}"
        else -> "$n ${type.unitFa} ${type.fa}"
    }
}

/** «۲٫۵ میلیون تومان». */
fun loanRialFa(rial: Long): String = "${faCompact(tomanOf(abs(rial)))} تومان"

/** Everything one person owes or is owed, in its own units: «۲ سکه امامی و ۲٫۵ میلیون تومان». */
fun loanWhatFa(view: LoanView, type: (String) -> AssetType): String =
    (view.units.map { (id, amount) -> loanAmountFa(type(id), amount) } +
        listOfNotNull(view.rial.takeIf { it != 0L }?.let(::loanRialFa)))
        .joinToString(" و ")

/**
 * The promise, from today: «قرار ۱۵ آبان», «قرار امروزه», «۳ روز از قرار گذشته». Null with no
 * promise, and once the account is settled — a date nobody owes anything by is not a date.
 * The second value says it is overdue, which is the one case worth a caution colour.
 */
fun loanPromiseFa(view: LoanView, today: Long): Pair<String, Boolean>? {
    val day = view.person.promise ?: return null
    if (view.side == LoanSide.SETTLED) return null
    return when {
        day < today -> "${faNumber((today - day).toDouble())} روز از قرار گذشته" to true
        day == today -> "قرار امروزه" to false
        else -> "قرار ${faDayMonth(day, today)}" to false
    }
}

/** «۱۵ آبان», with the year only when it is not this one. */
fun faDayMonth(day: Long, today: Long): String {
    val d = jalaliOf(day)
    val sameYear = d.year == jalaliOf(today).year
    return "${faNumber(d.day.toDouble())} ${MONTHS[d.month - 1]}" + if (sameYear) "" else " ${faYear(d.year)}"
}

/**
 * The row's one line under the name: an overdue promise first, because it is the one thing that
 * asks something of her; then what is owed when it is more than Toman; then the promise.
 */
fun loanSubFa(view: LoanView, today: Long, type: (String) -> AssetType): Pair<String, Boolean>? {
    val promise = loanPromiseFa(view, today)
    return when {
        promise?.second == true -> promise
        view.units.isNotEmpty() -> loanWhatFa(view, type) to false
        else -> promise
    }
}

/**
 * What linking this much to this person would leave, said before she commits: «حسین ۴٫۱ میلیون
 * تومان بهت بدهکار می‌شه.» Only the Toman part moves, so the sentence is about the whole account
 * after it.
 */
fun loanAfterFa(view: LoanView, deltaRial: Long, type: (String) -> AssetType): String {
    val rial = view.rial + deltaRial
    val toman = view.toman + tomanOf(deltaRial)
    return loanStandsFa(view, view.copy(rial = rial, toman = toman, side = sideOf(rial, view.units, toman)), type)
}

/**
 * Where an account will stand once a change she is about to make is made, measured against where it
 * stands now: a payment that leaves something says what is *left* («مرتضی هنوز ۵ گرم طلا بهت بدهکار
 * می‌مونه»), more lent says the new whole («روی هم»), and one that clears it says so. A bare «بدهکار
 * می‌شه» under a part payment read as a new debt.
 */
fun loanStandsFa(before: LoanView, after: LoanView, type: (String) -> AssetType): String {
    val name = after.person.name
    val what = loanWhatFa(after, type)
    val kept = after.side == before.side
    // By today's value; units with no rate fall back on their own count.
    val smaller = kept && (
        abs(after.toman) < abs(before.toman) ||
            (after.toman == before.toman && after.units.values.sumOf { abs(it) } < before.units.values.sumOf { abs(it) })
        )
    return when (after.side) {
        LoanSide.SETTLED -> "حسابت با $name صاف می‌شه."
        LoanSide.OWED -> when {
            smaller -> "$name هنوز $what بهت بدهکار می‌مونه."
            kept -> "$name روی هم $what بهت بدهکار می‌شه."
            else -> "$name $what بهت بدهکار می‌شه."
        }
        LoanSide.OWE -> when {
            smaller -> "هنوز $what به $name بدهکار می‌مونی."
            kept -> "روی هم $what به $name بدهکار می‌شی."
            else -> "$what به $name بدهکار می‌شی."
        }
    }
}

/**
 * What resizing [move] to [edited] does to her دارایی, said under the figure before she saves: the
 * difference, joining or leaving. Null when the move never touched a holding or the size is unchanged.
 */
fun loanEditHoldingFa(move: LoanMove, edited: LoanMove, type: (String) -> AssetType): String? {
    if (move.holdingKey.isBlank()) return null
    val cash = move.typeId.isBlank()
    val gain = if (cash) tomanOf(move.rial - edited.rial) else move.amount - edited.amount
    if (abs(gain) <= UNIT_EPSILON) return null
    val what = if (cash) loanRialFa(move.rial - edited.rial) else loanAmountFa(type(move.typeId), gain)
    return if (gain > 0.0) "$what به دارایی‌هات اضافه می‌شه." else "$what از دارایی‌هات کم می‌شه."
}

/** Whether a move this way pays the account down — «پس گرفتم», «پس دادم» — rather than adding to it. */
fun loanRepays(side: LoanSide, giving: Boolean): Boolean =
    (side == LoanSide.OWED && !giving) || (side == LoanSide.OWE && giving)

/** A trail line's title: «دادی», «پس داد», «از قبل», or the bank row's own name. */
fun loanEventTitleFa(event: LoanEvent, type: (String) -> AssetType): String {
    event.entry?.let { return txnTitleFa(it.txn) }
    val move = event.move ?: return ""
    val out = if (move.typeId.isBlank()) move.rial > 0L else move.amount > 0.0
    if (move.opening) return if (out) "از قبل بهت بدهکار بود" else "از قبل بهش بدهکار بودی"
    val what = if (move.typeId.isBlank()) "نقد" else loanAmountFa(type(move.typeId), move.amount)
    return if (out) "$what دادی" else "$what گرفتی"
}

/** Under the title: where it came from. */
fun loanEventSubFa(event: LoanEvent): String? {
    event.entry?.let { return it.categoryFa }
    val move = event.move ?: return null
    return when {
        move.opening -> null
        move.holdingKey.isNotBlank() -> if ((if (move.typeId.isBlank()) move.rial > 0L else move.amount > 0.0)) {
            "از دارایی‌هات"
        } else {
            "به دارایی‌هات"
        }
        else -> "دستی"
    }
}
