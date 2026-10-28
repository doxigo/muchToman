package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ledger's word search. The filter is the one thing standing between «I know I paid this»
 * and a claim that the money is not there — so what it treats as the same word (three digit
 * sets, ZWNJ, arabic ي/ك, letter case) is pinned here, where a regression costs a red line
 * instead of a missing transaction.
 */
class LedgerSearchTest {

    private var n = 0

    private fun entry(
        merchant: String = "",
        signed: Long? = -2_500_000L,
        category: String = "خوراکی",
        note: String = "",
        bank: String = "SAMAN",
        transfer: Boolean = false,
        sourceKind: String = "sms",
        ownerName: String = "",
        refNo: String = "",
        accountId: String = bank,
        ownerMemberId: String = "",
        ownerAvatar: String = "",
    ): LedgerEntry {
        n++
        val ref = "s:%04d:0".format(n)
        return LedgerEntry(
            txn = Txn(
                ref = ref, srcHash = ref, seq = 0, at = 1_700_000_000_000L, day = 20_000L,
                bank = bank, accountId = accountId,
                direction = signed?.let { if (it > 0) "in" else "out" },
                amountRial = signed?.let { kotlin.math.abs(it) }, signedRial = signed,
                balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
                merchant = merchant, merchantNorm = "", refNo = refNo, printedAt = "",
                channel = "unknown", unitPrinted = "none", inferred = false,
                parserVer = PARSER_VERSION, sourceKind = sourceKind,
            ),
            categoryId = "cat_x", categoryFa = category, confidence = 95,
            needsReview = false, duplicate = false, transfer = transfer,
            ownerMemberId = ownerMemberId,
            ownerName = ownerName,
            ownerAvatar = ownerAvatar,
            note = note,
        )
    }

    // ---- the folding itself ----

    @Test
    fun `all three digit sets fold to the same ascii`() {
        assertEquals("250", searchFold("250"))
        assertEquals("250", searchFold("۲۵۰"))
        assertEquals("250", searchFold("٢٥٠"))
        assertEquals("0123456789", searchFold("۰۱۲۳۴۵۶۷۸۹"))
    }

    @Test
    fun `zwnj and spaces are not part of a word`() {
        // «اسنپ‌فود», «اسنپ فود» and «اسنپفود» are one merchant three ways.
        assertEquals(searchFold("اسنپ‌فود"), searchFold("اسنپ فود"))
        assertEquals(searchFold("اسنپ‌فود"), searchFold("اسنپفود"))
    }

    @Test
    fun `arabic-spelled persian letters and latin case fold away`() {
        // A bank's gateway spells with ي/ك; her keyboard makes ی/ک.
        assertEquals(searchFold("علي"), searchFold("علی"))
        assertEquals(searchFold("كافه"), searchFold("کافه"))
        assertEquals(searchFold("SNAPP"), searchFold("snapp"))
    }

    // ---- what a query matches ----

    @Test
    fun `a blank query narrows nothing`() {
        val row = entry(merchant = "کافه دنج")
        assertTrue(matchesLedgerSearch(row, ""))
        assertTrue(matchesLedgerSearch(row, "   "))
    }

    @Test
    fun `the merchant answers, however the zwnj fell`() {
        val row = entry(merchant = "اسنپ‌فود")
        assertTrue(matchesLedgerSearch(row, "اسنپ فود"))
        assertTrue(matchesLedgerSearch(row, "اسنپفود"))
        assertTrue(matchesLedgerSearch(row, "فود"))
        assertFalse(matchesLedgerSearch(row, "دیجی"))
    }

    @Test
    fun `a latin merchant answers in either case`() {
        val row = entry(merchant = "Snapp")
        assertTrue(matchesLedgerSearch(row, "snapp"))
        assertTrue(matchesLedgerSearch(row, "SNAPP"))
    }

    @Test
    fun `a merchantless row answers to the bank name it shows`() {
        // The row's title falls back to the bank, so the search must find what the eye reads.
        val row = entry(merchant = "", bank = "SAMAN")
        assertTrue(matchesLedgerSearch(row, "سامان"))
        assertFalse(matchesLedgerSearch(row, "ملت"))
    }

    @Test
    fun `the category line answers, transfer override included`() {
        assertTrue(matchesLedgerSearch(entry(category = "خوراکی"), "خورا"))
        // A transfer row prints «انتقال بین حساب‌ها» whatever it was filed as, and matches
        // what it prints rather than what it hides.
        val transfer = entry(category = "خوراکی", transfer = true)
        assertTrue(matchesLedgerSearch(transfer, "انتقال"))
        assertFalse(matchesLedgerSearch(transfer, "خوراکی"))
    }

    @Test
    fun `her note answers, digits in either script`() {
        val row = entry(note = "قسط ۱۲ لپ‌تاپ")
        assertTrue(matchesLedgerSearch(row, "قسط"))
        assertTrue(matchesLedgerSearch(row, "12"))       // ascii finds the persian ۱۲
        assertTrue(matchesLedgerSearch(row, "لپ تاپ"))   // space finds the ZWNJ
        val ascii = entry(note = "قسط 12 لپ‌تاپ")
        assertTrue(matchesLedgerSearch(ascii, "۱۲"))     // and persian finds the ascii 12
    }

    @Test
    fun `the amount answers by its digits, typed on any keyboard`() {
        // ۲۵۰ هزار تومان = 2,500,000 ریال. The rial digits contain the toman digits,
        // so either way she thinks of the figure lands.
        val row = entry(merchant = "کافه دنج", signed = -2_500_000L)
        assertTrue(matchesLedgerSearch(row, "250"))
        assertTrue(matchesLedgerSearch(row, "۲۵۰"))
        assertTrue(matchesLedgerSearch(row, "٢٥٠"))
        assertTrue(matchesLedgerSearch(row, "250000"))   // the toman figure
        assertTrue(matchesLedgerSearch(row, "2500000"))  // the rial figure
        assertFalse(matchesLedgerSearch(row, "999"))
    }

    @Test
    fun `a wordless miss does not fall through to the amount`() {
        // «کافه» has no digits: a row it matches must match by its words, not by everything
        // having an amount.
        val row = entry(merchant = "داروخانه", signed = -2_500_000L)
        assertFalse(matchesLedgerSearch(row, "کافه"))
    }

    @Test
    fun `a row with no amount neither crashes nor matches digits`() {
        // A stated balance carries no movement; digits cannot find it, words still can.
        val row = entry(merchant = "کافه دنج", signed = null)
        assertFalse(matchesLedgerSearch(row, "250"))
        assertTrue(matchesLedgerSearch(row, "کافه"))
    }

    @Test
    fun `matching is the same fold on both sides`() {
        // A query written with arabic letters and persian digits finds a row written the
        // other way around — the fold is one function, applied twice.
        val row = entry(merchant = "كافه 24")
        assertTrue(matchesLedgerSearch(row, "کافه ۲۴"))
    }

    @Test
    fun `the bank answers even when a merchant took the title`() {
        val row = entry(merchant = "کافه دنج", bank = "SAMAN")
        assertTrue(matchesLedgerSearch(row, "سامان"))
        assertFalse(matchesLedgerSearch(row, "ملت"))
        // A hand-entered row has no bank behind it: its "MANUAL" placeholder must not answer
        // to whatever name the enum falls back to.
        val manual = entry(merchant = "کافه دنج", bank = "MANUAL", sourceKind = "manual")
        assertFalse(matchesLedgerSearch(manual, bankNameOf("MANUAL")))
    }

    @Test
    fun `the owner answers, because the row prints who it belongs to`() {
        val row = entry(merchant = "کافه دنج", ownerName = "مریم")
        assertTrue(matchesLedgerSearch(row, "مریم"))
        assertFalse(matchesLedgerSearch(row, "سهیل"))
        // No owner printed, nothing to answer — and never a crash on the blank.
        assertFalse(matchesLedgerSearch(entry(merchant = "کافه دنج"), "مریم"))
    }

    @Test
    fun `the reference number answers, typed on any keyboard`() {
        val row = entry(merchant = "کافه دنج", refNo = "784512")
        assertTrue(matchesLedgerSearch(row, "784512"))
        assertTrue(matchesLedgerSearch(row, "۷۸۴۵۱۲"))
        assertFalse(matchesLedgerSearch(row, "999999"))
    }

    // ---- what دفتر lists ----

    @Test
    fun `a split row is listed as its parts, each narrowed on its own`() {
        val row = entry(merchant = "کافه دنج", signed = -60_000_000L).copy(
            split = listOf(SplitPart("cat_smokes", "دخانیات", 53_500_000L), SplitPart("cat_cafe", "کافی‌شاپ", 6_500_000L)),
        )
        val all = ledgerRows(listOf(row), LedgerLens.ALL, emptyList(), "")
        assertEquals(
            listOf("cat_smokes" to -53_500_000L, "cat_cafe" to -6_500_000L),
            all.map { it.categoryId to it.txn.signedRial },
        )
        // Both open the one transaction behind them.
        assertEquals(listOf(row.txn.ref, row.txn.ref), all.map { it.txn.ref })
        assertEquals(listOf("cat_cafe"), ledgerRows(listOf(row), LedgerLens.EXPENSE, listOf("cat_cafe"), "").map { it.categoryId })
        assertEquals(listOf("cat_smokes"), ledgerRows(listOf(row), LedgerLens.ALL, emptyList(), "دخانیات").map { it.categoryId })
        assertTrue(ledgerRows(listOf(row), LedgerLens.INCOME, emptyList(), "").isEmpty())
    }

    // ---- whose, and which account ----

    @Test
    fun `a member and an account narrow together with the lens and the words`() {
        val mine = entry(merchant = "نانوایی", ownerMemberId = "me", ownerName = "سهیل")
        val second = entry(merchant = "کافه", accountId = "MELLAT:5915", bank = "MELLAT", ownerMemberId = "me", ownerName = "سهیل")
        val salary = entry(merchant = "حقوق", signed = 900_000_000L, ownerMemberId = "me", ownerName = "سهیل")
        val hers = entry(merchant = "نانوایی", accountId = "family:m2:SAMAN", ownerMemberId = "m2", ownerName = "مریم")
        val rows = listOf(mine, second, salary, hers)
        fun refs(lens: LedgerLens = LedgerLens.ALL, query: String = "", member: String = "", account: String = "") =
            ledgerRows(rows, lens, emptyList(), query, member, account).map { it.txn.ref }

        assertEquals(rows.map { it.txn.ref }, refs())
        assertEquals(listOf(hers.txn.ref), refs(member = "m2"))
        assertEquals(listOf(mine.txn.ref, salary.txn.ref), refs(account = "SAMAN"))
        assertEquals(listOf(mine.txn.ref), refs(LedgerLens.EXPENSE, member = "me", account = "SAMAN"))
        assertEquals(listOf(mine.txn.ref), refs(query = "نانوایی", member = "me"))
        // Her account and his name: nobody's rows, which is what the empty answer is for.
        assertTrue(refs(member = "m2", account = "SAMAN").isEmpty())
    }

    @Test
    fun `members are offered only where there is more than one, hers first`() {
        assertTrue(memberPicks(listOf(entry(), entry()), "").isEmpty())
        // A household where only she has rows here is no choice either.
        assertTrue(memberPicks(listOf(entry(ownerMemberId = "me", ownerName = "سهیل")), "me").isEmpty())
        val picks = memberPicks(
            listOf(
                entry(ownerMemberId = "m2", ownerName = "مریم", ownerAvatar = "👩"),
                entry(ownerMemberId = "me", ownerName = "سهیل"),
                entry(ownerMemberId = "m2", ownerName = "مریم", ownerAvatar = "👩"),
            ),
            "me",
        )
        assertEquals(listOf("me" to "سهیل", "m2" to "مریم"), picks.map { it.id to it.label })
        assertEquals("👩", picks[1].avatar)
    }

    @Test
    fun `accounts are told apart the way the bank sheet tells them`() {
        // One account is no choice; a hand-entered row on none is no account.
        assertTrue(accountPicks(listOf(entry(), entry(bank = "MANUAL", sourceKind = "manual")), emptyMap()).isEmpty())

        val picks = accountPicks(
            listOf(
                entry(bank = "MELLAT", accountId = "MELLAT"),
                entry(bank = "MELLAT", accountId = "MELLAT:5915"),
                entry(bank = "SAMAN", accountId = "SAMAN"),
                entry(bank = "MANUAL", accountId = "SAMAN", sourceKind = "manual"),
                entry(bank = "MELLAT", accountId = "family:m2:MELLAT", ownerMemberId = "m2", ownerName = "مریم"),
            ),
            // The first account keeps the bank's name as its key; its number lives on the sheet.
            mapOf("MELLAT" to "4007", "SAMAN" to "1111"),
        )
        assertEquals(
            listOf(
                // Alone at its bank, it needs no number.
                "SAMAN" to "بانک سامان",
                "MELLAT" to "بانک ملت، حساب ۴۰۰۷",
                "MELLAT:5915" to "بانک ملت، حساب ۵۹۱۵",
                // Somebody else's, after hers and named for whose it is.
                "family:m2:MELLAT" to "بانک ملت مریم",
            ),
            picks.map { it.id to it.label },
        )
        assertEquals(listOf("SAMAN", "MELLAT", "MELLAT", "MELLAT"), picks.map { it.bank })
    }

    @Test
    fun `an empty narrowing names whose and which account`() {
        assertEquals("از مریم چیزی اینجا نیست", filterEmptyFa("مریم", null))
        assertEquals("از بانک ملت چیزی اینجا نیست", filterEmptyFa(null, "بانک ملت"))
        assertEquals("از مریم توی بانک ملت چیزی اینجا نیست", filterEmptyFa("مریم", "بانک ملت"))
        assertEquals("با این فیلتر چیزی اینجا نیست", filterEmptyFa(null, null))
    }
}
