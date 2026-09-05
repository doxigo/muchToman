package com.doxigo.muchtoman

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class LinkRegressionTest {
    private fun txn(id: String, at: Long, signed: Long, account: String = "SAMAN", channel: String = "unknown") = Txn(
        ref = id, srcHash = id, seq = 0, at = at, day = 0, bank = "SAMAN", accountId = account,
        direction = if (signed > 0) "in" else "out", amountRial = abs(signed), signedRial = signed,
        balanceRial = null, feeRial = null, mask = "", instrument = "unknown", merchant = "",
        merchantNorm = "", refNo = "", printedAt = "", channel = channel, unitPrinted = "rial",
        inferred = false, parserVer = PARSER_VERSION,
    )

    @Test
    fun `a reused reference cannot hide different money or transfer legs`() {
        val a = txn("a", 1, -1_000_000).copy(refNo = "reference")
        val incompatible = listOf(
            txn("b", 2, 1_000_000, "OTHER"),
            txn("b", 2, -2_000_000),
            txn("b", 2, -1_000_000, "OTHER"),
            txn("b", 2, 1_000_000),
            a.copy(ref = "b", at = 2, direction = null, signedRial = null),
        )
        for (b in incompatible) {
            val links = findDuplicates(listOf(a, b.copy(refNo = a.refNo)))
            assertTrue(links.isNotEmpty())
            assertTrue(hiddenRefs(links).isEmpty())
            assertFalse(links.any { it.auto })
        }
    }

    @Test
    fun `matching references with contradictory card masks remain questions`() {
        val a = txn("a", 1, -1_000_000).copy(refNo = "reference", mask = "1234")
        val b = a.copy(ref = "b", at = 2, mask = "5678")
        assertTrue(hiddenRefs(findDuplicates(listOf(a, b))).isEmpty())
    }

    @Test
    fun `a compatible repeated message is hidden but a later installment remains visible`() {
        val a = txn("a", 1, -1_000_000).copy(refNo = "reference")
        val repeated = a.copy(ref = "b", at = 2)
        assertEquals(setOf("b"), hiddenRefs(findDuplicates(listOf(a, repeated))))
        val installment = a.copy(ref = "c", at = 31 * DAY_MS)
        assertTrue(findDuplicates(listOf(a, installment)).isEmpty())
    }

    @Test
    fun `time index preserves brute force matching over mixed rails boundaries and competing legs`() {
        val random = Random(54321)
        repeat(100) { round ->
            val rows = (0 until 80).map { i ->
                val channel = listOf("unknown", "paya", "satna")[random.nextInt(3)]
                txn(
                    "$round-$i", random.nextLong(0, 100 * 3_600_000L),
                    (if (random.nextBoolean()) 1 else -1) * random.nextLong(1, 6) * 100_000,
                    "account-${random.nextInt(3)}", channel,
                )
            }.shuffled(random)
            assertEquals(bruteTransfers(rows).toSet(), findTransfers(rows).toSet())
        }
        for (window in listOf(TRANSFER_WINDOW_MS, SLOW_RAIL_WINDOW_MS)) {
            for (distance in listOf(-window - 1, -window, window, window + 1)) {
                val outgoing = txn("out", 200_000_000, -500_000)
                val incoming = txn("in", outgoing.at + distance, 500_000, "OTHER",
                    if (window == SLOW_RAIL_WINDOW_MS) "paya" else "unknown")
                assertEquals(bruteTransfers(listOf(outgoing, incoming)), findTransfers(listOf(outgoing, incoming)))
            }
        }
    }

    private fun bruteTransfers(rows: List<Txn>): List<LinkCandidate> {
        fun rail(a: Txn, b: Txn) = listOf(a.channel, b.channel).firstOrNull { it == "paya" || it == "satna" }
        val candidates = rows.filter { it.direction == "out" && it.amountRial != null }.map { sent ->
            sent to rows.filter { received ->
                val slow = rail(sent, received) != null
                received.direction == "in" && received.amountRial != null &&
                    received.accountId != sent.accountId &&
                    abs(received.at - sent.at) <= (if (slow) SLOW_RAIL_WINDOW_MS else TRANSFER_WINDOW_MS) &&
                    if (slow) received.amountRial == sent.amountRial else {
                        received.amountRial <= sent.amountRial!! &&
                            sent.amountRial - received.amountRial <= TRANSFER_FEE_MAX_RIAL
                    }
            }
        }
        val claims = candidates.flatMap { it.second }.groupingBy { it.ref }.eachCount()
        return candidates.mapNotNull { (sent, matches) ->
            val nearest = matches.minWithOrNull(compareBy({ abs(it.at - sent.at) }, { it.ref }))
                ?: return@mapNotNull null
            val slow = rail(sent, nearest)
            val exact = sent.amountRial == nearest.amountRial
            val refs = listOf(sent.ref, nearest.ref).sorted()
            val later = if (sent.at > nearest.at || (sent.at == nearest.at && sent.ref > nearest.ref)) sent else nearest
            LinkCandidate(
                refs[0], refs[1], LinkKind.TRANSFER,
                slow ?: if (exact) "exact" else "fee-band",
                matches.size == 1 && claims[nearest.ref] == 1 && exact && slow == null,
                later.ref,
            )
        }
    }
}
