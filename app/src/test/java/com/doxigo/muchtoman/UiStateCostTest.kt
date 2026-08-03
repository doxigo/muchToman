package com.doxigo.muchtoman

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The screen reads [UiState.effective], [UiState.listHoldings] and [UiState.totals] the better
 * part of ten times in one composition pass, and building `effective` copies the whole rate map
 * three times over — the Worker's several hundred plus a couple of thousand نماد once بورس has
 * loaded. As `get()`s that was ~0.6 ms of pure rebuilding per pass on a warmed desktop JVM,
 * paid again on every frame of a scroll; the رمزارز list stuttered for it, worst on an old
 * phone. `by lazy` makes it one build per state.
 *
 * Identity is the whole assertion: the same instance back means nothing was rebuilt. Turn any
 * of the three back into a `get()` and this fails.
 */
class UiStateCostTest {

    private val state = UiState(
        holdings = listOf(Holding("usd", 2.0), Holding("btc", 0.5)),
        rates = Rates(1L, mapOf("usd" to 100_000.0, "btc" to 9e9)),
        tse = TseSnapshot(1L, mapOf("tse_1" to 500.0)),
        smsEnabled = true,
        bankAccounts = listOf(BankAccount("MELLAT", balance = 1_000_000.0, anchored = true)),
    )

    @Test
    fun `each derived value is built once per state`() {
        assertSame(state.effective, state.effective)
        assertSame(state.listHoldings, state.listHoldings)
        assertSame(state.totals, state.totals)
    }

    @Test
    fun `a copied state rebuilds them, so nothing is ever stale`() {
        val next = state.copy(holdings = state.holdings + Holding(TOMAN_ID, 5.0))

        assertNotSame(state.listHoldings, next.listHoldings)
        assertNotSame(state.totals, next.totals)
        // The one that matters: a new rate really does reach the total.
        val richer = state.copy(rates = Rates(2L, state.rates.toman + ("usd" to 200_000.0)))
        assert(richer.totals.toman > state.totals.toman)
    }
}
