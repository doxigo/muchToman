package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The money path only. If these pass, a wrong total on screen is a display bug, not a
 * maths bug. Run with: ./gradlew test
 */
class MoneyTest {

    @Test
    fun `parses persian digits`() {
        assertEquals(3000.0, parseAmount("۳۰۰۰")!!, 0.0)
        assertEquals(3000.0, parseAmount("3000")!!, 0.0)
        assertEquals(3000.0, parseAmount("٣٠٠٠")!!, 0.0)  // Arabic-Indic
    }

    @Test
    fun `ignores thousands separators and spaces`() {
        assertEquals(3000.0, parseAmount("۳٬۰۰۰")!!, 0.0)
        assertEquals(3000.0, parseAmount("3,000")!!, 0.0)
        assertEquals(1200.0, parseAmount(" ۱ ۲۰۰ ")!!, 0.0)
    }

    @Test
    fun `parses both decimal separators`() {
        assertEquals(0.5, parseAmount("۰٫۵")!!, 1e-9)
        assertEquals(0.5, parseAmount("0.5")!!, 1e-9)
    }

    @Test
    fun `rejects nonsense instead of guessing`() {
        assertNull(parseAmount("abc"))
        assertNull(parseAmount("۱۲۳x"))
        assertNull(parseAmount(""))
        assertNull(parseAmount("-۵"))   // negative holdings are not a thing
    }

    @Test
    fun `total multiplies each holding by its rate`() {
        val holdings = listOf(Holding("usd", 3000.0), Holding("usdt", 2000.0))
        val rates = mapOf("usd" to 118_500.0, "usdt" to 118_000.0)
        assertEquals(3000 * 118_500.0 + 2000 * 118_000.0, computeTotals(holdings, rates).toman, 0.01)
    }

    @Test
    fun `a set-aside holding is out of the total and not reported missing`() {
        val holdings = listOf(
            Holding("usd", 100.0, excluded = true),
            Holding(TOMAN_ID, 5_000.0),
            Holding("btc", 1.0, excluded = true),   // excluded AND unpriced: still not "missing"
        )
        val t = computeTotals(holdings, mapOf("usd" to 118_500.0, TOMAN_ID to 1.0))
        assertEquals(5_000.0, t.toman, 0.0)
        assertTrue(t.missing.isEmpty())
    }

    @Test
    fun `assets with no rate are excluded and reported, never counted as zero`() {
        val holdings = listOf(Holding("usd", 100.0), Holding("btc", 0.5))
        val t = computeTotals(holdings, mapOf("usd" to 118_500.0))
        assertEquals(11_850_000.0, t.toman, 0.01)
        assertEquals(listOf("btc"), t.missing)
    }

    @Test
    fun `a zero or negative rate counts as missing, not as free money`() {
        val t = computeTotals(listOf(Holding("usd", 100.0)), mapOf("usd" to 0.0))
        assertEquals(0.0, t.toman, 0.0)
        assertEquals(listOf("usd"), t.missing)
    }

    @Test
    fun `toman counts at face value even with no rates at all`() {
        // Cash in the bank must survive a dead network and an empty rates payload.
        val rates = effectiveRates(Rates(), emptyMap())
        val t = computeTotals(listOf(Holding(TOMAN_ID, 50_000_000.0)), rates)
        assertEquals(50_000_000.0, t.toman, 0.0)
        assertTrue(t.missing.isEmpty())
    }

    @Test
    fun `toman rate cannot be overridden to something other than itself`() {
        val rates = effectiveRates(Rates(1L, mapOf(TOMAN_ID to 7.0)), mapOf(TOMAN_ID to 99.0))
        assertEquals(1.0, rates[TOMAN_ID]!!, 0.0)
    }

    @Test
    fun `a car is worth what she typed, whatever any rate says`() {
        // Nobody quotes her car, so the figure she typed IS the value: a stray "car" price from
        // the network, or a نرخ she once typed against it, must not scale her own valuation.
        val rates = effectiveRates(Rates(1L, mapOf("car" to 7.0)), mapOf("car" to 99.0))
        for (id in listOf("car", "house", "land")) {
            assertEquals(Kind.PROPERTY, resolveType(id, emptyList()).kind)
            val t = computeTotals(listOf(Holding(id, 900_000_000.0)), rates)
            assertEquals(id, 900_000_000.0, t.toman, 0.0)
            assertTrue(t.missing.isEmpty())
        }
    }

    @Test
    fun `toman adds to the rest of the portfolio`() {
        val holdings = listOf(Holding(TOMAN_ID, 50_000_000.0), Holding("usd", 3000.0))
        val rates = effectiveRates(Rates(1L, mapOf("usd" to 187_000.0)), emptyMap())
        assertEquals(50_000_000.0 + 561_000_000.0, computeTotals(holdings, rates).toman, 0.01)
    }

    @Test
    fun `typed digits are grouped so zeroes never have to be counted`() {
        assertEquals("۸۵٬۰۰۰٬۰۰۰", groupDigits("85000000").text)
        assertEquals("۱۲۳", groupDigits("123").text)
        assertEquals("۱٬۲۳۴", groupDigits("1234").text)
        // no trailing separator on an exact multiple of three
        assertEquals("۱۰۰", groupDigits("100").text)
        assertEquals("", groupDigits("").text)
    }

    @Test
    fun `grouping leaves the decimal part alone`() {
        assertEquals("۱٬۲۳۴٫۵۶۷۸", groupDigits("1234.5678").text)
        assertEquals("۰٫۱۵", groupDigits("0.15").text)
    }

    @Test
    fun `grouping accepts digits the persian keyboard produces`() {
        assertEquals("۸۵٬۰۰۰٬۰۰۰", groupDigits("۸۵۰۰۰۰۰۰").text)
    }

    @Test
    fun `caret maps across injected separators in both directions`() {
        val g = groupDigits("85000000") // -> ۸۵٬۰۰۰٬۰۰۰
        assertEquals(0, g.origToDisp[0])
        assertEquals(g.text.length, g.origToDisp[8])          // caret at end stays at end
        assertEquals(8, g.dispToOrig[g.text.length])
        // every position must round-trip back to itself
        for (i in 0..8) assertEquals(i, g.dispToOrig[g.origToDisp[i]])
    }

    @Test
    fun `grouped display still parses back to the same number`() {
        for (raw in listOf("85000000", "1234.5678", "0.15", "۸۵۰۰۰۰۰۰")) {
            assertEquals(parseAmount(raw)!!, parseAmount(groupDigits(raw).text)!!, 1e-9)
        }
    }

    @Test
    fun `compact form never rounds money upward`() {
        // regression: 10,800,000 used to render as "۱۱ میلیون"
        val c = faCompact(10_800_000.0)
        assertTrue("got $c", c.contains("میلیون"))
        assertTrue("got $c", !c.startsWith(faNumber(11.0)))
        assertEquals("${faDecimal(10.8, 1)} میلیون", c)
        // a whole number keeps no stray decimal
        assertEquals("${faNumber(11.0)} میلیون", faCompact(11_000_000.0))
    }

    @Test
    fun `compact form never overstates - it truncates rather than rounds`() {
        // 2.95 billion must not become "۳ میلیارد"
        assertEquals("${faDecimal(2.9, 1)} میلیارد", faCompact(2_950_000_000.0))
        assertEquals("${faDecimal(10.8, 1)} میلیون", faCompact(10_899_999.0))
        assertEquals("${faNumber(11.0)} میلیون", faCompact(11_000_000.0))
        // never larger than the true value, across a spread of magnitudes
        for (v in listOf(1_999_999.0, 2_950_000_000.0, 10_899_999.0, 999_999.0, 45_678.0)) {
            val shown = faCompact(v)
            val unit = when {
                v >= 1_000_000_000 -> 1_000_000_000.0
                v >= 1_000_000 -> 1_000_000.0
                else -> 1_000.0
            }
            val digits = shown.substringBefore(' ')
            assertTrue("$shown overstates $v", parseAmount(digits)!! * unit <= v + 0.001)
        }
    }

    @Test
    fun `binary representation error does not eat a decimal the compact form owes her`() {
        // 1,007,000 over a million is 1.00699999… as a raw double, and truncating that binary
        // hair rendered «۱٫۰۰۶ میلیون» — a thousand Toman she really has. Settled in decimal
        // first, the same BigDecimal move faHeld makes, then cut in the one safe direction.
        assertEquals("۱٫۰۰۷ میلیون", faCompact(1_007_000.0, 3, pad = true))
        assertEquals("${faDecimal(1.007, 3)} میلیون", faCompact(1_007_000.0, 3))
    }

    @Test
    fun `grouped whole figures truncate too - rounding inflated them`() {
        // faNumber used to round: 999.9 became «۱٬۰۰۰», money the account does not hold.
        assertEquals("۹۹۹", faNumber(999.9))
        assertEquals(faNumber(999.0), faNumber(999.999))
        // Truncation, so a real fraction is simply dropped…
        assertEquals(faNumber(2.0), faNumber(2.999999))
        // …while a sub-Rial binary hair under a figure she really has is not eaten.
        assertEquals(faNumber(3.0), faNumber(2.9999999999))
    }

    @Test
    fun `numbers are spelled out in persian`() {
        assertEquals("ده میلیون و هشتصد هزار", faWords(10_800_000))
        assertEquals("صفر", faWords(0))
        assertEquals("یک", faWords(1))
        assertEquals("بیست و یک", faWords(21))
        assertEquals("صد", faWords(100))
        assertEquals("صد و پنج", faWords(105))
        assertEquals("هزار", faWords(1_000))            // not "یک هزار"
        assertEquals("یک میلیون", faWords(1_000_000))   // but یک is kept here
        assertEquals("سه هزار", faWords(3_000))
        assertEquals("پانزده", faWords(15))
        assertEquals("نهصد و نود و نه", faWords(999))
    }

    @Test
    fun `spelled out amounts skip what cannot help`() {
        assertNull(faWordsToman(0.0))
        assertTrue(faWordsToman(10_800_000.0)!!.endsWith("تومان"))
    }

    @Test
    fun `rates are shown full when small and compact when huge`() {
        assertTrue(faRate(187_000.0).contains("۱۸۷"))
        assertTrue(!faRate(187_000.0).contains("هزار"))
        assertTrue(faRate(180_000_000.0).contains("میلیون"))
    }

    @Test
    fun `a held amount sheds decimals it has the digits to spare`() {
        // The row that started this: six decimals of Tether left no line for the rate.
        assertEquals(faDecimal(10_709.13, 2), faHeld(10_709.135681, 6))
        // Never rounded up — she must not read more than she has.
        assertEquals(faDecimal(10_709.99, 2), faHeld(10_709.999, 6))
        // …and never rounded down past what she does have: 10709.13 is not exactly a double.
        assertEquals(faDecimal(10_709.13, 2), faHeld(10_709.13, 6))
        // Under a thousand there is room; under one unit every decimal is the amount.
        assertEquals(faDecimal(12.3456, 4), faHeld(12.345678, 6))
        assertEquals(faDecimal(0.000425, 6), faHeld(0.000425, 6))
        // Never more precision than the asset itself claims.
        assertEquals(faDecimal(1_234.0, 0), faHeld(1_234.0, 0))
        assertEquals(faDecimal(152.375, 3), faHeld(152.375, 3))
    }

    @Test
    fun `an unknown held asset still resolves instead of disappearing`() {
        val t = resolveType("sol", emptyList())
        assertEquals("sol", t.id)
        assertEquals(Kind.CRYPTO, t.kind)
    }

    @Test
    fun `a static id is never shadowed by a coin with the same ticker`() {
        val t = resolveType("nok", listOf(Coin("nok", "Some Coin", icon = "")))
        assertEquals("کرون نروژ", t.fa)
        assertEquals(Kind.FIAT, t.kind)
    }

    @Test
    fun `overrides win over fetched rates`() {
        val merged = effectiveRates(Rates(1L, mapOf("usd" to 100.0)), mapOf("usd" to 130.0))
        assertEquals(130.0, merged["usd"]!!, 0.0)
    }

    @Test
    fun `the hero total carries three decimals without ever rounding up`() {
        assertEquals("${faDecimal(9.643, 3)} میلیارد", faCompact(9_643_999_999.0, 3))
        assertEquals("${faNumber(3.0)} میلیارد", faCompact(3_000_000_000.0, 3))
        assertEquals("${faDecimal(10.8, 3)} میلیون", faCompact(10_800_000.0, 3))
    }

    @Test
    fun `figures on the list hold three decimals unless they are all zeros`() {
        // A fractional figure keeps its full width, so precision does not appear and vanish
        // between one row and the next…
        assertEquals("۵۵۹٫۵۰۰ میلیون", faCompact(559_500_000.0, 3, pad = true))
        assertEquals("۹٫۷۰۰ میلیارد", faCompact(9_700_000_000.0, 3, pad = true))
        // …but one that lands exactly on the unit drops the decimals entirely: the three
        // zeros in "۱۸۰٫۰۰۰ میلیون" carry nothing and only slow the reading down.
        assertEquals("۳ میلیارد", faCompact(3_000_000_000.0, 3, pad = true))
        assertEquals("۱۸۰ میلیون", faCompact(180_000_000.0, 3, pad = true))
        // padding adds zeros; it must never add value
        assertEquals("۹٫۶۴۳ میلیارد", faCompact(9_643_999_999.0, 3, pad = true))
    }

    @Test
    fun `prose keeps dropping the trailing zero`() {
        // "۱۱ میلیون" is how it is said mid-sentence; "۱۱٫۰ میلیون" is not.
        assertEquals("${faNumber(11.0)} میلیون", faCompact(11_000_000.0))
        assertEquals("${faDecimal(9.7, 1)} میلیارد", faCompact(9_700_000_000.0))
    }

    @Test
    fun `big amounts are spoken in persian magnitudes`() {
        assertTrue(faCompact(355_500_000.0).contains("میلیون"))
        assertTrue(faCompact(3_555_000_000.0).contains("میلیارد"))
        assertTrue(faCompact(45_000.0).contains("هزار"))
    }

    @Test
    fun `history keeps one entry per day and never outgrows a year`() {
        var h = emptyMap<Long, Double>()
        h = recordDay(h, 100, 5.0)
        h = recordDay(h, 100, 7.0)          // same day converges to the latest total
        assertEquals(1, h.size)
        assertEquals(7.0, h[100L]!!, 0.0)
        for (d in 0L until 500L) h = recordDay(h, d, d.toDouble())
        assertEquals(HISTORY_KEEP_DAYS, h.size)
        assertNull(h[99L])                  // pruned from the old end
        assertEquals(499.0, h[499L]!!, 0.0)
    }

    @Test
    fun `change is measured against the newest snapshot old enough`() {
        val h = mapOf(65L to 7.0, 70L to 8.0, 100L to 10.0)
        val c = changeOver(h, today = 100, windowDays = 30, current = 12.0)!!
        assertEquals(70L, c.sinceDay)       // newest at or before day 70, not day 65
        assertEquals(4.0, c.delta, 0.0)
        assertEquals(50.0, c.percent!!, 1e-9)
    }

    @Test
    fun `a short history refuses to impersonate a longer window`() {
        // 10 days of data must not be sold as a one-month change.
        val h = mapOf(90L to 8.0, 100L to 10.0)
        assertNull(changeOver(h, today = 100, windowDays = 30, current = 12.0))
    }

    @Test
    fun `a small gap just inside the window is tolerated`() {
        // Window edge is day 70; the app was first opened on day 72. Close enough to answer.
        val h = mapOf(72L to 8.0)
        val c = changeOver(h, today = 100, windowDays = 30, current = 12.0)
        assertEquals(72L, c!!.sinceDay)
        assertEquals(4.0, c.delta, 0.0)
    }

    // ───────────────── real messages, copied verbatim ─────────────────
    // Saman and Blu, from the user's own phone, sender numbers and all. Kept character for
    // character — the Arabic ك and ي, the missing space before ريال, the "انتقال پل" typo —
    // because every one of those is a thing the parser has to survive, and tidying them here
    // would only hide it.

    private val SAMAN_NUM = "0999 992 0000"
    private val BLU_NUM = "0999 998 7641"

    private val SAMAN_IN = """
        بانك سامان
        واريز مبلغ  300,000,000ريال
        به  829-800-1092308-1
        مانده 1,978,750,309
        1405/5/1
        11:26
    """.trimIndent()

    private val SAMAN_OUT = """
        بانك سامان
        برداشت مبلغ 1,500,000,000 انتقال وجه
        از  829-800-1092308-1
        مانده 460,725,309
        1405/5/3
        21:59:27
    """.trimIndent()

    private val BLU_IN = """
        بلو
        واریز پول
        سیدسهیل عزیز، 1,000,000,000 ریال به حساب شما نشست.
        موجودی: 1,863,578,773 ریال
        ۱۹:۵۸
        ۱۴۰۵.۰۵.۰۳
    """.trimIndent()

    private val BLU_OUT = """
        بلو
        برداشت پول
        سیدسهیل عزیز، 25,000,000 ریال از حساب شما پرید.
        موجودی: 1,566,643,023 ریال
        ۱۶:۰۰
        ۱۴۰۵.۰۵.۰۴
    """.trimIndent()

    private val BLU_TRANSFER = """
        بلو
        انتقال پل
         سیدسهیل عزیز، 500,000,000 ریال از حساب شما پرید.
         موجودی: 1,007,033,963 ریال
        ۱۰:۰۴
        ۱۴۰۵.۰۵.۰۵
    """.trimIndent()

    // ───────────────── the stamp the bank printed ─────────────────

    /**
     * «زمان ثبت» is the bank's own stamp, and for three of the banks in the corpus that stamp is
     * two lines rather than one. Reading only the date meant the hour sat visible in «منبع» right
     * under a row that would not say it.
     */
    @Test
    fun `a time on its own line belongs to the date above or below it`() {
        // سامان writes the time under the date, بلو over it, and both are the bank's own stamp.
        assertEquals("1405/5/1 11:26", printedStampIn(SAMAN_IN))
        assertEquals("1405/5/3 21:59:27", printedStampIn(SAMAN_OUT))
        assertEquals("۱۴۰۵.۰۵.۰۵ ۱۰:۰۴", printedStampIn(BLU_TRANSFER))

        // Where the bank set the two together itself, its own separator stands untouched.
        assertEquals("05/08_13:37", printedStampIn("276.800.504939.1\n-30,000,000\n05/08_13:37"))

        // Only whitespace may stand between the two, so a colon from elsewhere in the message is
        // never mistaken for the time of the transaction.
        assertEquals("05/04/27", printedStampIn("ساعات کار: 08:00 تا 14:00\nمبلغ8,444,425\n05/04/27"))

        // A bare hour is not a stamp: most messages print no date at all, and answering «زمان ثبت»
        // with one would have the bank state a time it never stated.
        assertEquals("", printedStampIn("ملت\nبرداشت4,960,000\n11:26"))
    }

    // ───────────────── who sent it ─────────────────

    @Test
    fun `the same sender matches in every form the network writes it`() {
        // Android reports the sender as +98…, 98… or 0… depending on the network and the SIM,
        // and comparing the strings would make those three different banks.
        for (form in listOf(
            "0999 992 0000", "09999920000", "+989999920000", "989999920000",
            "9999920000", "0999-992-0000", "۰۹۹۹۹۹۲۰۰۰۰",
        )) {
            assertEquals("«$form» did not match Saman", Bank.SAMAN, bankOf(form))
        }
        assertEquals(Bank.BLU, bankOf("+989999987641"))
    }

    @Test
    fun `a bank is found from any of the numbers it sends from`() {
        assertEquals(Bank.BLU, bankOf("0999 998 7641"))
        assertEquals(Bank.KHAVARMIANEH, bankOf("20004861")) // خاورمیانه's second shortcode
        assertEquals(Bank.SAMAN, bankOf("+989999920000"))
        assertEquals(Bank.REFAH, bankOf("100031"))
        assertEquals(Bank.REFAH, bankOf("100032"))
        assertEquals(Bank.BLU, bankOf("90000258"))
        assertEquals(Bank.KHAVARMIANEH, bankOf("+9820004861"))
        assertEquals(Bank.BLU, bankOf("+9890000258"))
        assertEquals(Bank.BLU, bankOf("98300087641"))
        assertEquals(Bank.SAMAN, bankOf("+989820000"))
        assertEquals(Bank.SAMAN, bankOf("9820000"))
        assertEquals(Bank.KHAVARMIANEH, bankOf("+989820004860"))
        // Melli sends from a card prefix and from a mobile line, and that line is one digit from
        // Saderat's — the three forms of each fold onto its own key, never onto its neighbour's.
        assertEquals(Bank.MELLI, bankOf("6037"))
        assertEquals(Bank.MELLI, bankOf("09830009417"))
        assertEquals(Bank.MELLI, bankOf("+98 983 000 9417"))
        assertEquals(Bank.MELLI, bankOf("9830009417"))
        assertEquals(Bank.SADERAT, bankOf("09830009419"))
        // A shortcode is exactly itself. Nothing is folded into it and nothing is trimmed off
        // it — one digit out, or a country code bolted on, is a different sender. If a carrier
        // ever does deliver one in an unexpected shape it becomes a suggestion card, which is
        // a better answer than matching loosely and letting a stranger in.
        assertNull(bankOf("2000486"))
        assertNull(bankOf("200048610"))
        assertNull(bankOf("9000025"))
        assertNull(bankOf("+989126271948"))
    }

    @Test
    fun `ayandeh sender suggestions are not trusted as another bank`() {
        assertNull(bankOf("AyandehBank"))
        assertNull(bankOf("+98700745"))
        assertTrue(isIgnoredBankSms("AyandehBank", "مانده 5,000,000 ریال"))
        assertTrue(isIgnoredBankSms("+98700745", "مانده 5,000,000 ریال"))
        assertTrue(isIgnoredBankSms("unknown", "بانک آينده\nمانده 5,000,000 ریال"))
        assertNull(guessBank("بانک آینده\nانتقال به بانک رفاه\nمانده 5,000,000 ریال"))
    }

    @Test
    fun `the last stated balance wins however many messages came before it`() {
        // Her real Saman thread: the final message states مانده 460,725,309 ریال, so the
        // balance is 46,072,530.9 تومان no matter what the earlier ones did. A stale figure
        // survived only because the messages were never re-read, not because the maths drifted.
        val thread = listOf(
            "بانك سامان\nواريز مبلغ  300,000,000ريال\nبه  829-800-1092308-1\nمانده 1,978,750,309",
            "بانك سامان\nبرداشت مبلغ 108,338,130 انتقال وجه\nاز  829-800-1092308-1\nمانده 1,978,750,309",
            "بانك سامان\nبرداشت مبلغ 5,000,000 ازخود پرداز\nاز  829-800-1092308-1\nمانده 1,960,750,309",
            SAMAN_OUT,   // مانده 460,725,309
        )
        var accounts = listOf<BankAccount>()
        for ((i, body) in thread.withIndex()) {
            accounts = applyBankSms(accounts, parseBankSms(SAMAN_NUM, body, i.toLong())!!)
        }
        assertEquals(1, accounts.size)
        assertEquals(46_072_530.9, bankTotal(accounts, emptySet()), 0.01)
    }

    @Test
    fun `only a message naming one of her banks earns a new-number suggestion`() {
        // The first cut listed every sender whose message contained مانده — which is also how
        // Irancell announces remaining data — and the sheet filled with red noise. Now the
        // figure must sit right after the balance word, be big enough to be money, and the
        // body must name one of the banks we actually read.
        val atm = "بانک خاورمیانه\nبرداشت وجه از خودپرداز\n-5,025,000\n020/000016703\nمانده 338,185,038"
        assertTrue(looksLikeBankSms(atm))
        assertEquals(Bank.KHAVARMIANEH, guessBank(atm))
        assertEquals(Bank.BLU, guessBank("بلو\nموجودی: 1,007,033,963 ریال"))
        // A bank listed by name with no numbers of its own is suggestible too, and that is the
        // whole point of listing it: her tap on the suggestion is the only route by which a
        // تجارت sending number can ever become known to this phone. The cost is that a promotion
        // naming a bank and quoting a big enough figure now earns one suggestion, dismissed once.
        assertEquals(Bank.TEJARAT, guessBank("بانک تجارت\nخرید اینترنتی\nمانده 5,000,000"))
        // An operator's مانده is data, not money; a bank nowhere in the list suggests nothing;
        // a small figure is not a balance; chatter is chatter.
        assertTrue(!looksLikeBankSms("مانده اینترنت شما: 2,500,000 کیلوبایت"))
        assertNull(guessBank("جشنواره بانک ملل! مانده 5,000,000"))
        // مسکن joined the name-only list, so what used to be the "nowhere in the list"
        // example is now suggestible like تجارت above.
        assertEquals(Bank.MASKAN, guessBank("جشنواره بانک مسکن! مانده 5,000,000"))
        // آینده is in the enum but still on IGNORED_BANKS, which wins: being listed by name makes
        // a bank suggestible, and the ignore list is the separate, deliberate veto over that.
        assertNull(guessBank("بانک آینده\nخرید اینترنتی\nمانده 5,000,000"))
        assertTrue(!looksLikeBankSms("مانده وقت شما ۳ روز است"))
        assertTrue(!looksLikeBankSms("رمز یکبار مصرف شما: 48213"))
        assertTrue(!looksLikeBankSms("سلام قربونت برم"))
    }

    @Test
    fun `a number she confirmed herself reads exactly like a built-in one`() {
        val extra = extraLookup(mapOf("KHAVARMIANEH" to listOf("2000 4001")))
        assertNull(bankOf("20004001"))
        assertEquals(Bank.KHAVARMIANEH, bankOf("20004001", extra))
        // A shortcode is exactly itself: no country code bolted on, nothing trimmed off.
        assertNull(bankOf("+9820004001", extra))
        assertNull(bankOf("4001", extra))
        val m = parseBankSms(
            "20004001",
            "بانک خاورمیانه برداشت وجه از خودپرداز\n-5,025,000\nمانده 338,185,038",
            1L,
            extra,
        )!!
        assertEquals(Bank.KHAVARMIANEH, m.bank)
        assertEquals(33_818_503.8, m.balance!!, 0.01)
        // A bank name the enum no longer has is skipped, never crashed on.
        assertTrue(extraLookup(mapOf("NOPE" to listOf("123456"))).isEmpty())
    }

    @Test
    fun `parsian's lettered header matches whatever case it arrives in`() {
        // No sample message for پارسیان yet, so only the gate is claimed here: messages from
        // this header reach the parser at all. Whether its body shape reads is untested until
        // a real one turns up.
        assertEquals(Bank.PARSIAN, bankOf("PARSIANBANK"))
        assertEquals(Bank.PARSIAN, bankOf("ParsianBank"))
        assertNull(bankOf("Parsian Bank"))   // a different header, not a spacing variant
    }

    @Test
    fun `refah's lettered header is built in and matches like a number`() {
        // Refah really does send from "Refah Bank" — a name, not a number. The whole header
        // is the identity: case and the spacing between the words are the carrier's noise,
        // but a different name, or the words run together, is a different sender.
        assertEquals(Bank.REFAH, bankOf("Refah Bank"))
        assertEquals(Bank.REFAH, bankOf("REFAH BANK"))
        assertEquals(Bank.REFAH, bankOf(" refah  bank "))
        assertEquals(Bank.REFAH, bankOf("Refah\u00A0Bank"))   // carriers send NBSP too
        assertEquals(Bank.REFAH, bankOf("RefahBank"))   // listed separately; not a fold
        assertEquals(Bank.REFAH, bankOf("refahbank"))
        assertNull(bankOf("Refah"))
        val m = parseBankSms("Refah Bank", "بانک رفاه\nواریز مبلغ 5,000,000 ریال\nمانده 80,000,000 ریال", 1L)!!
        assertEquals(Bank.REFAH, m.bank)
        assertEquals(8_000_000.0, m.balance!!, 0.01)
    }

    @Test
    fun `pasargad's lettered header and bare-figure message read to the stated remainder`() {
        assertEquals(Bank.PASARGAD, bankOf("B.Pasargad"))
        assertEquals(Bank.PASARGAD, bankOf(" b.pasargad "))   // case and spacing forgiven
        assertNull(bankOf("Pasargad"))                        // a different header entirely
        // Real message, shape and all: a dotted account number, a bare minus figure with no
        // direction word, a date with underscores, and a مانده that names no unit.
        val body = """
            276.800.504939.1
            -30,000,000
            05/08_13:37
            مانده: 33,916,067
        """.trimIndent()
        val m = parseBankSms("B.Pasargad", body, 1L)!!
        assertEquals(Bank.PASARGAD, m.bank)
        assertEquals(3_391_606.7, m.balance!!, 0.01)   // no unit anywhere reads as Rial
        assertNull(m.delta)     // no direction word states no delta; the مانده is the truth
        assertTrue(!m.inferred)
        val accounts = applyBankSms(emptyList(), m)
        assertTrue(accounts.single().trusted)
        assertEquals(3_391_606.7, bankTotal(accounts, emptySet()), 0.01)
    }

    @Test
    fun `pasargad's fee message states its remainder as موجودی in rial`() {
        // A different shape from the one above: موجودی rather than مانده, a dotted account
        // number, and both figures naming ریال outright.
        val body = "*بانك ياساركاد* كارمزد پيامك بانكي 6 ماهه دوم سال 1400 برداشت از: " +
            "1503.8000.11222610.1 مبلغ: 75,000 ريال 00/12/17_16:43 موجودي: 154,874 ريال"
        val m = parseBankSms("B.Pasargad", body, 1L)!!
        assertEquals(Bank.PASARGAD, m.bank)
        assertEquals(-7_500.0, m.delta!!, 0.01)      // برداشت/کارمزد both say which way
        assertEquals(15_487.4, m.balance!!, 0.01)
        assertTrue(!m.inferred)
    }

    @Test
    fun `saderat's shortcodes and bare-figure messages read to the stated remainder`() {
        assertEquals(Bank.SADERAT, bankOf("+98 9870 0719"))
        assertEquals(Bank.SADERAT, bankOf("98700719"))       // same shortcode, no country code
        assertEquals(Bank.SADERAT, bankOf("09830009419"))    // and +98… 98… 0… are one line
        assertEquals(Bank.SADERAT, bankOf("BankSaderat"))
        assertNull(bankOf("Saderat"))

        // Direction is a trailing +/- rather than a word, so neither message states a delta —
        // the مانده it prints is what the balance comes from.
        val deposit = parseBankSms("98700719", "پايا: 1,479,680+ حساب: 27007 مانده: 32,203,090 0503 - 13:04", 1L)!!
        assertEquals(3_220_309.0, deposit.balance!!, 0.01)   // no unit anywhere reads as Rial
        assertNull(deposit.delta)
        assertTrue(!deposit.inferred)

        val purchase = parseBankSms("BankSaderat", "پايانه فروش: 4,100,000- حساب: 27007 مانده:28,103,090 0503 - 17:06", 2L)!!
        assertEquals(2_810_309.0, purchase.balance!!, 0.01)

        val accounts = applyBankSms(applyBankSms(emptyList(), deposit), purchase)
        assertTrue(accounts.single().trusted)
        assertEquals(2_810_309.0, bankTotal(accounts, emptySet()), 0.01)
    }

    @Test
    fun `eghtesad novin's lettered header and real withdrawal message are read`() {
        val body = """
            برداشت: 126-800-1026659-1
            -3,100,000ريال
            مانده:2,271,325,358ريال
            5/8-10:04
            مرکزآواي نوين:02162740
        """.trimIndent()

        assertEquals(Bank.EGHTESAD_NOVIN, bankOf("ENBank"))
        assertEquals(Bank.EGHTESAD_NOVIN, bankOf(" enbank "))
        val message = parseBankSms("ENBank", body, 1L)!!
        assertEquals(Bank.EGHTESAD_NOVIN, message.bank)
        assertEquals("126-800-1026659-1", message.mask)
        assertEquals(-310_000.0, message.delta!!, 0.01)
        assertEquals(227_132_535.8, message.balance!!, 0.01)
        assertTrue(!message.inferred)
        val account = applyBankSms(emptyList(), message).single()
        assertTrue(account.trusted)
        assertEquals("ENBank", account.sender)
    }

    @Test
    fun `a lettered sender id she confirmed matches like a number`() {
        // Banks really do send from IDs like SAMANBANK. Reducing a sender to its digits
        // reduced those to nothing, so confirming one changed nothing — the silent kind of
        // broken, since the tap "worked" and the balance stayed frozen.
        val extra = extraLookup(mapOf("SAMAN" to listOf("SAMANBANK")))
        assertNull(bankOf("SAMANBANK"))
        assertEquals(Bank.SAMAN, bankOf("SAMANBANK", extra))
        assertEquals(Bank.SAMAN, bankOf("  samanbank ", extra))   // case and spacing forgiven
        assertNull(bankOf("OTHERBANK", extra))
        assertNull(bankOf("", extra))
        val m = parseBankSms("SAMANBANK", SAMAN_OUT, 1L, extra)!!
        assertEquals(46_072_530.9, m.balance!!, 0.01)
    }

    @Test
    fun `a header cannot wear a shortcode it merely contains`() {
        // "Tel100031" reduced to digits is Refah's shortcode. Reading it would let a sender
        // that is neither built in nor confirmed overwrite her Refah balance.
        assertNull(bankOf("Tel100031"))
        assertNull(bankOf("X20004861"))
        assertNull(bankOf("A90000258"))
        assertNull(parseBankSms("Tel100031", "مانده حساب: 500,000,000 ریال", 1L))
        // …and confirming a mixed header grants that header alone, not its digit tail.
        val extra = extraLookup(mapOf("KHAVARMIANEH" to listOf("MEB2024")))
        assertEquals(Bank.KHAVARMIANEH, bankOf("MEB2024", extra))
        assertNull(bankOf("Foroosh2024", extra))
        assertNull(bankOf("2024", extra))
        // A mobile line she confirms is one sender however the network writes it, and nothing
        // sharing its tail rides along.
        val line = extraLookup(mapOf("SAMAN" to listOf("0912 345 6789")))
        assertEquals(Bank.SAMAN, bankOf("+989123456789", line))
        assertEquals(Bank.SAMAN, bankOf("09123456789", line))
        assertEquals(Bank.SAMAN, bankOf("9123456789", line))
        assertNull(bankOf("3456789", line))
        assertNull(bankOf("989123456789123", line))
    }

    @Test
    fun `a bank name inside a longer word suggests nothing`() {
        // "سامانه" is the portal half of Iran's service messages mention, and it contains
        // "سامان" — a substring match offered to add a stock portal to her Saman account.
        assertNull(guessBank("موجودی سهام شما در سامانه ۴۵,۰۰۰,۰۰۰ ریال است"))
        assertNull(guessBank("فروشگاه رفاهی: موجودی کارت هدیه 5,000,000 ریال"))
        // The real thing still matches, with or without بانک in front of it.
        assertEquals(Bank.SAMAN, guessBank("بانک سامان\nمانده 460,725,309"))
        assertEquals(Bank.REFAH, guessBank("بانک رفاه - مانده 8,000,000 ریال"))
    }

    @Test
    fun `every sender number belongs to exactly one bank`() {
        // The lookup is built with toMap(), so two banks sharing a number would silently
        // resolve to whichever was declared last — and a number written with no digits at all
        // would key on "", which every alphanumeric sender in her inbox also keys on. This is
        // the check that keeps "adding a bank is one line" safe to keep doing.
        val keyed = Bank.entries.flatMap { b -> b.numbers.map { senderKey(it) to b } }
        assertEquals(keyed.size, keyed.map { it.first }.distinct().size)
        assertTrue(keyed.none { it.first.isBlank() })
    }

    @Test
    fun `everything else in her inbox is not read at all`() {
        // This is the whole point of matching on the number: no one-time code, no advert, no
        // message from her family can reach a function that moves money, whatever it says.
        assertNull(bankOf("+989121234567"))
        assertNull(bankOf("SAMANBANK"))          // the bank's name is not the bank's number
        assertNull(bankOf(""))
        // Not even a message that reads exactly like a real one.
        assertNull(parseBankSms("+989121234567", SAMAN_IN, 1L))
        assertNull(parseBankSms("10001", "بانک سامان واریز 500,000,000 ریال مانده 900,000,000", 1L))
    }

    @Test
    fun `a real saman message reads as arabic letters, no space before the unit, and all`() {
        val m = parseBankSms(SAMAN_NUM, SAMAN_IN, 1L)!!
        assertEquals(Bank.SAMAN, m.bank)          // "بانك" with the Arabic kaf
        assertEquals(30_000_000.0, m.delta!!, 0.01)   // 300,000,000ريال, no space
        assertEquals(197_875_030.9, m.balance!!, 0.01) // مانده, which carries no unit of its own
        assertEquals("829-800-1092308-1", m.mask)  // the account, not the 1405/5/1 date
        assertTrue(!m.inferred)

        val out = parseBankSms(SAMAN_NUM, SAMAN_OUT, 2L)!!
        assertEquals(-150_000_000.0, out.delta!!, 0.01)
        assertEquals(46_072_530.9, out.balance!!, 0.01)
        // Same account both ways, so the two fold into one balance rather than two.
        assertEquals(m.mask, out.mask)
    }

    @Test
    fun `a real blu message reads without any مبلغ label to hang the amount on`() {
        val inb = parseBankSms(BLU_NUM, BLU_IN, 1L)!!
        assertEquals(Bank.BLU, inb.bank)
        assertEquals(100_000_000.0, inb.delta!!, 0.01)   // "…نشست", no مبلغ anywhere
        assertEquals(186_357_877.3, inb.balance!!, 0.01) // موجودی, not مانده
        assertTrue(!inb.inferred)

        val out = parseBankSms(BLU_NUM, BLU_OUT, 2L)!!
        assertEquals(-2_500_000.0, out.delta!!, 0.01)
        assertEquals(156_664_302.3, out.balance!!, 0.01)
    }

    @Test
    fun `blu's mistyped transfer still moves the money the right way`() {
        // The message says "انتقال پل" — a typo for پول, and not "انتقال وجه" either. The
        // direction has to come from Blu's own "از حساب شما پرید" instead.
        val m = parseBankSms(BLU_NUM, BLU_TRANSFER, 3L)!!
        assertEquals(Bank.BLU, m.bank)
        assertEquals(-50_000_000.0, m.delta!!, 0.01)
        assertEquals(100_703_396.3, m.balance!!, 0.01)
    }

    @Test
    fun `the real messages fold into one balance per bank, at the bank's own figure`() {
        val thread = listOf(
            SAMAN_NUM to SAMAN_IN, SAMAN_NUM to SAMAN_OUT,
            BLU_NUM to BLU_IN, BLU_NUM to BLU_OUT, BLU_NUM to BLU_TRANSFER,
        )
        var accounts = listOf<BankAccount>()
        for ((i, m) in thread.withIndex()) {
            accounts = applyBankSms(accounts, parseBankSms(m.first, m.second, i.toLong())!!)
        }
        assertEquals(2, accounts.size)
        // Whatever the transactions were, each balance is the last figure its bank stated.
        assertEquals(46_072_530.9, accounts.first { it.bank == Bank.SAMAN.name }.balance, 0.01)
        assertEquals(100_703_396.3, accounts.first { it.bank == Bank.BLU.name }.balance, 0.01)
        assertTrue(accounts.all { it.trusted })
        assertEquals(146_775_927.2, bankTotal(accounts, emptySet()), 0.01)
    }

    // ─────────────────── reading the body ───────────────────
    // Shaped like the real messages but shortened. The senders are real, because nothing is
    // read without one.

    private fun sms(body: String, at: Long = 1_000L, from: String = "0999 992 0000") =
        parseBankSms(from, body, at)

    @Test
    fun `a rial amount is never read as toman`() {
        // The tenfold error. 5,000,000 ریال is 500,000 تومان and nothing else.
        val m = sms("بانک سامان\nبرداشت: 5,000,000 ریال\nمانده: 12,000,000 ریال")!!
        assertEquals(Bank.SAMAN, m.bank)
        assertEquals(-500_000.0, m.delta!!, 0.01)
        assertEquals(1_200_000.0, m.balance!!, 0.01)
        assertTrue(!m.inferred)
        // Stated in Toman, it is taken at face value.
        val t = sms("واریز مبلغ 250,000 تومان\nمانده 3,000,000 تومان", from = BLU_NUM)!!
        assertEquals(Bank.BLU, t.bank)
        assertEquals(250_000.0, t.delta!!, 0.01)
    }

    @Test
    fun `a body naming both units reads each figure in the unit printed beside it`() {
        // The tenfold error, arriving by the back door: deciding the unit once for the whole
        // message meant one "معادل … تومان" aside made every ریال figure in it ten times bigger.
        val m = sms("واریز مبلغ 300,000,000 ریال (معادل 30,000,000 تومان)\nمانده 1,978,750,309 ریال")!!
        assertEquals(30_000_000.0, m.delta!!, 0.01)
        assertEquals(197_875_030.9, m.balance!!, 0.01)
        // …and a fee quoted in Toman does not lift the Rial balance beside it either.
        val f = sms("برداشت مبلغ 5,000,000 ریال\nمانده 200,000,000 ریال\nکارمزد 500 تومان")!!
        assertEquals(-500_000.0, f.delta!!, 0.01)
        assertEquals(20_000_000.0, f.balance!!, 0.01)
    }

    @Test
    fun `an account number between the label and the figure is not the figure`() {
        // "مانده حساب 829-800-1092308-1 : 50,000,000 ریال" — the first digits after the label
        // are the account, and reading them as the balance turned 5 million into 82 Toman.
        val m = sms("برداشت 5,000,000 ریال\nمانده حساب 829-800-1092308-1 : 50,000,000 ریال")!!
        assertEquals(5_000_000.0, m.balance!!, 0.01)
        assertEquals("829-800-1092308-1", m.mask)
        // A masked card number is skipped the same way.
        val c = sms("موجودی کارت 6037-9911-1234-5678 مبلغ 5,000,000 ریال")!!
        assertEquals(500_000.0, c.balance!!, 0.01)
    }

    @Test
    fun `khavarmianeh writes its withdrawals as a bare minus figure`() {
        // Real message. There is no مبلغ label, the amount is a bare "-5,025,000" on its own
        // line, and a reference number sits between it and the مانده. Reading the minus sign as
        // an identifier dash skipped the amount and took "020" out of the reference instead —
        // harmless here because the مانده wins, and a silent nonsense in any message without one.
        val body = """
            بانک خاورمیانه
            برداشت وجه از خودپرداز
            -5,025,000
            020/000016703
            مانده 338,185,038
            05/03
            15:39
        """.trimIndent()
        val m = parseBankSms("+9820004861", body, 1L)!!
        assertEquals(Bank.KHAVARMIANEH, m.bank)
        assertEquals(33_818_503.8, m.balance!!, 0.01)   // 338,185,038 ریال
        assertEquals(-502_500.0, m.delta!!, 0.01)       // 5,025,000 ریال, out
        assertTrue(!m.inferred)
        // The balance is what is stored, and it is the figure her bank app shows.
        val accounts = applyBankSms(emptyList(), m)
        assertEquals(33_818_503.8, bankTotal(accounts, emptySet()), 0.01)
    }

    @Test
    fun `resalat states a balance and no direction at all`() {
        // رسالت writes no واریز and no برداشت — just a signed amount on its own line. So there
        // is no delta to be had, and the مانده is the whole message. That is the safe half:
        // a stated balance replaces what we hold outright and never needs a sign.
        val body = """
            10.7488478.1
            -1,000,000,000
            05/03_15:14
            مانده: 2,807,813
        """.trimIndent()
        val m = parseBankSms("ResalatBank", body, 1L)!!
        assertEquals(Bank.RESALAT, m.bank)
        assertEquals(280_781.3, m.balance!!, 0.01)   // 2,807,813 ریال — no unit printed
        assertNull(m.delta)
        // The message named no amount either, so nothing was read at low confidence.
        assertTrue(!m.inferred)
        val accounts = applyBankSms(emptyList(), m)
        assertEquals(280_781.3, bankTotal(accounts, emptySet()), 0.01)
    }

    @Test
    fun `mellat labels every line and prints no unit at all`() {
        // ملت glues its figures straight onto the label — "برداشت500,000,000" — and names
        // neither ریال nor تومان anywhere, so both figures fall back to ریال.
        val body = """
            حساب7591126460
            برداشت500,000,000
            مانده140,285,187
            05/05/11-11:52
        """.trimIndent()
        val m = parseBankSms("Bank Mellat", body, 1L)!!
        assertEquals(Bank.MELLAT, m.bank)
        assertEquals(14_028_518.7, m.balance!!, 0.01)   // 140,285,187 ریال
        assertEquals(-50_000_000.0, m.delta!!, 0.01)    // 500,000,000 ریال, out
        assertTrue(!m.inferred)
        // The date on the last line is digits and a dash; it is not an account number.
        assertEquals("", m.mask)
        val accounts = applyBankSms(emptyList(), m)
        assertEquals(14_028_518.7, bankTotal(accounts, emptySet()), 0.01)
    }

    @Test
    fun `a long figure is never cut in half by the search window`() {
        val m = sms("موجودی قابل برداشت حساب سپرده کوتاه مدت 1,978,750,309 ریال")!!
        assertEquals(197_875_030.9, m.balance!!, 0.01)
    }

    @Test
    fun `a dot is a thousands separator here, never a decimal point`() {
        // Rial has no fractions. Reading "500.000" as five hundred is the same figure a
        // thousand times too small.
        val m = sms("واریز مبلغ 500.000 ریال\nمانده 20.000.000 ریال")!!
        assertEquals(50_000.0, m.delta!!, 0.01)
        assertEquals(2_000_000.0, m.balance!!, 0.01)
    }

    @Test
    fun `a loan balance is debt, not money`() {
        // "مانده بدهی" is what she owes. Counting it as cash adds the size of her debt to her
        // wealth — wrong in the one direction this app must never be wrong in.
        val m = sms("قسط تسهیلات پرداخت مبلغ 3,000,000 ریال\nمانده بدهی 2,400,000,000 ریال")
        assertTrue("a loan balance must not become a balance", m?.balance == null)
        assertEquals(-300_000.0, m!!.delta!!, 0.01)
    }

    @Test
    fun `an emptied account is allowed to read zero`() {
        var accounts = applyBankSms(emptyList(), sms("مانده 50,000,000 ریال", at = 1)!!)
        assertEquals(5_000_000.0, bankTotal(accounts, emptySet()), 0.01)
        accounts = applyBankSms(accounts, sms("برداشت 50,000,000 ریال\nمانده: 0 ریال", at = 2)!!)
        assertEquals(0.0, bankTotal(accounts, emptySet()), 0.01)
        assertTrue(accounts.single().anchored)   // still a stated balance, still counted
    }

    @Test
    fun `one account that a bank sometimes names and sometimes does not stays one account`() {
        // Saman prints the number on a transfer and leaves it off a card purchase. Keying on
        // the number alone forked that into two accounts and bankTotal added both.
        var accounts = applyBankSms(emptyList(), parseBankSms(SAMAN_NUM, SAMAN_IN, 1L)!!)
        accounts = applyBankSms(accounts, sms("خرید مبلغ 5,000,000 ریال\nمانده 1,973,750,309 ریال", at = 2)!!)
        assertEquals(1, accounts.size)
        assertEquals(197_375_030.9, bankTotal(accounts, emptySet()), 0.01)
        assertEquals("829-800-1092308-1", accounts.single().mask)  // the specific name is kept
    }

    @Test
    fun `a transaction older than the balance she typed in does not move it`() {
        var accounts = applyBankSms(emptyList(), sms("واریز 1,000,000 ریال", at = 1)!!)
        accounts = anchorAccount(accounts, accounts.single().key, 50_000_000.0, at = 10_000)
        // A message from before she told us the balance is already inside that figure.
        accounts = applyBankSms(accounts, sms("واریز 300,000,000 ریال", at = 500)!!)
        assertEquals(50_000_000.0, bankTotal(accounts, emptySet()), 0.01)
    }

    @Test
    fun `an amount with no unit anywhere is still read as rial`() {
        // Saman's own withdrawals name no unit at all. Flagging that would put a warning on
        // half her statement, and a warning that is always on is one she stops reading.
        val m = sms("واریز 10,000,000\nمانده 45,000,000")!!
        assertEquals(1_000_000.0, m.delta!!, 0.01)
        assertEquals(4_500_000.0, m.balance!!, 0.01)
        assertTrue(!m.inferred)
    }

    @Test
    fun `persian digits read the same as latin ones`() {
        val m = sms("واریز مبلغ ۱٬۲۰۰٬۰۰۰ ریال\nمانده ۸٬۰۰۰٬۰۰۰ ریال")!!
        assertEquals(120_000.0, m.delta!!, 0.01)
        assertEquals(800_000.0, m.balance!!, 0.01)
    }

    @Test
    fun `a message that does not say which way the money went states no delta`() {
        // Guessing a direction is how a deposit becomes a withdrawal.
        val m = sms("مانده حساب شما: 9,000,000 ریال")!!
        assertNull(m.delta)
        assertEquals(900_000.0, m.balance!!, 0.01)
        // …and something with no amount and no balance is not a transaction, even from a bank.
        assertNull(sms("رمز یکبار مصرف شما: 48213"))
    }

    @Test
    fun `a stated balance wins over accumulating, so a missed message self-corrects`() {
        var accounts = listOf<BankAccount>()
        accounts = applyBankSms(accounts, sms("واریز 1,000,000 ریال\nمانده 5,000,000 ریال", at = 1)!!)
        assertEquals(500_000.0, bankTotal(accounts, emptySet()), 0.01)
        // Pretend the next message never arrived. The one after it still puts her right.
        accounts = applyBankSms(accounts, sms("برداشت 2,000,000 ریال\nمانده 20,000,000 ریال", at = 3)!!)
        assertEquals(2_000_000.0, bankTotal(accounts, emptySet()), 0.01)
        assertTrue(accounts.single().trusted)
    }

    @Test
    fun `a balance built only from transactions is never counted, and never goes negative`() {
        // Adding up transactions from zero gives the change since we started reading, not the
        // account — so it is shown and flagged, but stays out of the total until she says what
        // the account really holds. Start on a withdrawal and the figure is below zero, which
        // counted would take money off her total that never existed.
        var accounts = applyBankSms(emptyList(), sms("برداشت 25,000,000 ریال", at = 1)!!)
        val acc = accounts.single()
        assertEquals(-2_500_000.0, acc.balance, 0.01)
        assertTrue(!acc.anchored)
        assertTrue(!acc.trusted)
        assertEquals(0.0, bankTotal(accounts, emptySet()), 0.01)

        accounts = anchorAccount(accounts, acc.key, 9_000_000.0, at = 5)
        assertTrue(accounts.single().trusted)
        assertEquals(9_000_000.0, bankTotal(accounts, emptySet()), 0.01)
        // …and transactions after that build on the real figure.
        accounts = applyBankSms(accounts, sms("برداشت 1,000,000 ریال", at = 6)!!)
        assertEquals(8_900_000.0, bankTotal(accounts, emptySet()), 0.01)
    }

    @Test
    fun `one unanchored account does not drag down the banks that are anchored`() {
        // Saman states a مانده and counts; Blu has only sent a purchase alert so far and does
        // not — the total must be Saman's alone, not Saman minus Blu's running deficit.
        var accounts = applyBankSms(emptyList(), sms("مانده 50,000,000 ریال", at = 1)!!)
        accounts = applyBankSms(accounts, sms("برداشت 9,000,000 ریال", at = 2, from = BLU_NUM)!!)
        assertEquals(2, accounts.size)                                   // both are still listed
        assertEquals(5_000_000.0, bankTotal(accounts, emptySet()), 0.01) // only one is counted
    }

    @Test
    fun `an older message never overwrites a newer balance`() {
        var accounts = applyBankSms(emptyList(), sms("واریز 1 ریال\nمانده 90,000,000 ریال", at = 500)!!)
        accounts = applyBankSms(accounts, sms("واریز 1 ریال\nمانده 10,000,000 ریال", at = 100)!!)
        assertEquals(9_000_000.0, bankTotal(accounts, emptySet()), 0.01)
    }

    @Test
    fun `one bank is one balance, however many ways its messages name the account`() {
        // The doubling. Saman prints the account number on a transfer and the card mask on a
        // purchase, and both state the same مانده. Telling accounts apart by what was printed
        // made that one account into two rows, each anchored at the full balance, and the total
        // added both — her money doubled on screen with nothing flagged.
        var accounts = applyBankSms(emptyList(), parseBankSms(SAMAN_NUM, SAMAN_OUT, 1L)!!)
        assertEquals(46_072_530.9, bankTotal(accounts, emptySet()), 0.01)
        accounts = applyBankSms(accounts, sms("خرید مبلغ 1,000,000 ریال کارت 6037****1234 مانده 460,725,309 ریال", at = 2)!!)
        assertEquals(1, accounts.size)
        assertEquals(46_072_530.9, bankTotal(accounts, emptySet()), 0.01)
        // Two different banks, however, never merge.
        accounts = applyBankSms(accounts, sms("موجودی 20,000,000 ریال", at = 3, from = BLU_NUM)!!)
        assertEquals(2, accounts.size)
        assertEquals(48_072_530.9, bankTotal(accounts, emptySet()), 0.01)
    }

    @Test
    fun `a bank switched off stays tracked but leaves the total`() {
        var accounts = applyBankSms(emptyList(), sms("مانده 50,000,000 ریال", at = 1)!!)
        accounts = applyBankSms(accounts, sms("موجودی 20,000,000 ریال", at = 2, from = BLU_NUM)!!)
        assertEquals(7_000_000.0, bankTotal(accounts, emptySet()), 0.01)
        assertEquals(5_000_000.0, bankTotal(accounts, setOf(Bank.BLU.name)), 0.01)
        assertEquals(2, accounts.size) // switching off is not forgetting
    }

    @Test
    fun `the rows an older build wrote collapse to one per bank on the way in`() {
        // A real phone came back with 38 rows across three banks: the build that keyed on the
        // identifier a message printed treated every reference and card number as its own
        // account, and summed all of them. They also share a key now, and duplicate keys in the
        // accounts list crash the sheet the moment she opens it.
        val stored = listOf(
            BankAccount("SAMAN", "829-800-1092308-1", 46_072_530.9, updatedAt = 50, anchored = true),
            BankAccount("SAMAN", "6037****1234", 46_072_530.9, updatedAt = 40, anchored = true),
            BankAccount("SAMAN", "", -300_000.0, updatedAt = 90),               // newer, but a guess
            BankAccount("BLU", "", 100_703_396.3, updatedAt = 10, anchored = true),
        ) + (1..34).map { BankAccount("REFAH", "ref-$it", 1_000_000.0, updatedAt = it.toLong()) }

        val collapsed = collapseAccounts(stored)
        assertEquals(3, collapsed.size)
        assertEquals(3, collapsed.map { it.key }.distinct().size)   // unique, so the sheet opens
        // A stated balance beats a running total even when the running total is newer.
        val saman = collapsed.first { it.bank == "SAMAN" }
        assertEquals(46_072_530.9, saman.balance, 0.01)
        assertEquals("829-800-1092308-1", saman.mask)               // the newest anchored row
        // And the total is one balance per bank, not the sum of the duplicates.
        assertEquals(146_775_927.2, bankTotal(collapsed, emptySet()), 0.01)
    }

    @Test
    fun `rows from banks we no longer read are dropped, not shown as an unnamed bank`() {
        // Her phone showed a column of rows all titled «بانک» — MELLAT, TEJARAT and the rest,
        // written when the bank was guessed from wording. They cannot be named, they can never
        // be corrected by another message, and one of them read −۹۱۶ میلیارد.
        //
        // A name that later joins the enum leaves this filter by design: it can be named and a
        // real message will correct it, which is the visible-and-correctable case the sheet is
        // for, not the unnameable one this drops.
        val stored = listOf(
            BankAccount("MELLAT", balance = -916_104_000_000.0, updatedAt = 9, anchored = true),
            BankAccount("TEJARAT", balance = 53_012_000_000_000.0, updatedAt = 8, anchored = true),
            BankAccount("OTHER", balance = 47_815_000.0, updatedAt = 7, anchored = true),
            BankAccount("SAMAN", balance = 46_072_530.9, updatedAt = 6, anchored = true),
            BankAccount("KHAVARMIANEH", balance = 300_000_000.0, updatedAt = 5, anchored = true),
        )
        val kept = collapseAccounts(stored)
        // ملت is that later joiner: it has a number now, so its row stays rather than
        // vanishing — and one real message from that number replaces the −۹۱۶ میلیارد outright.
        assertEquals(listOf("MELLAT", "SAMAN", "KHAVARMIANEH"), kept.map { it.bank })
        assertTrue(kept.none { it.bankFa == Bank.OTHER.fa })
        val fixed = applyBankSms(kept, parseBankSms("Bank Mellat", "مانده140,285,187", 10L)!!)
        assertEquals(360_101_049.6, bankTotal(fixed, emptySet()), 0.01)
    }

    @Test
    fun `the same message counted twice would invent money, so it is keyed by content`() {
        val body = "بانک سامان\nواریز 1,000,000 ریال"
        assertEquals(smsKey("Bank", body, 42L), smsKey("Bank", body, 42L))
        assertTrue(smsKey("Bank", body, 42L) != smsKey("Bank", body, 43L))
        assertTrue(smsKey("Bank A", body, 42L) != smsKey("Bank B", body, 42L))
        // Pruning keeps the newest keys, so a long-lived inbox cannot grow the set forever.
        val many = (1..SMS_KEEP + 50).map { "k$it" }
        val kept = rememberSeen(emptySet(), many)
        assertEquals(SMS_KEEP, kept.size)
        assertTrue("k${SMS_KEEP + 50}" in kept)
    }

    @Test
    fun `a coin is found by its persian name, its latin name or its ticker`() {
        // The reported bug: once bitpin gave DOT a Persian name, the Worker overwrote the
        // latin one and "Polkadot" stopped finding anything.
        val dot = Coin("dot", "پولکادات", en = "Polkadot").toAssetType()
        for (q in listOf("Polkadot", "polkadot", "POLKA", " polka dot ", "dot", "DOT", "پولکادات")) {
            assertTrue("«$q» found nothing", matchesSearch(dot, q))
        }
        assertTrue(!matchesSearch(dot, "Cardano"))
        // A coin the Worker never named in latin is still reachable the two old ways.
        val sol = Coin("sol", "سولانا").toAssetType()
        assertTrue(matchesSearch(sol, "سولانا"))
        assertTrue(matchesSearch(sol, "sol"))
    }

    @Test
    fun `the fixed assets answer to english too`() {
        val usd = STATIC_CATALOG.first { it.id == "usd" }
        assertTrue(matchesSearch(usd, "dollar"))
        assertTrue(matchesSearch(usd, "USD"))
        assertTrue(matchesSearch(usd, "دلار"))
        assertTrue(matchesSearch(STATIC_CATALOG.first { it.id == "gold18" }, "gold"))
        // An empty query is not a match-everything.
        assertTrue(!matchesSearch(usd, "   "))
    }

    @Test
    fun `the picker offers one parsian coin, counted in سوت`() {
        // Fifteen sizes of one coin buried every other سکه in the list. The id is a contract
        // with the Worker, which derives a per-سوت rate under exactly this key.
        val offered = STATIC_CATALOG.filter { it.id.startsWith("parsian") }
        assertEquals(listOf("parsian"), offered.map { it.id })

        val parsian = offered.single()
        assertEquals("سکه پارسیان", parsian.fa)
        assertEquals("سوت", parsian.unitFa)
        // سوت come in whole numbers, and a holding is their sum across whatever sizes she has.
        assertEquals(0, parsian.dec)
        assertEquals(Kind.COIN, parsian.kind)
        assertTrue(matchesSearch(parsian, "پارسیان"))
        assertTrue(matchesSearch(parsian, "parsian"))
    }

    @Test
    fun `a holding saved against an old per-size parsian id still resolves`() {
        // Dropping the fifteen from the picker must not turn a saved holding into a bare
        // ticker priced at nothing — they are still quoted, and per size they are the more
        // accurate of the two.
        for (soot in (1..15).map { it * 100 }) {
            val type = resolveType("parsian_$soot", emptyList())
            assertEquals("parsian_$soot", type.id)
            assertEquals(Kind.COIN, type.kind)
            assertEquals("عدد", type.unitFa)
        }
        assertEquals("سکه پارسیان ۱۰۰ سوت", resolveType("parsian_100", emptyList()).fa)
        assertEquals("سکه پارسیان ۱۵۰۰ سوت", resolveType("parsian_1500", emptyList()).fa)
    }

    @Test
    fun `silver is priced by the gram at both purities`() {
        val silver = STATIC_CATALOG.filter { it.kind == Kind.SILVER }
        assertEquals(listOf("silver_999", "silver_925"), silver.map { it.id })
        assertTrue(silver.all { it.unitFa == "گرم" && it.dec == 3 })
        assertTrue(matchesSearch(silver.first(), "silver"))
        assertTrue(matchesSearch(silver.first(), "نقره"))
    }

    @Test
    fun `no two assets in the catalogue share an id`() {
        // Two rows with one id is a holding that renders twice and totals twice. The picker
        // grew fifteen coins and two metals at once; this is the cheap guard on that.
        val ids = STATIC_CATALOG.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    /** The list she reads, banded by kind. Same holdings, same order, same total. */
    private val banded = listOf(
        Holding(TOMAN_ID, 50_000_000.0),
        Holding("usd", 3_000.0),
        Holding("eur", 500.0),
        Holding("gold18", 12.0),
        Holding("btc", 0.5),
        Holding("sol", 40.0, excluded = true),   // set aside on purpose
        Holding("doge", 100_000.0),              // no rate for it
    )
    private val bandedCoins =
        listOf(Coin("btc", "بیت‌کوین"), Coin("sol", "سولانا"), Coin("doge", "دوج‌کوین"))
    private val bandedRates = effectiveRates(
        Rates(
            1L,
            mapOf(
                "usd" to 187_000.0,
                "eur" to 200_000.0,
                "gold18" to 90_000_000.0,
                "btc" to 10_000_000_000.0,
                "sol" to 14_000_000.0,
            ),
        ),
        emptyMap(),
    )

    @Test
    fun `sections keep every holding, in the order the list already had them`() {
        // Grouping the list is a way of reading it, not of rewriting it: no row may move,
        // vanish or appear twice, and a kind she does not hold gets no heading.
        val sections = holdingsByKind(banded, bandedCoins)
        assertEquals(listOf(Kind.CASH, Kind.FIAT, Kind.GOLD, Kind.CRYPTO), sections.keys.toList())
        assertEquals(banded, sections.values.flatten())
    }

    @Test
    fun `the section subtotals add up to exactly the total above them`() {
        // A section head that showed money the hero total leaves out would be the same
        // quietly-wrong total this app exists to avoid, arriving from the other end.
        val sections = holdingsByKind(banded, bandedCoins)
        assertEquals(
            computeTotals(banded, bandedRates).toman,
            sections.values.sumOf { computeTotals(it, bandedRates).toman },
            0.01,
        )
        // …and specifically: the set-aside SOL and the unpriced DOGE are in nobody's subtotal.
        assertEquals(
            0.5 * 10_000_000_000.0,
            computeTotals(sections.getValue(Kind.CRYPTO), bandedRates).toman,
            0.01,
        )
    }

    @Test
    fun `percent is omitted when the baseline was zero`() {
        val c = changeOver(mapOf(50L to 0.0), today = 100, windowDays = 30, current = 12.0)!!
        assertNull(c.percent)
    }
}
