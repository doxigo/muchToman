package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The market-watch body is `@`-separated into five sections and only the third is read. Rows
 * are `;`-separated, columns `,`-separated, and the columns that matter are:
 *
 *     0 ins_code  2 l18  3 l30  6 pc  7 pl  13 py
 *
 * Padding here keeps each row at the real 23-column width so the indices under test are the
 * indices in production.
 */
private fun row(
    insCode: String,
    l18: String,
    l30: String,
    pc: String,
    pl: String = "0",
    py: String = "0",
    yval: String = "300", // سهام عادی unless a test says otherwise
    extraColumns: Int = 0,
): String {
    val c = MutableList(23) { "0" }
    c[0] = insCode
    c[1] = "IRO1TEST0001"
    c[2] = l18
    c[3] = l30
    c[6] = pc
    c[7] = pl
    c[13] = py
    c[22] = yval
    // The 26-column variant only ever appends, so the read indices are unmoved.
    repeat(extraColumns) { c.add("0") }
    return c.joinToString(",")
}

private fun body(vararg rows: String) =
    "unused@marketState@${rows.joinToString(";")}@bestLimits@12345"

class TseParseTest {

    @Test
    fun `quotes are Rial and become Toman`() {
        val snap = parseMarketWatch(body(row("1", "شتران", "پالايش نفت تهران", "45290")), 99L)

        assertEquals(4529.0, snap.toman.getValue("tse_1"), 0.001)
        assertEquals(99L, snap.updatedAt)
    }

    @Test
    fun `closing price wins, then last trade, then yesterday`() {
        val snap = parseMarketWatch(
            body(
                row("1", "الف", "شرکت الف", pc = "1000", pl = "2000", py = "3000"),
                // Not traded today: قیمت پایانی is zero and the last trade stands in.
                row("2", "ب", "شرکت ب", pc = "0", pl = "2000", py = "3000"),
                // Suspended: only yesterday's close is left, and it is still what it is worth.
                row("3", "ج", "شرکت ج", pc = "0", pl = "0", py = "3000"),
            ),
            0L,
        )

        assertEquals(100.0, snap.toman.getValue("tse_1"), 0.001)
        assertEquals(200.0, snap.toman.getValue("tse_2"), 0.001)
        assertEquals(300.0, snap.toman.getValue("tse_3"), 0.001)
    }

    @Test
    fun `a row with no price at all is left out rather than priced at zero`() {
        val snap = parseMarketWatch(
            body(
                row("1", "الف", "شرکت الف", pc = "1000"),
                row("2", "ب", "شرکت ب", pc = "0", pl = "0", py = "0"),
            ),
            0L,
        )

        assertEquals(setOf("tse_1"), snap.toman.keys)
        assertNull(snap.toman["tse_2"])
        assertEquals(1, snap.stocks.size)
    }

    @Test
    fun `the 26-column variant parses from the same indices`() {
        val snap = parseMarketWatch(
            body(row("1", "شتران", "پالايش نفت تهران", "45290", extraColumns = 3)),
            0L,
        )

        assertEquals(4529.0, snap.toman.getValue("tse_1"), 0.001)
        assertEquals("شتران", snap.stocks.single().symbol)
    }

    @Test
    fun `Arabic letters are folded so a correctly typed name still finds the symbol`() {
        val snap = parseMarketWatch(body(row("1", "شتران", "پالايش نفت تهران", "45290")), 0L)
        val type = snap.stocks.single().toAssetType()

        // TSETMC writes ي; her keyboard produces ی. Both have to find it.
        assertTrue(matchesSearch(type, "پالایش"))
        assertTrue(matchesSearch(type, "پالايش"))
        assertTrue(matchesSearch(type, "شتران"))
    }

    @Test
    fun `a share is whole units, named by its نماد, and priced under STOCK`() {
        val snap = parseMarketWatch(body(row("7", "فولاد", "فولاد مباركه اصفهان", "12000")), 0L)
        val type = snap.stocks.single().toAssetType()

        assertEquals("tse_7", type.id)
        assertEquals("فولاد", type.fa)
        assertEquals(Kind.STOCK, type.kind)
        assertEquals("سهم", type.unitFa)
        assertEquals(0, type.dec)
    }

    @Test
    fun `stock prices reach the total through the same rates map as everything else`() {
        val snap = parseMarketWatch(body(row("7", "فولاد", "فولاد مباركه اصفهان", "12000")), 0L)
        val rates = effectiveRates(Rates(1L, mapOf("usd" to 100_000.0)), emptyMap(), snap)

        val totals = computeTotals(
            listOf(Holding("tse_7", 500.0), Holding("usd", 2.0)),
            rates,
        )

        // 500 shares at 1,200 Toman, plus two dollars.
        assertEquals(800_000.0, totals.toman, 0.001)
        assertTrue(totals.missing.isEmpty())
    }

    @Test
    fun `a hand-typed override still beats the fetched price`() {
        val snap = parseMarketWatch(body(row("7", "فولاد", "فولاد مباركه", "12000")), 0L)
        val rates = effectiveRates(Rates(), mapOf("tse_7" to 5_000.0), snap)

        assertEquals(5_000.0, rates.getValue("tse_7"), 0.001)
    }

    @Test
    fun `a truncated body raises rather than reporting an empty market`() {
        // Half a response must not read downstream as "every share is worth nothing".
        val e = runCatching { parseMarketWatch("unused@marketState", 0L) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)

        val allUnpriced = runCatching {
            parseMarketWatch(body(row("1", "الف", "شرکت الف", pc = "0", pl = "0", py = "0")), 0L)
        }.exceptionOrNull()
        assertTrue(allUnpriced is IllegalStateException)
    }

    @Test
    fun `derivatives are left out, spot instruments are kept`() {
        val snap = parseMarketWatch(
            body(
                row("1", "فولاد", "فولاد مباركه", "1000", yval = "300"), // سهام عادی
                row("2", "اهرم", "صندوق اهرم", "1000", yval = "305"), // صندوق اهرمی — spot units
                row("3", "پارند", "صندوق پارند", "1000", yval = "305"), // درآمد ثابت
                row("4", "وبملتح", "حق تقدم بانک ملت", "1000", yval = "400"), // حق تقدم
                row("5", "اخزا", "اسناد خزانه", "1000", yval = "306"), // اوراق
                row("6", "ضخود7001", "اختیار خرید خودرو", "1000", yval = "311"), // call
                row("7", "طخود7001", "اختیار فروش خودرو", "1000", yval = "312"), // put
                row("8", "جوسط0106", "آتی زعفران", "1000", yval = "304"), // آتی
                row("9", "ضهرم1001", "اختیار فولاد هرمزگان", "1000", yval = "321"),
            ),
            0L,
        )

        // A leveraged FUND is still a spot holding: the leverage is inside it, the units are
        // owned outright. A leveraged CONTRACT is not, and its premium is not a value.
        assertEquals(
            listOf("فولاد", "اهرم", "پارند", "وبملتح", "اخزا"),
            snap.stocks.map { it.symbol },
        )
        for (derivative in listOf("tse_6", "tse_7", "tse_8", "tse_9")) {
            assertNull(snap.toman[derivative])
        }
    }

    @Test
    fun `the gold funds are kept — they are type 380, not 305`() {
        // Every صندوق طلا on the exchange is a commodity-backed fund, which TSETMC types 380
        // rather than grouping with the other ETFs under 305. An allow-list built from the
        // ordinary fund code alone silently dropped all 49 of them — طلا، عیار، کهربا، مثقال
        // — which is to say the single category this app most needs to price.
        val snap = parseMarketWatch(
            body(
                row("1", "طلا", "صندوق س. طلای لوتوس", "1300210", yval = "380"),
                row("2", "عیار", "صندوق س. طلای عیار مفید", "509260", yval = "380"),
                row("3", "کهربا", "صندوق س. طلای کهربا", "176270", yval = "380"),
            ),
            0L,
        )

        assertEquals(listOf("طلا", "عیار", "کهربا"), snap.stocks.map { it.symbol })
        assertEquals(130_021.0, snap.toman.getValue("tse_1"), 0.001)
    }

    @Test
    fun `an unknown instrument type is left out rather than guessed at`() {
        // Option codes multiply — one per underlying — so anything unrecognised stays out.
        val snap = parseMarketWatch(
            body(
                row("1", "فولاد", "فولاد مباركه", "1000", yval = "300"),
                row("2", "ضجدید", "اختیار تازه‌فهرست‌شده", "1000", yval = "399"),
            ),
            0L,
        )

        assertEquals(setOf("tse_1"), snap.toman.keys)
    }

    @Test
    fun `malformed rows are skipped without losing the good ones`() {
        val snap = parseMarketWatch(
            body(
                "short,row,nowhere,near,wide,enough",
                row("2", "ب", "شرکت ب", "1000"),
                "",
            ),
            0L,
        )

        assertEquals(setOf("tse_2"), snap.toman.keys)
    }
}
