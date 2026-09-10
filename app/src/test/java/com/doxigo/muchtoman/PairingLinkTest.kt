package com.doxigo.muchtoman

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PairingLinkTest {
    private val allowed = "https://sync.muchtoman.com"

    /** Builds a link with the same shape [pairingUrl] emits. */
    private fun link(base: String, hid: String = "a".repeat(32), key: ByteArray = ByteArray(32)) =
        "$base/join#url=${Uri.encode(base)}&hid=$hid&pair=code123&scope=family:home" +
            "&k=${b64Url(key)}"

    @Test
    fun `legitimate link parses and round-trips`() {
        val invite = parsePairingLink(link(allowed), allowed)
        assertNotNull(invite)
        assertEquals(allowed, invite!!.base)
        assertEquals("a".repeat(32), invite.hid)
        assertEquals("family:home", invite.scope)
    }

    @Test
    fun `foreign origin is refused`() {
        assertNull(parsePairingLink(link("https://evil.example"), allowed))
    }

    @Test
    fun `same host different scheme is refused`() {
        assertNull(parsePairingLink(link("http://sync.muchtoman.com"), allowed))
    }

    @Test
    fun `explicit default port is the same origin`() {
        assertNotNull(parsePairingLink(link("https://sync.muchtoman.com:443"), allowed))
    }

    @Test
    fun `non-default port is refused`() {
        assertNull(parsePairingLink(link("https://sync.muchtoman.com:8443"), allowed))
    }

    @Test
    fun `host comparison ignores case`() {
        assertNotNull(parsePairingLink(link("https://SYNC.muchtoman.com"), allowed))
    }

    @Test
    fun `dev override allows a local server`() {
        assertNotNull(parsePairingLink(link("http://localhost:8788"), "http://localhost:8788"))
    }

    @Test
    fun `existing invariants still hold`() {
        // hid outside 16..64
        assertNull(parsePairingLink(link(allowed, hid = "a".repeat(8)), allowed))
        // key not 32 bytes
        assertNull(parsePairingLink(link(allowed, key = ByteArray(16)), allowed))
        // missing url=
        assertNull(
            parsePairingLink(
                "$allowed/join#hid=${"a".repeat(32)}&pair=code123&scope=family:home&k=${b64Url(ByteArray(32))}",
                allowed,
            )
        )
    }
}
