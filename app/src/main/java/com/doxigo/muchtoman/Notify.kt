package com.doxigo.muchtoman

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * The only thing in this app that speaks while it is closed.
 *
 * There are exactly two channels and two reasons to use them: a budget she set has crossed a line
 * she asked to be told about, and a transaction has landed — filed by a rule, or waiting for her
 * to say what it was. Nothing here fires because the app was opened, because a price moved, or
 * because a week went by — the rule the reward system already runs on, applied to the one surface
 * that can interrupt her.
 *
 * ## Why any of this exists
 *
 * A budget that is only true while the app is open is a scoreboard. «۸۰٪ رفته» is worth knowing at
 * the till, and by the time she next opens the app the decision it was for has been made. The
 * backlog is the same argument on a different clock: «این چی بود؟» is one tap on the day it happened
 * — see `Filing.kt`. Both have to reach her where she is, which on Android means a notification and
 * therefore a permission, and the permission is asked for at the moment one of the two becomes
 * possible — never at launch, exactly as `READ_SMS` is asked for when she switches the messages on.
 *
 * ## Two channels, so either can be silenced without the other
 *
 * Both are IMPORTANCE_DEFAULT now that `SmsReceiver` makes them prompt: a budget crossing is a
 * decision she can still make, and a transaction note that arrives while the purchase is still in
 * her hand is the moment worth a sound — six hours later it was only homework, which is what it
 * used to be and why this channel used to be IMPORTANCE_LOW. The split remains so a household that
 * wants budget alerts and no transaction notes — or the reverse — is one switch in Android's own
 * settings, not an uninstall, and either can be turned back down there too.
 *
 * ## One note per thing, and what «thing» means on each channel
 *
 * A budget is one conversation, so it gets one note that is replaced: «۸۰٪» followed by «۹۵٪» is
 * that conversation moving on, not two of them.
 *
 * A transaction is not. Each is a separate question with its own answer and its own clock, and the
 * backlog used to get one note that each new arrival overwrote — so two spends five minutes apart
 * left one line on her lock screen and the first receipt quietly stopped existing. Now every
 * transaction gets its own note, tagged with its `ref`, stacked under a group summary the platform
 * bundles them into and capped at [LANDED_NOTE_LIMIT] so a Thursday cannot fill the shade. Opening
 * the app takes the whole stack back at once — see [clearFilingNote].
 *
 * Beyond that: nothing is ongoing, nothing is a foreground service, and every note is dismissible
 * and opens the screen it is about.
 */

/** «بودجه» — one of the two channels. Named so it can be silenced on its own in Android's settings. */
private const val BUDGET_CHANNEL = "budget"

/** «دسته‌بندی» — the quiet one. See [ensureFilingChannel] for why it is quiet. */
private const val FILING_CHANNEL = "filing"

/**
 * One id for every budget note, with the goal's id as the tag.
 *
 * Tag-plus-id is what the platform offers for "one of a set", and it is collision-free by
 * construction — hashing goal ids into an int space is not, and two budgets whose notes replaced
 * each other would be a bug nobody could reproduce.
 */
private const val BUDGET_NOTE_ID = 1

/**
 * One id for every transaction note, with the transaction's `ref` as the tag.
 *
 * The same tag-plus-id arrangement [BUDGET_NOTE_ID] uses, and for the same reason: `ref` is already
 * the primary key of the row, so it is collision-free without hashing anything into an int space.
 * It is also what makes the whole stack findable — see [clearFilingNote], which asks the platform
 * for everything it has posted under this id rather than keeping a list of its own that could drift.
 *
 * There is one more thing it buys, quietly: a phone upgrading from the build that posted a single
 * untagged note at this id still has that note live, and it comes down with the rest.
 */
private const val FILING_NOTE_ID = 2

/**
 * The line over the stack: how many landed, and how much is waiting in all.
 *
 * Untagged and separate from [FILING_NOTE_ID], so [clearFilingNote] can tell the summary from the
 * notes it summarises without inspecting either. When only one transaction has landed the platform
 * shows that note and hides this — which is why [filingAlertTitle] still reads correctly alone.
 */
private const val FILING_SUMMARY_ID = 3

/**
 * What bundles the transaction notes together.
 *
 * Without it, four spends are four separate lines competing with everything else in her shade;
 * with it they are one entry she can expand, and the platform takes the summary down on its own
 * once she has swiped the last child away.
 */
private const val FILING_GROUP = "com.doxigo.muchtoman.FILING"

/** Read by [MainActivity] to open on the screen a notification was about. */
const val EXTRA_OPEN_TAB = "com.doxigo.muchtoman.OPEN_TAB"

/**
 * Set by the filing note, which asks for something rather than reporting something — so it opens the
 * deck that answers it rather than a screen she would then have to find her way out of.
 */
const val EXTRA_OPEN_DECK = "com.doxigo.muchtoman.OPEN_DECK"

/**
 * Whether a notification posted right now would actually appear.
 *
 * Both halves are load-bearing. From API 33 the runtime permission may be denied; on every version
 * she can switch the app's notifications off in system settings, and a feature that promises to
 * tell her something must be able to find out that it cannot.
 */
fun canNotify(context: Context): Boolean {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/**
 * Creates the channel if it is not already there. Idempotent, and cheap enough to call before
 * every post rather than tracking whether it has been done.
 *
 * IMPORTANCE_DEFAULT, which makes a sound: this is the one thing the app has to say while it is
 * closed, and a silent budget alert is a budget alert that arrives tomorrow. She can turn the
 * sound off per channel in Android's settings, and the channel exists so that is one tap and not
 * "turn off notifications for this app".
 */
private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(BUDGET_CHANNEL) != null) return
    manager.createNotificationChannel(
        NotificationChannel(
            BUDGET_CHANNEL,
            "بودجه",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "وقتی خرج یک دسته به بودجه‌ای که گذاشتی نزدیک می‌شه یا از اون می‌گذره."
            setShowBadge(true)
        },
    )
}

/**
 * The transaction channel, created the same way and for the same reasons.
 *
 * IMPORTANCE_DEFAULT, like «بودجه» — but it was IMPORTANCE_LOW until `SmsReceiver` existed, and the
 * old reasoning is worth keeping: a note that trailed the spend by up to six hours was homework,
 * and homework belongs in the shade. Now the note lands while the purchase is still in her hand,
 * and a sound at that moment is the difference between filing it in one tap and the small
 * archaeology `Filing.kt` describes. She can turn it back down per channel in Android's settings —
 * and a phone whose channel already exists keeps whatever weight it has, because the platform lets
 * an app lower an existing channel but never raise one.
 */
private fun ensureFilingChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(FILING_CHANNEL) != null) return
    manager.createNotificationChannel(
        NotificationChannel(
            FILING_CHANNEL,
            "دسته‌بندی",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "وقتی تراکنش تازه‌ای می‌رسه — چه منتظر دسته‌بندی، چه خودکار ثبت‌شده."
            setShowBadge(true)
        },
    )
}

/**
 * Where a budget note goes when it is tapped: the budget screen, and the budget screen only.
 *
 * SINGLE_TOP with CLEAR_TOP so it reuses the activity she already has rather than stacking a
 * second one — [MainActivity.onNewIntent] is what reads the extra in that case. One PendingIntent
 * for every budget on purpose: they all lead to the same screen, so distinguishing them would only
 * buy the platform more objects to keep.
 */
private fun openBudgets(context: Context): PendingIntent = destination(context, 0) {
    it.putExtra(EXTRA_OPEN_TAB, Tab.BUDGET.name)
}

/**
 * Where the filing note goes: straight into the deck, which is the answer to the question it asked.
 *
 * The deck rather than the دفتر tab, because this note is a request and the tab is only the room the
 * request is answered in. It is safe to land on even when there is nothing left — a deck with no
 * cards is the «مرور هفتگی» summary, which is a reasonable place to arrive at from a note about a
 * backlog she has since cleared on the other phone.
 */
private fun openDeck(context: Context): PendingIntent = destination(context, 1) {
    it.putExtra(EXTRA_OPEN_DECK, true)
}

/**
 * Where a filed-only note goes: the دفتر tab, where the row it reported is the newest thing on
 * screen. Not the deck — a note that says «ثبت شد» has nothing for the deck to ask, and landing her
 * in the weekly summary would answer a question she did not tap to ask. Refiling from the timeline
 * is one tap on the row, which is exactly the veto the note offered.
 */
private fun openLedgerTab(context: Context): PendingIntent = destination(context, 2) {
    it.putExtra(EXTRA_OPEN_TAB, Tab.LEDGER.name)
}

/**
 * One activity, reached two ways.
 *
 * [requestCode] is the whole reason this is a function. Two PendingIntents are the same
 * PendingIntent as far as the platform is concerned when their intents match on component, action,
 * data and flags — and **extras are not compared**. Both of these differ in nothing but their
 * extra, so with one request code the second `FLAG_UPDATE_CURRENT` would silently rewrite the
 * first: tapping a budget alert would open the review deck, and no test on a JVM could ever
 * catch it.
 */
private fun destination(context: Context, requestCode: Int, extra: (Intent) -> Unit): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .also(extra)
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Says one thing about one budget, and replaces whatever it said last.
 *
 * Every word comes from [budgetAlertTitle] and [budgetAlertBody], which are pure and tested. This
 * function's whole job is the platform: no figure is computed here, and no sentence is assembled
 * here, so what a test asserts and what lands on her lock screen are the same strings.
 */
// The permission *is* checked, one line into the body, by [canNotify] — which is the only place in
// the app that knows all the ways a note can fail to appear, and is deliberately not inlined here so
// that the screen and the worker ask the same question this does. Lint's dataflow does not follow a
// check across a function boundary, so it is told here, at the single call it applies to. The
// runCatching below is the belt to that braces: a SecurityException from an OEM's own idea of
// notification policy costs a missed alert and never a crash.
@SuppressLint("MissingPermission")
fun notifyBudget(context: Context, budget: BudgetProgress) {
    if (!canNotify(context)) return
    ensureChannel(context)
    val title = budgetAlertTitle(budget)
    val body = budgetAlertBody(budget)
    val note = NotificationCompat.Builder(context, BUDGET_CHANNEL)
        // The app's own mark. A notification icon is drawn from the alpha channel alone, so the
        // solid toman glyph comes out as the silhouette the platform wants.
        .setSmallIcon(R.drawable.ic_toman)
        .setColor(0xFF0A423B.toInt())
        .setContentTitle(title)
        .setContentText(body)
        // Persian wraps long and these two lines carry the whole message; collapsed, the figure
        // at the end of the body — which is the part she needs — is the part that is cut.
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setContentIntent(openBudgets(context))
        // Read out as one sentence rather than as a heading and an orphaned figure.
        .setTicker("$title. $body")
        .build()
    // Wrapped: posting can throw on an OEM build that has its own idea about notification limits,
    // and a budget note that cannot be shown must not take down the worker that computed it.
    runCatching { NotificationManagerCompat.from(context).notify(budget.goal.id, BUDGET_NOTE_ID, note) }
        .onFailure { android.util.Log.w("muchtoman", "budget notify failed: $it") }
}

/**
 * Says one thing per transaction that landed, and one thing over the top of them.
 *
 * The notes are posted oldest-last so the newest is the one that arrives most recently and sorts to
 * the top of the shade, and the summary goes out after all of them: a summary that lands first is a
 * group with no children for as long as the loop takes, which some system UIs draw as an empty
 * bundle.
 *
 * Every word comes from [landedTitle], [landedBody], [filingAlertTitle] and [filingAlertBody], which
 * are pure and tested, for the reason [notifyBudget]'s do: what a test asserts and what lands on
 * her lock screen have to be the same strings.
 *
 * Each post is wrapped on its own. One note refused — an OEM's per-app ceiling, a `ref` the
 * platform dislikes — must not cost the other seven, and least of all the summary.
 */
// Same reasoning as [notifyBudget]: [canNotify] is the one place that knows every way a note can
// fail to appear, and lint's dataflow does not follow the check across a function boundary.
@SuppressLint("MissingPermission")
fun notifyFiling(context: Context, news: FilingNews) {
    val alert = news.alert ?: return
    if (!canNotify(context)) return
    ensureFilingChannel(context)
    val manager = NotificationManagerCompat.from(context)
    for (entry in news.landed.asReversed()) {
        runCatching { manager.notify(entry.txn.ref, FILING_NOTE_ID, landedNote(context, entry)) }
            .onFailure { android.util.Log.w("muchtoman", "filing notify failed: $it") }
    }
    runCatching { manager.notify(FILING_SUMMARY_ID, filingSummary(context, alert)) }
        .onFailure { android.util.Log.w("muchtoman", "filing summary failed: $it") }
}

/**
 * One transaction's note: what landed, and the one thing there is to do about it.
 *
 * GROUP_ALERT_CHILDREN rather than the summary, and it is the difference between hearing about a
 * spend and not: the summary is hidden whenever a single transaction has landed, which is nearly
 * every time, and a group that alerts through a hidden note alerts through nothing. The platform
 * rate-limits an app's sounds to roughly one a second, so the sweep that brings four still makes
 * about one noise.
 *
 * No `setNumber` here, deliberately. The count belongs to [filingSummary], and launchers that draw
 * a badge sum a group's children — eight notes each carrying the backlog's size would badge the
 * backlog eight times over.
 *
 * ## No `setWhen(txn.at)` either, which cost a note before it was found
 *
 * Stamping the note with the transaction's own time reads better — the six-hour sweep can be hours
 * behind the پیامک — and the platform **silently drops** anything whose `when` is more than about a
 * fortnight old: `NotificationService: Ignored enqueue for old …`, no exception, nothing for the
 * `runCatching` around the post to catch. A four-transaction batch posted three notes and a summary
 * that counted four, and the one that vanished was the oldest, which is the one she is least likely
 * to remember unaided.
 *
 * That is not a rewind-only curiosity. A phone whose notifications were off for a fortnight leaves
 * its mark where it stood — [LedgerWatchWorker] skips the announce entirely — so the batch waiting
 * on the far side of switching them back on is exactly the batch this would eat. The post time is
 * what every other app shows; the transaction's own time is on the row in دفتر, one tap away.
 */
private fun landedNote(context: Context, entry: LedgerEntry): Notification {
    val title = landedTitle(entry)
    val body = landedBody(entry)
    return NotificationCompat.Builder(context, FILING_CHANNEL)
        .setSmallIcon(R.drawable.ic_toman)
        .setColor(0xFF0A423B.toInt())
        .setContentTitle(title)
        .setContentText(body)
        // Persian wraps long, and on a filed row the category at the end of the body is the half
        // she cannot work out from the title.
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        // Not CATEGORY_REMINDER, which the platform reserves for something *she* asked to be
        // reminded of at a time she chose. This is the app noticing something.
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        // The pre-O mirror of the channel importance, kept in step with [ensureFilingChannel].
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setGroup(FILING_GROUP)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .setAutoCancel(true)
        // A row still waiting opens the deck that answers it; one the rules already filed opens the
        // timeline it is sitting in, where changing its category is one tap on the row.
        .setContentIntent(if (entry.needsReview) openDeck(context) else openLedgerTab(context))
        // Read out as one sentence rather than as a heading and an orphaned figure.
        .setTicker("$title. $body")
        .build()
}

/**
 * The line over the stack, and on a phone showing a single note, the note itself.
 *
 * Silent — GROUP_ALERT_CHILDREN — because the notes underneath already made the sound. Its content
 * intent is the deck whenever anything is waiting, since that is what a total is an argument for.
 *
 * ## What it counts, and the one thing it does not
 *
 * [FilingAlert] is *this wakeup's* batch, and the stack under it is not: a spend at noon she never
 * looked at is still standing when the half-past-twelve one arrives, so «۲ تراکنش تازه رسید» can
 * head a bundle of three. That is left alone deliberately. «تازه رسید» is a claim about arrival and
 * stays true of every note under it; the figure that must not drift — [setNumber], and the «روی هم»
 * line in [filingAlertBody] — is the whole backlog either way, read off the ledger and not off the
 * batch. Making the heading count the shade instead would mean asking the platform what is standing
 * and building these words from the answer, which trades a pure, tested sentence for a query that
 * can only be checked on a phone.
 */
private fun filingSummary(context: Context, alert: FilingAlert): Notification {
    val title = filingAlertTitle(alert)
    val body = filingAlertBody(alert)
    return NotificationCompat.Builder(context, FILING_CHANNEL)
        .setSmallIcon(R.drawable.ic_toman)
        .setColor(0xFF0A423B.toInt())
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setGroup(FILING_GROUP)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        // The whole backlog, not the new ones: launchers that draw a number draw this one, and a
        // badge that disagrees with the badge on the دفتر tab is two apps' worth of counting.
        // Zero — everything filed — draws no number, which is the platform's own convention.
        .setNumber(alert.waiting)
        .setAutoCancel(true)
        // Anything left to file opens the deck that files it; a batch that only reported the rules'
        // work opens the timeline the work is sitting in.
        .setContentIntent(if (alert.waiting > 0) openDeck(context) else openLedgerTab(context))
        .setTicker("$title. $body")
        .build()
}

/**
 * Takes back every transaction note at once. Called the moment she opens the app, which is the
 * answer to all of them.
 *
 * The live set is asked for rather than remembered. A list of posted tags kept in prefs would have
 * to survive process death, an import, and her swiping half of them away by hand — and every one of
 * those drifts silently into a note that can never be taken down. The platform already holds the
 * truth, keyed by [FILING_NOTE_ID], which is exactly the set this posted.
 *
 * That also picks up the single untagged note left standing by the build before this one, whose id
 * was the same.
 */
fun clearFilingNote(context: Context) {
    val manager = NotificationManagerCompat.from(context)
    runCatching {
        manager.cancel(FILING_SUMMARY_ID)
        for (live in manager.activeNotifications) {
            if (live.id == FILING_NOTE_ID) manager.cancel(live.tag, FILING_NOTE_ID)
        }
    }.onFailure { android.util.Log.w("muchtoman", "filing cancel failed: $it") }
}

/**
 * Takes back whatever was said about one budget.
 *
 * Called for every budget that is no longer near its cap, which is how a new month clears last
 * month's warning off her lock screen without her having to swipe it: the window rolled over, the
 * figure went back to nothing, and the note that described the old window is no longer true.
 */
fun clearBudgetNote(context: Context, goalId: String) {
    runCatching { NotificationManagerCompat.from(context).cancel(goalId, BUDGET_NOTE_ID) }
        .onFailure { android.util.Log.w("muchtoman", "budget cancel failed: $it") }
}

/**
 * The whole of what happens when the ledger has moved: work out what is worth saying, say it, and
 * write down what was said.
 *
 * Shared by the app's own ledger pipeline and by [LedgerWatchWorker], deliberately — two callers
 * with two copies of this sequence is two chances to notify twice or not at all. Idempotent: the
 * marks it writes are what stop the next call repeating itself, and the notification tag means
 * that even a genuine race between the app and the worker posts one note rather than two.
 *
 * Notifies even when the app is in the foreground. She may be reading the ledger on another tab,
 * and a redundant note she can dismiss is a much smaller failure than a missed one — which is the
 * only kind of failure this feature has.
 *
 * The marks below are get-then-set on prefs, so this must run under [ledgerGate] — which both
 * callers ([AppVm]'s publishLedger and [LedgerWatchWorker]) already hold around the larger
 * section this belongs to. Held there rather than taken here because the gate is not reentrant:
 * locking again inside would hang the very callers that protect it.
 */
fun announceBudgets(context: Context, store: Store, budgets: List<BudgetProgress>) {
    // As soon as she keeps a budget, not when the first one crosses a line. A channel created
    // lazily on the first post does not exist in Android's own settings until then, so the switch
    // that silences these is missing at exactly the moment she might go looking for it.
    if (budgets.isNotEmpty()) ensureChannel(context)
    val news = budgetNews(budgets, store.budgetMarks)
    store.budgetMarks = news.marks
    for (budget in news.alerts) notifyBudget(context, budget)
    // Anything back under the first threshold — a new window, or a receipt she refiled — has no
    // note to keep standing.
    for (budget in budgets) if (!budget.loud) clearBudgetNote(context, budget.goal.id)
}

/**
 * The same sequence for the backlog: work out what has landed, say it, and write down how far this
 * phone has got.
 *
 * Called **only from the watch worker**, and that is the whole difference from [announceBudgets].
 * A budget crossing is worth repeating to her face because it is about a decision she is in the
 * middle of making; a backlog is not, because the app she is holding is already showing it to her
 * on the tab badge, on the pill at the top of دفتر, and in the deck those two open. The foreground
 * calls [markFilingSeen] instead.
 *
 * Idempotent, like its sibling: the mark it writes is what stops the next call repeating itself.
 *
 * Like [announceBudgets], the mark is get-then-set on prefs and runs under [ledgerGate], held by
 * the worker around the section this is part of — never taken here, because it is not reentrant.
 */
fun announceFiling(context: Context, store: Store, view: LedgerView) {
    // Any ledger at all means this channel can speak — the note covers filed landings too, so
    // waiting for a backlog would create the channel after its first reason to exist.
    if (view.entries.isNotEmpty()) ensureFilingChannel(context)
    val news = filingNews(view.entries, view.review, store.filingMark)
    store.filingMark = news.mark
    // Nothing left to file and nothing new to say — she cleared the backlog on the other phone,
    // or a parser fix filed the last of it — so a standing *ask* is describing work that is gone.
    // This also retires an unread filed-only note at the next sweep, deliberately: what it
    // reported is on the timeline either way, and a report is not worth keeping stale.
    //
    // The whole stack, not just the summary: every one of those notes is describing the same
    // vanished work.
    if (view.review.isEmpty() && news.alert == null) clearFilingNote(context)
    notifyFiling(context, news)
}

/**
 * She is looking at the ledger, so the backlog has been said better than a notification could say
 * it: take the note back and move the mark past everything on her screen.
 *
 * This is what makes the whole feature quiet rather than nagging. Without it, an evening spent in
 * the app filing nine of twelve receipts would be followed at 3am by a note about the three she
 * decided to leave — which she did not decide by accident.
 *
 * The mark is get-then-set on prefs and its one caller — [AppVm]'s publishLedger — holds
 * [ledgerGate] around it; not taken here, because the gate is not reentrant.
 */
fun markFilingSeen(context: Context, store: Store, view: LedgerView) {
    // As soon as there is a ledger, not when the first note is posted — the same rule
    // [announceBudgets] follows, and for the same reason: a channel created lazily on the first
    // post is missing from Android's own settings at exactly the moment she goes looking for the
    // switch that silences it.
    if (view.entries.isNotEmpty()) ensureFilingChannel(context)
    store.filingMark = filingNews(view.entries, view.review, store.filingMark).mark
    clearFilingNote(context)
}
