package com.doxigo.muchtoman

import kotlinx.serialization.Serializable

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
    SAMAN("بانک سامان", listOf("0999 992 0000", "+989820000", "9820000")),
    // Refah and Pasargad also send from lettered headers, not numbers at all. senderKey
    // keeps those as their own name, so listing one works exactly like listing a shortcode.
    REFAH("بانک رفاه", listOf("100031", "100032", "Refah Bank")),
    PASARGAD("بانک پاسارگاد", listOf("B.Pasargad")),
    EGHTESAD_NOVIN("بانک اقتصاد نوین", listOf("ENBank")),
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
    PARSIAN("بانک پارسیان", listOf("PARSIANBANK")),

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
// inline (?U) flag, so include Unicode separator categories explicitly.
private val WHITESPACE = Regex("[\\s\\p{Z}]+")

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
 * What a bank owes on, not what it holds. "مانده بدهی" and "مانده تسهیلات" are a loan balance,
 * and reading one as cash adds the size of her debt to her wealth.
 */
private val NOT_A_BALANCE = listOf("بدهی", "تسهیلات", "وام", "قسط", "چک", "کارت اعتباری")

/** A run of digits with its separators, in any of the three digit sets. */
private val NUMBER = Regex("[0-9۰-۹٠-٩][0-9۰-۹٠-٩,،٬.٫]*[0-9۰-۹٠-٩]|[0-9۰-۹٠-٩]")

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

private fun figureAfter(
    text: String,
    labels: List<String>,
    window: Int = 48,
    allowZero: Boolean = false,
    veto: List<String> = emptyList(),
): Figure? {
    for (label in labels) {
        var from = 0
        while (true) {
            val at = text.indexOf(label, from)
            if (at < 0) break
            val start = at + label.length
            from = start
            // "مانده بدهی" is a different noun from "مانده".
            val ahead = text.substring(start, minOf(text.length, start + 16))
            if (veto.any { ahead.contains(it) }) continue

            for (m in NUMBER.findAll(text).dropWhile { it.range.first < start }) {
                if (m.range.first - start > window) break
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
    val fallback = fallbackDivisor(text)

    // Zero is allowed here and nowhere else: an emptied account really does have a balance of
    // nought, and refusing to read it would leave the old figure standing for ever.
    val balance = figureAfter(text, BALANCE_WORDS, allowZero = true, veto = NOT_A_BALANCE)
        ?.let { it.value / (it.divisor ?: fallback) }

    // Direction decides the sign, so a message that states no direction states no delta —
    // guessing one is how a deposit becomes a withdrawal.
    val deposit = IN_WORDS.any { text.contains(it) }
    val withdrawal = OUT_WORDS.any { text.contains(it) }
    val amount = figureAfter(text, AMOUNT_WORDS)
        ?: figureAfter(text, IN_WORDS.takeIf { deposit } ?: emptyList())
        ?: figureAfter(text, OUT_WORDS.takeIf { withdrawal } ?: emptyList())
    val moved = amount?.let { it.value / (it.divisor ?: fallback) }
    val delta = when {
        moved == null -> null
        deposit && !withdrawal -> moved
        withdrawal && !deposit -> -moved
        else -> null // both or neither: the message does not say which way the money went
    }

    val sms = BankSms(
        bank = bank,
        sender = sender.trim(),
        mask = accountIn(text),
        delta = delta,
        balance = balance,
        at = at,
        // The bank is no longer ever a guess — the sender settled it. What is left is a
        // message that named an amount and a balance but not which way the money went.
        inferred = delta == null && balance != null && amount != null,
    )
    return sms.takeIf { !it.empty }
}

/** Words that mark a مانده as an operator's bundle — data, minutes, credit — not money at a bank. */
private val OPERATOR_WORDS = listOf("اینترنت", "بسته", "گیگ", "مکالمه", "شارژ")

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
    val f = figureAfter(text, BALANCE_WORDS, veto = NOT_A_BALANCE + OPERATOR_WORDS)
    return f != null && f.value >= 100_000
}

/**
 * Which of the banks we read this body claims, by its own words, to be from. A suggestion for
 * her to confirm with a tap, never a reason to move money — matching on wording is exactly
 * what was removed when the sender numbers became the gate, and it stays removed.
 */
fun guessBank(body: String): Bank? {
    if (isIgnoredBankSms("", body)) return null
    val text = normalise(body)
    return Bank.entries.filter { it.numbers.isNotEmpty() }
        .firstOrNull { b -> containsWord(text, b.fa.split(' ').first { it != "بانک" }) }
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
 * accumulated balance is money invented out of nothing.
 */
fun smsKey(body: String, at: Long): String = "$at:${body.trim().hashCode()}"

/** Keeps the seen-message set from growing without bound; a year of messages is ample. */
const val SMS_KEEP = 2_000

fun rememberSeen(seen: Set<String>, added: List<String>): Set<String> =
    (seen + added).let { if (it.size <= SMS_KEEP) it else it.toList().takeLast(SMS_KEEP).toSet() }
