package com.doxigo.muchtoman

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.truncate

private val FA: Locale = Locale.forLanguageTag("fa-IR")
private val FA_INTEGER = object : ThreadLocal<NumberFormat>() {
    override fun initialValue(): NumberFormat = NumberFormat.getIntegerInstance(FA)
}

/** Grouped Persian digits: 118500 -> "۱۱۸٬۵۰۰" */
fun faNumber(value: Double): String =
    FA_INTEGER.get()!!.format(value.roundToLong())

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
fun tomanOf(rial: Long): Double = rial / 10.0

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
    // comes and goes — except a figure that lands exactly on the unit, which drops its
    // decimals entirely: "۱۸۰٫۰۰۰ میلیون" is three zeros carrying nothing, and reads worse
    // than "۱۸۰ میلیون".
    var f = 1.0
    repeat(dec) { f *= 10 }
    val t = truncate(toman / div * f) / f
    return "${faDecimal(t, dec, pad && t % 1.0 != 0.0)} $unit"
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
 * How much of a thing she holds, sized for a list row. Every coin carries six decimals so the
 * edit field can round-trip a dust balance, but six decimals of ten thousand Tether is
 * precision nobody scans — and on a row it is what pushed the rate, the figure she opened the
 * app to check, off the end of the line. Below one unit every decimal still counts, so a
 * fraction of a Bitcoin keeps all of them; the exact figure is one tap away either way.
 *
 * Truncated, never rounded, for the same reason [faCompact] is: never show more than she has.
 */
fun faHeld(amount: Double, dec: Int): String {
    val d = when {
        amount >= 1_000 -> 2
        amount >= 1 -> 4
        else -> dec
    }.coerceAtMost(dec)
    if (d >= dec) return faDecimal(amount, dec)      // nothing to drop; leave it untouched
    // Settled at the asset's own precision before anything is cut. 10709.13 is not exactly
    // representable as a double — truncating the stored value directly takes off a cent that
    // she really has, which is the one direction this function must never round.
    return faDecimal(
        BigDecimal(amount).setScale(dec, RoundingMode.HALF_UP).setScale(d, RoundingMode.DOWN).toDouble(),
        d,
    )
}

/**
 * How one unit is worth, sized to the number: a dollar reads better in full ("۱۸۷٬۰۰۰"),
 * a gold coin does not ("۱۸۰ میلیون" beats nine digits).
 */
fun faRate(rate: Double): String =
    if (rate >= 1_000_000) faCompact(rate) else faNumber(rate)

/**
 * Wraps a run in Unicode isolates. A latin ticker dropped into Persian text drags the
 * numbers around it out of order — "۴۰ SOL • نرخ ۱۴ میلیون" renders as "SOL ۴۰ • نرخ..."
 * without this. FSI/PDI tells the bidi algorithm to treat the run as one opaque unit.
 */
// Lint flags the isolate characters as spoofing, which is what the check is for: bidi controls
// smuggled into a string change what a reader sees without changing what the code does. Here
// they are the payload rather than a hidden passenger — one FSI, one PDI, balanced, wrapping
// exactly the run named in the argument. Suppressed at the single line that is allowed to
// contain them, so the check keeps working everywhere else.
@Suppress("BidiSpoofing")
fun bidi(s: String): String = "⁨$s⁩"

/**
 * The same isolate, but forced left-to-right, for the digits of a signed amount.
 *
 * [bidi] resolves direction from the first strong character, so an isolate wrapped around
 * "+۱۶۵٫۱ میلیون" takes its direction from the «م» of the magnitude, turns the whole run
 * right-to-left, and throws the sign to the far side of the figure.
 */
// Same reasoning as above: LRI and PDI, balanced, wrapping exactly the argument.
@Suppress("BidiSpoofing")
fun ltrFigure(s: String): String = "⁦$s⁩"

/**
 * «−۴۰۰ میلیون»: signed, compact, and laid out the way Persian actually reads it.
 *
 * Two things have to be true at once, and only one arrangement gives both. The sign belongs on
 * the left of the digits, the way every bank writes it — and the magnitude and «تومان» after it
 * belong at the end of the phrase, which in a right-to-left line is the left end. So the digits
 * and their sign are isolated as one left-to-right island, and the magnitude word is left
 * outside it to flow on in the RTL line: «تومان میلیون −۴۰۰» on screen, «−۴۰۰ میلیون تومان»
 * read aloud. Isolating the pair together, or forcing the whole line left-to-right, each
 * satisfies one of the two and breaks the other.
 */
fun faSignedCompact(toman: Double, positive: Boolean): String =
    faSignedParts(toman, positive).let { (digits, magnitude) ->
        if (magnitude == null) digits else "$digits $magnitude"
    }

/**
 * The same figure, split where a display-sized one needs to set the two halves differently.
 *
 * At 50sp with a magnitude word the same size beside it, the sign sits between two equal runs
 * and stops reading as a sign at all — «۴۰۰ − میلیون» looks like a dash joining two words. The
 * split lets the caller step the magnitude down so the sign clearly belongs to the digits.
 * Returns the isolated signed digits, and the magnitude word, which is null under a thousand.
 */
fun faSignedParts(toman: Double, positive: Boolean): Pair<String, String?> {
    val parts = faCompact(abs(toman)).split(' ', limit = 2)
    return ltrFigure((if (positive) "+" else "−") + parts[0]) to parts.getOrNull(1)
}

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
    if (epochMillis <= 0) return "هنوز به‌روز نشده"
    val mins = ((now - epochMillis) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "همین الان"
        mins < 60 -> "${faNumber(mins.toDouble())} دقیقه پیش"
        mins < 60 * 24 -> "${faNumber((mins / 60).toDouble())} ساعت پیش"
        else -> "${faNumber((mins / (60 * 24)).toDouble())} روز پیش"
    }
}
