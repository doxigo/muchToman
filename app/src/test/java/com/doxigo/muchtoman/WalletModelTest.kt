package com.doxigo.muchtoman

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletModelTest {

    @Test
    fun `holdings saved before wallet tracking remain manual`() {
        val holding = Json.decodeFromString<Holding>(
            """{"typeId":"btc","amount":2.5,"excluded":false}""",
        )

        assertEquals(2.5, holding.amount, 0.0)
        assertNull(holding.wallet)
    }

    @Test
    fun `a wallet link does not change how its verified amount is valued`() {
        val holding = Holding(
            typeId = "eth",
            amount = 1.25,
            wallet = WalletLink("ethereum", "اتریوم", "0x123", updatedAt = 10L),
        )

        assertEquals(250.0, computeTotals(listOf(holding), mapOf("eth" to 200.0)).toman, 0.0)
    }

    @Test
    fun `invalid wallet text is rejected before a network request`() {
        assertTrue(isWalletAddressFormatValid("ethereum", "0x0000000000000000000000000000000000000000"))
        assertTrue(isWalletAddressFormatValid("bitcoin", "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"))
        assertTrue(isWalletAddressFormatValid("solana", "11111111111111111111111111111111"))
        assertTrue(isWalletAddressFormatValid("tron", "T" + "1".repeat(33)))
        assertFalse(isWalletAddressFormatValid("ethereum", "word word word word word word"))
        assertFalse(isWalletAddressFormatValid("unknown", "0x0000000000000000000000000000000000000000"))
        assertTrue(isWalletContractFormatValid("ethereum", "0x0000000000000000000000000000000000000000"))
        assertTrue(isWalletContractFormatValid("bitcoin", ""))
        assertFalse(isWalletContractFormatValid("bitcoin", "not-a-contract"))
    }

    @Test
    fun `a future wallet timestamp cannot suppress refresh indefinitely`() {
        val now = 1_000_000L
        assertTrue(isWalletBalanceValid(WalletBalance(1.0, now), now))
        assertFalse(isWalletBalanceValid(WalletBalance(1.0, now + 5 * 60_000L + 1), now))
    }

    @Test
    fun `an overflowing holding is reported missing instead of poisoning the total`() {
        val totals = computeTotals(
            listOf(Holding("huge", Double.MAX_VALUE), Holding("usd", 2.0)),
            mapOf("huge" to 2.0, "usd" to 10.0),
        )

        assertEquals(20.0, totals.toman, 0.0)
        assertEquals(listOf("huge"), totals.missing)
    }
}
