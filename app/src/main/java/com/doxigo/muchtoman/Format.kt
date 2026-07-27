package com.doxigo.muchtoman

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.truncate

private val FA: Locale = Locale.forLanguageTag("fa-IR")

/** Grouped Persian digits: 118500 -> "۱۱۸٬۵۰۰" */
fun faNumber(value: Double): String =
    NumberFormat.getIntegerInstance(FA).format(value.roundToLong())

/**
 * Persian digits with decimals kept: 0.0345 -> "۰٫۰۳۴۵". With [pad] the decimals are held at
 * exactly [decimals] places, zeros and all, so a column of figures keeps one shape.
 */
fun faDecimal(value: Double, decimals: Int, pad: Boolean = false): String {
    val f = NumberFormat.getNumberInstance(FA)
    f.maximumFractionDigits = decimals
    f.minimumFractionDigits = if (pad) decimals else 0
    return f.format(value)
}

/**
 * Toman amounts get long fast (3000 USD is a nine-digit number of Toman), and a wall of
 * digits is exactly what an older reader can't scan. Iranians say the magnitude out loud,
 * so we do too: "۳۰۰ میلیون" instead of "۳۰۰٬۰۰۰٬۰۰۰".
 */
fun faCompact(toman: Double, dec: Int = 1, pad: Boolean = false): String {
    val n = abs(toman)
    val (unit, div) = when {
        n >= 1_000_000_000_000.0 -> "همت" to 1_000_000_000_000.0   // هزار میلیارد
        n >= 1_000_000_000.0 -> "میلیارد" to 1_000_000_000.0
        n >= 1_000_000.0 -> "میلیون" to 1_000_000.0
        n >= 1_000.0 -> "هزار" to 1_000.0
        else -> return faNumber(toman)
    }
    // Truncated, never rounded. Rounding is what made 10.8 million read as "۱۱ میلیون", and
    // rounding to one decimal still turns 2.95 into "۳". For money there is only one safe
    // direction: never show more than she has. The exact figure sits alongside.
    //
    // Two forms, and the difference is deliberate. Prose (dec = 1, unpadded) drops a trailing
    // zero, because "۱۱ میلیون" is how the amount is actually said mid-sentence. The figures
    // on the list and the hero use dec = 3 padded: a holding is usually a whole number times a
    // round rate, so without padding they collapse to "۸۵۰٫۸" and the precision looks like it
    // comes and goes. Fixed width means every figure on screen has the same shape.
    var f = 1.0
    repeat(dec) { f *= 10 }
    return "${faDecimal(truncate(toman / div * f) / f, dec, pad)} $unit"
}

private val ONES = arrayOf("", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه")
private val TEENS = arrayOf(
    "ده", "یازده", "دوازده", "سیزده", "چهارده",
    "پانزده", "شانزده", "هفده", "هجده", "نوزده",
)
private val TENS = arrayOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
private val HUNDREDS =
    arrayOf("", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد")
private val SCALES = arrayOf("", "هزار", "میلیون", "میلیارد", "هزار میلیارد")

/** 1..999 in words. */
private fun tripleToWords(n: Int): String {
    val parts = mutableListOf<String>()
    val h = n / 100
    val r = n % 100
    if (h > 0) parts += HUNDREDS[h]
    when {
        r in 10..19 -> parts += TEENS[r - 10]
        else -> {
            if (r / 10 > 0) parts += TENS[r / 10]
            if (r % 10 > 0) parts += ONES[r % 10]
        }
    }
    return parts.joinToString(" و ")
}

/**
 * The amount spelled out: 10_800_000 -> "ده میلیون و هشتصد هزار". Digits are quick to scan
 * but easy to misread by a factor of ten; the words are the check on that, which is the whole
 * reason they are here. Exact, never rounded.
 */
fun faWords(value: Long): String {
    if (value == 0L) return "صفر"
    if (value < 0) return "منفی ${faWords(-value)}"

    val groups = mutableListOf<Int>()      // least significant first
    var v = value
    while (v > 0) {
        groups += (v % 1000).toInt()
        v /= 1000
    }
    if (groups.size > SCALES.size) return "" // beyond هزار میلیارد; caller shows digits only

    val parts = mutableListOf<String>()
    for (i in groups.indices.reversed()) {
        val g = groups[i]
        if (g == 0) continue
        // "هزار" alone reads naturally; "یک هزار" does not. Every larger scale keeps its یک.
        val words = if (g == 1 && i == 1) "" else tripleToWords(g)
        parts += listOf(words, SCALES[i]).filter { it.isNotBlank() }.joinToString(" ")
    }
    return parts.joinToString(" و ")
}

/** Words for a Toman figure, or null when spelling it out would not help. */
fun faWordsToman(value: Double): String? {
    if (value <= 0 || value >= 1e15) return null
    val words = faWords(value.roundToLong())
    return if (words.isBlank()) null else "$words تومان"
}

/**
 * Reads a number the way a Persian keyboard actually produces it: Persian (۰-۹) or
 * Arabic-Indic (٠-٩) digits, Persian decimal separator, thousands separators ignored.
 * Returns null on anything it doesn't understand rather than guessing — this is the
 * money path, a silent misparse would show her the wrong total.
 */
fun parseAmount(input: String): Double? {
    val sb = StringBuilder()
    for (c in input) {
        when {
            c in '۰'..'۹' -> sb.append('0' + (c - '۰'))
            c in '٠'..'٩' -> sb.append('0' + (c - '٠'))
            c.isDigit() -> sb.append(c)
            c == '.' || c == '٫' -> sb.append('.')
            // thousands separators and stray spaces are simply ignored
            c == ',' || c == '،' || c == '٬' || c == ' ' || c == '‏' -> Unit
            else -> return null
        }
    }
    val s = sb.toString()
    if (s.isEmpty() || s == ".") return null
    return s.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }
}

/**
 * How one unit is worth, sized to the number: a dollar reads better in full ("۱۸۷٬۰۰۰"),
 * a gold coin does not ("۱۸۰ میلیون" beats nine digits).
 */
fun faRate(rate: Double): String =
    if (rate >= 1_000_000) faCompact(rate) else faNumber(rate)

/**
 * Wraps a run in Unicode isolates. A latin ticker dropped into Persian text drags the
 * numbers around it out of order — "۴۰ SOL · نرخ ۱۴ میلیون" renders as "SOL ۴۰ · نرخ..."
 * without this. FSI/PDI tells the bidi algorithm to treat the run as one opaque unit.
 */
fun bidi(s: String): String = "⁨$s⁩"

/**
 * Plain ASCII digits for the *raw* text-field state — Locale.US on purpose, so the stored
 * string always round-trips through [parseAmount] no matter what the device locale is.
 * Grouping and Persian digits are applied on top of this purely for display.
 */
fun trimNumber(value: Double, dec: Int): String {
    val s = String.format(Locale.US, "%.${dec}f", value)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

/**
 * Digits grouped for reading while she types, so "85000000" is never a column of zeroes she
 * has to count. Carries the index maps the text field needs to keep the caret in the right
 * place across the separators we inject.
 */
class GroupedDigits(val text: String, val origToDisp: IntArray, val dispToOrig: IntArray)

private fun persianDigit(c: Char): Char? = when (c) {
    in '0'..'9' -> '۰' + (c - '0')
    in '۰'..'۹' -> c
    in '٠'..'٩' -> '۰' + (c - '٠')
    else -> null
}

fun groupDigits(raw: String): GroupedDigits {
    val isSep = { c: Char -> c == '.' || c == '٫' }
    // Only the integer part gets separators; everything after the decimal point is left alone.
    val decimalAt = raw.indexOfFirst(isSep).let { if (it < 0) raw.length else it }
    val intDigits = (0 until decimalAt).count { persianDigit(raw[it]) != null }

    val out = StringBuilder()
    val owner = ArrayList<Int>()          // output index -> original index
    val origToDisp = IntArray(raw.length + 1)

    var seen = 0
    for (i in raw.indices) {
        origToDisp[i] = out.length
        val c = raw[i]
        val digit = persianDigit(c)

        when {
            digit != null -> {
                out.append(digit); owner.add(i)
                if (i < decimalAt) {
                    seen++
                    val remaining = intDigits - seen
                    // Group from the right, never trailing.
                    if (remaining > 0 && remaining % 3 == 0) {
                        out.append('٬'); owner.add(i + 1)
                    }
                }
            }
            isSep(c) -> { out.append('٫'); owner.add(i) }
            else -> { out.append(c); owner.add(i) }
        }
    }
    origToDisp[raw.length] = out.length

    val dispToOrig = IntArray(out.length + 1)
    for (j in 0 until out.length) dispToOrig[j] = owner[j]
    dispToOrig[out.length] = raw.length

    return GroupedDigits(out.toString(), origToDisp, dispToOrig)
}

/** "۵ دقیقه پیش" — vague on purpose; the exact second never matters here. */
fun faAgo(epochMillis: Long, now: Long): String {
    if (epochMillis <= 0) return "بدون به‌روزرسانی"
    val mins = ((now - epochMillis) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "همین الان"
        mins < 60 -> "${faNumber(mins.toDouble())} دقیقه پیش"
        mins < 60 * 24 -> "${faNumber((mins / 60).toDouble())} ساعت پیش"
        else -> "${faNumber((mins / (60 * 24)).toDouble())} روز پیش"
    }
}
