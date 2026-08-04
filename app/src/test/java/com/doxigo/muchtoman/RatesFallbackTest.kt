package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app does with a half-answer from the Worker. The Worker's own sources fail
 * independently, so "prices but no catalogue" is a normal response, not a broken one.
 * Run with: ./gradlew test
 */
class RatesFallbackTest {

    @Test
    fun `a response with no catalogue keeps the names we already had, and the new prices`() {
        val wallet = WalletOption("bitcoin", "بیت کوین")
        val cached = Rates(
            1L,
            mapOf("btc" to 1.0),
            listOf(Coin("btc", "بیت‌کوین", "Bitcoin", "u", listOf(wallet))),
        )
        // What the Worker sends when its name-and-logo source is the one that is down.
        val fresh = Rates(2L, mapOf("btc" to 2.0), emptyList())

        val merged = mergeRates(fresh, cached)
        assertEquals(2.0, merged.toman.getValue("btc"), 0.0) // the price is the new one
        assertEquals("بیت‌کوین", merged.coins.single().name) // the name did not go stale
        assertEquals(wallet, merged.coins.single().wallets.single())
        assertEquals(2L, merged.updatedAt)
    }

    @Test
    fun `a real catalogue always wins over the cached one`() {
        val cached = Rates(1L, emptyMap(), listOf(Coin("btc", "بیت‌کوین", "Bitcoin", "u")))
        val fresh = Rates(2L, emptyMap(), listOf(Coin("eth", "اتریوم", "Ethereum", "u")))
        assertEquals("eth", mergeRates(fresh, cached).coins.single().id)
    }

    @Test
    fun `an unreachable GitHub does not retract an update note already on screen`() {
        val cached = Rates(1L, mapOf("btc" to 1.0), emptyList(), Release("1.1.0", "u"))
        val fresh = Rates(2L, mapOf("btc" to 2.0), emptyList(), latest = null)
        assertEquals("1.1.0", mergeRates(fresh, cached).latest?.name)

        // …but a release the Worker did reach always wins, including a rolled-back one.
        val rolled = fresh.copy(latest = Release("1.0.9", "u"))
        assertEquals("1.0.9", mergeRates(rolled, cached).latest?.name)
    }

    @Test
    fun `a newer release is the one with a higher number, not a longer string`() {
        assertTrue(isNewerVersion("1.10.0", "1.9.0")) // the compare a string sort gets backwards
        assertTrue(isNewerVersion("2.0", "1.99.99"))
        assertTrue(isNewerVersion("1.0.1", "1.0"))

        assertFalse(isNewerVersion("1.0", "1.0.0")) // a missing component is a zero
        assertFalse(isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(isNewerVersion("1.9.0", "1.10.0"))
        assertFalse(isNewerVersion("1.2.3-beta", "1.2.3")) // a pre-release of this build is not an update
        assertFalse(isNewerVersion("", "1.0.0")) // nothing parsed = nothing to offer
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

    @Test
    fun `network catalogue URLs are restricted to the trusted services`() {
        val now = 1_000_000L
        val safeIcon = "https://rates.muchtoman.com/coin-icon?path=%2Fcoins%2Fimages%2F1%2Fsmall.png"
        val raw = Rates(
            updatedAt = now,
            toman = mapOf("btc" to 1.0, "bad" to Double.POSITIVE_INFINITY),
            coins = listOf(
                Coin("btc", "Bitcoin", icon = safeIcon),
                Coin("eth", "Ethereum", icon = "https://example.com/tracker.png"),
            ),
            latest = Release("2.0", "https://evil.example/fake.apk"),
        )

        val clean = sanitizeRates(raw, "https://rates.muchtoman.com/rates", now)

        assertEquals(setOf("btc"), clean.toman.keys)
        assertEquals(safeIcon, clean.coins.first().icon)
        assertEquals("", clean.coins.last().icon)
        assertNull(clean.latest)
    }

    @Test
    fun `a future rates timestamp cannot suppress refresh indefinitely`() {
        val now = 1_000_000L
        val raw = Rates(now + 5 * 60_000L + 1, mapOf("usd" to 1.0))
        assertEquals(0L, sanitizeRates(raw, "https://rates.muchtoman.com/rates", now).updatedAt)
    }
}
