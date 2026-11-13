package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The two small readers behind a hand-entered transaction: the clock field, and the hero
 * corner's day-with-minute. Both are the difference between a row landing where it happened
 * and a row landing at midnight.
 */
class ManualTxnTest {

    @Test
    fun `a clock round-trips through its own print`() {
        val minute = (14 * 60L + 3) * 60_000L
        val at = tehranDayStart(20_000L) + minute
        assertEquals("۱۴:۰۳", faClock(at))
        assertEquals(minute, parseFaClock("۱۴:۰۳"))
        assertEquals(minute, parseFaClock("14:03"))
        // What the field itself holds: digits only, the colon drawn by the mask.
        assertEquals(minute, parseFaClock(clockDigits(faClock(at))))
        assertEquals("1403", clockDigits("۱۴:۰۳"))
    }

    @Test
    fun `nonsense is refused rather than guessed at`() {
        // A lone hour is not accepted: guessing «:۰۰» silently is how a transaction lands at
        // the top of the day's band instead of where it happened.
        assertNull(parseFaClock("۱۴"))
        // Three digits could be ۹:۳۰ or ۹۳:۰ half-typed; the field asks for all four.
        assertNull(parseFaClock("930"))
        assertNull(parseFaClock("25:00"))
        assertNull(parseFaClock("14:60"))
        assertNull(parseFaClock(""))
    }

    @Test
    fun `midnight itself is a real time`() {
        assertEquals(0L, parseFaClock("0000"))
        assertEquals((23 * 60L + 59) * 60_000L, parseFaClock("۲۳:۵۹"))
    }

    @Test
    fun `the very millisecond the sheet opened at is kept while the minute is left alone`() {
        // A figure typed for a bank at 14:03:45, then a row at the seeded «۱۴:۰۳»: stamped at the
        // minute's start, it would land before the figure and move nothing.
        val minute = (14 * 60L + 3) * 60_000L
        val openedAt = tehranDayStart(20_000L) + minute + 45_000L
        assertEquals(openedAt, stampedAt(20_000L, minute, openedAt))
        assertEquals(tehranDayStart(20_000L) + minute - 60_000L, stampedAt(20_000L, minute - 60_000L, openedAt))
        assertEquals(tehranDayStart(19_999L) + minute, stampedAt(19_999L, minute, openedAt))
    }

    @Test
    fun `a typed row moves the bank she keeps by hand, and nothing else`() {
        val at = 1_800_000_000_000L
        val mellat = BankAccount("MELLAT", balance = 10_000_000.0, updatedAt = at, anchored = true, manual = true)
        val saman = BankAccount("SAMAN", balance = 5_000_000.0, updatedAt = at, anchored = true)
        val typed = mapOf(
            "MELLAT" to BalanceAnchor("a", "MELLAT", at, 100_000_000L, "user", at, at),
            "SAMAN" to BalanceAnchor("b", "SAMAN", at, 50_000_000L, "user", at, at),
        )
        fun row(id: String, rial: Long, moment: Long, account: String?) = manualToRow(
            ManualTxn(id, moment, tehranDay(moment), rial, accountId = account, createdAt = moment, updatedAt = moment),
        )
        val rows = listOf(
            row("1", -4_500_000L, at + 1, "MELLAT"),
            // Before her figure, so already inside it.
            row("2", -9_000_000L, at - 1, "MELLAT"),
            // Cash, and a bank whose messages are read: its own message reports the money.
            row("3", -1_000_000L, at + 2, null),
            row("4", -1_000_000L, at + 3, "SAMAN"),
        )
        val next = typedOnto(listOf(mellat, saman), typed, rows)
        assertEquals(9_550_000.0, next[0].balance, 0.0)
        assertEquals(at, next[0].updatedAt)
        assertSame(saman, next[1])
        // Worked out, not stepped: the row deleted, the figure is hers again.
        assertEquals(10_000_000.0, typedOnto(next, typed, rows.drop(1))[0].balance, 0.0)
        // A figure that is not the one the account holds — its write failed — leaves it alone.
        assertSame(mellat, typedOnto(listOf(mellat), mapOf("MELLAT" to typed.getValue("MELLAT").copy(at = at - 5)), rows)[0])
    }

    @Test
    fun `the hero corner carries the minute only when one was recorded`() {
        val day = 20_000L
        val at = tehranDayStart(day) + (4 * 60L + 20) * 60_000L
        assertEquals("دیروز، ${bidi("۰۴:۲۰")}", faDayMoment(at, day, today = day + 1))
        // A row entered from a date alone sits on Tehran midnight exactly, and printing «۰۰:۰۰»
        // there would state a minute nobody recorded.
        assertEquals("دیروز", faDayMoment(tehranDayStart(day), day, today = day + 1))
    }
}
