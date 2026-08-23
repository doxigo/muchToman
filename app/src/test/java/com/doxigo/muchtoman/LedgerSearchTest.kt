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
    ): LedgerEntry {
        n++
        val ref = "s:%04d:0".format(n)
        return LedgerEntry(
            txn = Txn(
                ref = ref, srcHash = ref, seq = 0, at = 1_700_000_000_000L, day = 20_000L,
                bank = bank, accountId = bank,
                direction = signed?.let { if (it > 0) "in" else "out" },
                amountRial = signed?.let { kotlin.math.abs(it) }, signedRial = signed,
                balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
                merchant = merchant, merchantNorm = "", refNo = "", printedAt = "",
                channel = "unknown", unitPrinted = "none", inferred = false,
                parserVer = PARSER_VERSION, sourceKind = sourceKind,
            ),
            categoryId = "cat_x", categoryFa = category, confidence = 95,
            needsReview = false, duplicate = false, transfer = transfer,
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
}
