package com.doxigo.muchtoman

import org.junit.Assert.*
import org.junit.Test

class ReviewRegressionTest {
    private val now = 100 * DAY_MS
    private val wallet = WalletLink("bitcoin", "بیت کوین", "address", updatedAt = now)

    @Test fun `each edition selects its own trusted APK or release page`() {
        val origin = "https://rates.muchtoman.com"
        val page = "https://github.com/doxigo/muchToman/releases/tag/v2.0"
        val release = Release("2.0", page, "$origin/download", apkLite = "$origin/download/lite")
        val cleaned = sanitizeRates(Rates(now, mapOf("usd" to 1.0), latest = release), "$origin/rates", now).latest!!
        assertEquals("$origin/download", cleaned.downloadUrlFor(false))
        assertEquals("$origin/download/lite", cleaned.downloadUrlFor(true))
        assertEquals(page, cleaned.copy(apkLite = "").downloadUrlFor(true))
        val wrong = sanitizeRates(Rates(now, latest = release.copy(apkLite = "$origin/download")), "$origin/rates", now).latest!!
        assertEquals(page, wrong.downloadUrlFor(true))
    }

    @Test fun `a fresh price does not make an old wallet quantity safe for history`() {
        val stale = Holding("btc", 2.0, wallet = wallet.copy(updatedAt = now - WALLET_SNAPSHOT_MAX_AGE_MS - 1))
        val prices = mapOf("btc" to 100.0)
        assertNull(snapshotHistory(emptyMap(), listOf(stale), prices, now, now))
        val refreshed = refreshedSnapshotHoldings(listOf(stale), mapOf(stale.key to (stale to WalletBalance(3.0, now))))
        assertEquals(300.0, snapshotHistory(emptyMap(), refreshed, prices, now, now)!![100L]!!, 0.0)
    }

    @Test fun `a wallet fetch cannot overwrite an intervening holding edit or new link`() {
        val original = Holding("btc", 2.0, wallet = wallet)
        val result = mapOf(original.key to (original to WalletBalance(3.0, now + 1)))
        val edited = original.copy(amount = 7.0)
        assertEquals(listOf(edited), refreshedSnapshotHoldings(listOf(edited), result))
        val relinked = original.copy(wallet = wallet.copy(address = "other"))
        assertEquals(listOf(relinked), refreshedSnapshotHoldings(listOf(relinked), result))
        assertTrue(refreshedSnapshotHoldings(emptyList(), result).isEmpty())
    }

    @Test fun `parser refresh recomputes deltas while retaining legacy anchors and absent banks`() {
        val anchored = BankAccount("SAMAN", balance = 100.0, updatedAt = 20L, anchored = true)
        val unanchored = BankAccount("MELLAT", balance = 999.0, updatedAt = 20L)
        val absent = BankAccount("PASARGAD", balance = 42.0, updatedAt = 20L, anchored = true)
        fun sms(bank: Bank, at: Long, delta: Double?, balance: Double? = null) =
            BankSms(bank, "sender", "", delta, balance, at, false)
        val refreshed = rebuildBankAccounts(listOf(anchored, unanchored, absent), listOf(
            sms(Bank.SAMAN, 19L, 5.0), sms(Bank.SAMAN, 20L, 5.0),
            sms(Bank.SAMAN, 21L, 7.0), sms(Bank.MELLAT, 10L, 3.0),
            sms(Bank.MELLAT, 20L, 4.0),
        )).associateBy { it.bank }
        assertEquals(107.0, refreshed.getValue("SAMAN").balance, 0.0)
        assertEquals(7.0, refreshed.getValue("MELLAT").balance, 0.0)
        assertEquals(absent, refreshed.getValue("PASARGAD"))
        val corrected = rebuildBankAccounts(listOf(anchored), listOf(sms(Bank.SAMAN, 20L, null, 80.0)))
        assertEquals(80.0, corrected.single().balance, 0.0)
    }

    @Test fun `backup reminders are opt in and reset after a successful export`() {
        assertFalse(backupReminderDue(false, 0L, now))
        assertTrue(backupReminderDue(true, 0L, now))
        assertFalse(backupReminderDue(true, now, now))
        assertFalse(backupReminderDue(true, now, now + 30 * DAY_MS - 1))
        assertTrue(backupReminderDue(true, now, now + 30 * DAY_MS))
        assertFalse(backupReminderDue(true, now + DAY_MS, now))
    }

    @Test fun `a backup holding swept codes is said, and asks for its replacement at once`() {
        val stored = now - 10 * DAY_MS
        // Nothing swept, nothing to say.
        assertFalse(backupHoldsSweptCodes(null, now, restoredNow = true))
        // A file made after the oldest code was stored holds it; one made before does not, and a
        // phone that never made one has nothing to warn about.
        assertTrue(backupHoldsSweptCodes(stored, stored + DAY_MS, restoredNow = false))
        assertFalse(backupHoldsSweptCodes(stored, stored - DAY_MS, restoredNow = false))
        assertFalse(backupHoldsSweptCodes(stored, 0L, restoredNow = false))
        // The file this launch was restored from held whatever the sweep then found in it.
        assertTrue(backupHoldsSweptCodes(stored, 0L, restoredNow = true))
        // Due the same day, rather than thirty days on — but only where she asked for reminders.
        assertTrue(backupReminderDue(true, now, now, holdsCodes = true))
        assertFalse(backupReminderDue(false, now, now, holdsCodes = true))
    }
}
