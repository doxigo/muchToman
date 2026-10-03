package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spreadsheet export (Csv.kt): what Excel needs to open it right, the guard that keeps a
 * merchant's name from running as a formula, and دفتر's rows — split, transfer, family — as
 * lines. Mirrored by `pwa/test/csv.test.ts`, which pins the same file byte for byte.
 */
class CsvTest {

    private var n = 0
    private val day = jalaliDay(1405, 7, 11)
    private val afternoon = tehranDayStart(day) + (14 * 60 + 3) * 60_000L

    private fun entry(
        merchant: String = "",
        signed: Long? = -2_500_000L,
        at: Long = afternoon,
        bank: String = "SAMAN",
        sourceKind: String = "sms",
        note: String = "",
        ownerName: String = "",
        transfer: Boolean = false,
        duplicate: Boolean = false,
        split: List<SplitPart> = emptyList(),
    ): LedgerEntry {
        n++
        val ref = "s:%04d:0".format(n)
        return LedgerEntry(
            txn = Txn(
                ref = ref, srcHash = ref, seq = 0, at = at, day = day,
                bank = bank, accountId = bank,
                direction = signed?.let { if (it > 0) "in" else "out" },
                amountRial = signed?.let { kotlin.math.abs(it) }, signedRial = signed,
                balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
                merchant = merchant, merchantNorm = "", refNo = "", printedAt = "",
                channel = "unknown", unitPrinted = "none", inferred = false,
                parserVer = PARSER_VERSION, sourceKind = sourceKind,
            ),
            categoryId = "cat_x", categoryFa = "خوراکی", confidence = 95,
            needsReview = false, duplicate = duplicate, transfer = transfer,
            ownerName = ownerName, note = note, split = split,
        )
    }

    @Test
    fun `the ledger as دفتر lists it, one line per row`() {
        val csv = ledgerCsv(
            listOf(
                entry(
                    merchant = "کافه دنج", signed = -60_000_000L,
                    split = listOf(SplitPart("cat_smokes", "دخانیات", 53_500_000L), SplitPart("cat_cafe", "کافی‌شاپ", 6_500_000L)),
                ),
                entry(merchant = "کافه دنج", duplicate = true),
                entry(signed = -10_000_000L, transfer = true),
                entry(merchant = "=HYPERLINK(\"x\")", signed = 12_345L, note = "قسط, \"دوم\"", ownerName = "مریم"),
                entry(signed = -12_340L, at = tehranDayStart(day), bank = "MANUAL", sourceKind = "manual"),
                entry(signed = null),
            ),
        )
        assertEquals(
            "\uFEFF" + listOf(
                "تاریخ,ساعت,مبلغ (ریال),مبلغ (تومان),بانک,طرف حساب,دسته,یادداشت,نوع,صاحب تراکنش",
                "1405/07/11,14:03,-53500000,-5350000,بانک سامان,کافه دنج,دخانیات,,خرج,",
                "1405/07/11,14:03,-6500000,-650000,بانک سامان,کافه دنج,کافی‌شاپ,,خرج,",
                "1405/07/11,14:03,-10000000,-1000000,بانک سامان,,انتقال بین حساب‌ها,,انتقال,",
                "1405/07/11,14:03,12345,1234.5,بانک سامان,\"'=HYPERLINK(\"\"x\"\")\",خوراکی,\"قسط, \"\"دوم\"\"\",درآمد,مریم",
                // Entered from a bare date: no minute to print.
                "1405/07/11,,-12340,-1234,ورود دستی,,خوراکی,,خرج,",
                "1405/07/11,14:03,,,بانک سامان,,خوراکی,,مانده,",
            ).joinToString("") { "$it\r\n" },
            csv,
        )
        assertTrue(csv.startsWith("\uFEFF"))
        assertFalse(csv.replace("\r\n", "").contains('\n'))
    }

    @Test
    fun `quoting is RFC 4180`() {
        assertEquals("plain", csvField("plain"))
        assertEquals("\"a,b\"", csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", csvField("say \"hi\""))
        assertEquals("\"two\nlines\"", csvField("two\nlines"))
        assertEquals("\"cr\rhere\"", csvField("cr\rhere"))
    }

    @Test
    fun `a text cell that would run as a formula stays text`() {
        for (s in listOf("=1+1", "+989120000000", "-2", "@SUM(A1)", "\tx")) assertEquals("'$s", csvText(s))
        assertEquals("\"'\rx\"", csvText("\rx"))
        assertEquals("کافه -دنج", csvText("کافه -دنج"))
        assertEquals("", csvText(""))
    }

    @Test
    fun `toman is the rial divided by ten, exactly`() {
        assertEquals("1234.5", csvToman(12_345L))
        assertEquals("1234", csvToman(12_340L))
        assertEquals("-1234.5", csvToman(-12_345L))
        assertEquals("-0.5", csvToman(-5L))
        assertEquals("0", csvToman(0L))
        assertEquals("999999999999.9", csvToman(9_999_999_999_999L))
    }

    @Test
    fun `the file is named for today in Tehran, Jalali, Latin digits`() {
        assertEquals("muchtoman-1405-07-11.csv", ledgerCsvName(afternoon))
    }
}
