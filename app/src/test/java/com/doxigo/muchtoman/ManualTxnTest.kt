package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }

    @Test
    fun `nonsense is refused rather than guessed at`() {
        // A lone hour is not accepted: guessing «:۰۰» silently is how a transaction lands at
        // the top of the day's band instead of where it happened.
        assertNull(parseFaClock("۱۴"))
        assertNull(parseFaClock("25:00"))
        assertNull(parseFaClock("14:60"))
        assertNull(parseFaClock(""))
    }

    @Test
    fun `midnight itself is a real time`() {
        assertEquals(0L, parseFaClock("0:00"))
        assertEquals((23 * 60L + 59) * 60_000L, parseFaClock("۲۳:۵۹"))
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
