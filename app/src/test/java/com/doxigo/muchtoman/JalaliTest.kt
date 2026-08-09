package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The app itself cannot use `java.time` — minSdk 24, no desugaring — but these tests run on a
 * JVM that has it, so the hand-rolled arithmetic gets checked against a real calendar rather
 * than against itself.
 */
class JalaliTest {

    @Test
    fun `the date printed in her own saman message converts both ways`() {
        // 1405/5/1 is the date Saman printed on the deposit in sms/saman.json, and that message
        // arrived on 2026-07-23. One real anchor from the corpus beats any number of invented ones.
        val day = LocalDate.of(2026, 7, 23).toEpochDay()
        assertEquals(JalaliDate(1405, 5, 1), jalaliOf(day))
        assertEquals(day, jalaliDay(1405, 5, 1))
    }

    @Test
    fun `nowruz lands where the calendar says it does`() {
        // Round-tripping cannot catch an algorithm that is consistently wrong; only real dates
        // can. 1 فروردین falls on the March equinox, which is the 20th or the 21st.
        val nowruz = mapOf(
            1399 to LocalDate.of(2020, 3, 20),
            1400 to LocalDate.of(2021, 3, 21),
            1401 to LocalDate.of(2022, 3, 21),
            1402 to LocalDate.of(2023, 3, 21),
            1403 to LocalDate.of(2024, 3, 20),
            1404 to LocalDate.of(2025, 3, 21),
            1405 to LocalDate.of(2026, 3, 21),
            1406 to LocalDate.of(2027, 3, 21),
        )
        for ((jy, gregorian) in nowruz) {
            assertEquals("1 فروردین $jy", gregorian.toEpochDay(), jalaliDay(jy, 1, 1))
        }
    }

    @Test
    fun `every day of fifty years converts to a real date and back`() {
        val from = LocalDate.of(1990, 1, 1).toEpochDay()
        val until = LocalDate.of(2040, 1, 1).toEpochDay()
        var previous: JalaliDate? = null
        for (day in from..until) {
            val j = jalaliOf(day)
            assertEquals("round trip at $j", day, jalaliDay(j.year, j.month, j.day))
            assertTrue("month out of range at $j", j.month in 1..12)
            assertTrue("day out of range at $j", j.day in 1..jalaliMonthLength(j.year, j.month))
            // Consecutive days either advance the day by one or roll over to the 1st.
            previous?.let { p ->
                val rolled = j.day == 1 && (j.month == p.month + 1 || (j.month == 1 && p.month == 12))
                assertTrue("$p did not lead to $j", j.day == p.day + 1 || rolled)
            }
            previous = j
        }
    }

    @Test
    fun `a jalali year is 365 days, or 366 when اسفند runs to thirty`() {
        for (jy in 1395..1420) {
            val length = jalaliDay(jy + 1, 1, 1) - jalaliDay(jy, 1, 1)
            val esfand = jalaliMonthLength(jy, 12).toLong()
            assertEquals("year $jy", 6 * 31 + 5 * 30 + esfand, length)
            assertTrue("year $jy ran $length days", length == 365L || length == 366L)
        }
    }

    @Test
    fun `a month starts on its first and thirteen months back is a real month start`() {
        val day = LocalDate.of(2026, 7, 23).toEpochDay()
        assertEquals(JalaliDate(1405, 5, 1), jalaliOf(jalaliMonthStart(day)))
        // The backfill horizon. Thirteen months before مرداد ۱۴۰۵ is تیر ۱۴۰۴.
        val horizon = jalaliMonthsBack(day, 13)
        assertEquals(JalaliDate(1404, 4, 1), jalaliOf(horizon))
        assertTrue("the horizon must be in the past", horizon < day)
        // …and it stays a real month start whatever day of the month you ask from.
        for (offset in 0L..400L) {
            val back = jalaliMonthsBack(day - offset, 13)
            assertEquals(1, jalaliOf(back).day)
        }
    }

    @Test
    fun `the week starts on saturday`() {
        val from = LocalDate.of(2020, 1, 1).toEpochDay()
        for (day in from..(from + 400)) {
            val start = weekStart(day)
            assertEquals(DayOfWeek.SATURDAY, LocalDate.ofEpochDay(start).dayOfWeek)
            assertTrue("week start must not be in the future", start <= day)
            assertTrue("week start must be within six days", day - start <= 6)
        }
    }

    @Test
    fun `a purchase just after midnight in tehran belongs to that day, not the one before`() {
        // The bug this constant exists to prevent. 00:30 Tehran on 1405/05/01 is 21:00 UTC on
        // the previous Gregorian day, so a UTC epoch day files it against the month before
        // whenever it lands on the 1st.
        val firstOfMordad = jalaliDay(1405, 5, 1)
        val justAfterMidnight = tehranDayStart(firstOfMordad) + 30 * 60 * 1000L
        assertEquals(firstOfMordad, tehranDay(justAfterMidnight))
        // The UTC reckoning the wealth history uses (Data.kt:215, `now / DAY_MS`) gets it
        // wrong, which is precisely why the ledger does not share it.
        assertEquals(firstOfMordad - 1, justAfterMidnight / DAY_MS)
        // And the last moment of the day is still that day.
        assertEquals(firstOfMordad, tehranDay(tehranDayStart(firstOfMordad + 1) - 1))
    }

    @Test
    fun `the clock on a transaction reads the tehran wall time`() {
        // Same instant as the test above: 00:30 Tehran is 21:00 UTC the day before, which is what
        // an hour taken from the raw epoch would print. Zero-padded so a column of them lines up.
        val firstOfMordad = jalaliDay(1405, 5, 1)
        assertEquals("۰۰:۰۰", faClock(tehranDayStart(firstOfMordad)))
        assertEquals("۰۰:۳۰", faClock(tehranDayStart(firstOfMordad) + 30 * 60 * 1000L))
        assertEquals("۱۴:۰۳", faClock(tehranDayStart(firstOfMordad) + (14 * 60 + 3) * 60_000L))
        assertEquals("۲۳:۵۹", faClock(tehranDayStart(firstOfMordad + 1) - 1))
    }

    @Test
    fun `a moment states its minute only when the record knows one`() {
        // A row written from a date alone lands on Tehran midnight exactly, and «۰۰:۰۰» there
        // would be a minute nobody recorded. Anything else in the day keeps its clock.
        val firstOfMordad = jalaliDay(1405, 5, 1)
        val midnight = tehranDayStart(firstOfMordad)
        // The clock is spelled through bidi() rather than written out, so this file never has to
        // carry the isolate characters the lint check exists to catch.
        assertEquals("۱ مرداد ۱۴۰۵", faMoment(midnight, firstOfMordad))
        assertEquals("۱ مرداد ۱۴۰۵، ${bidi("۰۰:۰۰")}", faMoment(midnight + 1, firstOfMordad))
        assertEquals(
            "۱ مرداد ۱۴۰۵، ${bidi("۱۴:۰۳")}",
            faMoment(midnight + (14 * 60 + 3) * 60_000L, firstOfMordad),
        )
    }

    /**
     * The window ends every budget is measured against.
     *
     * `BudgetTest` checks what a budget makes of these; this checks the arithmetic itself against a
     * real calendar, which is the only thing that can catch a boundary that is consistently one day
     * out — a month end one day early moves a transaction between two budgets and makes both wrong.
     */
    @Test
    fun `every window end is the first day after its own window`() {
        for (jy in 1400..1410) {
            for (jm in 1..12) {
                val anyDay = jalaliDay(jy, jm, 3)
                // A month's end is the first of the next month, and اسفند's is 1 فروردین.
                val monthEnd = jalaliMonthEnd(anyDay)
                assertEquals(
                    "end of $jy/$jm",
                    if (jm == 12) JalaliDate(jy + 1, 1, 1) else JalaliDate(jy, jm + 1, 1),
                    jalaliOf(monthEnd),
                )
                assertEquals("$jy/$jm length", jalaliMonthLength(jy, jm), (monthEnd - jalaliMonthStart(anyDay)).toInt())

                // A quarter is three whole months, and no quarter shares a day with another.
                val q = jalaliQuarter(jm)
                assertEquals("quarter of $jy/$jm", (jm - 1) / 3 + 1, q)
                assertEquals(jalaliDay(jy, (q - 1) * 3 + 1, 1), jalaliQuarterStart(anyDay))
                assertEquals(
                    "quarter end of $jy/$jm",
                    if (q == 4) jalaliDay(jy + 1, 1, 1) else jalaliDay(jy, q * 3 + 1, 1),
                    jalaliQuarterEnd(anyDay),
                )
            }
        }
    }

    @Test
    fun `a week ends on the Saturday after the Saturday it began on`() {
        for (day in 0L..400L) {
            assertEquals(7L, weekEnd(day) - weekStart(day))
            assertEquals(DayOfWeek.SATURDAY, LocalDate.ofEpochDay(weekEnd(day)).dayOfWeek)
            assertTrue(day < weekEnd(day))
        }
    }

    @Test
    fun `a deadline months ahead rolls the year and lands on a real last day`() {
        // اسفند plus three months is the end of خرداد in the next year, not month 15 of this one.
        val esfand = jalaliDay(1405, 12, 10)
        assertEquals(JalaliDate(1406, 3, 31), jalaliOf(jalaliMonthsAheadEnd(esfand, 3)))
        assertEquals(JalaliDate(1406, 12, 29), jalaliOf(jalaliMonthsAheadEnd(esfand, 12)))
        // Whatever the horizon, the day after it is always the first of a month.
        for (months in 1..24) {
            for (jm in 1..12) {
                val end = jalaliMonthsAheadEnd(jalaliDay(1404, jm, 17), months)
                assertEquals("$jm +$months", 1, jalaliOf(end + 1).day)
                assertEquals("$jm +$months", jalaliMonthEnd(end), end + 1)
            }
        }
    }

    @Test
    fun `days before the epoch floor rather than truncate toward zero`() {
        // Integer division truncates toward zero, which would put every instant in the twelve
        // hours before 1970 on the wrong day. Nothing in the ledger reaches back that far, but
        // the arithmetic is shared and a flooring bug here is invisible until it is not.
        assertEquals(-1L, tehranDay(tehranDayStart(0) - 1))
        assertEquals(0L, tehranDay(tehranDayStart(0)))
        for (day in -800L..800L) {
            assertEquals(day, tehranDay(tehranDayStart(day)))
            assertEquals(day, tehranDay(tehranDayStart(day + 1) - 1))
        }
    }
}
