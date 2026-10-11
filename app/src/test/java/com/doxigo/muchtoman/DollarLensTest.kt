package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DollarLensTest {

    @Test
    fun `toman up, dollar up more, so she is poorer in dollars`() {
        // Day 70: 100 Toman at 10 a dollar = $10. Today: 108 Toman at 12 a dollar = $9.
        val lens = dollarLens(mapOf(65L to 90.0, 70L to 100.0), mapOf(70L to 10.0), 100, 108.0, 12.0)!!
        assertEquals(70L, lens.sinceDay) // changeOver's day, so the hero pill and the card agree
        assertEquals(8.0, lens.wealth, 1e-9)
        assertEquals(20.0, lens.dollar, 1e-9)
        assertEquals(-10.0, lens.real, 1e-9)
    }

    @Test
    fun `no rate on the base day is no card, never a neighbour's rate`() {
        assertNull(dollarLens(mapOf(70L to 100.0), mapOf(69L to 10.0, 71L to 10.0), 100, 108.0, 12.0))
        assertNull(dollarLens(mapOf(70L to 100.0), mapOf(70L to 10.0), 100, 108.0, null))
        // Ten days of history are not a month.
        assertNull(dollarLens(mapOf(90L to 100.0), mapOf(90L to 10.0), 100, 108.0, 12.0))
    }

    @Test
    fun `the held toman's cost reads today's balance at two rates`() {
        // 100M Toman at 110k a dollar is $909.09; at the 100k of thirty days ago it was $1,000.
        val lens = DollarLens(wealth = 0.0, dollar = 10.0, real = 0.0, sinceDay = 0L)
        assertEquals(1000.0 - 100_000_000.0 / 110_000.0, lens.heldTomanCostUsd(100_000_000.0, 110_000.0), 1e-6)
    }
}
