package com.doxigo.muchtoman

/**
 * Jalali dates and Tehran days.
 *
 * No `java.time` anywhere: minSdk is 24 and core-library desugaring is not enabled, so this is
 * `Long` arithmetic and nothing else. `Math.floorDiv`/`floorMod` are avoided as well — the long
 * overloads are not available across the whole supported range — so the one flooring division
 * this needs is spelled out below rather than borrowed.
 *
 * The conversion is the standard Borkowski/jalaali algorithm with its breaks table, not a
 * 33-year approximation. A month boundary that is one day out is not a display bug: it moves a
 * transaction between two monthly reports, and every figure in both is then wrong by that
 * transaction.
 */

/**
 * Tehran is UTC+03:30, and has been fixed there since Iran abolished daylight saving in 2022.
 *
 * ponytail: a constant, because Iran has no DST. One constant becomes a function if that changes.
 */
const val TEHRAN_OFFSET_MS = 12_600_000L

/** Unix epoch day 0 — 1970-01-01 — as a Julian day number. */
private const val JDN_AT_EPOCH = 2_440_588L

/** Flooring division: -1/10 must be -1, not 0, or every instant before the epoch lands a day late. */
private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0) q - 1 else q
}

/**
 * The day a moment falls on **in Tehran**.
 *
 * Deliberately not the UTC epoch day that [recordDay] uses for the wealth history. A purchase at
 * 01:00 Tehran belongs to that day, and a ledger that files it under the previous one reports
 * the first three and a half hours of every month against the month before. The existing history
 * stays on UTC days on purpose — recomputing it would shift the chart under her for no gain.
 */
fun tehranDay(epochMillis: Long): Long = floorDiv(epochMillis + TEHRAN_OFFSET_MS, DAY_MS)

/** The first moment of a Tehran day, back in epoch milliseconds. */
fun tehranDayStart(day: Long): Long = day * DAY_MS - TEHRAN_OFFSET_MS

/**
 * The Saturday on or before [day] — the start of the Iranian week.
 *
 * Epoch day 0 was a Thursday, which puts Saturday at `day % 7 == 2`.
 */
fun weekStart(day: Long): Long = day - ((day - 2) % 7 + 7) % 7

/** A date in the Jalali calendar. [month] and [day] are 1-based, as they are when written down. */
data class JalaliDate(val year: Int, val month: Int, val day: Int) {
    override fun toString(): String = "%04d/%02d/%02d".format(year, month, day)
}

/** The Jalali date of a Tehran [day]. */
fun jalaliOf(day: Long): JalaliDate = jdnToJalali(day + JDN_AT_EPOCH)

/** The Tehran day a Jalali date falls on — the inverse of [jalaliOf]. */
fun jalaliDay(year: Int, month: Int, day: Int): Long =
    jalaliToJdn(year, month, day) - JDN_AT_EPOCH

/** The first day of the Jalali month containing [day]. */
fun jalaliMonthStart(day: Long): Long =
    jalaliOf(day).let { jalaliDay(it.year, it.month, 1) }

/**
 * The first day of the Jalali month [months] before the one containing [day].
 *
 * This is what bounds the backfill: thirteen months back is far enough for every
 * year-over-year comparison the reports make, plus a month of margin.
 */
fun jalaliMonthsBack(day: Long, months: Int): Long {
    val here = jalaliOf(day)
    val total = here.year * 12 + (here.month - 1) - months
    return jalaliDay(total / 12, total % 12 + 1, 1)
}

/** How many days the given Jalali month holds: 31, 30, or 29/30 for اسفند. */
fun jalaliMonthLength(year: Int, month: Int): Int = when {
    month <= 6 -> 31
    month <= 11 -> 30
    else -> if (jalCal(year).leap == 0) 30 else 29
}

// ─────────────────────────── the algorithm ───────────────────────────
// Ported from the reference jalaali implementation. Everything below works in Julian day
// numbers, which is the only representation in which both calendars are plain arithmetic.

private val BREAKS = intArrayOf(
    -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
    1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
)

private class JalCal(val leap: Int, val gy: Int, val march: Int)

private fun jalCal(jy: Int): JalCal {
    require(jy in -61..3177) { "jalali year out of range: $jy" }
    val gy = jy + 621
    var leapJ = -14
    var jp = BREAKS[0]
    var jump = 0
    for (i in 1 until BREAKS.size) {
        val jm = BREAKS[i]
        jump = jm - jp
        if (jy < jm) break
        leapJ += (jump / 33) * 8 + (jump % 33) / 4
        jp = jm
    }
    var n = jy - jp
    leapJ += (n / 33) * 8 + (n % 33 + 3) / 4
    if (jump % 33 == 4 && jump - n == 4) leapJ += 1
    val leapG = gy / 4 - ((gy / 100 + 1) * 3) / 4 - 150
    val march = 20 + leapJ - leapG
    if (jump - n < 6) n = n - jump + ((jump + 4) / 33) * 33
    var leap = ((n + 1) % 33 - 1) % 4
    if (leap == -1) leap = 4
    return JalCal(leap, gy, march)
}

private fun gregorianToJdn(gy: Int, gm: Int, gd: Int): Long {
    val a = (gy + (gm - 8) / 6 + 100100).toLong()
    var d = (a * 1461) / 4 + (153 * ((gm + 9) % 12) + 2) / 5 + gd - 34840408
    d -= ((gy + 100100 + (gm - 8) / 6) / 100 * 3) / 4 - 752
    return d
}

private fun jdnToGregorianYear(jdn: Long): Int {
    var j = 4 * jdn + 139361631
    j += ((4 * jdn + 183187720) / 146097 * 3) / 4 * 4 - 3908
    val i = (j % 1461) / 4 * 5 + 308
    val gm = ((i / 153) % 12) + 1
    return (j / 1461 - 100100 + (8 - gm) / 6).toInt()
}

private fun jdnToJalali(jdn: Long): JalaliDate {
    val gy = jdnToGregorianYear(jdn)
    var jy = gy - 621
    val r = jalCal(jy)
    var k = jdn - gregorianToJdn(gy, 3, r.march)
    if (k >= 0) {
        if (k <= 185) return JalaliDate(jy, (1 + k / 31).toInt(), (k % 31 + 1).toInt())
        k -= 186
    } else {
        jy -= 1
        k += 179
        if (r.leap == 1) k += 1
    }
    return JalaliDate(jy, (7 + k / 30).toInt(), (k % 30 + 1).toInt())
}

private fun jalaliToJdn(jy: Int, jm: Int, jd: Int): Long {
    val r = jalCal(jy)
    return gregorianToJdn(r.gy, 3, r.march) + (jm - 1) * 31 - jm / 7 * (jm - 7) + jd - 1
}
