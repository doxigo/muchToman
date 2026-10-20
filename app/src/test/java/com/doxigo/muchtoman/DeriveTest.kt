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
    fun `a poison-pill timestamp keeps its money and loses only its day`() {
        // A restore tool that writes DATE in microseconds stamps a message fifty thousand years
        // out. The Jalali arithmetic throws past year 3177, the row sorts newest-first for ever,
        // and rows already stored this way re-derive through here — so the clamp has to hold at
        // this gate too, not only at ingest. The figure is real money; only its stamp is
        // nonsense. The money is never wrong, only its day.
        val now = 1_700_000_000_000L
        val micro = SmsSource(
            srcHash = srcHash("100031", "z", 1_700_000_000_000_000L),
            sender = "100031", addrKey = "100031",
            body = "بانک رفاه واریز مبلغ 900,000 ریال مانده 1,000,000 ریال",
            at = 1_700_000_000_000_000L, ingestedAt = 0L,
        )
        val row = parseToRows(micro, emptyMap(), now).single()
        assertEquals(900_000L, row.amountRial)
        assertEquals(now + AT_FUTURE_SLACK_MS, row.at)
        assertEquals(tehranDay(row.at), row.day)

        // …and a stamp from before time clamps to the epoch rather than a negative day.
        val negative = micro.copy(srcHash = srcHash("100031", "z", -5L), at = -5L)
        assertEquals(0L, parseToRows(negative, emptyMap(), now).single().at)

        // A real stamp passes through untouched.
        assertEquals(2L, parseToRows(micro.copy(at = 2L), emptyMap(), now).single().at)
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
    fun `the bank's own مانده says which way the money went, and her answer beats it`() {
        fun saman(at: Long, body: String, sender: String = "0999 992 0000") = parseToRows(
            SmsSource(srcHash(sender, body, at), sender, srcAddrKeyV1(sender), body, at, 0L), emptyMap(),
        ).single()
        val rows = listOf(
            saman(1, "بانک سامان\nواریز مبلغ 1,000,000 ریال\nمانده 10,000,000 ریال"),
            // A deposit worded with a spend word, as some banks head an incoming transfer.
            saman(2, "بانک سامان\nانتقال وجه مبلغ 2,000,000 ریال\nمانده 12,000,000 ریال"),
            // The same transaction told again, worded its own way: a copy — Blu's SMS and its app's
            // notification are this — so it is measured as its original was, not as nothing moving.
            saman(3, "بانک سامان\nواریز مبلغ 2,000,000 ریال\nمانده 12,000,000 ریال", sender = "9820000"),
            // A deposit that names its fee names a spend word too, and read as no direction at all.
            saman(4, "بانک سامان\nواریز مبلغ 3,000,000 ریال\nکارمزد 5,000 ریال\nمانده 15,000,000 ریال"),
            // A message went missing before this one: the مانده moved by more than its amount,
            // so it proves nothing and the words stand.
            saman(5, "بانک سامان\nبرداشت مبلغ 1,000,000 ریال\nمانده 5,000,000 ریال"),
            // No مانده to measure by.
            saman(6, "بانک سامان\nانتقال وجه مبلغ 700,000 ریال"),
        )
        assertEquals(listOf("in", "out", "in", null, "out", "out"), rows.map { it.direction })
        fun paired(rows: Collection<Txn>) = findDuplicates(rows.toList())
            .any { setOf(it.aRef, it.bRef) == setOf(rows.first { r -> r.at == 2L }.ref, rows.first { r -> r.at == 3L }.ref) }

        val settled = settleDirections(rows.shuffled(kotlin.random.Random(7))).associateBy { it.at }
        assertEquals(listOf(1_000_000L, 2_000_000L, 2_000_000L, 3_000_000L, -1_000_000L, -700_000L),
            (1L..6L).map { settled.getValue(it).signedRial })
        assertEquals(false, settled.getValue(4).inferred)
        assertTrue(paired(settled.values))

        // Her answer beats the words where there is no مانده and beats the مانده too, and the copy
        // goes the way she said its original went, so the two are still one transaction.
        val hers = settleDirections(rows, mapOf(rows[5].ref to "in", rows[1].ref to "out")).associateBy { it.at }
        assertEquals(listOf(700_000L, -2_000_000L, -2_000_000L), listOf(6L, 2L, 3L).map { hers.getValue(it).signedRial })
        assertTrue(paired(hers.values))
        // Anything she could not have written is no answer.
        assertEquals(rows[0], settleDirections(listOf(rows[0]), mapOf(rows[0].ref to "sideways")).single())
    }

    @Test
    fun `a bank's two accounts are two accounts, and a message naming neither goes to the last`() {
        fun mellat(at: Long, body: String) = parseToRows(
            SmsSource(srcHash("Bank Mellat", body, at), "Bank Mellat", srcAddrKeyV1("Bank Mellat"), body, at, 0L), emptyMap(),
        ).single()
        val rows = assignAccounts(listOf(
            mellat(1, "برداشت1,000,000\nمانده9,000,000"),
            mellat(2, "حساب7591126460\nبرداشت1,000,000\nمانده8,000,000"),
            mellat(3, "حساب9750457\nواریز2,000,000\nمانده50,000,000"),
            mellat(4, "برداشت500,000\nمانده49,500,000"),
            // The same account starred out to its tail.
            mellat(5, "حساب***6460\nبرداشت700,000\nمانده7,300,000"),
        ).shuffled(kotlin.random.Random(3))).sortedBy { it.at }
        assertEquals(listOf("MELLAT", "MELLAT", "MELLAT:0457", "MELLAT:0457", "MELLAT"), rows.map { it.accountId })
        // Each account's مانده is measured against its own, not its sibling's.
        assertEquals(listOf(-1_000_000L, -1_000_000L, 2_000_000L, -500_000L, -700_000L), settleDirections(rows).map { it.signedRial })
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
