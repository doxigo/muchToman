package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transaction note: what counts as new, and what it is allowed to say.
 *
 * Everything a phone would post is a pure function of the ledger, the review list and one
 * millisecond stamp, so all of it is checked here. The things that would be silently wrong on a
 * phone and are checked hardest: that a first run cannot ambush her with a backlog she has been
 * living with, that a receipt she left unfiled on purpose stays quiet, that a row the rules filed
 * is reported once and never again, and that nothing about an *unfiled* row answers the question
 * it is asking her.
 */
class FilingTest {

    private var n = 0

    /** Stamps are plain milliseconds here: the mark is a stamp, and nothing else about it is a day. */
    private fun waiting(at: Long, merchant: String = "اسنپ", signed: Long? = -4_500_000L): LedgerEntry {
        n++
        val ref = "s:%04d:0".format(n)
        return LedgerEntry(
            txn = Txn(
                ref = ref, srcHash = ref, seq = 0, at = at, day = tehranDay(at),
                bank = "SAMAN", accountId = "SAMAN",
                direction = signed?.let { if (it > 0) "in" else "out" },
                amountRial = signed?.let { kotlin.math.abs(it) }, signedRial = signed,
                balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
                merchant = merchant, merchantNorm = merchant, refNo = "", printedAt = "",
                channel = "unknown", unitPrinted = "none", inferred = false,
                parserVer = PARSER_VERSION,
            ),
            categoryId = CAT_UNCATEGORISED, categoryFa = "دسته‌بندی نشده", confidence = Confidence.NONE,
            needsReview = true, duplicate = false, transfer = false,
        )
    }

    /** A row the rules were sure about: filed on arrival, never in the review list. */
    private fun filed(at: Long, categoryFa: String = "خوراک", merchant: String = "اسنپ") =
        waiting(at, merchant).copy(
            categoryId = CAT_SHOPPING_ID, categoryFa = categoryFa,
            confidence = Confidence.RULE_EXACT, needsReview = false,
        )

    // ─────────────────────────── what counts as new ───────────────────────────

    @Test
    fun `the first pass only learns where the ledger is`() {
        // A fresh install part-way through its first import, or an upgrade onto the build that
        // introduced the mark. Sixty-seven waiting and not one word about them.
        val review = (1..67L).map { waiting(at = 1_000L * it) }
        val news = filingNews(review, review, said = 0L)
        assertNull(news.alert)
        assertEquals(67_000L, news.mark)
    }

    @Test
    fun `anything newer than the mark is news, and the mark moves past it`() {
        val review = listOf(waiting(at = 500L), waiting(at = 900L))
        val news = filingNews(review, review, said = 400L)
        val alert = news.alert!!
        assertEquals(2, alert.fresh)
        assertEquals(2, alert.waiting)
        assertEquals(900L, alert.newest.txn.at)
        assertEquals(900L, news.mark)
    }

    @Test
    fun `a receipt she has already been shown says nothing a second time`() {
        val review = listOf(waiting(at = 500L), waiting(at = 900L))
        val first = filingNews(review, review, said = 400L)
        assertNotNull(first.alert)
        // Same list, mark in hand: she has seen these.
        assertNull(filingNews(review, review, first.mark).alert)
        assertEquals(first.mark, filingNews(review, review, first.mark).mark)
    }

    // ─────────────────────────── what the rules filed ───────────────────────────

    @Test
    fun `a spend the rules filed is still news, and the mark moves past it`() {
        // The whole point of the change: this used to pass in silence.
        val done = filed(at = 900L)
        val news = filingNews(listOf(done), emptyList(), said = 400L)
        val alert = news.alert!!
        assertEquals(0, alert.fresh)
        assertEquals(1, alert.filed)
        assertEquals(0, alert.waiting)
        assertEquals(900L, news.mark)
        // Told once: the mark in hand, the same row says nothing a second time.
        assertNull(filingNews(listOf(done), emptyList(), news.mark).alert)
    }

    @Test
    fun `bookkeeping is not news - a transfer leg or a duplicate says nothing`() {
        val moved = filed(at = 900L).copy(transfer = true)
        val twice = filed(at = 950L).copy(duplicate = true)
        val news = filingNews(listOf(moved, twice), emptyList(), said = 400L)
        assertNull(news.alert)
        // But the mark still passes them, or they would be reconsidered on every wakeup for ever.
        assertEquals(950L, news.mark)
    }

    @Test
    fun `the first pass is silent about filed rows too`() {
        assertNull(filingNews(listOf(filed(at = 900L)), emptyList(), said = 0L).alert)
    }

    // ─────────────────────────── filing the whole backlog ───────────────────────────

    @Test
    fun `a suggested category is kept, and the fallbacks split by direction`() {
        val suggested = waiting(at = 100L)
            .copy(categoryId = CAT_SHOPPING_ID, categoryFa = "خرید روزانه")
        val out = waiting(at = 200L)
        val inflow = waiting(at = 300L, signed = 4_000_000L)
        val unknown = waiting(at = 400L, signed = null)

        val plan = autoFilePlan(listOf(suggested, out, inflow, unknown))
        assertEquals(4, plan.total)
        assertEquals(1, plan.suggested)
        assertEquals(1, plan.shopping)
        assertEquals(2, plan.other)

        val byRef = plan.assignments.associate { it.first.txn.ref to it.second }
        assertEquals(CAT_SHOPPING_ID, byRef[suggested.txn.ref])
        assertEquals(CAT_SHOPPING_ID, byRef[out.txn.ref])
        // Money in with no rule, and a message with no direction: «سایر» is the honest word.
        assertEquals(CAT_OTHER, byRef[inflow.txn.ref])
        assertEquals(CAT_OTHER, byRef[unknown.txn.ref])
    }

    @Test
    fun `an empty backlog is an empty plan`() {
        val plan = autoFilePlan(emptyList())
        assertEquals(0, plan.total)
        assertTrue(plan.assignments.isEmpty())
    }

    @Test
    fun `filing some of them is not what makes the rest news again`() {
        // She spent an evening in the app and left three of twelve for later. That was a decision,
        // and it must not be answered at 3am with a note about the three.
        val left = listOf(waiting(at = 300L), waiting(at = 500L), waiting(at = 900L))
        assertNull(filingNews(left, left, said = 900L).alert)
    }

    @Test
    fun `two more landing on top of a backlog she has seen is news, and the count is the whole of it`() {
        val old = listOf(waiting(at = 300L), waiting(at = 900L))
        val fresh = listOf(waiting(at = 1_400L), waiting(at = 1_500L))
        val alert = filingNews(old + fresh, old + fresh, said = 900L).alert!!
        assertEquals(2, alert.fresh)
        // The count in the note is everything waiting, not only what arrived — she is being asked
        // to sit down with the deck, and the deck holds four.
        assertEquals(4, alert.waiting)
    }

    @Test
    fun `a mark is never lowered, so an emptied backlog cannot make old rows new`() {
        // Everything filed: nothing to say, and nothing to forget either. A mark that fell back to
        // zero here would announce the whole ledger the next time one row was refiled.
        val cleared = filingNews(emptyList(), emptyList(), said = 900L)
        assertNull(cleared.alert)
        assertEquals(900L, cleared.mark)
        val one = listOf(waiting(at = 500L))
        assertNull(filingNews(one, one, cleared.mark).alert)
    }

    @Test
    fun `reaching back through the inbox cannot ambush her`() {
        // rewindIngest imports a year of messages, all stamped in the past. She asked for them, she
        // is looking at the app, and none of them is news.
        val imported = (1..300L).map { waiting(at = it) }
        assertNull(filingNews(imported, imported, said = 1_000L).alert)
    }

    // ─────────────────────────── the words ───────────────────────────

    private fun alert(fresh: Int, waiting: Int, entry: LedgerEntry, filed: Int = 0) =
        FilingAlert(fresh = fresh, waiting = waiting, newest = entry, filed = filed)

    @Test
    fun `one transaction is named and priced, because she can still picture it`() {
        val one = alert(1, 1, waiting(at = 900L))
        assertEquals("اسنپ: ۴۵۰ هزار تومان رفت", filingAlertTitle(one))
        assertEquals("تا یادته، بگو چی بود", filingAlertBody(one))
    }

    @Test
    fun `money arriving is said to have arrived, and neither is called خرج`() {
        val incoming = alert(1, 1, waiting(at = 900L, merchant = "", signed = 92_000_000L))
        // No merchant on the message, so the bank that reported it is what it is called.
        assertEquals("بانک سامان: ۹٫۲ میلیون تومان رسید", filingAlertTitle(incoming))
        // Nothing in either line answers the question the note is asking.
        for (text in listOf(filingAlertTitle(incoming), filingAlertBody(incoming))) {
            assertTrue(text, !text.contains("خرج") && !text.contains("درآمد"))
        }
    }

    @Test
    fun `a message that did not say which way the money went is not guessed at`() {
        val unknown = alert(1, 1, waiting(at = 900L, signed = null))
        assertEquals("یک تراکنش تازه از اسنپ", filingAlertTitle(unknown))
    }

    @Test
    fun `several are counted rather than listed, and the plural is a plural`() {
        val many = alert(3, 3, waiting(at = 900L))
        assertEquals("۳ تراکنش تازه رسید", filingAlertTitle(many))
        assertEquals("تا یادته، بگو چی بودن", filingAlertBody(many))
    }

    @Test
    fun `the total only appears when something older is behind the new ones`() {
        assertEquals(
            "تا یادته، بگو چی بودن • روی هم ۹ تراکنش دسته‌بندی نشده داری",
            filingAlertBody(alert(3, 9, waiting(at = 900L))),
        )
        // Three of three is not «روی هم سه تا» — there is nothing for the total to add.
        assertTrue(!filingAlertBody(alert(3, 3, waiting(at = 900L))).contains("روی هم"))
    }

    @Test
    fun `a spend the rules filed is named, priced, and given its answer to veto`() {
        val one = alert(0, 0, filed(at = 900L), filed = 1)
        // The same headline an unfiled one gets: what landed is the news either way.
        assertEquals("اسنپ: ۴۵۰ هزار تومان رفت", filingAlertTitle(one))
        // The rules' answer, and the veto — never «بگو چی بود», which it already knows.
        assertEquals("تو «خوراک» ثبت شد • اگه جاش نیست، عوضش کن", filingAlertBody(one))
    }

    @Test
    fun `several filed rows are counted, and no favourite category is named`() {
        val many = alert(0, 0, filed(at = 900L), filed = 3)
        assertEquals("۳ تراکنش تازه رسید", filingAlertTitle(many))
        val body = filingAlertBody(many)
        assertEquals("خودکار دسته‌بندی شدن • یه نگاه بنداز که سر جاشون نشسته باشن", body)
        assertTrue(body, !body.contains("خوراک"))
    }

    @Test
    fun `filed news over an old backlog still says how much is waiting`() {
        assertEquals(
            "تو «خوراک» ثبت شد • اگه جاش نیست، عوضش کن • روی هم ۲ تراکنش دسته‌بندی نشده داری",
            filingAlertBody(alert(0, 2, filed(at = 900L), filed = 1)),
        )
    }

    @Test
    fun `when both kinds land the ask leads and the filed ones are a count after it`() {
        val mixed = alert(1, 1, waiting(at = 900L), filed = 2)
        // The title counts everything that landed — three arrived, whatever their state.
        assertEquals("۳ تراکنش تازه رسید", filingAlertTitle(mixed))
        assertEquals("تا یادته، بگو چی بود • ۲ تای دیگه خودکار ثبت شدن", filingAlertBody(mixed))
        assertEquals(
            "تا یادته، بگو چی بودن • یکی هم خودکار ثبت شد",
            filingAlertBody(alert(2, 2, waiting(at = 900L), filed = 1)),
        )
    }

    @Test
    fun `no word anywhere in the note tells her she is behind`() {
        val shapes = listOf(
            alert(1, 1, waiting(at = 900L)),
            alert(4, 20, waiting(at = 900L)),
            alert(0, 0, filed(at = 900L), filed = 1),
            alert(2, 5, waiting(at = 900L), filed = 3),
        )
        for (shape in shapes) {
            val said = "${filingAlertTitle(shape)} ${filingAlertBody(shape)}"
            for (verdict in listOf("هنوز", "دوباره", "فراموش", "باید", "عقب")) {
                assertTrue(said, !said.contains(verdict))
            }
        }
    }
}
