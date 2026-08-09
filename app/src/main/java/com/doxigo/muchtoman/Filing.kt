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
 *  - **It says what landed, never what it was.** «۴۵۰ هزار تومان رفت» is a fact about a direction.
 *    Calling it خرج would be the app answering the question it is asking her — and an unfiled
 *    movement is exactly as likely to be a transfer between her own accounts.
 *
 * And one it inherits: this is homework, not money. A budget that has crossed its cap interrupts,
 * because there is a decision left to make; this waits quietly in the shade for the next time she
 * looks at her phone. Same ranking [pressingBudget] already makes for the home screen — see the
 * channel importances in `Notify.kt`.
 */

/**
 * What is worth saying about the backlog right now.
 *
 * Nothing here is stored. Like [BudgetProgress], it is worked out from the ledger every time it is
 * asked for, so a receipt filed on the other phone in the household moves it the moment the sync
 * lands rather than leaving a stored figure to drift.
 */
data class FilingAlert(
    /** How many landed since she was last told or last looked. Never zero — that is no news. */
    val fresh: Int,
    /** How many are waiting in all, the fresh ones included. Never fewer than [fresh]. */
    val waiting: Int,
    /**
     * The newest of the fresh ones, which is the one she is most likely to still be able to place.
     * Only ever spoken aloud when it is the *only* one, since naming one of four is picking a
     * favourite out of a list she has to work through anyway.
     */
    val newest: LedgerEntry,
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
 * It is a high-water mark and never lowered, for the reason [BudgetMark]'s level is not.
 */
data class FilingNews(val alert: FilingAlert?, val mark: Long)

/**
 * What has landed since [said], and how far the mark moves.
 *
 * [said] of zero means this phone has never looked, and that case is deliberately **silent**: it is
 * a fresh install part-way through its first import, or an upgrade onto a build that had no mark to
 * keep, and neither is a moment to hand her a backlog she has been living with. The first pass only
 * learns where the ledger is.
 */
fun filingNews(review: List<LedgerEntry>, said: Long): FilingNews {
    val mark = maxOf(said, review.maxOfOrNull { it.txn.at } ?: 0L)
    val fresh = review.filter { it.txn.at > said }
    val alert = if (said <= 0L || fresh.isEmpty()) {
        null
    } else {
        FilingAlert(fresh = fresh.size, waiting = review.size, newest = fresh.maxBy { it.txn.at })
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
    if (alert.fresh > 1) return "${faNumber(alert.fresh.toDouble())} تراکنش تازه رسید"
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
 * The line under it: why now, and how much is actually waiting.
 *
 * «تا یادته» is the whole argument for interrupting her at all, and it is an argument rather than an
 * instruction — the app has no claim on her afternoon, it just knows the answer is easier today.
 *
 * The total is only added when there is something older behind the new ones, and it is phrased as a
 * state rather than as an addition: «۹ تراکنش دسته‌بندی نشده داری» cannot be read as nine *more* on
 * top of the three in the title. The words are the ones the ledger already uses for an unfiled row.
 */
fun filingAlertBody(alert: FilingAlert): String {
    val head = if (alert.fresh == 1) "تا یادته، بگو چی بود" else "تا یادته، بگو چی بودن"
    if (alert.waiting <= alert.fresh) return head
    return "$head • روی هم ${faNumber(alert.waiting.toDouble())} تراکنش دسته‌بندی نشده داری"
}
