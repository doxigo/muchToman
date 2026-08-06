package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two editions differ by one list, and everything that keeps the lite build navigable rests on
 * two properties of it: دارایی is in it, and its first entry is somewhere real. `Ui.kt` opens on
 * `tabs.first()` and sends the back button there, so an empty or reordered list is not a cosmetic
 * bug — it is a lite build that opens on a screen it does not have.
 *
 * This runs per flavour, which is the point: it is `testLiteDebugUnitTest` that proves the
 * `buildConfigField` actually reached the lite variant, and a flavour block silently misconfigured
 * so that both APKs are the full app would otherwise look exactly like success.
 */
class EditionTest {

    @Test
    fun `every edition has دارایی, because that is the app lite is`() {
        assertTrue("دارایی missing from ${tabs.map { it.fa }}", Tab.ASSETS in tabs)
    }

    @Test
    fun `the tab the app opens on is one it has`() {
        assertTrue("no tabs at all", tabs.isNotEmpty())
        assertTrue("opens on a tab outside its own bar", tabs.first() in tabs)
    }

    @Test
    fun `the edition is the one this variant was built for`() {
        // FLAVOR comes from the flavour's own name and needs no `buildConfigField` to exist, which
        // makes it the only fact here that editing the flavour block cannot get wrong. Asserting
        // LITE *against* it is what catches the failure that otherwise looks exactly like success:
        // a `buildConfigField` that never reached the lite variant, leaving both APKs the full app
        // under two names. Branching on LITE alone would have happily agreed with itself.
        assertEquals(
            "BuildConfig.LITE disagrees with the flavour it was built from",
            BuildConfig.FLAVOR == "lite",
            BuildConfig.LITE,
        )
        if (BuildConfig.LITE) {
            assertEquals(listOf(Tab.ASSETS), tabs)
        } else {
            assertEquals(Tab.entries, tabs)
            assertEquals(Tab.HOME, tabs.first())
        }
    }
}
