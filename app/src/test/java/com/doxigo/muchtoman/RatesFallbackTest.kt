package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the app does with a half-answer from the Worker. The Worker's own sources fail
 * independently, so "prices but no catalogue" is a normal response, not a broken one.
 * Run with: ./gradlew test
 */
class RatesFallbackTest {

    @Test
    fun `a response with no catalogue keeps the names we already had, and the new prices`() {
        val cached = Rates(1L, mapOf("btc" to 1.0), listOf(Coin("btc", "بیت‌کوین", "Bitcoin", "u")))
        // What the Worker sends when its name-and-logo source is the one that is down.
        val fresh = Rates(2L, mapOf("btc" to 2.0), emptyList())

        val merged = mergeRates(fresh, cached)
        assertEquals(2.0, merged.toman.getValue("btc"), 0.0) // the price is the new one
        assertEquals("بیت‌کوین", merged.coins.single().name) // the name did not go stale
        assertEquals(2L, merged.updatedAt)
    }

    @Test
    fun `a real catalogue always wins over the cached one`() {
        val cached = Rates(1L, emptyMap(), listOf(Coin("btc", "بیت‌کوین", "Bitcoin", "u")))
        val fresh = Rates(2L, emptyMap(), listOf(Coin("eth", "اتریوم", "Ethereum", "u")))
        assertEquals("eth", mergeRates(fresh, cached).coins.single().id)
    }

    @Test
    fun `a coin with a price but no catalogue entry still counts toward the total`() {
        // The Worker now sends rates for ids that are not in the catalogue it shipped.
        // Nothing may make that coin fall out of her total.
        val rates = effectiveRates(Rates(1L, mapOf("dot" to 300_000.0), emptyList()), emptyMap())
        val totals = computeTotals(listOf(Holding("dot", 2.0)), rates)
        assertEquals(600_000.0, totals.toman, 0.01)
        assertEquals(emptyList<String>(), totals.missing)
    }
}
