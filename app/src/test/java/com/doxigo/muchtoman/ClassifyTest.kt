package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule table and the two detectors, both of which are pure functions of the transactions. */
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
    fun `the picker offers the side the money went, plus the way back from a transfer`() {
        val incoming = categoryChoices(BUILTIN_CATEGORIES, "in").map { it.nameFa }
        assertEquals(
            listOf(
                "درآمد", "حقوق", "فروش", "پاداش", "سود سرمایه‌گذاری", "پس‌گرفتن قرض", "همسر",
                "سایر", "انتقال بین حساب‌ها",
            ),
            incoming,
        )

        val outgoing = categoryChoices(BUILTIN_CATEGORIES, "out").map { it.nameFa }
        assertTrue("خواربار" in outgoing)
        assertTrue("سفر" in outgoing)
        assertTrue("درآمد" !in outgoing)

        // Retired, and never offered again: «انتقال وجه» named how the money left and never why,
        // which is the one thing filing it was supposed to record. It stays in the shipped list,
        // archived, so the rows already under it keep their name.
        assertTrue("انتقال وجه" !in outgoing)
        assertTrue(BUILTIN_CATEGORIES.any { it.nameFa == "انتقال وجه" && it.archived })

        // همسر is one of the others that ignores direction: money between the two of them is one
        // category whichever way it went, and it is an EXPENSE row, so only the id can carry it
        // into the incoming grid.
        assertTrue("همسر" in outgoing)

        // سایر is the third, and it runs the other way — an INCOME row that only its id carries
        // into the spending grid. One category and not a pair: two rows called سایر would be one
        // line in the month's report anyway, and one name is one mark to learn.
        assertTrue("سایر" in outgoing)
        assertEquals(1, BUILTIN_CATEGORIES.count { it.nameFa == "سایر" })

        // «دسته‌بندی نشده» is the absence of an answer and never one of them. انتقال is the one
        // that ignores direction: both legs of a real transfer must be able to reach it, and the
        // incoming leg is exactly the one that reads as invented income without it.
        for (list in listOf(incoming, outgoing, categoryChoices(BUILTIN_CATEGORIES, null).map { it.nameFa })) {
            assertTrue("انتقال بین حساب‌ها" in list)
            assertTrue("دسته‌بندی نشده" !in list)
        }

        // A direction the parser could not read is not a reason to hide half the answers. Two are
        // held back whichever way the money went: «دسته‌بندی نشده» and the archived «انتقال وجه».
        assertEquals(BUILTIN_CATEGORIES.size - 2, categoryChoices(BUILTIN_CATEGORIES, null).size)
    }

    /**
     * The picker learns where her money goes, and the two guards that keep it an order rather
     * than a shuffle: nothing moves on a single filing, and the ways out never move at all.
     */
    @Test
    fun `the picker puts what she actually files first`() {
        val today = jalaliDay(1405, 5, 1)
        fun filed(categoryId: String, daysAgo: Long, times: Int) = List(times) {
            LedgerEntry(
                txn = txn(at = tehranDayStart(today - daysAgo) + 1, signed = -1_000_000),
                categoryId = categoryId,
                categoryFa = "",
                confidence = Confidence.NONE,
                needsReview = false,
                duplicate = false,
                transfer = false,
            )
        }

        // Four recent trips to the pharmacy and three to the tailor, against a shipped order that
        // opens with خواربار and puts both of them well down the grid.
        val use = categoryUseOf(filed("cat_health", 3, 4) + filed("cat_clothing", 10, 3), today)
        val outgoing = categoryChoices(BUILTIN_CATEGORIES, "out", use).map { it.nameFa }
        assertEquals(listOf("سلامت", "مد و پوشاک"), outgoing.take(2))
        // Everything it did not promote holds the shipped order exactly, so the tail of the grid
        // stays where she last saw it.
        val untouched = categoryChoices(BUILTIN_CATEGORIES, "out").map { it.nameFa }
        assertEquals(untouched.filter { it !in setOf("سلامت", "مد و پوشاک") }, outgoing.drop(2))

        // One filing is not a habit. A grid that reorders itself the first time she answers is a
        // grid she has to read from the top every time.
        val once = categoryUseOf(filed("cat_beauty", 0, 1), today)
        assertEquals(untouched, categoryChoices(BUILTIN_CATEGORIES, "out", once).map { it.nameFa })

        // Older filings weigh less: the same four visits half a year back do not outrank three
        // from last week.
        val stale = categoryUseOf(filed("cat_health", 180, 4) + filed("cat_clothing", 7, 3), today)
        assertEquals("مد و پوشاک", categoryChoices(BUILTIN_CATEGORIES, "out", stale).first().nameFa)

        // The ways out stay ways out however often they are taken: a picker that learns to put
        // «none of the others» under her thumb has taught her to stop filing.
        val escapes = mapOf(CAT_OTHER to 40.0, CAT_TRANSFER to 40.0)
        val ordered = categoryChoices(BUILTIN_CATEGORIES, "out", escapes).map { it.nameFa }
        assertEquals(listOf("سایر", "انتقال بین حساب‌ها"), ordered.takeLast(2))

        // A transfer leg is not money moving, so it is not a data point about where money goes —
        // the same reason it is out of every total.
        assertEquals(
            emptyMap<String, Double>(),
            categoryUseOf(filed("cat_health", 1, 5).map { it.copy(transfer = true) }, today),
        )
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
    fun `a monthly cycle back to the same balance is rent, not a duplicate`() {
        // Same account, same amount, same مانده afterwards — a fixed rent that returns the
        // account to the same figure every month looks exactly like the balance match, and
        // without a window it was auto-hidden from every report for ever. Not even a question:
        // paying the rent every month is what an account is for.
        val months = (0..2L).map {
            txn(at = 1_000_000 + it * 30 * DAY_MS, signed = -80_000_000, balance = 120_000_000)
        }
        assertTrue(findDuplicates(months).isEmpty())
    }

    @Test
    fun `a reference number re-used across months is a cycle, not a duplicate`() {
        // Some banks print a contract or instalment number where the idempotency key belongs,
        // and it repeats every month by design.
        val a = txn(at = 1_000_000, signed = -5_000_000, refNo = "020/000016703")
        val b = txn(at = 1_000_000 + 30 * DAY_MS, signed = -5_000_000, refNo = "020/000016703")
        assertTrue(findDuplicates(listOf(a, b)).isEmpty())
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
    fun `two equal payments out cannot both claim the one leg that came in`() {
        // The mirror of the case above, and it was checked only from the sent side: each payment
        // saw exactly one counter-leg and looked unique from where it stood, so both auto-paired
        // with the same incoming leg — and the one of them that had actually left the household,
        // the rent say, vanished from the report as "a transfer".
        val rent = txn(at = 1_000_000, signed = -50_000_000, account = "SAMAN")
        val moved = txn(at = 1_040_000, signed = -50_000_000, account = "REFAH")
        val landed = txn(at = 1_080_000, signed = 50_000_000, account = "BLU")
        val links = findTransfers(listOf(rent, moved, landed))
        assertTrue("one of these is a real expense; neither may settle silently", links.none { it.auto })
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
        // Order-hostile on purpose: the later-in-time leg carries the lexicographically SMALLER
        // ref. Refs are hashes, so their order against time is a coin flip, and hiding by ref
        // order — which is how the pair happens to be stored — hid the original and kept the echo
        // whenever the flip came up wrong. This used to pass only because the test refs sorted
        // the same way time did.
        val early = txn(at = 1000, signed = -5_000_000, balance = 90_000_000, ref = "s:zzzz:0")
        val later = txn(at = 2000, signed = -5_000_000, balance = 90_000_000, ref = "s:aaaa:0")
        val links = findLinks(listOf(early, later), emptyList())
        val hidden = hiddenRefs(links)
        assertEquals(1, hidden.size)
        assertEquals("the later one is the one that goes", later.ref, hidden.single())
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
