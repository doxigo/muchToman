package com.doxigo.muchtoman

/**
 * Cheques she has written — «چک صیادی», a dated promise drawn on one of her bank accounts.
 *
 * A cheque is one more row of the `goal` table, as an installment plan is: `kind = cheque`, the
 * amount in [Goal.targetRial], the date written on it in [Goal.startsOn], the bank it draws on — a
 * [Bank] name, the key the bank accounts sheet folds its balance under — in [Goal.categoryId], and
 * who or what it is for in [Goal.nameFa], blank when she left that out. [Goal.endsOn] is the day she
 * said «پاس شد», null while it is still out there. No table and no migration, for `Budget.kt`'s
 * reason — and so the backup, which carries the whole table, carries cheques with nothing added.
 *
 * Always private, as a plan is: [Goal.shared] is false, so the sync never publishes these.
 *
 * Why it exists: a cheque that bounces in Iran is a penalty and a mark against her name, and the app
 * already reads each account's balance off its messages. So when the account a cheque draws on does
 * not hold it as the date nears, the card says so in words, with the figure missing — before the day
 * rather than after.
 *
 * ponytail: outgoing only, and settled by her tap. Received cheques, and settling from the bank's own
 * message of the debit, wait until somebody asks.
 */

/** A cheque she has just written, or null when the answers are not one. */
fun newCheque(
    id: String,
    nameFa: String,
    amountRial: Long,
    due: Long,
    bank: String,
    now: Long,
    mineId: String = "",
): Goal? {
    if (amountRial <= 0L || amountRial > MAX_PLAUSIBLE_RIAL || bank.isBlank()) return null
    return Goal(
        id = id,
        nameFa = nameFa.trim().take(40),
        targetRial = amountRial,
        kind = GoalKind.CHEQUE,
        categoryId = bank,
        period = GoalPeriod.ONCE,
        startsOn = due,
        createdAt = now,
        updatedAt = now,
        ownerMemberId = mineId,
        editedByMemberId = mineId,
    )
}

/** The cheques still out there — not passed, not deleted — soonest first, ties in the order written. */
fun openCheques(goals: List<Goal>): List<Goal> =
    goals.filter { it.kind == GoalKind.CHEQUE && !it.deleted && it.endsOn == null }
        .sortedWith(compareBy({ it.startsOn }, { it.createdAt }))

/** The card's title: who it is for, or the bank when she left that blank. */
fun chequeTitleFa(cheque: Goal): String = cheque.nameFa.ifBlank { "چک ${bankNameOf(cheque.categoryId.orEmpty())}" }

/** The cheque inside a sentence: «چک «اجاره»», or «چک بانک ملت» with no name to quote. */
private fun chequeFa(cheque: Goal): String =
    if (cheque.nameFa.isBlank()) chequeTitleFa(cheque) else "چک «${cheque.nameFa}»"

/** «۳ روز مونده», «امروز», «۲ روز گذشته» — the card's corner. */
fun chequeWhenFa(due: Long, today: Long): String = when {
    due == today -> "امروز"
    due > today -> "${faNumber((due - today).toDouble())} روز مونده"
    else -> "${faNumber((today - due).toDouble())} روز گذشته"
}

/**
 * The card's warning, or null while there is nothing to warn of: the date is further off than
 * [daysBefore] — the reminder's window from تنظیمات, never less than the day itself, as
 * [pressingInstallment] keeps it — or the account holds it.
 *
 * Holds it means holds every open cheque on that account up to and including this one: two of ۲۰
 * against ۳۰ are each covered alone and not together. Unknown is said as unknown — no account for
 * that bank, or one whose figure is only a running sum ([BankAccount.anchored]) — and never read as
 * nothing in it.
 *
 * The balance truncates as every figure does; what is missing is cut at a thousand Toman rather than
 * a hundred thousand, because a shortfall read low is a cheque that still bounces.
 */
fun chequeWarningFa(
    cheque: Goal,
    open: List<Goal>,
    accounts: List<BankAccount>,
    today: Long,
    daysBefore: Int,
): String? {
    if (cheque.startsOn - today > daysBefore.coerceAtLeast(0)) return null
    val bank = bankNameOf(cheque.categoryId.orEmpty())
    val need = cheque.targetRial + open
        .filter {
            it.id != cheque.id && it.categoryId == cheque.categoryId &&
                compareValuesBy(it, cheque, { c -> c.startsOn }, { c -> c.createdAt }) < 0
        }
        .sumOf { it.targetRial }
    val account = accounts.firstOrNull { it.bank == cheque.categoryId && it.anchored }
        ?: return "موجودی حساب $bank رو نمی‌دونیم؛ مطمئن شو ${faCompact(tomanOf(need), dec = 3)} تومان توش هست."
    val balanceRial = Math.round(account.balance * 10.0)
    if (balanceRial >= need) return null
    val earlier = if (need > cheque.targetRial) "با چک‌های زودتر همین حساب، " else ""
    return "تو حساب $bank ${faCompact(tomanOf(balanceRial))} تومان هست؛ " +
        "$earlier${faCompact(tomanOf(need - balanceRial), dec = 3)} کمه."
}

// ─────────────────────────── on home ───────────────────────────

/** The cheque worth home's attention card: inside the window, one the account cannot cover first. */
fun pressingCheque(open: List<Goal>, accounts: List<BankAccount>, today: Long, daysBefore: Int): Goal? {
    val near = open.filter { it.startsOn - today <= daysBefore.coerceAtLeast(0) }
    return near.firstOrNull { chequeWarningFa(it, open, accounts, today, daysBefore) != null } ?: near.firstOrNull()
}

/** «سررسید چک «اجاره» فرداست.» and, when it applies, the warning after it — home leaves out the why. */
fun chequeInsight(cheque: Goal, open: List<Goal>, accounts: List<BankAccount>, today: Long, daysBefore: Int): Insight {
    val warning = chequeWarningFa(cheque, open, accounts, today, daysBefore)
    return Insight(
        text = "${chequeReminderTitle(cheque, today)}." + warning?.let { " $it" }.orEmpty(),
        why = "چکی که خودت ثبت کردی: ${chequeLineFa(cheque)}، ${faDate(cheque.startsOn)}.",
        refs = emptyList(),
        tone = Insight.Tone.ATTENTION,
    )
}

// ─────────────────────────── the reminder ───────────────────────────

/**
 * Which cheques are worth a reminder now, and what to remember having said — [installmentNews]'s
 * arrangement, on the same window and waking hours: once per date, and only a date still to come.
 * Cheque ids and plan ids are both [uuid7]s, so the marks share `installmentMarks` without meeting.
 *
 * ponytail: one reminder per cheque. A balance that drops after the reminder said it was covered
 * is said on the card, not by a second note.
 */
fun chequeNews(open: List<Goal>, daysBefore: Int, said: Map<String, Long>, now: Long): Pair<List<Goal>, Map<String, Long>> {
    val today = tehranDay(now)
    val awake = remindAwake(now)
    val due = mutableListOf<Goal>()
    val marks = HashMap<String, Long>()
    for (cheque in open) {
        val day = cheque.startsOn
        if (daysBefore >= 0 && awake && day >= today && day - today <= daysBefore && said[cheque.id] != day) {
            due += cheque
            marks[cheque.id] = day
        } else {
            said[cheque.id]?.let { marks[cheque.id] = it }
        }
    }
    return due to marks
}

/** «سررسید چک «اجاره» فرداست» — the note's title, and home's sentence. */
fun chequeReminderTitle(cheque: Goal, today: Long): String =
    "سررسید ${chequeFa(cheque)} ${installmentWhenFa(cheque.startsOn - today)}"

/** «۵۰ میلیون تومان از بانک ملت» — the cheque in one line. */
fun chequeLineFa(cheque: Goal): String =
    "${faCompact(tomanOf(cheque.targetRial), dec = 3)} تومان از ${bankNameOf(cheque.categoryId.orEmpty())}"

/** The warning when there is one; otherwise how much, from where, and when. */
fun chequeReminderBody(cheque: Goal, open: List<Goal>, accounts: List<BankAccount>, today: Long, daysBefore: Int): String =
    chequeWarningFa(cheque, open, accounts, today, daysBefore) ?: "${chequeLineFa(cheque)} • ${faDate(cheque.startsOn)}"

// ─────────────────────────── the date she types ───────────────────────────

/**
 * The date as written on the cheque, typed as its eight digits — «۱۴۰۵۰۹۱۵», which the field draws as
 * «۱۴۰۵/۰۹/۱۵» — as a Tehran day, or null. Anything but digits is ignored. A day past its month is
 * refused, never clamped: «۳۱ مهر» is a typo, not the 30th.
 */
fun parseChequeDate(text: String): Long? {
    val digits = clockDigits(text)
    if (digits.length != 8) return null
    val year = digits.take(4).toInt()
    val month = digits.substring(4, 6).toInt()
    val day = digits.takeLast(2).toInt()
    if (year !in 1300..1499 || month !in 1..12 || day !in 1..jalaliMonthLength(year, month)) return null
    return jalaliDay(year, month, day)
}

/** A stored date back in the field, as she would have typed it: «14050915». */
fun chequeDateDigits(day: Long): String = clockDigits(jalaliOf(day).toString())
