package com.doxigo.muchtoman

import android.Manifest
import android.annotation.SuppressLint
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
 * she asked to be told about, and a transaction has landed that nobody has said what it was.
 * Nothing here fires because the app was opened, because a price moved, or because a week went by —
 * the rule the reward system already runs on, applied to the one surface that can interrupt her.
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
 * ## The two are not equal, and the channels say so
 *
 * A budget is money and a decision she can still make, so «بودجه» is IMPORTANCE_DEFAULT and makes a
 * sound. Filing is homework, so «دسته‌بندی» is IMPORTANCE_LOW: it lands in the shade, it never
 * takes over the screen, and it never makes a noise. The same ranking [pressingBudget] already
 * makes when the two compete for the one line on the home screen. Two channels rather than one so
 * that either can be silenced without the other — a household that wants budget alerts and no
 * homework is one switch in Android's own settings, not an uninstall.
 *
 * ## What it will not do
 *
 * One live notification per budget and exactly one for the backlog, replaced rather than stacked:
 * one budget is one conversation, «۸۰٪» followed by «۹۵٪» is that conversation moving on, and a
 * per-transaction note would turn a household's Thursday into eleven of them. Nothing is ongoing,
 * nothing is a foreground service, and every note is dismissible and opens the screen it is about.
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
 * The backlog's one note. No tag: there is one backlog and therefore one conversation about it, and
 * the count inside it is what changes.
 */
private const val FILING_NOTE_ID = 2

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
 * The quiet channel, created the same way and for the same reasons — with one difference.
 *
 * IMPORTANCE_LOW: it appears in the shade and on the lock screen, it badges the launcher icon, and
 * it does not make a sound or take over the screen. That is the correct weight for what it says.
 * The backlog is at worst four notes a day — the watch runs every six hours — and each one replaces
 * the last, so a household that spends on a Thursday would be buzzed at four times for homework
 * that will keep. It is also the honest reading of what the note is: nothing here has to be done
 * now, only *remembered* now, and remembering is what the shade is for.
 *
 * She can raise it in Android's own settings if she would rather be told out loud, which is a
 * better arrangement than the app deciding on her behalf that filing receipts is urgent.
 */
private fun ensureFilingChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(FILING_CHANNEL) != null) return
    manager.createNotificationChannel(
        NotificationChannel(
            FILING_CHANNEL,
            "دسته‌بندی",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "وقتی تراکنش تازه‌ای رسیده و هنوز دسته‌ای نداره."
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
 * Says the one thing there is to say about the backlog, and replaces whatever it said last.
 *
 * Every word comes from [filingAlertTitle] and [filingAlertBody], which are pure and tested, for the
 * reason [notifyBudget]'s do: what a test asserts and what lands on her lock screen have to be the
 * same strings.
 */
// Same reasoning as [notifyBudget]: [canNotify] is the one place that knows every way a note can
// fail to appear, and lint's dataflow does not follow the check across a function boundary.
@SuppressLint("MissingPermission")
fun notifyFiling(context: Context, alert: FilingAlert) {
    if (!canNotify(context)) return
    ensureFilingChannel(context)
    val title = filingAlertTitle(alert)
    val body = filingAlertBody(alert)
    val note = NotificationCompat.Builder(context, FILING_CHANNEL)
        .setSmallIcon(R.drawable.ic_toman)
        .setColor(0xFF0A423B.toInt())
        .setContentTitle(title)
        .setContentText(body)
        // Persian wraps long, and the count at the end of the body is the half she cannot work
        // out from the title.
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        // Not CATEGORY_REMINDER, which the platform reserves for something *she* asked to be
        // reminded of at a time she chose. This is the app noticing something.
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        // The whole backlog, not the new ones: launchers that draw a number draw this one, and a
        // badge that disagrees with the badge on the دفتر tab is two apps' worth of counting.
        .setNumber(alert.waiting)
        .setAutoCancel(true)
        .setContentIntent(openDeck(context))
        .setTicker("$title. $body")
        .build()
    runCatching { NotificationManagerCompat.from(context).notify(FILING_NOTE_ID, note) }
        .onFailure { android.util.Log.w("muchtoman", "filing notify failed: $it") }
}

/** Takes back the backlog note. Called the moment she opens the app, which is the answer to it. */
fun clearFilingNote(context: Context) {
    runCatching { NotificationManagerCompat.from(context).cancel(FILING_NOTE_ID) }
        .onFailure { android.util.Log.w("muchtoman", "filing cancel failed: $it") }
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
 */
fun announceFiling(context: Context, store: Store, review: List<LedgerEntry>) {
    if (review.isNotEmpty()) ensureFilingChannel(context)
    val news = filingNews(review, store.filingMark)
    store.filingMark = news.mark
    // Nothing left to file — she cleared it on the other phone, or a parser fix filed the last of
    // it — so a note still standing is describing a backlog that is gone.
    if (review.isEmpty()) clearFilingNote(context)
    news.alert?.let { notifyFiling(context, it) }
}

/**
 * She is looking at the ledger, so the backlog has been said better than a notification could say
 * it: take the note back and move the mark past everything on her screen.
 *
 * This is what makes the whole feature quiet rather than nagging. Without it, an evening spent in
 * the app filing nine of twelve receipts would be followed at 3am by a note about the three she
 * decided to leave — which she did not decide by accident.
 */
fun markFilingSeen(context: Context, store: Store, review: List<LedgerEntry>) {
    // As soon as there is a backlog, not when the first note is posted — the same rule
    // [announceBudgets] follows, and for the same reason: a channel created lazily on the first
    // post is missing from Android's own settings at exactly the moment she goes looking for the
    // switch that silences it.
    if (review.isNotEmpty()) ensureFilingChannel(context)
    store.filingMark = filingNews(review, store.filingMark).mark
    clearFilingNote(context)
}
