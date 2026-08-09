package com.doxigo.muchtoman

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
 * How often the ledger is looked at while the app is closed.
 *
 * Six hours, which is the compromise the architecture forces and it is worth being plain about it.
 * There is no SMS broadcast receiver — see [readSmsInbox] — so a purchase becomes visible to this
 * app only when something reads the inbox, and this is the only thing that reads it unattended.
 * Four wakeups a day is close enough that «۸۰٪ رفته» still arrives while the month has something
 * left in it, and that «این چی بود؟» still arrives on the day she could answer it, and far enough
 * apart to be free.
 *
 * ponytail: the ceiling on how fresh either notification can be. A `RECEIVE_SMS` receiver would
 * make them instant and would also mean this app asks for the permission that reads messages as
 * they arrive, which is a different app.
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
            announceFiling(app, store, view.review)
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
