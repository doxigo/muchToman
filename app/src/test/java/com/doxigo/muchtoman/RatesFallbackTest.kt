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
    fun `the update note downloads the proxied APK, and only from the rates origin`() {
        val now = 1_000_000L
        val page = "https://github.com/doxigo/muchToman/releases/tag/v2.0"
        val origin = "https://rates.muchtoman.com"
        val ours = "$origin/rates"

        // The normal answer: the tap downloads the file rather than opening a github.com page.
        val proxied = sanitizeRates(
            Rates(now, mapOf("usd" to 1.0), latest = Release("2.0", page, "$origin/download")),
            ours,
            now,
        )
        assertEquals("$origin/download", proxied.latest?.downloadUrlFor(false))

        // Someone else's APK is dropped on its own — the release page survives it, because a
        // note that falls back to GitHub still beats no note at all.
        val hijacked = sanitizeRates(
            Rates(now, mapOf("usd" to 1.0), latest = Release("2.0", page, "https://evil.example/x/download")),
            ours,
            now,
        )
        assertEquals("", hijacked.latest?.apk)
        assertEquals(page, hijacked.latest?.downloadUrlFor(false))

        // Right origin, wrong path: /download is the only thing this link may ever be.
        val elsewhere = sanitizeRates(
            Rates(now, mapOf("usd" to 1.0), latest = Release("2.0", page, "$origin/rates")),
            ours,
            now,
        )
        assertEquals(page, elsewhere.latest?.downloadUrlFor(false))

        // No APK in the release the Worker saw: the page is all there is, and it is still shown.
        val pageOnly = sanitizeRates(
            Rates(now, mapOf("usd" to 1.0), latest = Release("2.0", page)),
            ours,
            now,
        )
        assertEquals(page, pageOnly.latest?.downloadUrlFor(false))
    }

    @Test
    fun `release notes are trimmed of anything that would misdraw the update sheet`() {
        val now = 1_000_000L
        val page = "https://github.com/doxigo/muchToman/releases/tag/v2.0"
        val ours = "https://rates.muchtoman.com/rates"

        val clean = sanitizeRates(
            Rates(
                now,
                mapOf("usd" to 1.0),
                latest = Release(
                    "2.0",
                    page,
                    notes = listOf(
                        "  سود بانکی رو دیگه درست می‌خونه  ",
                        // A control character in a bullet is either a mistake upstream or an
                        // attempt to draw something the sheet's own layout did not put there.
                        "دو تا بانک\u0000 جدید",
                        "",
                        "   ",
                        "ط".repeat(200),
                        "پنجم",
                        "ششم",
                        "هفتم",
                        "هشتم که دیگه جا نمی‌شه",
                    ),
                ),
            ),
            ours,
            now,
        )

        val notes = clean.latest!!.notes
        // The blanks are gone rather than kept as empty bullets, so the six that survive are
        // six real lines, and it is the eighth that falls off the end.
        assertEquals(6, notes.size)
        assertEquals("سود بانکی رو دیگه درست می‌خونه", notes.first())
        assertEquals("دو تا بانک جدید", notes[1])
        assertEquals(120, notes[2].length)
        assertEquals(listOf("پنجم", "ششم", "هفتم"), notes.drop(3))

        // A release with nothing to say still announces itself.
        val bare = sanitizeRates(
            Rates(now, mapOf("usd" to 1.0), latest = Release("2.0", page)),
            ours,
            now,
        )
        assertEquals(emptyList<String>(), bare.latest?.notes)
    }

    @Test
    fun `a future rates timestamp cannot suppress refresh indefinitely`() {
        val now = 1_000_000L
        val raw = Rates(now + 5 * 60_000L + 1, mapOf("usd" to 1.0))
        assertEquals(0L, sanitizeRates(raw, "https://rates.muchtoman.com/rates", now).updatedAt)
    }
}
