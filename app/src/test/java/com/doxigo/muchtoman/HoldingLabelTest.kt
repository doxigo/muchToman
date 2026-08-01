package com.doxigo.muchtoman

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HoldingLabelTest {

    @Test
    fun `holdings saved before custom names decode without one`() {
        val holding = Json.decodeFromString<Holding>(
            """{"typeId":"usdt","amount":10.5,"excluded":false}""",
        )

        assertEquals("", holding.label)
        assertEquals("تتر", holding.nameOr("تتر"))
    }

    @Test
    fun `a custom name replaces the asset's own, and blank falls back to it`() {
        assertEquals(
            "تتر شخصی",
            Holding("usdt", 1.0, label = "تتر شخصی").nameOr("تتر"),
        )
        // Whitespace is not a name. Anything that reaches the row must be printable, or the
        // row loses its title and becomes an unlabelled number.
        assertEquals("تتر", Holding("usdt", 1.0, label = "   ").nameOr("تتر"))
    }

    @Test
    fun `two holdings of one asset stand apart and both count`() {
        val personal = Holding("usdt", 10.0, id = newHoldingId(), label = "تتر شخصی")
        val shared = Holding("usdt", 5.0, id = newHoldingId(), label = "تتر مشترک")

        assertNotEquals(personal.key, shared.key)
        assertEquals(
            30_000.0,
            computeTotals(listOf(personal, shared), mapOf("usdt" to 2_000.0)).toman,
            0.0,
        )
    }

    @Test
    fun `a holding saved before multiples were allowed is keyed by its asset`() {
        val old = Json.decodeFromString<Holding>("""{"typeId":"usdt","amount":10.5}""")

        assertEquals("usdt", old.key)
    }

    @Test
    fun `naming a holding leaves its money alone`() {
        val named = Holding("usdt", 10.5, label = "تتر مشترک")

        assertEquals(
            21_000.0,
            computeTotals(listOf(named), mapOf("usdt" to 2_000.0)).toman,
            0.0,
        )
    }
}
