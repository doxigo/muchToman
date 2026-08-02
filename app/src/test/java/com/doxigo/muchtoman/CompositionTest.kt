package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionTest {

    private val rates = mapOf(TOMAN_ID to 1.0, "usd" to 100.0, "gold18" to 500.0, "btc" to 9_000.0)

    @Test
    fun `parts sum to the same total the hero shows`() {
        val holdings = listOf(
            Holding(TOMAN_ID, 1_000.0),
            Holding("usd", 10.0),
            Holding("gold18", 2.0),
            Holding("btc", 1.0),
        )
        val parts = compositionByKind(holdings, emptyList(), rates)

        assertEquals(computeTotals(holdings, rates).toman, parts.sumOf { it.second }, 0.0)
        assertEquals(
            listOf(Kind.CASH, Kind.FIAT, Kind.GOLD, Kind.CRYPTO),
            parts.map { it.first },
        )
    }

    @Test
    fun `an excluded holding is not part of the composition`() {
        val holdings = listOf(Holding(TOMAN_ID, 1_000.0), Holding("usd", 10.0, excluded = true))
        val parts = compositionByKind(holdings, emptyList(), rates)

        assertEquals(listOf(Kind.CASH), parts.map { it.first })
    }

    @Test
    fun `a kind worth nothing is left out`() {
        val holdings = listOf(Holding(TOMAN_ID, 1_000.0), Holding("usd", 0.0))
        assertEquals(listOf(Kind.CASH), compositionByKind(holdings, emptyList(), rates).map { it.first })
    }
}
