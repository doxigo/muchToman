package com.doxigo.muchtoman

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

/**
 * The chart's safety net: one snapshot a day whether or not the app is opened, so a week of
 * not looking is a week of chart, not a hole. Runs the exact code path the app runs —
 * [snapshotHistory] guards against stale rates, and [recordDay] overwrites the same day, so
 * this and an app open on the same day converge on one entry instead of arguing.
 */
class DailySnapshotWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val holdings = store.holdings
        val (rates, stocks) = coroutineScope {
            val rates = async { fetchRates(BuildConfig.RATES_URL) }
            val stocks = if (holdings.any { isStockId(it.typeId) }) async { fetchTse() } else null
            rates.await() to stocks?.await()
        }
        rates.onSuccess {
            store.cachedRates = mergeRates(it, store.cachedRates)
        }
        // بورس has to be fetched here too. Without it the unattended snapshot values a
        // portfolio holding shares against rates that have none, snapshotHistory refuses the
        // whole day, and the chart quietly stops for anyone who owns a single نماد.
        stocks?.onSuccess { store.cachedStocks = it }
        // A failed fetch still falls through: cached rates younger than a day are good enough,
        // and snapshotHistory is the one that decides.
        snapshotHistory(
            store.history,
            listHoldings(holdings, store.smsEnabled, store.bankAccounts, store.disabledBanks),
            effectiveRates(store.cachedRates, store.overrides, store.cachedStocks),
            store.cachedRates.updatedAt,
            System.currentTimeMillis(),
        )?.let { store.history = it }
        // AndWait: doWork returning is what makes this process killable again, and a
        // fire-and-forget redraw would race that.
        updateTotalWidgetAndWait(applicationContext)
        return Result.success()
    }
}

/** Idempotent; KEEP means calling this on every app start never resets the schedule. */
fun scheduleDailySnapshot(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "daily-snapshot",
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<DailySnapshotWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build(),
    )
}

/**
 * How often the ledger is looked at while the app is closed *without being asked to*.
 *
 * Six hours is no longer the freshness of anything: [SmsReceiver] runs the watch within seconds of
 * a bank message landing, and this schedule is the net under it. What falls through is real —
 * `RECEIVE_SMS` denied on its own, an OEM that quietly stops delivering the broadcast, a message
 * that arrived before the permission did — and in every one of those cases four sweeps a day is
 * what keeps «۸۰٪ رفته» arriving the same day rather than never.
 */
private const val LEDGER_WATCH_HOURS = 6L

private const val LEDGER_WATCH_WORK = "ledger-watch"

/**
 * What this work was called when it only watched budgets, cancelled on the way past.
 *
 * The unique name is the platform's identity for a schedule, and the enqueued spec names the worker
 * class that is going to run — so a rename of the class alone would leave every phone that already
 * keeps a budget scheduled against a class that no longer exists. Cancelling the old name and
 * enqueueing the new one is the arrangement with no way to get it half right, and it costs one
 * no-op call on every phone that never had the old work.
 *
 * ponytail: droppable once no install can still be carrying a v1.1.x schedule.
 */
private const val LEGACY_BUDGET_WATCH_WORK = "budget-watch"

/**
 * The unattended half of the app: the only thing that reads the inbox while she is not looking, and
 * the only thing that speaks while the app is closed.
 *
 * Reached two ways — by [SmsReceiver] seconds after a bank message lands, and by
 * [scheduleLedgerWatch]'s six-hour sweep for whatever the broadcast path missed. The same worker on
 * both on purpose: one definition of what a wakeup does, however the wakeup came about.
 *
 * It runs the same pipeline the app runs on every foreground — ingest, derive, then read the
 * ledger — because both things it might say are figures over her transactions and there is no
 * shortcut to either that is not a second, disagreeing definition. Every step of that pipeline is
 * already total and idempotent, which is the property that makes running it from here safe: a scan
 * that lands at the same moment as an app open produces the same rows.
 *
 * Nothing is written that she can see except a notification. No balance, no snapshot, no widget —
 * this worker's whole output is «a budget of yours crossed a line» and «something landed that
 * nobody has filed», and [announceBudgets] and [announceFiling] are what decide whether either is
 * worth saying.
 */
class LedgerWatchWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val store = Store(app)
        val durable = DurableDb.get(app)
        // Every check before anything expensive. A phone with nothing to watch, or one where she
        // has turned the notifications off, must pay for this wakeup with a SQLite read and a
        // preference lookup — not with a rebuild of the ledger it was never going to say anything
        // about.
        val budgets = durable.goals().active().any { it.kind == GoalKind.CAP }
        if (!budgets && !store.smsEnabled) return Result.success()
        if (!canNotify(app)) return Result.success()

        return runCatching {
            val derived = DerivedDb.get(app)
            val extra = extraLookup(store.extraBankNumbers)
            val added = ingestBankSms(app, durable, extra)
            if (added > 0 || needsDerive(derived)) derive(durable, derived, extra)
            // One read of the ledger for both. Two would be two walks over four thousand rows and,
            // worse, two answers to «what is in the ledger right now».
            val view = ledgerView(derived, durable)
            announceBudgets(app, store, view.budgets)
            announceFiling(app, store, view)
            Result.success()
        }.getOrElse {
            // Retry rather than success: a failed read here is an alert that did not happen, and
            // WorkManager's backoff is a better answer than waiting six hours for the next one.
            android.util.Log.w("muchtoman", "ledger watch failed: $it")
            Result.retry()
        }
    }
}

/**
 * Watch the ledger, or stop watching once there is nothing to watch.
 *
 * Scheduled by whether there is anything to say rather than KEEP-on-every-start, which is the one
 * place this differs from [scheduleDailySnapshot] and the reason is the asymmetry: the snapshot has
 * something to do on every phone, and this has something to do only on a phone that keeps a budget
 * or reads bank messages. Cancelling when the last of both goes is what keeps a feature she is not
 * using from waking her phone four times a day for ever.
 *
 * [wanted] is that question already answered by the caller, because the two halves of it live in
 * two different places — the goals table and her SMS switch — and the callers have both to hand.
 */
fun scheduleLedgerWatch(context: Context, wanted: Boolean) {
    val work = WorkManager.getInstance(context)
    work.cancelUniqueWork(LEGACY_BUDGET_WATCH_WORK)
    if (!wanted) {
        work.cancelUniqueWork(LEDGER_WATCH_WORK)
        return
    }
    work.enqueueUniquePeriodicWork(
        LEDGER_WATCH_WORK,
        // KEEP, so the schedule is not reset every time the app is opened — which would push the
        // next run six hours out on a phone she opens every morning, i.e. never run it at all.
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<LedgerWatchWorker>(LEDGER_WATCH_HOURS, TimeUnit.HOURS)
            // No network constraint on purpose: everything this needs is the inbox and two local
            // databases, and neither alert must wait for Wi-Fi.
            .build(),
    )
}

/** The receiver's one-shot runs of the watch, named apart from the schedule so neither owns the other. */
private const val LEDGER_WATCH_NOW_WORK = "ledger-watch-now"

/**
 * How long after the broadcast the watch runs. SMS_RECEIVED reaches us and the default SMS app in
 * parallel, and only that app writes the message into the store [readSmsInbox] reads — run at once
 * and the message this is about is the one message not there yet. A few seconds is comfortably past
 * that write, and «the second it arrives» to a person.
 */
private const val LEDGER_WATCH_NOW_DELAY_SECONDS = 5L

/**
 * The real-time half of what [scheduleLedgerWatch] does four times a day: a bank message has just
 * landed, so run [LedgerWatchWorker] now rather than at the next tick.
 *
 * The receiver deliberately knows nothing the worker does not. It ingests nothing, parses no body,
 * and posts no notification — it answers one question, «was that a bank?», with the same [bankOf]
 * the ingest gate uses, and enqueues the exact worker the schedule runs. Anything the broadcast
 * path misses — permission denied, an OEM that drops the broadcast — is therefore not a second
 * code path half-covered, it is the same code path on the six-hour net.
 *
 * The sender check is not an optimisation. The alternative is this app's process waking for every
 * verification code and family message a phone receives, which is a battery cost paid to banks'
 * spam competitors — and the check is one map lookup against the same numbers ingest would refuse
 * anyway.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val store = Store(context)
        if (!store.smsEnabled) return
        // getMessagesFromIntent reassembles a long message's parts, but the sender is on every
        // part, so any() is asking one question however the message was split. Wrapped because a
        // malformed PDU from a broken SMSC throws inside the platform's parser, and a message this
        // app cannot even look at must not crash it.
        val extra = extraLookup(store.extraBankNumbers)
        val fromBank = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                .any { bankOf(it?.originatingAddress.orEmpty(), extra) != null }
        }.getOrDefault(false)
        if (!fromBank) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            LEDGER_WATCH_NOW_WORK,
            // APPEND_OR_REPLACE, never REPLACE: a purchase and its balance line arrive as two
            // messages seconds apart, and REPLACE would cancel a run already past its marks but
            // not yet past its notify — an alert written down as said and never posted. Appending
            // runs again after, and a run with nothing new says nothing.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<LedgerWatchWorker>()
                .setInitialDelay(LEDGER_WATCH_NOW_DELAY_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }
}

/**
 * Whether the phone will let this app's background work run when it is due.
 *
 * Doze and App Standby are the stock behaviour and are not the problem: the system delivers
 * SMS_RECEIVED to a manifest receiver either way. The problem is what every OEM layers over them —
 * MIUI, EMUI, One UI, ColorOS all suspend backgrounded apps far harder than AOSP does, and on those
 * phones a spend can go quiet until the six-hour sweep. Exemption is the one lever the platform
 * offers to ask for that to stop.
 *
 * True is not a promise. An OEM's own «autostart» switch lives outside this API and cannot be read
 * or asked for at all, so a phone can report itself unrestricted here and still throttle the
 * receiver. It is the difference between the app knowing it has a problem and knowing it does not
 * have *this* problem.
 */
fun backgroundUnrestricted(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return true
    return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
        .getOrDefault(true)
}

/**
 * Ask to be exempted, and fall back to the list she can do it from by hand.
 *
 * The direct request is one dialog with one Yes in it, which is the whole reason to prefer it. It
 * is also the one an OEM is most likely to have removed — hence the settings-list fallback, which
 * is a longer road to the same switch but exists on every build. Returns false when neither opens,
 * which is a phone this app cannot help and must not pretend it has.
 */
@SuppressLint("BatteryLife")
fun askBackgroundExemption(context: Context): Boolean {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isSuccess) return true
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(list) }.isSuccess
}
