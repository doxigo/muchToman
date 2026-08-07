package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The parts of the ingest layer that are pure. The Room side is exercised on a device — these
 * are the pieces a year of her corrections will hang off, so they are worth pinning here where
 * the check costs nothing to run.
 */
class LedgerTest {

    @Test
    fun `the frozen address key still agrees with the live one`() {
        // These two are the same function today and are allowed to drift apart tomorrow.
        //
        // READ THIS BEFORE "FIXING" A FAILURE HERE. When senderKey legitimately changes — a
        // carrier delivers a header in a shape nobody expected, as has already happened once —
        // the correct response is to DELETE this test, not to update srcAddrKeyV1 to match.
        // srcAddrKeyV1 changing rewrites every src_hash, which orphans every correction
        // attached to every message at once. That is what the copy exists to prevent, and this
        // test exists only to make the moment they diverge a deliberate act rather than a
        // surprise.
        val senders = listOf(
            "0999 992 0000", "09999920000", "+989999920000", "989999920000", "9999920000",
            "0999-992-0000", "۰۹۹۹۹۹۲۰۰۰۰", "100031", "Refah Bank", "REFAH BANK",
            " refah  bank ", "Refah Bank", "RefahBank", "B.Pasargad", "ENBank",
            "PARSIANBANK", "Bank Mellat", "Tel100031", "+98 9870 0719", "20004861", "",
        )
        for (sender in senders) {
            assertEquals("«$sender»", senderKey(sender), srcAddrKeyV1(sender))
        }
    }

    @Test
    fun `the message hash is pinned to a value computed outside this codebase`() {
        // Independently computed with sha256(addrKey + NUL + at + NUL + trimmed body). If this
        // ever fails, the identity of every stored message has changed and every decision
        // pointing at one has been orphaned — which is a migration, never a patch.
        assertEquals(
            "278256bcb1a2ef5ba3dc2951413be77381f974b637d64a3a153a054ffbc9af1a",
            srcHash("0999 992 0000", "بانک سامان", 1L),
        )
        assertEquals(
            "8ce70b641e0f2b5dee28dd826ba65a76e7199d55b92265478976e1391d2b9694",
            srcHash("Refah Bank", "مانده 80,000,000 ریال", 42L),
        )
    }

    @Test
    fun `one message written three ways by the network is one message`() {
        val body = "بانک سامان\nواریز 1,000,000 ریال"
        val canonical = srcHash("+989999920000", body, 99L)
        for (form in listOf("09999920000", "9999920000", "0999 992 0000", "۰۹۹۹۹۹۲۰۰۰۰")) {
            assertEquals("«$form»", canonical, srcHash(form, body, 99L))
        }
    }

    @Test
    fun `anything that makes it a different message makes it a different key`() {
        val body = "بانک سامان\nواریز 1,000,000 ریال"
        val base = srcHash("0999 992 0000", body, 42L)
        assertNotEquals(base, srcHash("0999 998 7641", body, 42L))       // another bank
        assertNotEquals(base, srcHash("0999 992 0000", body, 43L))       // a millisecond later
        assertNotEquals(base, srcHash("0999 992 0000", "بانک سامان\nواریز 2,000,000 ریال", 42L))
        // Surrounding whitespace is not part of the message; whitespace inside it is.
        assertEquals(base, srcHash("0999 992 0000", "\n  $body  \n", 42L))
        assertNotEquals(base, srcHash("0999 992 0000", body.replace("\n", " "), 42L))
    }

    @Test
    fun `a body cannot be shaped to forge another message's key`() {
        // The separator is NUL precisely because an SMS body cannot contain one. Without it,
        // a body beginning with a timestamp could slide the field boundary and collide.
        assertNotEquals(
            srcHash("100031", "12", 3L),
            srcHash("100031", "2", 123L),
        )
    }

    @Test
    fun `the horizon is thirteen jalali months back and lands on a first`() {
        val now = tehranDayStart(LocalDate.of(2026, 7, 23).toEpochDay()) + 11 * 3_600_000L
        val horizon = sourceHorizon(now)
        val date = jalaliOf(tehranDay(horizon))
        assertEquals(1, date.day)
        assertEquals(JalaliDate(1404, 4, 1), date)
        assertTrue("the horizon must be in the past", horizon < now)
        // Far enough back for a year-over-year comparison, with a month of margin.
        val daysBack = tehranDay(now) - tehranDay(horizon)
        assertTrue("only $daysBack days of history", daysBack in 366..430)
    }

    @Test
    fun `the prune floor sits below the horizon by its grace period`() {
        // A first ingest reads nothing historical — it starts at `now` — so rewindIngest is the
        // only thing that ever reaches backwards, and it stops at the horizon. Retention has to
        // stay below that, or a rewind would store a sender's history and prune it in one call.
        val now = System.currentTimeMillis()
        val floor = sourcePruneFloor(now)
        assertTrue("prune floor must not reach above the horizon", floor < sourceHorizon(now))
        assertEquals(SOURCE_GRACE_DAYS, (sourceHorizon(now) - floor) / DAY_MS)
    }

    @Test
    fun `a reference names its message and its position in it`() {
        val hash = srcHash("100031", "بانک رفاه", 1L)
        assertEquals("s:$hash:0", refOf(hash))
        assertEquals("s:$hash:1", refOf(hash, 1))
        assertEquals("m:abc", manualRef("abc"))
        // The two namespaces can never collide, whatever a uuid happens to look like.
        assertTrue(refOf(hash).startsWith("s:"))
        assertTrue(manualRef(hash).startsWith("m:"))
    }
}
