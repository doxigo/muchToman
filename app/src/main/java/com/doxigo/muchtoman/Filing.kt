package com.doxigo.muchtoman

/**
 * «این چی بود؟» — the one field a bank message never carries, and the only one she can fill in.
 *
 * A پیامک states an amount, a time, a bank and sometimes a merchant. What it never states is the
 * thing every report, every budget and the whole of دخل و خرج is built on: which category it was.
 * The rules answer that when they are sure; everything else waits in [LedgerView.review].
 *
 * ## Why this is worth a notification at all
 *
 * Because the answer decays. «۴۵۰ هزار از اسنپ‌پی» is one tap on the day it happened and a small
 * archaeology two weeks later, so the cost of a backlog is not the backlog — it is that the answers
 * get worse the longer it stands, and a category she guessed at is a report that is quietly wrong.
 * The badge on دفتر only says so while the app is open, which is the one moment she is already
 * looking.
 *
 * ## Three rules, which are what keep a standing backlog from becoming a standing nag
 *
 *  - **Only what is new is worth saying.** [filingNews] tracks the newest transaction this phone has
 *    accounted for and says nothing about anything older. Nine unfiled receipts she has already
 *    seen are not news; the tenth one is.
 *  - **Opening the app counts as having been told.** Nothing here is ever posted by the app itself
 *    — see [markFilingSeen]. A notification about rows she is looking at is the app talking over
 *    itself, and the badge, the pill and the deck have already said it better.
 *  - **It never answers its own question.** «۴۵۰ هزار تومان رفت» is a fact about a direction, and
 *    calling an *unfiled* movement خرج would be the app answering what it is asking her — it is
 *    exactly as likely to be a transfer between her own accounts. A row the rules filed is the one
 *    exception, and there the note reports the rules' answer as an answer — «تو «خوراک» ثبت شد» —
 *    so she can veto it, which is a different thing from guessing on her behalf.
 *
 * Every transaction that lands gets said, filed or not. The rules filing a spend used to be the end
 * of it, and that silence read as the app having missed the message; now that the note arrives
 * seconds after the پیامک — see `SmsReceiver` — landing is the event, and what the rules did with
 * it is the news. The unfiled ones ask, the filed ones report, and both exist for the same reason:
 * a ledger she has seen every row of is the one the reports can be trusted against.
 */

/**
 * What is worth saying about the backlog right now.
 *
 * Nothing here is stored. Like [BudgetProgress], it is worked out from the ledger every time it is
 * asked for, so a receipt filed on the other phone in the household moves it the moment the sync
 * lands rather than leaving a stored figure to drift.
 */
data class FilingAlert(
    /** How many landed *unfiled* since she was last told or last looked. */
    val fresh: Int,
    /** How many are waiting in all, the fresh ones included. Never fewer than [fresh]. */
    val waiting: Int,
    /**
     * The newest of everything that landed, filed or not, which is the one she is most likely to
     * still be able to place. Only ever spoken aloud when it is the *only* one, since naming one
     * of four is picking a favourite out of a list she has to work through anyway. When it is the
     * only one and the rules filed it, its [LedgerEntry.categoryFa] is the answer the body reports.
     */
    val newest: LedgerEntry,
    /**
     * How many landed already filed by a rule — the ones that used to pass in silence. Zero and
     * [fresh] zero together are no news, and [filingNews] returns no alert rather than this.
     */
    val filed: Int = 0,
)

/**
 * The alert, if there is one, and the mark to write down afterwards.
 *
 * [mark] is the newest `at` this phone has accounted for. A millisecond stamp rather than a count,
 * and that choice is load-bearing three times over:
 *
 *  - She files two and two more land: a count would not have moved, and the new pair is still news.
 *  - She refiles a receipt back into the backlog: it is old, its stamp is old, and it says nothing.
 *  - She reaches back a year with `rewindIngest`: every row it imports is stamped in the past, so a
 *    deliberate import cannot ambush her with a notification about last spring.
 *
 * It is a high-water mark and never lowered, for the reason [BudgetMark]'s level is not — except
 * back to the wall clock, which no honestly-stamped row can ever be ahead of.
 */
data class FilingNews(val alert: FilingAlert?, val mark: Long)

/**
 * How many rows a phone that has never spoken may speak for.
 *
 * The first-pass silence exists so a ledger she has been living with cannot be announced at her in
 * one go — a rewind through a year of the inbox, or a second phone in the household receiving the
 * first sync. Neither of those is small. A fresh install is: [ingestBankSms] seeds its watermark at
 * `now` and reads nothing that was already in the inbox, so the first thing a new phone ever holds
 * is the spend that just happened, and staying silent for it made the app look like it had missed
 * the message it had in fact just read.
 *
 * Three rather than one because a single message can produce more than one transaction, and two
 * can land between one wakeup and the next.
 */
private const val FIRST_PASS_LIMIT = 3

/**
 * What has landed since [said], and how far the mark moves.
 *
 * [entries] is the whole ledger and [review] the unfiled slice of it — passed separately rather
 * than re-filtered here, so «what needs review» keeps exactly one definition, [LedgerView.review].
 * The filed slice is taken from [entries] with review's own exclusions: a duplicate's second leg
 * and a settled transfer are not spends, and a note about either would report bookkeeping as news.
 *
 * [said] of zero means this phone has never looked, and it stays **silent** there unless the whole
 * ledger is [FIRST_PASS_LIMIT] rows or fewer: a rewind through the inbox or a first sync is a
 * backlog she has been living with and must not be announced at her in one go, while a ledger
 * holding nothing but what just arrived is a new phone's first spend and is exactly what she
 * installed this for.
 */
fun filingNews(
    entries: List<LedgerEntry>,
    review: List<LedgerEntry>,
    said: Long,
    now: Long = System.currentTimeMillis(),
): FilingNews {
    // Over everything, not just review: a filed row must move the mark too, or it would be news
    // again on every wakeup for ever. Capped at the wall clock, belt and braces under the ingest
    // clamp — a mark written off a poison stamp would silence every note until that date arrived.
    val mark = maxOf(said, entries.maxOfOrNull { it.txn.at } ?: 0L).coerceAtMost(now)
    val fresh = review.filter { it.txn.at > said }
    val filed = entries.filter {
        !it.needsReview && !it.duplicate && !it.transfer && it.txn.at > said
    }
    // A first pass over a ledger that already holds more than a handful is the rewind-or-sync
    // case: learn where it is and say nothing. Over a ledger holding only what just arrived, it
    // is a new phone's first spend, and that is worth saying.
    val seeding = said <= 0L && entries.size > FIRST_PASS_LIMIT
    val alert = if (seeding || (fresh.isEmpty() && filed.isEmpty())) {
        null
    } else {
        FilingAlert(
            fresh = fresh.size,
            waiting = review.size,
            newest = (fresh + filed).maxBy { it.txn.at },
            filed = filed.size,
        )
    }
    return FilingNews(alert, mark)
}

/**
 * The one line the note leads with: what landed.
 *
 * One of them is named and priced, because a single transaction is a thing she can picture and
 * «اسنپ‌پی: ۴۵۰ هزار تومان رفت» is very nearly the answer already. Several are counted instead — a
 * list of three merchants does not fit on a lock screen and picking one of them to print would make
 * the other two invisible.
 *
 * «رفت» and «رسید» are directions and nothing more. The direction is the one thing the message did
 * state; whether it was خرج, a refund or a move between her own accounts is precisely the question
 * this note exists to ask, so it is not answered here. A message that did not say which way the
 * money went gets neither verb rather than a guess.
 */
fun filingAlertTitle(alert: FilingAlert): String {
    val landed = alert.fresh + alert.filed
    if (landed > 1) return "${faNumber(landed.toDouble())} تراکنش تازه رسید"
    val txn = alert.newest.txn
    val who = txnTitleFa(txn)
    val rial = txn.amountRial ?: return "یک تراکنش تازه از $who"
    val moved = when (txn.direction) {
        "in" -> " رسید"
        "out" -> " رفت"
        else -> ""
    }
    return "$who: ${faCompact(tomanOf(rial))} تومان$moved"
}

/**
 * The line under it: what the rules did, why now, and how much is actually waiting.
 *
 * «تا یادته» is the whole argument for interrupting her at all, and it is an argument rather than an
 * instruction — the app has no claim on her afternoon, it just knows the answer is easier today.
 *
 * A filed row is reported in the same spirit: the rules' answer with an invitation to veto it —
 * «اگه جاش نیست، عوضش کن» — never a verdict. Only a lone filed transaction gets its category named,
 * for the reason only a lone one is named in the title: naming one of four is picking a favourite.
 * When both kinds landed, the ask leads — it is the half she can act on — and the filed ones are a
 * count after it.
 *
 * The total is only added when there is something older behind the new ones, and it is phrased as a
 * state rather than as an addition: «۹ تراکنش دسته‌بندی نشده داری» cannot be read as nine *more* on
 * top of the three in the title. The words are the ones the ledger already uses for an unfiled row.
 */
fun filingAlertBody(alert: FilingAlert): String {
    // Nothing to ask, only to report: the rules filed everything that landed.
    if (alert.fresh == 0) {
        val head = if (alert.filed == 1) {
            "تو «${alert.newest.categoryFa}» ثبت شد • اگه جاش نیست، عوضش کن"
        } else {
            "خودکار دسته‌بندی شدن • یه نگاه بنداز که سر جاشون نشسته باشن"
        }
        if (alert.waiting == 0) return head
        return "$head • روی هم ${faNumber(alert.waiting.toDouble())} تراکنش دسته‌بندی نشده داری"
    }
    val head = if (alert.fresh == 1) "تا یادته، بگو چی بود" else "تا یادته، بگو چی بودن"
    val filed = when {
        alert.filed == 0 -> ""
        alert.filed == 1 -> " • یکی هم خودکار ثبت شد"
        else -> " • ${faNumber(alert.filed.toDouble())} تای دیگه خودکار ثبت شدن"
    }
    if (alert.waiting <= alert.fresh) return "$head$filed"
    return "$head$filed • روی هم ${faNumber(alert.waiting.toDouble())} تراکنش دسته‌بندی نشده داری"
}

/**
 * Filing the whole backlog in one gesture, worked out before anything is written.
 *
 * Three buckets, in order of how much the app actually knows. A row the rules already guessed at
 * keeps that guess — the deck was only ever going to ask her to agree with it. A withdrawal nothing
 * matched goes to «خرید روزانه», which is where most everyday spending lands anyway, and anything
 * else — money in with no rule, a message with no direction — goes to «سایر», the honest word for
 * «none of the above». Every filing is an ordinary pinned decision, so any one of them can be
 * refiled by hand later exactly as if she had answered the card herself.
 */
data class AutoFilePlan(
    /** Each card in the deck, paired with where it goes. */
    val assignments: List<Pair<LedgerEntry, String>>,
    /** How many keep the category the rules already suggested. */
    val suggested: Int,
    /** How many withdrawals fall back to «خرید روزانه». */
    val shopping: Int,
    /** How many land in «سایر» because nothing else is known. */
    val other: Int,
) {
    val total: Int get() = assignments.size
}

fun autoFilePlan(review: List<LedgerEntry>): AutoFilePlan {
    val assignments = mutableListOf<Pair<LedgerEntry, String>>()
    var suggested = 0
    var shopping = 0
    var other = 0
    for (entry in review) {
        val category = when {
            entry.categoryId != CAT_UNCATEGORISED -> entry.categoryId.also { suggested++ }
            entry.txn.direction == "out" -> CAT_SHOPPING_ID.also { shopping++ }
            else -> CAT_OTHER.also { other++ }
        }
        assignments += entry to category
    }
    return AutoFilePlan(assignments, suggested, shopping, other)
}
