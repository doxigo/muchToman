package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule table and the four detectors, both of which are pure functions of the transactions. */
class ClassifyTest {

    private var n = 0

    /**
     * Every call gets its own ref, whether or not [at] was passed. The first version of this
     * derived the counter from the `at` default, so any two transactions given an explicit time
     * shared a ref — and a detector that skips `it.ref != fee.ref` then silently found nothing.
     */
    private fun txn(
        at: Long = 0L,
        signed: Long? = null,
        balance: Long? = null,
        account: String = "SAMAN",
        merchant: String = "",
        channel: String = "unknown",
        refNo: String = "",
        instrument: String = "unknown",
        ref: String? = null,
    ): Txn {
        n++
        val moment = if (at == 0L) n * 1000L else at
        val id = ref ?: "s:%04d:0".format(n)
        return Txn(
            ref = id, srcHash = id, seq = 0, at = moment, day = tehranDay(moment),
            bank = account, accountId = account,
            direction = signed?.let { if (it > 0) "in" else "out" },
            amountRial = signed?.let { kotlin.math.abs(it) }, signedRial = signed,
            balanceRial = balance, feeRial = null, mask = "", instrument = instrument,
            merchant = merchant, merchantNorm = merchantNorm(merchant), refNo = refNo,
            printedAt = "", channel = channel, unitPrinted = "none", inferred = false,
            parserVer = PARSER_VERSION,
        )
    }

    // ─────────────────────────── rules ───────────────────────────

    @Test
    fun `a rule is a conjunction and null means don't care`() {
        val rule = Rule(
            "r1", Priority.USER_OTHER, "cat_transport",
            pChannel = "pos", pDirection = "out", pMaxRial = 500_000,
        )
        assertTrue(rule.matches(txn(signed = -400_000, channel = "pos")))
        assertTrue("nothing is said about the merchant", rule.matches(txn(signed = -400_000, channel = "pos", merchant = "هرچیزی")))
        assertTrue("over the cap", !rule.matches(txn(signed = -600_000, channel = "pos")))
        assertTrue("wrong channel", !rule.matches(txn(signed = -400_000, channel = "atm")))
        assertTrue("wrong direction", !rule.matches(txn(signed = 400_000, channel = "pos")))
    }

    @Test
    fun `an amount predicate cannot match a transaction with no amount`() {
        // A balance-only message states no amount, and a rule about size must not fire on it.
        val rule = Rule("r1", Priority.USER_OTHER, "cat_shopping", pMinRial = 1)
        assertTrue(!rule.matches(txn(balance = 5_000_000)))
    }

    @Test
    fun `a decision she pinned to this transaction beats every rule`() {
        val rules = listOf(Rule("r1", Priority.USER_EXACT_MERCHANT, "cat_shopping", pChannel = "pos"))
        val c = classify(txn(signed = -1000, channel = "pos"), rules, pinned = "cat_health")
        assertEquals("cat_health", c.categoryId)
        assertEquals(Confidence.USER_PINNED, c.confidence)
        assertTrue(!c.needsReview)
    }

    @Test
    fun `a higher priority wins, then specificity, then the id`() {
        val t = txn(signed = -1000, channel = "pos", merchant = "دیجی کالا")
        val shipped = Rule("z", Priority.SHIPPED, "cat_shopping", pChannel = "pos")
        val hers = Rule("a", Priority.USER_EXACT_MERCHANT, "cat_health", pMerchantNorm = "دیجی کالا")
        assertEquals("cat_health", classify(t, listOf(shipped, hers)).categoryId)

        // Equal in every way but the id. The id tiebreak is a sync requirement: without it two
        // phones classify the same transaction differently and the family ledger disagrees with
        // itself for reasons nobody can debug.
        val a = Rule("aaa", Priority.USER_OTHER, "cat_dining", pChannel = "pos")
        val b = Rule("bbb", Priority.USER_OTHER, "cat_transport", pChannel = "pos")
        assertEquals("cat_transport", classify(t, listOf(a, b)).categoryId)
        assertEquals("cat_transport", classify(t, listOf(b, a)).categoryId)
    }

    @Test
    fun `matching nothing is uncategorised and always asked about`() {
        val c = classify(txn(signed = -1000), emptyList())
        assertEquals(CAT_UNCATEGORISED, c.categoryId)
        assertEquals(Confidence.NONE, c.confidence)
        assertTrue(c.needsReview)
    }

    @Test
    fun `a shipped guess asks once and her answer never asks again`() {
        // The whole learning loop, and it is a threshold rather than a model.
        val atm = txn(signed = -2_000_000, channel = "atm")
        val shipped = classify(atm, BUILTIN_RULES)
        assertEquals(CAT_CASH, shipped.categoryId)
        assertTrue("a shipped guess must be confirmed once", shipped.needsReview)
        assertTrue(shipped.confidence < REVIEW_BELOW)

        val hers = ruleFrom(atm, CAT_CASH, addrKey = "20004861", now = 5)
        val after = classify(atm, BUILTIN_RULES + hers)
        assertEquals(CAT_CASH, after.categoryId)
        assertTrue("and never again after that", !after.needsReview)
        assertTrue(after.confidence >= REVIEW_BELOW)
    }

    @Test
    fun `always-do-this keys on the merchant when there is one and the sender when there is not`() {
        val withMerchant = txn(signed = -1000, merchant = "فروشگاه رفاه", channel = "pos")
        val r1 = ruleFrom(withMerchant, "cat_groceries", addrKey = "100031", now = 1)
        assertEquals("فروشگاه رفاه", r1.pMerchantNorm)
        assertEquals(null, r1.pAddrKey)
        assertEquals(Priority.USER_EXACT_MERCHANT, r1.priority)
        assertEquals(withMerchant.ref, r1.originRef)

        val without = txn(signed = -1000, channel = "atm")
        val r2 = ruleFrom(without, CAT_CASH, addrKey = "20004861", now = 1)
        assertEquals(null, r2.pMerchantNorm)
        assertEquals("20004861", r2.pAddrKey)
        assertEquals("atm", r2.pChannel)
        assertEquals(Priority.USER_OTHER, r2.priority)
    }

    @Test
    fun `a settled transfer is bookkeeping, not a spending decision`() {
        val t = txn(signed = -50_000_000, channel = "transfer")
        val c = classify(t, BUILTIN_RULES, transferRefs = setOf(t.ref))
        assertEquals(CAT_TRANSFER, c.categoryId)
        assertTrue(!c.needsReview)
    }

    @Test
    fun `the picker offers only the side of the ledger the money went`() {
        val incoming = categoryChoices(BUILTIN_CATEGORIES, "in").map { it.nameFa }
        assertEquals(listOf("درآمد", "پس‌گرفتن قرض"), incoming)

        val outgoing = categoryChoices(BUILTIN_CATEGORIES, "out").map { it.nameFa }
        assertTrue("خواربار" in outgoing)
        assertTrue("درآمد" !in outgoing)

        // Neither list may offer the two that are never hers to pick.
        for (list in listOf(incoming, outgoing, categoryChoices(BUILTIN_CATEGORIES, null).map { it.nameFa })) {
            assertTrue("انتقال بین حساب‌ها" !in list)
            assertTrue("دسته‌بندی نشده" !in list)
        }

        // A direction the parser could not read is not a reason to hide half the answers.
        assertEquals(BUILTIN_CATEGORIES.size - 2, categoryChoices(BUILTIN_CATEGORIES, null).size)
    }

    // ─────────────────────────── links ───────────────────────────

    @Test
    fun `the bank's own reference number settles a duplicate outright`() {
        val a = txn(at = 1000, signed = -5_000_000, refNo = "020/000016703")
        val b = txn(at = 9_000_000, signed = -5_000_000, refNo = "020/000016703")
        val d = findDuplicates(listOf(a, b)).single()
        assertEquals(LinkKind.DUPLICATE, d.kind)
        assertEquals("refno", d.reason)
        assertTrue(d.auto)
    }

    @Test
    fun `two purchases cannot share a post-transaction balance`() {
        val a = txn(at = 1000, signed = -5_000_000, balance = 90_000_000)
        val b = txn(at = 2000, signed = -5_000_000, balance = 90_000_000)
        val d = findDuplicates(listOf(a, b)).single()
        assertEquals("balance", d.reason)
        assertTrue(d.auto)
    }

    @Test
    fun `two identical taxi fares a minute apart are asked about, never merged`() {
        val a = txn(at = 1000, signed = -500_000, merchant = "اسنپ")
        val b = txn(at = 41_000, signed = -500_000, merchant = "اسنپ")
        val d = findDuplicates(listOf(a, b)).single()
        assertEquals("near", d.reason)
        assertTrue("this one is real often enough that only she may decide", !d.auto)
    }

    @Test
    fun `a transfer between her own accounts is found and both legs come back`() {
        val out = txn(at = 1_000_000, signed = -50_000_000, account = "SAMAN")
        val into = txn(at = 1_060_000, signed = 50_000_000, account = "BLU")
        val link = findTransfers(listOf(out, into)).single()
        assertEquals(LinkKind.TRANSFER, link.kind)
        assertEquals("exact", link.reason)
        assertTrue(link.auto)
        assertEquals(setOf(out.ref, into.ref), transferRefs(listOf(link)))
    }

    @Test
    fun `a fee taken out of the transfer is found but has to be confirmed`() {
        val out = txn(at = 1_000_000, signed = -50_000_000, account = "SAMAN")
        val into = txn(at = 1_060_000, signed = 49_940_000, account = "BLU")
        val link = findTransfers(listOf(out, into)).single()
        assertEquals("fee-band", link.reason)
        assertTrue(!link.auto)
    }

    @Test
    fun `paya is never automatic because forty hours is too wide to trust`() {
        val out = txn(at = 1_000_000, signed = -50_000_000, account = "SAMAN", channel = "paya")
        val into = txn(at = 1_000_000 + 30L * 3600_000, signed = 50_000_000, account = "BLU")
        val link = findTransfers(listOf(out, into)).single()
        assertEquals("paya", link.reason)
        assertTrue(!link.auto)
    }

    @Test
    fun `the receiving bank is usually the one that names the rail`() {
        // Only the credit says «پایا» — سامان's debit calls it nothing but an انتقال. Reading
        // the rail off the sender alone gave this pair the fifteen-minute window and left a
        // real transfer showing as spending on one side and income on the other.
        val out = txn(at = 1_000_000, signed = -75_000_000, account = "SAMAN", channel = "transfer")
        val into = txn(at = 1_000_000 + 6L * 3600_000, signed = 75_000_000, account = "BLU", channel = "paya")
        val link = findTransfers(listOf(out, into)).single()
        assertEquals("paya", link.reason)
        assertTrue("forty hours is never automatic, whichever leg named it", !link.auto)
    }

    @Test
    fun `satna is a slow rail too, not an instant one`() {
        val out = txn(at = 1_000_000, signed = -75_000_000, account = "SAMAN", channel = "satna")
        val into = txn(at = 1_000_000 + 4L * 3600_000, signed = 75_000_000, account = "BLU")
        val link = findTransfers(listOf(out, into)).single()
        assertEquals("satna", link.reason)
        assertTrue(!link.auto)
    }

    @Test
    fun `an instant rail still gets only its fifteen minutes`() {
        // The wide window is for the slow rails alone. Two unrelated equal amounts six hours
        // apart must stay unrelated, or every salary and rent of the same size pairs up.
        val out = txn(at = 1_000_000, signed = -75_000_000, account = "SAMAN", channel = "card")
        val into = txn(at = 1_000_000 + 6L * 3600_000, signed = 75_000_000, account = "BLU")
        assertTrue(findTransfers(listOf(out, into)).isEmpty())
    }

    @Test
    fun `money sent to someone else is spending, not a transfer`() {
        // Both legs have to be in the ledger. Pretending otherwise makes her spending vanish.
        val out = txn(at = 1_000_000, signed = -50_000_000, account = "SAMAN")
        assertTrue(findTransfers(listOf(out)).isEmpty())
        // …and a matching credit on the SAME account is not a transfer either.
        val sameAccount = txn(at = 1_060_000, signed = 50_000_000, account = "SAMAN")
        assertTrue(findTransfers(listOf(out, sameAccount)).isEmpty())
    }

    @Test
    fun `two possible counter-legs are never linked automatically`() {
        val out = txn(at = 1_000_000, signed = -50_000_000, account = "SAMAN")
        val a = txn(at = 1_030_000, signed = 50_000_000, account = "BLU")
        val b = txn(at = 1_060_000, signed = 50_000_000, account = "REFAH")
        val link = findTransfers(listOf(out, a, b)).single()
        assertTrue("ambiguous matches go to the deck", !link.auto)
        assertTrue("and it takes the nearest in time", link.aRef == a.ref || link.bRef == a.ref)
    }

    @Test
    fun `a refund that says so is linked, and one that does not is asked about`() {
        val spent = txn(at = 1_000_000, signed = -3_000_000, merchant = "دیجی کالا")
        val back = txn(at = 5_000_000, signed = 3_000_000, merchant = "دیجی کالا")
        val labelled = findRefunds(listOf(spent, back)) {
            if (it.ref == back.ref) "برگشت وجه خرید" else ""
        }.single()
        assertEquals("keyword", labelled.reason)
        assertTrue(labelled.auto)

        val quiet = findRefunds(listOf(spent, back)) { "" }.single()
        assertEquals("merchant", quiet.reason)
        assertTrue(!quiet.auto)
    }

    @Test
    fun `a small outflow just after a big one may be its fee, and she decides`() {
        val purchase = txn(at = 1_000_000, signed = -5_000_000)
        val fee = txn(at = 1_030_000, signed = -75_000)
        val link = findLooseFees(listOf(purchase, fee)).single()
        assertEquals(LinkKind.FEE_OF, link.kind)
        assertTrue(!link.auto)
        // A fee the bank labelled needs no guessing at all.
        val labelled = txn(at = 1_030_000, signed = -75_000, channel = "fee")
        assertTrue(findLooseFees(listOf(purchase, labelled)).isEmpty())
    }

    @Test
    fun `her verdict always wins, in both directions`() {
        val a = txn(at = 1000, signed = -5_000_000, balance = 90_000_000)
        val b = txn(at = 2000, signed = -5_000_000, balance = 90_000_000)
        val found = findLinks(listOf(a, b), emptyList())
        assertEquals(1, found.size)

        val (lo, hi) = if (a.ref <= b.ref) a.ref to b.ref else b.ref to a.ref
        val rejected = findLinks(
            listOf(a, b),
            listOf(linkDecision(lo, hi, LinkKind.DUPLICATE, Verdict.REJECTED, 1)),
        )
        assertTrue("a rejection suppresses it for ever", rejected.isEmpty())

        // …and a confirmation creates a pair the detectors never proposed.
        val invented = findLinks(
            listOf(a, b),
            listOf(linkDecision("s:x:0", "s:y:0", LinkKind.TRANSFER, Verdict.CONFIRMED, 1)),
        )
        assertNotNull(invented.firstOrNull { it.kind == LinkKind.TRANSFER })
        assertTrue(invented.first { it.kind == LinkKind.TRANSFER }.auto)
    }

    @Test
    fun `a duplicate hides its later leg and never deletes anything`() {
        val a = txn(at = 1000, signed = -5_000_000, balance = 90_000_000)
        val b = txn(at = 2000, signed = -5_000_000, balance = 90_000_000)
        val links = findLinks(listOf(a, b), emptyList())
        val hidden = hiddenRefs(links)
        assertEquals(1, hidden.size)
        assertTrue("the later one is the one that goes", hidden.single() == maxOf(a.ref, b.ref))
    }

    @Test
    fun `detection is order-independent`() {
        val rows = listOf(
            txn(at = 1_000_000, signed = -50_000_000, account = "SAMAN"),
            txn(at = 1_060_000, signed = 50_000_000, account = "BLU"),
            txn(at = 3_000_000, signed = -5_000_000, refNo = "R1"),
            txn(at = 4_000_000, signed = -5_000_000, refNo = "R1"),
        )
        val forwards = findLinks(rows, emptyList()).toSet()
        val backwards = findLinks(rows.reversed(), emptyList()).toSet()
        assertEquals(forwards, backwards)
    }
}
