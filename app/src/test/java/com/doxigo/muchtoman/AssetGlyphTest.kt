package com.doxigo.muchtoman

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The asset rows' one promise after the emoji purge: everything the fixed catalogue offers
 * draws a mark, and the things that carry a real logo are left alone to carry it.
 */
class AssetGlyphTest {

    @Test
    fun `every fixed asset draws a mark`() {
        // Toman excepted: it has the real currency mark, which outranks anything drawn here.
        for (type in STATIC_CATALOG + BANK_TYPE) {
            if (type.id == TOMAN_ID) continue
            assertNotNull("«${type.fa}» (${type.id}) fell back to emoji", assetGlyph(type))
        }
    }

    @Test
    fun `a coin keeps its own logo`() {
        val btc = Coin(id = "btc", name = "بیت‌کوین", en = "Bitcoin", icon = "https://x/btc.png")
        assertNull(assetGlyph(btc.toAssetType()))
    }
}
