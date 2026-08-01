package com.doxigo.muchtoman

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sender, body, and when the network stamped it. */
data class RawSms(val from: String, val body: String, val at: Long)

fun canReadSms(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

fun openSmsThread(context: Context, sender: String): Boolean {
    if (sender.isBlank()) return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.fromParts("smsto", sender, null))
    val handler = Telephony.Sms.getDefaultSmsPackage(context)
        ?: intent.resolveActivity(context.packageManager)?.packageName
    handler?.takeUnless { it == "android" }?.let(intent::setPackage)
    return runCatching { context.startActivity(intent) }.isSuccess
}

/**
 * Everything in the inbox newer than [since], oldest first — the order [applyBankSms] needs,
 * so a stated balance is never overwritten by a transaction that came before it.
 *
 * There is deliberately no broadcast receiver behind this. Nothing displays a balance while the
 * app is closed, so a message that arrives then can just as well be read the next time she
 * opens it: the inbox is already the record, and a background write path is a whole class of
 * bugs bought for no visible benefit.
 */
suspend fun readSmsInbox(context: Context, since: Long): List<RawSms> = withContext(Dispatchers.IO) {
    if (!canReadSms(context)) return@withContext emptyList()
    val rows = mutableListOf<RawSms>()
    runCatching {
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            val from = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                rows += RawSms(
                    c.getString(from).orEmpty(),
                    c.getString(body).orEmpty(),
                    c.getLong(date),
                )
            }
        }
    }.onFailure {
        // A revoked permission or an OEM-locked provider must not take the app down with it;
        // the balances simply stop moving and the log line says why.
        android.util.Log.w("muchtoman", "sms read failed: $it")
    }
    rows
}
