package com.doxigo.muchtoman

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** The synthetic holding every tracked bank balance adds up into. Its rate is 1, like Toman. */
const val BANK_ID = "bank_accounts"

/**
 * The banks we read, each identified by the number its messages arrive from.
 *
 * Identity used to be guessed from words in the body, which meant every advert and one-time
 * code a bank sends had to be told apart from a real transaction by wording alone, and any
 * bank not in the list arrived as an unnamed guess. A sender number is not a guess: a message
 * either comes from Saman's number or it does not, and if it does not we do not read it.
 *
 * Adding a bank is one line here. Write the number however it is easiest to read — only its
 * digits are compared, and only the last ten of them, so every form Android hands us for the
 * same sender matches: +989999987641, 989999987641, 09999987641, "0999 998 7641".
 *
 * The logo is the other half: every Iranian bank's mark is already in `res/drawable` as
 * `ic_bank_<name>`, and `BankLogo` matches on this enum exhaustively, so the compiler asks for
 * the one line that points a new entry at its file.
 */
enum class Bank(val fa: String, val numbers: List<String>) {
    // A bank sends from more than one number: a mobile line and a shortcode, and Refah uses two
    // shortcodes. A shortcode is shorter than ten digits and so simply matches itself, and no
    // ten-digit mobile ending in the same run can collide with it.
    BLU("بلو بانک", listOf("0999 998 7641", "90000258", "+9890000258", "98300087641")),
    SAMAN("بانک سامان", listOf("0999 992 0000", "+989820000", "9820000", "6219")),
    // Refah and Pasargad also send from lettered headers, not numbers at all. senderKey
    // keeps those as their own name, so listing one works exactly like listing a shortcode.
    // "RefahBank" run together is a real header too, seen on a user's phone — not a spacing
    // variant senderKey can fold, so it is listed on its own.
    REFAH("بانک رفاه", listOf("100031", "100032", "Refah Bank", "RefahBank")),
    PASARGAD("بانک پاسارگاد", listOf("B.Pasargad")),
    EGHTESAD_NOVIN("بانک اقتصاد نوین", listOf("ENBank", "+98500015", "+98200050")),
    KHAVARMIANEH(
        "بانک خاورمیانه",
        listOf("20004861", "+9820004861", "+989820004860"),
    ),
    // The shortcode is listed both bare and with the country code in front of it, for the same
    // reason Blu's is: +98 in front of an eight-digit shortcode makes ten digits, which is a
    // different key from the eight, and the carrier picks either form.
    SADERAT(
        "بانک صادرات",
        listOf("+98 9870 0719", "98700719", "+98 983 000 9419", "BankSaderat"),
    ),
    RESALAT("بانک رسالت", listOf("ResalatBank")),
    PARSIAN("بانک پارسیان", listOf("PARSIANBANK", "+98300054", "+98300055", "+9850001099")),
    // Saman, Mellat and Melli each also send from the four digits their own cards start with —
    // 6219, 6104, 6037. Four digits is shorter than any mobile line, so each matches itself.
    MELLAT("بانک ملت", listOf("Bank Mellat", "6104")),
    // Melli's other line is a ten-digit number out of the same 98300094… block Saderat's is in,
    // one digit apart from it — so it is listed whole, and senderKey folds the +98…/98…/0…
    // forms of it onto one key without either bank reaching the other's.
    MELLI("بانک ملی ایران", listOf("6037", "09830009417", "+98700717")),
    // Dey sends from a lettered header. Listed with and without the space for the reason
    // Refah's is: "Day Bank" and "DayBank" are one sender to a human and two keys to senderKey.
    DEY("بانک دی", listOf("Day Bank", "DayBank", "+982000266", "+982000766")),
    TEJARAT("بانک تجارت", listOf("TejaratBank")),
    SEPAH("بانک سپه", listOf("SEPAH BANK")),
    KESHAVARZI("بانک کشاورزی", listOf("KESHAVARZI")),
    POST_BANK("پست بانک", listOf("POSTBANK", "+9850004940")),
    MASKAN("بانک مسکن", listOf("Bank Maskan")),
    // Before ایران زمین on purpose: a Mehr Iran body contains «ایران» too, and [guessBank]
    // takes the first keyword that matches, so «قرض‌الحسنه» has to be asked before «ایران».
    MEHR_IRAN("بانک قرض‌الحسنه مهر ایران", listOf("B.QMEHRIRAN", "+989810008528")),

    // ── Known by name only ──────────────────────────────────────────────────────────────────
    //
    // No numbers, and that is deliberate rather than unfinished. Every number above was read off
    // a real phone; the web has only each bank's *service* shortcode — the number you text to ask
    // for a balance — which is not what its notifications arrive from. Pasargad settles it: its
    // published service line is 1000900 and the header its messages actually carry is
    // "B.Pasargad". Guessing here would not merely fail to match, it would be unsafe: ingest
    // keeps whatever a matched sender says about money, and [bodyToStore] can only refuse a code
    // that also names money — a purchase code — by its wording, so one worded some way it has
    // never seen would reach the table that the privacy note in [ingestBankSms] says holds none.
    //
    // Listed anyway, because a named bank is what [guessBank] needs: her own message says
    // «بانک سینا» in its body, the sheet offers to add the number it came from, and her tap is
    // what teaches this phone the real one. Nothing is read from these until she does that.
    AYANDEH("بانک آینده", emptyList()),
    SHAHR("بانک شهر", emptyList()),
    SINA("بانک سینا", emptyList()),
    GARDESHGARI("بانک گردشگری", emptyList()),
    SARMAYEH("بانک سرمایه", emptyList()),
    KARAFARIN("بانک کارآفرین", emptyList()),
    TOSEE_TAAVON("بانک توسعه تعاون", emptyList()),
    IRAN_ZAMIN("بانک ایران زمین", emptyList()),
    SANAT_MADAN("بانک صنعت و معدن", emptyList()),

    /**
     * Never produced by reading a message. It is only what a balance saved by an older build
     * falls back to, so a bank that has since left this list still renders and can be deleted
     * rather than crashing on a name the enum no longer has.
     */
    OTHER("بانک", emptyList()),
}

/** ASCII digits only, from any of the three digit sets a phone number can arrive in. */
private fun digitsOf(s: String) = buildString {
    for (c in s) when (c) {
        in '0'..'9' -> append(c)
        in '۰'..'۹' -> append('0' + (c - '۰'))
        in '٠'..'٩' -> append('0' + (c - '٠'))
        else -> Unit          // +, spaces, dashes and parentheses are formatting, not identity
    }
}

/**
 * A sender reduced to what actually identifies it: the last ten digits, which for an Iranian
 * mobile number is the national significant number. The same bank reaches the phone as
 * +98…, 98… or 0… depending on the network and the SIM, and comparing the whole string would
 * make those three different senders. A shortcode shorter than ten digits is simply itself —
 * and a sender with no digits at all ("SAMANBANK") is its own name, case ignored, because
 * banks really do send from lettered IDs and reducing those to digits reduced them to nothing.
 */
fun senderKey(sender: String): String {
    val trimmed = sender.trim()
    // A lettered header is its own whole name; reducing "Tel100031" to digits would let a
    // stranger wear Refah's shortcode. Runs of whitespace collapse to one space, because
    // "Refah Bank" and "Refah  Bank" are one sender however the carrier spaces it.
    if (trimmed.any { it.isLetter() }) return trimmed.replace(WHITESPACE, " ").lowercase()
    val digits = digitsOf(trimmed)
    // +98…, 98… and 0… in front of the same mobile line are one sender written three ways.
    // Nothing else is folded: a shortcode is exactly itself.
    return when {
        digits.length == 12 && digits.startsWith("98") -> digits.drop(2)
        digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
        else -> digits
    }
}

// Bank headers really do arrive with non-breaking spaces. Android does not support Java's
// inline (?U) flag, so include Unicode separator categories explicitly. merchantNorm persists
// this output as a rule needle, so changing the pattern requires a rule migration.
internal val WHITESPACE = Regex("[\\s\\p{Z}]+")

private val BANK_BY_NUMBER: Map<String, Bank> =
    Bank.entries.flatMap { bank -> bank.numbers.map { senderKey(it) to bank } }.toMap()

/**
 * Which bank sent this, or null for the entire rest of her inbox. One key, one lookup — an
 * unexpected shape becomes a suggestion card she confirms in one tap, not a guess.
 */
fun bankOf(sender: String, extra: Map<String, Bank> = emptyMap()): Bank? {
    val key = senderKey(sender)
    if (key.isEmpty()) return null
    return BANK_BY_NUMBER[key] ?: extra[key]
}

/**
 * One balance we are tracking, identified by bank and the masked account tail the message
 * carried.
 *
 * [inferred] means at least one message folded into it was read at low confidence — an
 * unrecognised bank, or an amount with no ریال/تومان on it. [anchored] means the balance was
 * once set by a مانده the bank itself stated; without that, adding up transactions only ever
 * yields the *change* since we started reading, not what is in the account, so an unanchored
 * figure is a guess however cleanly every message parsed. She can anchor it by hand.
 */
@Serializable
data class BankAccount(
    val bank: String,
    val mask: String = "",
    val balance: Double = 0.0,
    val updatedAt: Long = 0L,
    val inferred: Boolean = false,
    val anchored: Boolean = false,
    val sender: String = "",
) {
    /** The bank alone: see [applyBankSms] for why the printed identifier cannot key an account. */
    val key: String get() = bank
    val bankFa: String
        get() = runCatching { Bank.valueOf(bank) }.getOrDefault(Bank.OTHER).fa

    /** Whether the figure can be shown as fact rather than as something to check. */
    val trusted: Boolean get() = anchored && !inferred
}

/**
 * What one message turned out to say. [balance] is the bank's own مانده and always wins over
 * [delta]: a message we never saw, or one she deleted, then costs us nothing — the next
 * message puts the balance right again instead of leaving it permanently adrift.
 */
data class BankSms(
    val bank: Bank,
    val sender: String,
    val mask: String,
    val delta: Double?,
    val balance: Double?,
    val at: Long,
    val inferred: Boolean,
    // Everything below is new and every one of it is defaulted, so every existing construction
    // and every existing assertion kept compiling and passing untouched when it arrived. The
    // ledger reads these; the balance fold above does not read any of them.
    /** Magnitude, in whole Rial. Present even when the direction is not, unlike [delta]. */
    val amountRial: Long? = null,
    val balanceRial: Long? = null,
    val feeRial: Long? = null,
    val merchant: String = "",
    val refNo: String = "",
    /** The bank's own date, and the time beside it where it printed one. Verbatim and unparsed. */
    val printedAt: String = "",
    val channel: Channel = Channel.UNKNOWN,
    val instrument: Instrument = Instrument.UNKNOWN,
    val unitPrinted: PrintedUnit = PrintedUnit.NONE,
) {
    /** Nothing to fold in — no amount and no balance means the message was not a transaction. */
    val empty: Boolean get() = delta == null && balance == null
}

// Persian text arrives with either spelling of these letters depending on the keyboard and
// the bank's own system; comparing without normalising them misses half the matches.
private fun normalise(s: String) = s
    .replace('ي', 'ی')
    .replace('ك', 'ک')
    .replace("\u200c", "")   // ZWNJ
    .replace('\u00a0', ' ')  // non-breaking space
    .lowercase()

/**
 * Which way the money went. The last entry in each list is Blu's own wording — it writes
 * "به حساب شما نشست" and "از حساب شما پرید" and labels one of its transfers "انتقال پل",
 * so a list built only from the formal words reads its transfers as no direction at all.
 */
private val IN_WORDS = listOf("واریز", "بستانکار", "افزایش یافت", "دریافت وجه", "نشست")
private val OUT_WORDS = listOf(
    "برداشت", "بدهکار", "خرید", "پرداخت", "انتقال", "کاهش یافت", "کارمزد", "قبض", "پرید",
)
private val BALANCE_WORDS = listOf("مانده", "موجودی")
private val AMOUNT_WORDS = listOf("مبلغ", "مقدار")

/**
 * A رمز پویا asks her to approve a purchase; it does not report one. It names the مبلغ and
 * usually «خرید», from the same number the bank's transactions come from, so the sender gate
 * lets it through and it read as a spend. If she approves, the real debit arrives as its own
 * message, and if she walks away nothing moved at all.
 *
 * «رمز» as a word of its own, never the one inside «کارمزد» or «رمزارز». Glued to «پویا» or
 * «دوم» counts too, because [normalise] strips the ZWNJ that «رمز‌پویا» is often written with.
 */
private val OTP = Regex("(?<!\\p{L})رمز(?!\\p{L})|رمز ?(?:پویا|دوم)")

/**
 * The same code worded with «کد»: «کد تایید», «کد یکبار مصرف», «کد فعالسازی», «کد ورود», «کد پویا».
 *
 * A closed list, never «کد» alone, because a transaction is full of codes that are not one:
 * «کد پیگیری», «کد رهگیری», «کد پایانه». And unlike «رمز» it only counts in a message that states
 * no balance — see [isOneTimeCode] for why.
 */
private val OTP_CODE = Regex("(?<!\\p{L})کد ?(?:تایید|تأیید|تائید|یک ?بار|فعال ?سازی|ورود|پویا)")

/**
 * Whether a body is a one-time code — the one test for it. [ingestBankSms] refuses to store what
 * this matches and [parseBankSms] declines it, and sharing it is what makes the refusal free: a
 * body ingest drops is one the ledger would never have read.
 *
 * A «کد» wording is let through when the message states a مانده. The two mistakes are not the
 * same size: a debit that prints its bank's authorisation code as «کد تایید» and was dropped here
 * is gone for good, balance and all, while a code that states a balance and is kept costs one
 * spend she can hide — and the next مانده puts the account right regardless.
 */
internal fun isOneTimeCode(body: String): Boolean {
    val text = normalise(body)
    return OTP.containsMatchIn(text) || (OTP_CODE.containsMatchIn(text) && statedBalance(text) == null)
}

/**
 * What ingest keeps of a body from a bank we read, or null for none of it.
 *
 * Every rule holds the same line — nothing is refused that the ledger reads — and each is the
 * reason a code worded some way this file has never seen still stays out:
 * - A one-time code is refused, by [isOneTimeCode], the test [parseBankSms] declines on.
 * - A body that says nothing about money is refused, however it is worded: no word [parseBankSms]
 *   reads a balance, an amount or a direction by, no unit, no figure grouped the way banks print
 *   money. A login code, an activation code, a welcome, a security notice all land here. The
 *   parser cannot read money out of such a body, and no fix to it could without inventing some.
 * - A «کد» code set beside a stated مانده is kept for the money, and only the money: the code's
 *   own digits are blanked and everything else stays verbatim.
 *
 * Changing what this keeps changes what the ledger can ever read, so it bumps [PARSER_VERSION]
 * like any parser change — which is also what makes [sweepSources] apply it to what is stored.
 */
internal fun bodyToStore(body: String): String? {
    if (isOneTimeCode(body)) return null
    val text = normalise(body)
    if (!carriesMoney(text)) return null
    return blankCodes(body, text)
}

/** A figure grouped in threes, the dot included — wider than the grouping [SIGNED] asks for, on purpose. */
private val GROUPED_FIGURE = Regex("[0-9۰-۹٠-٩]{1,3}(?:[,،٬.٫][0-9۰-۹٠-٩]{3})+")

private val UNIT_WORDS = listOf("ریال", "ر.ی", "تومان", "تومن")

/**
 * Whether a body says anything money could be read from. Built from the parser's own word lists,
 * so a word the parser learns is one this keeps, and wider than the parser everywhere else.
 */
private fun carriesMoney(text: String): Boolean =
    (BALANCE_WORDS + AMOUNT_WORDS + IN_WORDS + OUT_WORDS + UNIT_WORDS).any { it in text } ||
        GROUPED_FIGURE.containsMatchIn(text)

/**
 * The code a «کد» wording introduces: four to ten digits a few characters after it on the same
 * line, glued to no separator — which keeps a grouped amount, a date, a clock time or an account
 * number from ever being taken for one. It never looks past a code already blanked, so blanking
 * twice changes nothing.
 */
private val CODE_AFTER = Regex("^[^0-9۰-۹٠-٩\\n•]{0,24}([0-9۰-۹٠-٩]{4,10})(?![0-9۰-۹٠-٩,،٬.٫/:\\-])")
private val DIGIT_RUN = Regex("[0-9۰-۹٠-٩]+")

/**
 * [body] with the code each «کد» wording in [text] introduces blanked, digit for digit.
 *
 * Found in the normalised text and blanked in the raw body by its place among the digit runs:
 * normalising never adds, drops or reorders a digit, so the n-th run is the same run in both —
 * and one whose digits disagree anyway is left alone rather than guessed at.
 */
private fun blankCodes(body: String, text: String): String {
    val runs = DIGIT_RUN.findAll(text).toList()
    val codes = OTP_CODE.findAll(text).mapNotNull { m ->
        val from = m.range.last + 1
        val code = CODE_AFTER.find(text.substring(from))?.groups?.get(1) ?: return@mapNotNull null
        runs.indexOfFirst { it.range.first == from + code.range.first }.takeIf { it >= 0 }
    }.toSet()
    if (codes.isEmpty()) return body
    var n = -1
    return DIGIT_RUN.replace(body) { run ->
        n++
        if (n in codes && run.value == runs.getOrNull(n)?.value) "•".repeat(run.value.length) else run.value
    }
}

/**
 * What a bank owes on, not what it holds. "مانده بدهی" and "مانده تسهیلات" are a loan balance,
 * and reading one as cash adds the size of her debt to her wealth.
 */
private val NOT_A_BALANCE = listOf("بدهی", "تسهیلات", "وام", "قسط", "چک", "کارت اعتباری")

/**
 * Words that mark a مانده as an operator's bundle or an app wallet — data, minutes, credit,
 * «کیف پول» — not money at a bank. The bare «کیف» is deliberate: [normalise] strips ZWNJ, so
 * «کیف‌پول» arrives glued.
 */
private val OPERATOR_WORDS = listOf("اینترنت", "بسته", "گیگ", "مکالمه", "شارژ", "کیف")

/**
 * Both lists together, and one name for them on purpose: the veto used to live only in
 * [looksLikeBankSms], so a promo from a bank's own trusted number could still quote its
 * wallet's موجودی and have it overwrite — and anchor — the account. Every place that reads
 * a مانده as money must refuse the same words.
 */
private val BALANCE_VETO = NOT_A_BALANCE + OPERATOR_WORDS

/**
 * Under this, a run of digits is not the amount of a transaction.
 *
 * An advert from a bank's own number reaches the parser exactly as its transactions do — the
 * sender gate is the only gate, and the bank really did send it. What made one *a spend* was a
 * direction word it happened to contain and the first digits after it: «... تا ۱ میلیون تومان
 * تخفیف» is a «۱» followed within a few characters by «تومان», so the message arrived in the
 * deck as a purchase of one Toman, asking her to file an advert.
 *
 * Refusing rather than multiplying the scale word is the point. Banks print amounts in full —
 * "1,000,000" — and never in words, so a figure that needs «میلیون» to be money is prose. Read
 * as ten million Rial it would be a *large* invented transaction instead of a trivial one, and
 * an invented figure is worse the more plausible it looks.
 *
 * [feeIn] has had the same floor since the day پاسارگاد quoted «کارمزد پیامک بانکی ۶ ماهه» and
 * six months became six Rial; this is that rule arriving on the amount, where the only reason
 * it was not needed sooner is that the ledger is louder about spends than about fees.
 *
 * ponytail: a flat floor, in whatever unit the figure printed. Every real transaction is orders
 * of magnitude above it — a thousand Rial is a hundred Toman — so nothing needs the exactness
 * of comparing after conversion.
 */
private const val MIN_MONEY_FIGURE = 1000.0

/** A run of digits with its separators, in any of the three digit sets. */
private val NUMBER = Regex("[0-9۰-۹٠-٩][0-9۰-۹٠-٩,،٬.٫]*[0-9۰-۹٠-٩]|[0-9۰-۹٠-٩]")

/**
 * The amount with the bank's own sign glued to it. خاورمیانه, پاسارگاد and رسالت put it in front,
 * on a line of its own: "+6,000,000", "-30,000,000". صادرات and ملی put it behind, after a label:
 * «پایانه فروش: 4,100,000-», «سود:2,472,328+». Either way the sign is the direction and outranks
 * the words. خاورمیانه heads a transfer in «انتقال از اینترنت بانک از کارت 9295», and reading
 * «انتقال» as a spend took the card's last four digits as the amount. It also names nothing at
 * all on «سود صندوق», and صادرات names no direction on a POS purchase or a PAYA deposit, so the
 * words left the amount unknown.
 *
 * Thousands separators are required, which keeps a "+98…" phone number from reading as a
 * deposit, and only whitespace, a colon or the start of the text may stand in front of it. A
 * figure without them falls through to the words, as before.
 */
private const val GROUPED = "[0-9۰-۹٠-٩]{1,3}(?:[,،٬][0-9۰-۹٠-٩]{3})+"
private val SIGNED = Regex("(?<![^\\s:])(?:([+-])($GROUPED)|($GROUPED)([+-]))(?![0-9۰-۹٠-٩])")

private class Signed(val figure: Figure, val plus: Boolean)

/**
 * The first [SIGNED] figure that is not a balance. One with «مانده» or «موجودی» in front of it on
 * its own line is the balance being stated — an overdrawn one, or a bank that signs every figure —
 * and reading it as the amount would report everything the account holds as the sum that moved.
 */
private fun signedAmount(text: String): Signed? {
    val m = SIGNED.findAll(text).firstOrNull { m ->
        val lineStart = text.lastIndexOf('\n', m.range.first - 1) + 1
        BALANCE_WORDS.none { text.substring(lineStart, m.range.first).contains(it) }
    } ?: return null
    val (leadSign, leadFigure, trailFigure, trailSign) = m.destructured
    return Signed(
        Figure(moneyOf(leadFigure.ifEmpty { trailFigure })!!, unitAfter(text, m.range.last + 1)),
        plus = leadSign.ifEmpty { trailSign } == "+",
    )
}

private val FROM_BOX = Regex("از\\s+باکس")
private val FROM_ACCOUNT = Regex("از\\s+حساب")

/**
 * Which way a Blu box move went: true when money came out of a box into the account, false when it
 * went from the account into a box, null when the message is not a box move.
 *
 * A box is money she keeps aside inside Blu — savings, or a pot for one purpose — so a move either
 * way is her own money changing drawers, filed with transfers rather than as income or spending.
 * The words cannot say which way it went: Blu writes a move into a box as «از حساب در باکس …
 * نشست», and «نشست» is its word for money arriving, so it read as a deposit. The side the money
 * left does say: «از حساب» out of the account, «از باکس» back into it.
 *
 * A purchase is never one, whatever box it mentions: «… از حساب شما پرید» is money spent, and a
 * round-up into a box beside it is not the amount that moved.
 */
private fun boxMove(text: String): Boolean? {
    if (!text.contains("باکس") || text.contains("پرید")) return null
    val fromBox = FROM_BOX.containsMatchIn(text)
    val fromAccount = FROM_ACCOUNT.containsMatchIn(text)
    return when {
        fromBox && !fromAccount -> true
        fromAccount && !fromBox -> false
        else -> null
    }
}

/**
 * A money figure out of a body, with the unit printed next to it.
 *
 * Every separator is dropped, the dot included. Bank SMS quote whole Rial and never a fraction
 * of one, so "500.000" is five hundred thousand; reading its dot as a decimal point would
 * report five hundred, which is the same figure a thousand times too small.
 */
private class Figure(val value: Double, val divisor: Double?)

private fun moneyOf(run: String): Double? =
    digitsOf(run).takeIf { it.isNotEmpty() }?.toDoubleOrNull()

/**
 * The unit written immediately after a figure. This is per-figure on purpose: a body that says
 * "300,000,000 ریال (معادل 30,000,000 تومان)" names both, and deciding the unit once for the
 * whole message would read every ریال figure in it as تومان — the tenfold overstatement this
 * whole feature is built to avoid.
 */
private fun unitAfter(text: String, end: Int): Double? {
    val tail = text.substring(end, minOf(text.length, end + 14))
    return when {
        tail.contains("تومان") || tail.contains("تومن") -> 1.0
        tail.contains("ریال") || tail.contains("ر.ی") -> 10.0
        else -> null
    }
}

/**
 * The unit for a figure that printed none of its own. Only a body naming تومان and never ریال
 * is taken as Toman; anything else is Rial, which is both the convention and the direction that
 * understates rather than overstates.
 */
private fun fallbackDivisor(text: String): Double =
    if ((text.contains("تومان") || text.contains("تومن")) && !text.contains("ریال")) 1.0 else 10.0

/**
 * The first real money figure after any of [labels].
 *
 * Two things stop it grabbing the wrong digits. It searches the whole remaining body and only
 * then checks the match started within [window], so a long figure is never cut in half by the
 * window landing inside it — "1,978,750,309" truncated to "1,978," parses as one thousand nine
 * hundred and seventy-eight. And a match glued to a dash or a mask star is skipped, because
 * that is an account, card or reference number rather than an amount: in "مانده حساب
 * 829-800-1092308-1 : 50,000,000 ریال" the balance is the last run, not the first.
 */
private fun isDigit(c: Char?) =
    c != null && (c in '0'..'9' || c in '۰'..'۹' || c in '٠'..'٩')

/**
 * Whether this run of digits is part of an account, card or reference number rather than an
 * amount. What marks one is a dash *between two digit runs* — "829-800-1092308-1" — or a mask
 * star. A dash after a space or a line break is not that: it is a minus sign, and خاورمیانه
 * writes its withdrawals exactly so, as a bare "-5,025,000" on its own line. Treating that as
 * an identifier skipped the real amount and read the reference number underneath it instead.
 */
private fun isIdentifierPart(text: String, at: IntRange): Boolean {
    val before = text.getOrNull(at.first - 1)
    val after = text.getOrNull(at.last + 1)
    if (before == '*' || after == '*') return true
    if (before == '-' && isDigit(text.getOrNull(at.first - 2))) return true
    if (after == '-' && isDigit(text.getOrNull(at.last + 2))) return true
    return false
}

/**
 * Whether the word found at [at] sits right after «قابل», which turns an event into an
 * ability: «موجودی قابل برداشت» is what she may take out, not money leaving. Read as a
 * withdrawal, a Saman balance statement became a spend of everything the account held.
 * [normalise] already strips ZWNJ, so «قابل‌برداشت» arrives glued and the whitespace here
 * is optional.
 */
private fun qualifiedAt(text: String, at: Int): Boolean {
    var i = at - 1
    while (i >= 0 && text[i].isWhitespace()) i--
    if (i < 3 || !text.startsWith("قابل", i - 3)) return false
    // «مقابل» ends in the same four letters and is a different word.
    return text.getOrNull(i - 4)?.isLetter() != true
}

/** Whether any of [words] appears unqualified: a message whose only «برداشت» follows «قابل» states no direction. */
private fun statesDirection(text: String, words: List<String>): Boolean {
    for (word in words) {
        var i = text.indexOf(word)
        while (i >= 0) {
            if (!qualifiedAt(text, i)) return true
            i = text.indexOf(word, i + 1)
        }
    }
    return false
}

private fun figureAfter(
    text: String,
    labels: List<String>,
    window: Int = 48,
    allowZero: Boolean = false,
    veto: List<String> = emptyList(),
    stopAt: List<String> = emptyList(),
): Figure? {
    for (label in labels) {
        var from = 0
        while (true) {
            val at = text.indexOf(label, from)
            if (at < 0) break
            val start = at + label.length
            from = start
            if (qualifiedAt(text, at)) continue
            // "مانده بدهی" is a different noun from "مانده".
            val ahead = text.substring(start, minOf(text.length, start + 16))
            if (veto.any { ahead.contains(it) }) continue
            // Where this search must give up rather than keep walking. A figure on the far side
            // of «موجودی» is that balance being stated, and returning it as the amount reports
            // everything she has as the sum that just moved.
            val limit = stopAt
                .mapNotNull { word -> text.indexOf(word, start).takeIf { it >= 0 } }
                .minOrNull() ?: text.length

            for (m in NUMBER.findAll(text, start)) {
                if (m.range.first - start > window) break
                if (m.range.first >= limit) break
                if (isIdentifierPart(text, m.range)) continue
                val v = moneyOf(m.value) ?: continue
                if (v == 0.0 && !allowZero) continue
                return Figure(v, unitAfter(text, m.range.last + 1))
            }
        }
    }
    return null
}

/**
 * The مانده a message states, which [parseBankSms] anchors the account on.
 *
 * Zero is allowed here and nowhere else: an emptied account really does have a balance of
 * nought, and refusing to read it would leave the old figure standing for ever.
 */
private fun statedBalance(text: String): Figure? =
    figureAfter(text, BALANCE_WORDS, allowZero = true, veto = BALANCE_VETO)

/**
 * The last money figure *before* any of [labels], never crossing a line break.
 *
 * Most banks name the amount and then say what became of it — "واریز مبلغ ۵۰۰٬۰۰۰" — which is
 * what [figureAfter] reads. Blu says it the other way round: "۱٬۰۰۰٬۰۰۰٬۰۰۰ ریال به حساب شما
 * نشست", with the only word that gives the money a direction trailing the figure it belongs to.
 *
 * Bounded to the one line because that is how these messages are written: an amount and the
 * phrase directing it are a sentence, while the موجودی, the time and the date each get a line of
 * their own. Without the bound the nearest thing behind «نشست» on the previous line is the
 * balance, which is the bug this exists to fix, arriving from the other side.
 */
private fun figureBefore(text: String, labels: List<String>): Figure? {
    for (label in labels) {
        var from = 0
        while (true) {
            val at = text.indexOf(label, from)
            if (at < 0) break
            from = at + label.length
            if (qualifiedAt(text, at)) continue
            val lineStart = text.lastIndexOf('\n', at - 1) + 1
            val found = NUMBER.findAll(text.substring(lineStart, at))
                .map { IntRange(lineStart + it.range.first, lineStart + it.range.last) }
                .lastOrNull { !isIdentifierPart(text, it) && (moneyOf(text.substring(it)) ?: 0.0) > 0.0 }
                ?: continue
            return Figure(moneyOf(text.substring(found))!!, unitAfter(text, found.last + 1))
        }
    }
    return null
}

/**
 * How the message says which account it is about. Some banks mask a tail ("۱۲۳****"); Saman
 * prints the number in full ("829-800-1092308-1"). Without either, every account at a bank
 * collapses onto one balance and its siblings overwrite each other in turn.
 */
private val MASKED = Regex("[0-9۰-۹٠-٩]*\\*{2,}[0-9۰-۹٠-٩]*")
private val DASHED = Regex("[0-9۰-۹٠-٩]{2,}(?:-[0-9۰-۹٠-٩]+)+")

private fun digitsIn(s: String) =
    s.count { it in '0'..'9' || it in '۰'..'۹' || it in '٠'..'٩' }

private fun accountIn(text: String): String =
    MASKED.find(text)?.value
        // A Jalali date written 1405-05-01 is also digits and dashes; an account number is
        // longer than any date, so length is what tells them apart.
        ?: DASHED.findAll(text).map { it.value }.firstOrNull { digitsIn(it) >= 10 }.orEmpty()

/**
 * Read one message, or decline it.
 *
 * Two gates, in order. The sender must be a bank we read — that is what keeps the entire rest
 * of her inbox, every OTP and every advert and every message from her family, out of a
 * function that moves money. Then the body must actually say something happened: a bank sends
 * plenty that is not a transaction, and a null here is how those pass through untouched.
 */
fun parseBankSms(
    sender: String,
    body: String,
    at: Long,
    extra: Map<String, Bank> = emptyMap(),
): BankSms? {
    val bank = bankOf(sender, extra) ?: return null

    val text = normalise(body)
    if (isOneTimeCode(body)) return null
    val fallback = fallbackDivisor(text)

    val balance = statedBalance(text)?.let { it.value / (it.divisor ?: fallback) }

    // Direction decides the sign, so a message that states no direction states no delta —
    // guessing one is how a deposit becomes a withdrawal.
    val deposit = statesDirection(text, IN_WORDS)
    val withdrawal = statesDirection(text, OUT_WORDS)
    val inWords = IN_WORDS.takeIf { deposit } ?: emptyList()
    val outWords = OUT_WORDS.takeIf { withdrawal } ?: emptyList()
    val signed = signedAmount(text)
    val amount = (
        signed?.figure
            ?: figureAfter(text, AMOUNT_WORDS, stopAt = BALANCE_WORDS)
            ?: figureAfter(text, inWords, stopAt = BALANCE_WORDS)
            ?: figureAfter(text, outWords, stopAt = BALANCE_WORDS)
            // Nothing after the direction word, so the figure it refers to is the one in front of
            // it: Blu heads a deposit «دریافت پل», which names no direction at all, leaving «نشست»
            // at the end of the sentence as the only word that says which way the money went.
            ?: figureBefore(text, inWords)
            ?: figureBefore(text, outWords)
        )?.takeIf { it.value >= MIN_MONEY_FIGURE }
    val moved = amount?.let { it.value / (it.divisor ?: fallback) }
    val box = boxMove(text)
    val delta = when {
        moved == null -> null
        signed != null -> if (signed.plus) moved else -moved
        box != null -> if (box) moved else -moved
        deposit && !withdrawal -> moved
        withdrawal && !deposit -> -moved
        else -> null // both or neither: the message does not say which way the money went
    }
    if (delta == null && balance == null) return null

    val mask = accountIn(text)
    val extras = enrich(text, body, fallback)
    val sms = BankSms(
        bank = bank,
        sender = sender.trim(),
        mask = mask,
        delta = delta,
        balance = balance,
        at = at,
        // The bank is no longer ever a guess — the sender settled it. What is left is a
        // message that named an amount and a balance but not which way the money went.
        inferred = delta == null && balance != null && amount != null,
        // Whole Rial, taken off the figure before it was ever divided. Rial is what these
        // messages actually print; Toman is a display transform, and rounding one leg of a
        // transfer down while the other rounds up is how exact-amount matching stops working.
        amountRial = amount?.rial(fallback),
        balanceRial = balance?.let { Math.round(it * 10.0) },
        feeRial = extras.feeRial,
        merchant = extras.merchant,
        refNo = extras.refNo,
        printedAt = extras.printedAt,
        channel = extras.channel,
        instrument = instrumentOf(mask),
        unitPrinted = when (amount?.divisor) {
            1.0 -> PrintedUnit.TOMAN
            10.0 -> PrintedUnit.RIAL
            else -> PrintedUnit.NONE
        },
    )
    return sms
}

// ───────────────────────── enrichment ─────────────────────────
// Everything below reads the body and returns new fields. Nothing on the money path above
// reads any of it. That is not a convention to be careful about — there is no data path from
// [Extras] back into delta, balance, mask or inferred, so a bug in merchant extraction
// *cannot* move a number. A guarantee that holds because of the shape of the code beats one
// that holds because everyone remembered.

/**
 * How the money moved, as far as the message says. [BOX] is Blu moving money between the account
 * and one of its boxes — see [boxMove].
 */
enum class Channel { UNKNOWN, POS, ATM, PAYA, SATNA, CARD, TRANSFER, FEE, BILL, BOX }

/** What the message named the account by. Both collapse into one [BankSms.mask] string. */
enum class Instrument { UNKNOWN, CARD, ACCOUNT }

/** The unit the amount actually carried, before it was converted. */
enum class PrintedUnit { NONE, RIAL, TOMAN }

/** Named after the label it follows, so a fee is never mistaken for the transaction. */
private val FEE_WORDS = listOf("کارمزد", "هزینه")

private val REF_WORDS = listOf("پیگیری", "رهگیری", "مرجع", "شماره سند", "شناسه پرداخت")

/** خاورمیانه prints its reference as "020/000016703" with no label at all. */
private val SLASHED_REF = Regex("[0-9۰-۹٠-٩]{2,}/[0-9۰-۹٠-٩]{4,}")

// «مرکز» is deliberately not here: the only «مرکز» in the whole corpus is اقتصاد نوین signing
// its own call centre — «مرکزآواي نوين:02162740» — on every message it sends, and reading that
// as a merchant titled every one of the bank's transactions «آوای نوین» instead of the bank.
private val MERCHANT_WORDS = listOf("فروشگاه", "پذیرنده", "به نام", "بنام")

/**
 * A date the bank printed itself, matched verbatim and never parsed here.
 *
 * The separator classes are deliberately different on the two halves. Date parts join with
 * `/` or `.`; a time is introduced by a space, an underscore or a dash. Sharing one class made
 * "05/08_13:37" read as the three-part date "05/08_13" and swallow the time, and letting `-`
 * join date parts did the same to "5/8-10:04".
 *
 * The lookbehind is what keeps it off account numbers: without it "276.800.504939.1" offers
 * "800.50" as a date.
 */
private val PRINTED_AT = Regex(
    "(?<![0-9۰-۹٠-٩./\\-_])" +
        "[0-9۰-۹٠-٩]{1,4}[/.][0-9۰-۹٠-٩]{1,2}(?:[/.][0-9۰-۹٠-٩]{1,2})?" +
        "(?:[ _-]{1,3}[0-9۰-۹٠-٩]{1,2}:[0-9۰-۹٠-٩]{2}(?::[0-9۰-۹٠-٩]{2})?)?" +
        "(?![0-9۰-۹٠-٩])"
)

/** A clock time, hours to optional seconds, in either set of digits. */
private const val CLOCK = "[0-9۰-۹٠-٩]{1,2}:[0-9۰-۹٠-٩]{2}(?::[0-9۰-۹٠-٩]{2})?"

/**
 * The same time, on a line of its own rather than glued to the date.
 *
 * [PRINTED_AT] only ever caught an hour the bank set beside the date with a space, an underscore
 * or a dash. Half the corpus does not write it that way: سامان and خاورمیانه put the time on the
 * line under the date, and بلو puts it on the line above — so «۲۱:۵۱» was sitting in the message,
 * visible in «منبع» directly under a «زمان ثبت» that said only «۱۴۰۵.۰۵.۱۶».
 *
 * Anchored to the date on both sides, which is what keeps this from finding any colon in the
 * message: only whitespace may stand between the two, so a stamp is a date and the time written
 * against it and never a figure from two lines away.
 */
private val CLOCK_AFTER = Regex("^\\s*($CLOCK)(?![0-9۰-۹٠-٩:])")
private val CLOCK_BEFORE = Regex("(?<![0-9۰-۹٠-٩:])($CLOCK)\\s*$")

/**
 * The stamp the bank printed, which is a date and — where it wrote one — the time beside it.
 *
 * Verbatim in its parts: every character here is the bank's own, in the bank's own digits, and
 * nothing is parsed or reformatted. The one thing this decides is order. The two runs are joined
 * date first whichever way round the message printed them, because «زمان ثبت» is a column, and a
 * column of stamps that leads with the hour on some rows and the year on others cannot be read
 * down. Where the bank set them together already — «05/08_13:37» — its own separator stands.
 *
 * A time with no date beside it is not a stamp and is left alone: half the messages in the corpus
 * carry no date at all, and answering «زمان ثبت» with a bare hour would say the bank stated when
 * this happened when it did no such thing.
 */
internal fun printedStampIn(rawBody: String): String {
    val date = PRINTED_AT.find(rawBody) ?: return ""
    val stamp = date.value.trim()
    if (':' in stamp) return stamp
    val time = CLOCK_AFTER.find(rawBody.substring(date.range.last + 1))?.groupValues?.get(1)
        ?: CLOCK_BEFORE.find(rawBody.substring(0, date.range.first))?.groupValues?.get(1)
    return if (time == null) stamp else "$stamp $time"
}

/** What a message carries beyond the money. Every field is optional and every default is empty. */
internal class Extras(
    val merchant: String,
    val refNo: String,
    val printedAt: String,
    val channel: Channel,
    val feeRial: Long?,
)

/** Rial, as an integer, straight off a figure and before any division. */
private fun Figure.rial(fallback: Double): Long =
    Math.round(value * (10.0 / (divisor ?: fallback)))

private fun instrumentOf(mask: String): Instrument = when {
    mask.isEmpty() -> Instrument.UNKNOWN
    mask.contains('*') -> Instrument.CARD
    // A sixteen-digit run is a card number however it is punctuated — "6037-9911-1234-5678"
    // is a card, not an account, and only the length says so once the stars are gone.
    // Iranian account numbers are shorter than that.
    digitsIn(mask) >= 16 -> Instrument.CARD
    else -> Instrument.ACCOUNT
}

/**
 * The channel, most specific first. "پایانه فروش" must beat "خرید", and "کارت به کارت" must
 * beat the "انتقال" it usually appears beside, or every transfer reads as a generic one.
 */
private fun channelOf(text: String): Channel = when {
    // First, because a box move can be headed «انتقال» and is still no transfer to anyone.
    boxMove(text) != null -> Channel.BOX
    text.contains("خودپرداز") || text.contains("atm") -> Channel.ATM
    text.contains("پایانه فروش") || text.contains("پایانه") -> Channel.POS
    text.contains("کارت به کارت") -> Channel.CARD
    text.contains("ساتنا") -> Channel.SATNA
    text.contains("پایا") -> Channel.PAYA
    text.contains("قبض") -> Channel.BILL
    text.contains("خرید") -> Channel.POS
    text.contains("انتقال") -> Channel.TRANSFER
    // Last, because a fee named beside a purchase does not make the purchase a fee.
    FEE_WORDS.any { text.contains(it) } -> Channel.FEE
    else -> Channel.UNKNOWN
}

/**
 * A fee named inside another transaction's message.
 *
 * The first figure after the word is not always money: پاسارگاد writes "کارمزد پیامک بانکی
 * ۶ ماهه دوم سال ۱۴۰۰", where it is a number of months. So a fee must either name its own unit
 * or be big enough that it cannot be a count of anything.
 *
 * ponytail: a flat four-digit floor. If a real fee is ever quoted under 1,000 with no unit,
 * this needs the parent amount to compare against instead.
 */
private fun feeIn(text: String, fallback: Double): Long? {
    val fee = figureAfter(text, FEE_WORDS) ?: return null
    if (fee.divisor == null && fee.value < 1000) return null
    return fee.rial(fallback)
}

private fun refIn(text: String): String {
    for (word in REF_WORDS) {
        val at = text.indexOf(word)
        if (at < 0) continue
        val start = at + word.length
        val m = NUMBER.findAll(text, start)
            .firstOrNull { it.range.first - start <= 24 && digitsIn(it.value) >= 6 }
        if (m != null) return m.value
    }
    return SLASHED_REF.find(text)?.value.orEmpty()
}

/**
 * The shop or terminal, where the bank names one. Cut at a colon, a line break or a digit run,
 * because a name is often followed by a phone number or a terminal code.
 *
 * "پایانه" is deliberately not a merchant word even though it introduces one on some banks: on
 * صادرات it is followed by "فروش", and the merchant would come out as the word "sale".
 */
private fun merchantIn(text: String): String {
    for (word in MERCHANT_WORDS) {
        val at = text.indexOf(word)
        if (at < 0) continue
        val tail = text.substring(at + word.length).trimStart(' ', ':', '-', '،')
        val name = tail.takeWhile { it != '\n' && it != ':' && it != '،' && !isDigit(it) }.trim()
        if (name.length >= 2) return name.replace(WHITESPACE, " ")
    }
    return ""
}

internal fun enrich(normalised: String, rawBody: String, fallback: Double): Extras = Extras(
    merchant = merchantIn(normalised),
    refNo = refIn(normalised),
    // Off the raw body, not the normalised one: this is quoted back to her as the bank wrote it.
    printedAt = printedStampIn(rawBody),
    channel = channelOf(normalised),
    feeRial = feeIn(normalised, fallback),
)

private data class IgnoredBank(val name: String, val senders: List<String>)

private val IGNORED_BANKS = listOf(
    IgnoredBank("بانک آینده", listOf("AyandehBank", "+98700745")),
)

fun isIgnoredBankSms(sender: String, body: String): Boolean {
    val senderKey = senderKey(sender)
    val firstLine = normalise(body).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return IGNORED_BANKS.any { bank ->
        senderKey in bank.senders.map(::senderKey) || firstLine.startsWith(bank.name)
    }
}

/**
 * Whether a message we are *not* going to read is talking about a real bank balance: a money
 * figure sitting right after مانده/موجودی, big enough to be one, and not an operator's bundle.
 *
 * The first cut of this fired on any مانده anywhere — which is also how Irancell announces the
 * data she has left — and filled the sheet with red noise. It exists because "the app cannot
 * see my bank" and "the app reads my bank wrongly" look identical from the outside, and the
 * first is silent by design: a bank that adds a sending number simply freezes.
 */
fun looksLikeBankSms(body: String): Boolean {
    val text = normalise(body)
    val f = figureAfter(text, BALANCE_WORDS, veto = BALANCE_VETO)
    return f != null && f.value >= 100_000
}

/**
 * Which of the banks we read this body claims, by its own words, to be from. A suggestion for
 * her to confirm with a tap, never a reason to move money — matching on wording is exactly
 * what was removed when the sender numbers became the gate, and it stays removed.
 */
/**
 * The word that names this bank and nothing else — «تجارت» out of «بانک تجارت».
 *
 * Null for [Bank.OTHER] alone, whose name is «بانک» and so has no distinguishing word. That null
 * is what keeps it out of [guessBank]: it is a fallback for balances an older build saved, never
 * something a message can be read as.
 */
private val Bank.keyword: String? get() = fa.split(' ').firstOrNull { it != "بانک" }

/**
 * Which bank a message *says* it is from, for a sender this phone does not know yet.
 *
 * Deliberately not filtered to banks that have numbers: a bank listed by name only has no number
 * precisely because nobody has told us one, and this is the function that asks. Reached only for a
 * message that already passed [looksLikeBankSms] — a balance word and a figure over a hundred
 * thousand — so a keyword as ordinary as «شهر» or «آینده» costs at worst one suggestion she
 * dismisses, and a dismissal is remembered per sender.
 */
fun guessBank(body: String): Bank? {
    if (isIgnoredBankSms("", body)) return null
    val text = normalise(body)
    // The keyword is normalised like the text, or «قرض‌الحسنه» — whose ZWNJ the text no
    // longer has — could never match anything.
    return Bank.entries.firstOrNull { b ->
        b.keyword?.let { containsWord(text, normalise(it)) } == true
    }
}

/**
 * The word with no letter glued to either side. "سامانه" — the portal half the service
 * messages in Iran mention — contains "سامان", and a plain substring match was offering to
 * add a stock-portal's number to her Saman account.
 */
private fun containsWord(text: String, word: String): Boolean {
    var i = text.indexOf(word)
    while (i >= 0) {
        val before = text.getOrNull(i - 1)
        val after = text.getOrNull(i + word.length)
        if (before?.isLetter() != true && after?.isLetter() != true) return true
        i = text.indexOf(word, i + 1)
    }
    return false
}

/** One message that named a bank we read, sent from a number the list does not have. */
@Serializable
data class StrangeSender(
    val sender: String,
    val bank: String,
    val snippet: String,
    val at: Long,
)

fun snippetOf(body: String): String = body.trim().replace(Regex("\\s+"), " ").take(64)

/** A sender she could add by hand, with the newest of its messages that reads as money. */
data class SenderCandidate(val sender: String, val snippet: String, val at: Long, val guess: Bank?)

/**
 * The senders in [recent], newest first, that nothing here knows yet but whose messages would be
 * read — what the bank sheet offers when a bank's new number never became a suggestion.
 *
 * A suggestion needs the message to name its bank and state a مانده of a hundred thousand or more,
 * and three banks never manage both: صادرات and پاسارگاد do not name themselves, and خاورمیانه
 * stars its مانده out. A new number from any of them can only ever be added here.
 *
 * Each message is read as though its sender were already one of hers. [Bank.OTHER] stands in,
 * because which bank it is changes nothing the parser takes, so a sender is offered exactly when
 * adding it would make its messages count: one-time codes, chat and adverts with no amount are
 * declined there and never listed.
 */
fun senderCandidates(recent: List<RawSms>, extra: Map<String, Bank>, limit: Int = 20): List<SenderCandidate> {
    val offered = mutableSetOf<String>()
    val out = mutableListOf<SenderCandidate>()
    for (m in recent) {
        val sender = m.from.trim()
        val key = senderKey(sender)
        if (key.isEmpty() || key in offered || bankOf(sender, extra) != null) continue
        // آینده is ignored on purpose; offering it here would undo that with one tap.
        if (isIgnoredBankSms(sender, m.body)) continue
        parseBankSms(sender, m.body, m.at, mapOf(key to Bank.OTHER)) ?: continue
        offered += key
        out += SenderCandidate(sender, snippetOf(m.body), m.at, guessBank(m.body))
        if (out.size == limit) break
    }
    return out
}

/** The banks a sender can be added to by hand: every one listed, bar [Bank.OTHER] and the ignored. */
val PICKABLE_BANKS: List<Bank> =
    Bank.entries.filter { b -> b != Bank.OTHER && IGNORED_BANKS.none { it.name == b.fa } }

/**
 * Sender numbers she confirmed herself, folded into the same lookup the built-ins use.
 * A name that no longer matches the enum is skipped, never crashed on.
 */
fun extraLookup(numbers: Map<String, List<String>>): Map<String, Bank> =
    numbers.entries.flatMap { (name, nums) ->
        val bank = runCatching { Bank.valueOf(name) }.getOrNull() ?: return@flatMap emptyList()
        nums.map { senderKey(it) to bank }
    }.toMap()

/**
 * Fold one message into the balances. The bank's own مانده replaces what we held; only a
 * message that carried no balance is accumulated, and an out-of-order message never overwrites
 * a newer balance with an older one.
 */
fun applyBankSms(accounts: List<BankAccount>, sms: BankSms): List<BankAccount> {
    // One balance per bank, keyed by the bank alone — the identifier a message prints is kept
    // for her to read and never used to tell accounts apart.
    //
    // It cannot be used for that, because one account has several. Saman prints
    // "829-800-1092308-1" on a transfer, the card mask "6037****1234" on a purchase, and
    // nothing at all on some alerts, and every one of those states the same مانده. Keying on
    // what was printed forked that single account into two and three rows, each anchored at the
    // full balance, and the total added them all: her money doubled on screen, with nothing
    // flagged, which is precisely the failure this app exists to prevent. A second real account
    // at one bank is the lesser risk — its balance would be one figure rather than two, wrong
    // but visible and correctable in the sheet.
    val existing = accounts.firstOrNull { it.bank == sms.bank.name }

    // Nothing older than what we already know may change it — neither a stated balance, nor a
    // transaction that predates the balance she typed in by hand.
    if (existing != null && sms.at < existing.updatedAt) return accounts

    // Whichever side actually named the account wins, so the record reads more specific, never
    // less. It is a label only.
    val mask = sms.mask.ifBlank { existing?.mask.orEmpty() }

    val next = when {
        sms.balance != null ->
            // The bank stated the balance: that is the truth, and it anchors the account.
            BankAccount(
                bank = sms.bank.name,
                mask = mask,
                balance = sms.balance,
                updatedAt = sms.at,
                inferred = sms.inferred,
                anchored = true,
                sender = sms.sender,
            )
        sms.delta != null -> BankAccount(
            bank = sms.bank.name,
            mask = mask,
            balance = (existing?.balance ?: 0.0) + sms.delta,
            updatedAt = maxOf(sms.at, existing?.updatedAt ?: 0L),
            // Accumulating never un-marks a balance that was already a guess, and never turns
            // a running total of transactions into an anchored balance.
            inferred = sms.inferred || existing?.inferred == true,
            anchored = existing?.anchored == true,
            sender = sms.sender.ifBlank { existing?.sender.orEmpty() },
        )
        else -> return accounts
    }
    // Every row for this bank collapses into the one, so a phone upgraded from the build that
    // keyed on the printed identifier stops double-counting at the next message it reads.
    return accounts.filterNot { it.bank == sms.bank.name } + next
}

/** Legacy anchors have no manual/bank provenance, so only equally recent bank evidence replaces them. */
fun rebuildBankAccounts(previous: List<BankAccount>, messages: List<BankSms>): List<BankAccount> {
    var rebuilt = previous
    for ((bank, rows) in messages.groupBy { it.bank.name }) {
        val anchor = previous.firstOrNull { it.bank == bank && it.anchored }
        if (anchor == null) rebuilt = rebuilt.filterNot { it.bank == bank }
        for (sms in rows.sortedBy { it.at }) {
            if (anchor != null && (sms.at < anchor.updatedAt ||
                    (sms.at == anchor.updatedAt && sms.balance == null))) continue
            rebuilt = foldBankSms(rebuilt, sms)
        }
    }
    return rebuilt
}

/**
 * The one figure she can give us that no message carries: what an account actually holds right
 * now. Anchors it, so everything read afterwards accumulates onto a real balance instead of
 * onto zero.
 */
fun anchorAccount(accounts: List<BankAccount>, key: String, balance: Double, at: Long) =
    accounts.map {
        if (it.key == key) it.copy(balance = balance, updatedAt = at, inferred = false, anchored = true)
        else it
    }

/**
 * One row per bank, whatever is already on disk.
 *
 * The build that keyed an account by the identifier a message happened to print kept a row per
 * identifier: a real phone came back with thirty-eight of them across three banks, because
 * reference numbers, card numbers and account numbers all look alike to [accountIn]. Every one
 * of them was summed into her total. They are not separate accounts and never were, so they
 * fold back into one — and they must, because a bank is one key now, and duplicate keys in the
 * accounts list crash the sheet the moment she opens it.
 *
 * The survivor is the best evidence available: a stated balance beats a running total, and the
 * more recent beats the older. Adding them together would be the doubling all over again.
 */
fun collapseAccounts(accounts: List<BankAccount>): List<BankAccount> =
    accounts
        // A bank we no longer read can never be corrected by another message, so its figure is
        // frozen at whatever the build that wrote it believed — and it cannot even be named,
        // since the enum entry is gone. Rows from banks that were guessed at by wording, rather
        // than known by their number, are exactly this: unnameable and unfixable.
        .filter { it.bank in READ_BANKS }
        .groupBy { it.bank }
        .map { (_, rows) -> rows.maxWith(compareBy({ it.anchored }, { it.updatedAt })) }

private val READ_BANKS: Set<String> =
    Bank.entries.filter { it.numbers.isNotEmpty() }.map { it.name }.toSet()

/**
 * What the tracked accounts add up to: the banks she has switched on, and only the accounts a
 * bank has actually stated a balance for.
 *
 * An unanchored account is a running sum of the transactions we happened to read, not a
 * balance — start it on a withdrawal and it is negative, which would take money off her total
 * that never existed. So it is treated exactly as this app already treats an asset with no
 * rate: shown, reported, and left out of the total rather than guessed at. Entering the real
 * balance in the sheet is what makes it count.
 */
fun bankTotal(accounts: List<BankAccount>, disabled: Set<String>): Double =
    accounts.filter { it.anchored && it.bank !in disabled }.sumOf { it.balance }

/**
 * The dedup key for a message. The inbox row id is not it: a message re-inserted by a restore
 * or a different SMS app gets a new id and would be counted a second time, which on an
 * accumulated balance is money invented out of nothing. Sender, timestamp, and full content
 * are hashed together so two banks sending the same template at once are still distinct.
 */
fun smsKey(sender: String, body: String, at: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("${senderKey(sender)}\u0000$at\u0000${body.trim()}".toByteArray(Charsets.UTF_8))
    return hexOf(digest)
}

/**
 * Shared with [srcHash], which takes the same digest over a deliberately different input —
 * [srcAddrKeyV1] rather than [senderKey], for the reason spelled out there.
 */
internal fun sha256Hex(input: String): String =
    hexOf(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))

internal fun hexOf(digest: ByteArray): String {
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

/** Recognises keys written by builds before sender-aware SHA-256 deduplication. */
fun legacySmsKey(body: String, at: Long): String = "$at:${body.trim().hashCode()}"

/** Keeps the seen-message set from growing without bound; a year of messages is ample. */
const val SMS_KEEP = 2_000

fun rememberSeen(seen: Set<String>, added: List<String>): Set<String> =
    (seen + added).let { if (it.size <= SMS_KEEP) it else it.toList().takeLast(SMS_KEEP).toSet() }
