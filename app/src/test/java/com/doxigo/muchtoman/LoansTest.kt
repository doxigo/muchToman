package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «طلب و بدهی»: links, balances in their own units, the side, and the holdings a loan moves. */
class LoansTest {

    private var n = 0
    private val today = jalaliDay(1405, 7, 5)
    private val coin = STATIC_CATALOG.first { it.id == "coin_emami" }
    private val type: (String) -> AssetType = { id -> STATIC_CATALOG.first { it.id == id } }

    private fun entry(rial: Long, direction: String? = "out", day: Long = today, owner: String = ""): LedgerEntry {
        n++
        val ref = "s:%04d:0".format(n)
        return LedgerEntry(
            txn = Txn(
                ref = ref, srcHash = ref, seq = 0, at = tehranDayStart(day), day = day,
                bank = "SAMAN", accountId = "SAMAN", direction = direction,
                amountRial = rial, signedRial = if (direction == "out") -rial else rial,
                balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
                merchant = "کارت به کارت", merchantNorm = "کارت به کارت", refNo = "", printedAt = "",
                channel = "unknown", unitPrinted = "none", inferred = false,
                parserVer = PARSER_VERSION,
            ),
            categoryId = CAT_LOAN, categoryFa = "قرض", confidence = 100,
            needsReview = false, duplicate = false, transfer = false, ownerMemberId = owner,
        )
    }

    private val mahdi = LoanPerson("p1", "مهدی", createdAt = 1)
    private val hossein = LoanPerson("p2", "حسین", createdAt = 2)

    @Test
    fun `a link survives its round trip and refuses what this build never wrote`() {
        val link = LoanLink("0190-abc", -25_000_000)
        assertEquals(link, LoanLink.decode(link.encode()))
        assertNull(LoanLink.decode("p1:0"))
        assertNull(LoanLink.decode("p1:lots"))
        assertNull(LoanLink.decode(":5"))
        assertNull(LoanLink.decode("p1:${MAX_PLAUSIBLE_RIAL + 1}"))
        assertNull(LoanLink.decode("p1:-${MAX_PLAUSIBLE_RIAL + 1}"))
        // abs() of this one is still negative, and it slipped past the bound.
        assertNull(LoanLink.decode("p1:${Long.MIN_VALUE}"))
        assertNull(LoanLink.decode(null))
    }

    @Test
    fun `money out is owed to her, money in is owed by her, and no direction is no link`() {
        assertEquals(50_000_000L, loanLinkRial(entry(50_000_000, "out")))
        assertEquals(-50_000_000L, loanLinkRial(entry(50_000_000, "in")))
        assertNull(loanLinkRial(entry(50_000_000, null)))
        assertTrue(loanLinkable(entry(1, "in"), ""))
        assertFalse(loanLinkable(entry(1, "out", owner = "someone-else"), ""))
    }

    @Test
    fun `a balance is kept in what was lent and valued at today's rate`() {
        val lent = entry(50_000_000, "out", today - 50)
        val back = entry(25_000_000, "in", today - 3)
        val links = mapOf(
            lent.txn.ref to LoanLink(mahdi.id, 50_000_000),
            back.txn.ref to LoanLink(mahdi.id, -25_000_000),
            // A row the ledger has since pruned still counts.
            "s:gone:0" to LoanLink(mahdi.id, 1_000_000),
        )
        val coins = LoanMove("m1", mahdi.id, typeId = "coin_emami", amount = 2.0, day = today - 20, holdingKey = "h")
        val book = LoanBook(listOf(mahdi), listOf(coins))
        val view = loanView(mahdi, book, links, listOf(lent, back), mapOf("coin_emami" to 235_500_000.0))

        assertEquals(26_000_000L, view.rial)
        assertEquals(mapOf("coin_emami" to 2.0), view.units)
        assertEquals(2_600_000.0 + 471_000_000.0, view.toman, 0.001)
        assertEquals(LoanSide.OWED, view.side)
        assertEquals(1_000_000L, view.olderRial)
        assertEquals(3, view.events.size)
        assertEquals(back.txn.ref, view.events.first().entry?.txn?.ref)
        assertEquals("۲ سکه امامی و ۲٫۶ میلیون تومان", loanWhatFa(view, type))
    }

    @Test
    fun `money out to someone she owes pays it down and then turns it round`() {
        val borrowed = entry(9_000_000, "in")
        val links = mapOf(borrowed.txn.ref to LoanLink(hossein.id, -9_000_000))
        val book = LoanBook(listOf(hossein))
        val view = loanView(hossein, book, links, listOf(borrowed), emptyMap())
        assertEquals(LoanSide.OWE, view.side)
        assertEquals("بهش بدهکاری", loanSideFa(view.side))
        assertEquals(
            "با این، حسین ۴٫۱ میلیون تومان بهت بدهکار می‌شه.",
            loanAfterFa(view, 50_000_000, type),
        )
        assertEquals("با این، حسابت با حسین صاف می‌شه.", loanAfterFa(view, 9_000_000, type))
    }

    @Test
    fun `an asset with no rate is named and never counted as zero`() {
        val move = LoanMove("m", mahdi.id, typeId = "coin_emami", amount = 1.0, day = today)
        val view = loanView(mahdi, LoanBook(listOf(mahdi), listOf(move)), emptyMap(), emptyList(), emptyMap())
        assertEquals(listOf("coin_emami"), view.missing)
        assertEquals(LoanSide.OWED, view.side)
        val totals = loanTotals(listOf(view))
        assertEquals(0.0, totals.owedToman, 0.0)
        assertEquals(1, totals.owedPeople)
        assertEquals(listOf("coin_emami"), totals.missing)
    }

    @Test
    fun `a settled account sorts last and says so`() {
        val out = LoanMove("a", hossein.id, rial = 10_000_000, day = today - 2)
        val back = LoanMove("b", hossein.id, rial = -10_000_000, day = today)
        val owed = LoanMove("c", mahdi.id, rial = 1_000_000, day = today)
        val views = loanViews(LoanBook(listOf(hossein, mahdi), listOf(out, back, owed)), emptyMap(), emptyList(), emptyMap())
        assertEquals(listOf(mahdi.id, hossein.id), views.map { it.person.id })
        assertEquals(LoanSide.SETTLED, views.last().side)
        val totals = loanTotals(views)
        assertEquals(100_000.0, totals.owedToman, 0.0)
        assertEquals(0, totals.owePeople)
    }

    @Test
    fun `lending coins takes them out of the drawer, and only coins she has`() {
        val drawer = listOf(Holding("coin_emami", 4.0, id = "h1"), Holding("usd", 100.0, id = "h2"))
        val (after, key) = loanHoldings(drawer, "coin_emami", 2.0) { "new" }!!
        assertEquals("h1", key)
        assertEquals(2.0, after.first { it.key == "h1" }.amount, 0.0)
        assertNull(loanHoldings(drawer, "coin_emami", 5.0) { "new" })
        // Coming back with nothing to land on makes the holding.
        val (grown, made) = loanHoldings(drawer, "gold18", -3.5) { "new" }!!
        assertEquals("new", made)
        assertEquals(3.5, grown.first { it.key == "new" }.amount, 0.0)
        // Two holdings of one coin: the check names the fuller, which is the one lent from.
        val two = listOf(Holding("coin_emami", 1.0, id = "a"), Holding("coin_emami", 3.0, id = "b"))
        assertEquals("b", loanHoldingFor(two, "coin_emami", giving = true)?.key)
        assertEquals("b", loanHoldings(two, "coin_emami", 2.0) { "new" }!!.second)
        assertEquals("a", loanHoldingFor(two, "coin_emami", giving = false)?.key)
        assertNull(loanHoldingFor(drawer, "", giving = true))
        // A wallet-tracked holding is the chain's figure, not one a loan may move.
        val tracked = listOf(Holding("usdt", 50.0, wallet = WalletLink("trc20", "TRC20", "T..."), id = "w"))
        assertNull(loanHoldings(tracked, "usdt", 10.0) { "new" })
    }

    @Test
    fun `taking a move back puts the coins back, never below zero`() {
        val drawer = listOf(Holding("coin_emami", 2.0, id = "h1"))
        val lent = LoanMove("m", "p", typeId = "coin_emami", amount = 2.0, day = today, holdingKey = "h1")
        assertEquals(4.0, loanHoldingsUndo(drawer, lent).single().amount, 0.0)
        val returned = LoanMove("m", "p", typeId = "coin_emami", amount = -5.0, day = today, holdingKey = "h1")
        assertEquals(0.0, loanHoldingsUndo(drawer, returned).single().amount, 0.0)
    }

    @Test
    fun `a promise reads from today, and only while something is owed`() {
        val person = mahdi.copy(promise = today - 3)
        val owed = LoanMove("m", mahdi.id, rial = 8_000_000, day = today - 30)
        val view = loanView(person, LoanBook(listOf(person), listOf(owed)), emptyMap(), emptyList(), emptyMap())
        assertEquals("۳ روز از قرار گذشته" to true, loanPromiseFa(view, today))
        assertEquals("۳ روز از قرار گذشته" to true, loanSubFa(view, today, type))
        val later = view.copy(person = person.copy(promise = jalaliDay(1405, 8, 15)))
        assertEquals("قرار ۱۵ آبان" to false, loanPromiseFa(later, today))
        assertNull(loanPromiseFa(view.copy(side = LoanSide.SETTLED), today))
    }

    @Test
    fun `amounts are named the way she says them`() {
        assertEquals("۲ سکه امامی", loanAmountFa(coin, -2.0))
        assertEquals("۱۰۰ دلار آمریکا", loanAmountFa(type("usd"), 100.0))
        assertEquals("۳٫۵ گرم طلای ۱۸ عیار", loanAmountFa(type("gold18"), 3.5))
    }

    @Test
    fun `borrowed money can be filed as قرض from the income grid`() {
        val incoming = categoryChoices(BUILTIN_CATEGORIES, "in").map { it.id }
        assertTrue(CAT_LOAN in incoming)
        assertTrue(CAT_LOAN_BACK in incoming)
        assertTrue(CAT_LOAN in categoryChoices(BUILTIN_CATEGORIES, "out").map { it.id })
    }

    @Test
    fun `an empty name is not a person`() {
        assertNull(newLoanPerson("x", "   ", null, 0))
        assertEquals("مهدی", newLoanPerson("x", "  مهدی ", null, 0)?.name)
    }
}
