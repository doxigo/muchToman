package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backlog note: what counts as new, and what it is allowed to say.
 *
 * Everything a phone would post is a pure function of the review list and one millisecond stamp, so
 * all of it is checked here. The three things that would be silently wrong on a phone and are
 * checked hardest: that a first run cannot ambush her with a backlog she has been living with, that
 * a receipt she left unfiled on purpose stays quiet, and that nothing here answers the question it
 * is asking her.
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

    // ─────────────────────────── what counts as new ───────────────────────────

    @Test
    fun `the first pass only learns where the ledger is`() {
        // A fresh install part-way through its first import, or an upgrade onto the build that
        // introduced the mark. Sixty-seven waiting and not one word about them.
        val review = (1..67L).map { waiting(at = 1_000L * it) }
        val news = filingNews(review, said = 0L)
        assertNull(news.alert)
        assertEquals(67_000L, news.mark)
    }

    @Test
    fun `anything newer than the mark is news, and the mark moves past it`() {
        val news = filingNews(listOf(waiting(at = 500L), waiting(at = 900L)), said = 400L)
        val alert = news.alert!!
        assertEquals(2, alert.fresh)
        assertEquals(2, alert.waiting)
        assertEquals(900L, alert.newest.txn.at)
        assertEquals(900L, news.mark)
    }

    @Test
    fun `a receipt she has already been shown says nothing a second time`() {
        val review = listOf(waiting(at = 500L), waiting(at = 900L))
        val first = filingNews(review, said = 400L)
        assertNotNull(first.alert)
        // Same list, mark in hand: she has seen these.
        assertNull(filingNews(review, first.mark).alert)
        assertEquals(first.mark, filingNews(review, first.mark).mark)
    }

    @Test
    fun `filing some of them is not what makes the rest news again`() {
        // She spent an evening in the app and left three of twelve for later. That was a decision,
        // and it must not be answered at 3am with a note about the three.
        val left = listOf(waiting(at = 300L), waiting(at = 500L), waiting(at = 900L))
        assertNull(filingNews(left, said = 900L).alert)
    }

    @Test
    fun `two more landing on top of a backlog she has seen is news, and the count is the whole of it`() {
        val old = listOf(waiting(at = 300L), waiting(at = 900L))
        val fresh = listOf(waiting(at = 1_400L), waiting(at = 1_500L))
        val alert = filingNews(old + fresh, said = 900L).alert!!
        assertEquals(2, alert.fresh)
        // The count in the note is everything waiting, not only what arrived — she is being asked
        // to sit down with the deck, and the deck holds four.
        assertEquals(4, alert.waiting)
    }

    @Test
    fun `a mark is never lowered, so an emptied backlog cannot make old rows new`() {
        // Everything filed: nothing to say, and nothing to forget either. A mark that fell back to
        // zero here would announce the whole ledger the next time one row was refiled.
        val cleared = filingNews(emptyList(), said = 900L)
        assertNull(cleared.alert)
        assertEquals(900L, cleared.mark)
        assertNull(filingNews(listOf(waiting(at = 500L)), cleared.mark).alert)
    }

    @Test
    fun `reaching back through the inbox cannot ambush her`() {
        // rewindIngest imports a year of messages, all stamped in the past. She asked for them, she
        // is looking at the app, and none of them is news.
        val imported = (1..300L).map { waiting(at = it) }
        assertNull(filingNews(imported, said = 1_000L).alert)
    }

    // ─────────────────────────── the words ───────────────────────────

    private fun alert(fresh: Int, waiting: Int, entry: LedgerEntry) =
        FilingAlert(fresh = fresh, waiting = waiting, newest = entry)

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
    fun `no word anywhere in the note tells her she is behind`() {
        for (shape in listOf(alert(1, 1, waiting(at = 900L)), alert(4, 20, waiting(at = 900L)))) {
            val said = "${filingAlertTitle(shape)} ${filingAlertBody(shape)}"
            for (verdict in listOf("هنوز", "دوباره", "فراموش", "باید", "عقب")) {
                assertTrue(said, !said.contains(verdict))
            }
        }
    }
}
