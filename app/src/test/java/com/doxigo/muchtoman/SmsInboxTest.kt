package com.doxigo.muchtoman

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The inbox as the platform hands it over: a stand-in SMS provider behind the real ContentResolver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmsInboxTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val now = 1_780_000_000_000L

    /** Serves [inbox] for `date >= ?`, oldest first, as the telephony provider does. */
    class InboxProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor {
            val since = args?.firstOrNull()?.toLong() ?: Long.MIN_VALUE
            val cursor = MatrixCursor(arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE))
            for (m in inbox.filter { it.at >= since }.sortedBy { it.at }) cursor.addRow(arrayOf<Any>(m.from, m.body, m.at))
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
    }

    companion object {
        var inbox: List<RawSms> = emptyList()
    }

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(InboxProvider::class.java, "sms")
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
    }

    @Test
    fun `switching bank messages off stops ingest even while the permission stands`() = runBlocking {
        inbox = listOf(RawSms("0999 992 0000", "بانک سامان\nخرید مبلغ 1,250,000 ریال\nمانده 8,000,000 ریال", now + 1))
        val db = DurableDb.builder(app, "inbox-switch.db").build()
        try {
            db.meta().put(DurableMeta(SOURCE_SCANNED_TO, now.toString()))
            Store(app).smsEnabled = false
            assertEquals(0, ingestBankSms(app, db, emptyMap(), now + 2))
            assertEquals(0, db.smsSource().count())
            Store(app).smsEnabled = true
            assertEquals(1, ingestBankSms(app, db, emptyMap(), now + 2))
        } finally {
            db.close()
        }
    }
}
