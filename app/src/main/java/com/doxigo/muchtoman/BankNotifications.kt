package com.doxigo.muchtoman

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Blu's own app. Its transaction notifications carry the same words as its SMS. */
const val BLU_APP = "com.samanpr.blu"

/**
 * One bank-app notification as the message it stands for: title and text, one per line.
 *
 * Blu titles every alert «بلو» and puts the rest of its SMS in the text, so the two joined are that
 * SMS byte for byte, and the parser needs nothing new (the corpus pins it:
 * `blu-app-notification-parid`). The caller passes the big text when there is one: the collapsed
 * text is only what fits on one line, and an app is free to cut it there.
 */
fun bankNotification(pkg: String, title: CharSequence?, text: CharSequence?, at: Long): RawSms? {
    val body = text?.toString()?.takeIf { it.isNotBlank() } ?: return null
    val head = title?.toString()?.takeIf { it.isNotBlank() }
    return RawSms(pkg, listOfNotNull(head, body).joinToString("\n"), at)
}

/**
 * Reads Blu's transaction alerts for someone who takes them as notifications instead of SMS.
 *
 * Android hands a listener every notification on the phone, and the system page she grants it on
 * says so. This one looks at the package of each and nothing else, and drops everything that is
 * not Blu's before its title or text is touched. What is left goes through the same gate a
 * message does ([ingestNotifications]), into the same table, under the app's package as its
 * sender. From there on it is a Blu message: the ledger, the rules, the balance and the family
 * sync cannot tell the difference, and a phone getting both the SMS and the notification has the
 * second hidden by [findDuplicates], because both state the same موجودی.
 *
 * It answers to Android's notification-access switch alone, not to the SMS one: someone whose only
 * bank is Blu, on notifications, has no reason to grant this app her messages.
 */
class BankNotificationListener : NotificationListenerService() {

    /**
     * Whatever is still in the shade from before the listener was bound, including the one she
     * turned access on for. Asking again on every reconnect is free: the primary key drops repeats.
     */
    override fun onListenerConnected() {
        take(runCatching { activeNotifications }.getOrNull().orEmpty().toList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = take(listOf(sbn))

    private fun take(posted: List<StatusBarNotification>) {
        val notes = posted.filter { it.packageName == BLU_APP }.mapNotNull { sbn ->
            val extras = sbn.notification?.extras ?: return@mapNotNull null
            bankNotification(
                sbn.packageName,
                extras.getCharSequence(Notification.EXTRA_TITLE),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT),
                sbn.postTime,
            )
        }
        if (notes.isEmpty()) return
        val app = applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                val extra = extraLookup(Store(app).extraBankNumbers)
                val durable = DurableDb.get(app)
                val added = ledgerGate.withLock {
                    ingestNotifications(durable, notes, extra).also {
                        // Derived here rather than left to the worker: the worker derives when its
                        // own inbox read found something new, and this row did not come from there.
                        if (it > 0) derive(durable, DerivedDb.get(app), extra)
                    }
                }
                // The worker for what it says (budgets, «این چی بود؟») and the family sync.
                if (added > 0) watchLedgerSoon(app)
            }.onFailure { android.util.Log.w("muchtoman", "bank notification failed: $it") }
        }
    }
}

/** Whether she has given this app notification access. Android's switch, not one of ours. */
fun canReadNotifications(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

/**
 * Straight to this app's own switch where Android has that page (11 and later), else the list it
 * sits in. Access is only ever granted or taken back there; no dialog can ask for it.
 */
fun openNotificationAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val direct = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, BankNotificationListener::class.java).flattenToString(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isSuccess) return true
    }
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(list) }.isSuccess
}
