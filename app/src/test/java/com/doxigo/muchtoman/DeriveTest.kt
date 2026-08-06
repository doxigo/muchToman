package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derivation, as a pure function. Everything the old left fold guaranteed has to still be
 * true here, and a few things it could not manage have to be true as well.
 */
class DeriveTest {

    private var seq = 0

    private fun txn(
        at: Long,
        signed: Long? = null,
        balance: Long? = null,
        account: String = "SAMAN",
        ref: String = "s:%08d:0".format(seq++),
    ) = Txn(
        ref = ref, srcHash = ref, seq = 0, at = at, day = tehranDay(at),
        bank = account, accountId = account,
        direction = signed?.let { if (it > 0) "in" else "out" },
        amountRial = signed?.let { kotlin.math.abs(it) }, signedRial = signed,
        balanceRial = balance, feeRial = null, mask = "", instrument = "unknown",
        merchant = "", merchantNorm = "", refNo = "", printedAt = "",
        channel = "unknown", unitPrinted = "none", inferred = false,
        parserVer = PARSER_VERSION,
    )

    private fun anchor(at: Long, rial: Long, account: String = "SAMAN") = BalanceAnchor(
        id = uuid7(at), accountId = account, at = at, balanceRial = rial,
        source = "user", createdAt = at, updatedAt = at,
    )

    @Test
    fun `a stated balance anchors the account and later transactions build on it`() {
        val rows = listOf(
            txn(at = 1, signed = 10_000_000, balance = 50_000_000),
            txn(at = 2, signed = -20_000_000),
            txn(at = 3, signed = 5_000_000),
        )
        val b = deriveBalance("SAMAN", rows, null)!!
        // 50,000,000 stated, then -20m and +5m after it. The stating message's own +10m is
        // inside the figure it stated and must not be added again.
        assertEquals(35_000_000L, b.rial)
        assertTrue(b.anchored)
    }

    @Test
    fun `the last stated balance wins however many messages came before it`() {
        // The same guarantee the fold had, and the same real Saman thread that proved it.
        val rows = listOf(
            txn(at = 1, signed = 3_000_000_000, balance = 19_787_503_090),
            txn(at = 2, signed = -1_083_381_300, balance = 19_787_503_090),
            txn(at = 3, signed = -50_000_000, balance = 19_607_503_090),
            txn(at = 4, signed = -15_000_000_000, balance = 4_607_253_090),
        )
        assertEquals(4_607_253_090L, deriveBalance("SAMAN", rows, null)!!.rial)
    }

    @Test
    fun `an out-of-order message needs no guard because it is just a row with a time`() {
        // The fold needed `if (sms.at < existing.updatedAt) return accounts` to survive this.
        // Nothing here needs to know what order the rows arrived in.
        val forwards = listOf(
            txn(at = 100, balance = 90_000_000, ref = "s:a:0"),
            txn(at = 500, signed = -1_000_000, ref = "s:b:0"),
        )
        val backwards = forwards.reversed()
        assertEquals(
            deriveBalance("SAMAN", forwards, null)!!.rial,
            deriveBalance("SAMAN", backwards, null)!!.rial,
        )
        assertEquals(89_000_000L, deriveBalance("SAMAN", backwards, null)!!.rial)
    }

    @Test
    fun `a balance built only from transactions is never trusted, and may go negative`() {
        val rows = listOf(txn(at = 1, signed = -25_000_000))
        val b = deriveBalance("SAMAN", rows, null)!!
        assertEquals(-25_000_000L, b.rial)
        assertTrue("a running sum is not a balance", !b.anchored)
    }

    @Test
    fun `a figure she typed in beats an older stated one, and a newer stated one beats hers`() {
        val rows = listOf(txn(at = 100, balance = 10_000_000))
        // Hers is newer, so hers is the anchor.
        assertEquals(90_000_000L, deriveBalance("SAMAN", rows, anchor(at = 200, rial = 90_000_000))!!.rial)
        // Hers is older, so the bank's own figure wins.
        assertEquals(10_000_000L, deriveBalance("SAMAN", rows, anchor(at = 50, rial = 90_000_000))!!.rial)
    }

    @Test
    fun `the bank takes a tie on the millisecond`() {
        // Its arithmetic against her memory, at the same instant. Arbitrary, but the same
        // arbitrary on every device — which is what a family ledger needs to agree with itself.
        val rows = listOf(txn(at = 100, balance = 10_000_000))
        assertEquals(10_000_000L, deriveBalance("SAMAN", rows, anchor(at = 100, rial = 90_000_000))!!.rial)
    }

    @Test
    fun `a transaction older than the balance she typed in does not move it`() {
        val rows = listOf(txn(at = 500, signed = 3_000_000_000))
        assertEquals(500_000_000L, deriveBalance("SAMAN", rows, anchor(at = 10_000, rial = 500_000_000))!!.rial)
    }

    @Test
    fun `an emptied account reads zero and is still anchored`() {
        val rows = listOf(txn(at = 2, signed = -50_000_000, balance = 0))
        val b = deriveBalance("SAMAN", rows, null)!!
        assertEquals(0L, b.rial)
        assertTrue(b.anchored)
    }

    @Test
    fun `an account nothing is known about has no balance at all`() {
        assertNull(deriveBalance("SAMAN", emptyList(), null))
    }

    @Test
    fun `an absurd figure is dropped rather than saturating the ledger`() {
        // A real message from a test phone: "واریز مبلغ 999999999999999999999". Twenty-one
        // digits parse into a Double that has already lost precision, and converting that to
        // Long saturates at 9.2 x 10^18 — which then syncs, sums into a report, and is a
        // confident wrong total. It is left out instead, exactly as an unpriced asset is.
        val absurd = SmsSource(
            srcHash = srcHash("100031", "x", 1L), sender = "100031", addrKey = "100031",
            body = "بانک رفاه واریز مبلغ 999999999999999999999 مانده 99999999999",
            at = 1L, ingestedAt = 0L,
        )
        assertTrue(parseToRows(absurd, emptyMap()).isEmpty())

        // …and something merely large is still money.
        val large = SmsSource(
            srcHash = srcHash("100031", "y", 2L), sender = "100031", addrKey = "100031",
            body = "بانک رفاه واریز مبلغ 900,000,000,000 ریال مانده 1,000,000,000,000 ریال",
            at = 2L, ingestedAt = 0L,
        )
        assertEquals(900_000_000_000L, parseToRows(large, emptyMap()).single().amountRial)
    }

    @Test
    fun `deriving twice produces identical rows`() {
        // B.5, the invariant that makes shipping a parser fix a thing you can do on a Tuesday.
        // Order-dependence anywhere in the pipeline shows up here and nowhere else.
        val sources = CorpusFixtures.sources()
        assertTrue("no sources to derive from", sources.size >= 25)
        val once = sources.flatMap { parseToRows(it, emptyMap()) }
        val twice = sources.flatMap { parseToRows(it, emptyMap()) }
        assertEquals(once, twice)
        // …and shuffling the input cannot change any row, only their order.
        val shuffled = sources.shuffled(kotlin.random.Random(7))
            .flatMap { parseToRows(it, emptyMap()) }
        assertEquals(once.sortedBy { it.ref }, shuffled.sortedBy { it.ref })
    }

    @Test
    fun `every corpus message that parses becomes exactly one transaction`() {
        val sources = CorpusFixtures.sources()
        val rows = sources.flatMap { parseToRows(it, emptyMap()) }
        // Every ref is unique, so nothing can be counted twice.
        assertEquals(rows.size, rows.map { it.ref }.distinct().size)
        // Every row carries the message it came from and a Tehran day.
        for (r in rows) {
            assertTrue(r.ref.startsWith("s:${r.srcHash}:"))
            assertEquals(tehranDay(r.at), r.day)
            assertEquals(PARSER_VERSION, r.parserVer)
            // signed_rial is present exactly when a direction is.
            assertEquals(r.direction != null && r.amountRial != null, r.signedRial != null)
            r.signedRial?.let {
                assertEquals(if (r.direction == "in") r.amountRial else -r.amountRial!!, it)
            }
        }
    }

}
