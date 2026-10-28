package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the accounts sheet, a bank's page and the bank row on دارایی say about an account: how fresh
 * it is, when it has gone quiet, and what the switch takes away. pwa/test/derived.test.ts holds the
 * PWA's copies to the same cases.
 */
class BankCaptionTest {

    private val now = 1_800_000_000_000L
    private fun daysAgo(days: Int) = now - days * DAY_MS - 60_000L
    private val read = BankAccount(bank = "MELLAT", balance = 1_000.0, updatedAt = daysAgo(2), anchored = true, sender = "+98700")
    private val manual = read.copy(bank = "SAMAN", sender = "", manual = true)

    @Test
    fun `a bank read from its messages goes quiet at the quiet note's five days`() {
        assertNull(bankStaleDays(read.copy(updatedAt = daysAgo(QUIET_AFTER_DAYS - 1)), off = false, now))
        assertEquals(5, bankStaleDays(read.copy(updatedAt = daysAgo(5)), off = false, now))
        assertEquals(listOf("۸ روزه پیامی ازش ندیدیم"), bankCaptionFa(read.copy(updatedAt = daysAgo(8)), off = false, now))
        assertEquals(listOf("۲ روز پیش"), bankCaptionFa(read, off = false, now))
    }

    @Test
    fun `only a counted bank read from messages is ever called quiet`() {
        val old = daysAgo(40)
        // Switched off or never anchored: out of her total, so its age is only an age.
        assertNull(bankStaleDays(read.copy(updatedAt = old), off = true, now))
        assertEquals(listOf("۴۰ روز پیش", "خاموش"), bankCaptionFa(read.copy(updatedAt = old), off = true, now))
        assertEquals(
            listOf("۴۰ روز پیش", "توی جمع حساب نشده"),
            bankCaptionFa(read.copy(updatedAt = old, anchored = false), off = false, now),
        )
        // Kept by hand: nothing reads it, so it is never told no message came — it says who keeps it.
        assertNull(bankStaleDays(manual.copy(updatedAt = old), off = false, now))
        assertEquals(listOf("۴۰ روز پیش", "دستی"), bankCaptionFa(manual.copy(updatedAt = old), off = false, now))
        // Never updated has no age to count from.
        assertNull(bankStaleDays(read.copy(updatedAt = 0L), off = false, now))
    }

    @Test
    fun `the caption names which account and a figure to check`() {
        assertEquals(
            listOf("حساب ۱۲۳۴", "۶ روزه پیامی ازش ندیدیم", "نیاز به بررسی"),
            bankCaptionFa(read.copy(number = "1234", updatedAt = daysAgo(6), inferred = true), off = false, now),
        )
    }

    @Test
    fun `the row on دارایی counts the quiet ones after the guesses and before the switched off`() {
        val quiet = read.copy(bank = "SAMAN", updatedAt = daysAgo(9))
        assertEquals("۲ حساب", bankCountNoteFa(listOf(read, manual.copy(updatedAt = daysAgo(30))), emptySet(), now))
        assertEquals("۲ حساب  •  ۱ قدیمی", bankCountNoteFa(listOf(read, quiet), emptySet(), now))
        assertEquals("۲ حساب  •  نیاز به بررسی", bankCountNoteFa(listOf(read.copy(inferred = true), quiet), emptySet(), now))
        // A switched-off bank is out of the total, so its silence is not counted against it.
        assertEquals("۱ حساب  •  ۱ خاموش", bankCountNoteFa(listOf(read, quiet), setOf("SAMAN"), now))
    }

    @Test
    fun `the switch says what it takes away`() {
        assertEquals("خاموشش کنی، نه موجودیش توی جمع میاد نه تراکنش‌هاش توی دفتر.", bankSwitchFa(balanceOnly = false))
        assertEquals("خاموشش کنی، موجودیش توی جمع نمیاد.", bankSwitchFa(balanceOnly = true))
    }
}
