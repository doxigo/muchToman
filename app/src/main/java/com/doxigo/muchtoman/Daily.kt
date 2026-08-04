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
