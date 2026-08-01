package com.doxigo.muchtoman

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
