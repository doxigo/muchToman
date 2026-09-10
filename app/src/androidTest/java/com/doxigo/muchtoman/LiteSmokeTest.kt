package com.doxigo.muchtoman

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * [EditionTest]'s claim — the lite build is دارایی and nothing else — verified on a real frame
 * rather than on the [tabs] list (plan 008). Runs under `connectedLiteDebugAndroidTest`; on the
 * full variant it steps aside the same way [UiSmokeTest] steps aside on lite.
 */
class LiteSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        /** Same first-run skip as [UiSmokeTest]; see the note there. */
        @BeforeClass
        @JvmStatic
        fun skipTheFirstRunSheet() {
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences("muchtoman", Context.MODE_PRIVATE)
                .edit().putBoolean("onboarded", true).commit()
        }
    }

    @Before
    fun onlyTheLiteEdition() {
        assumeTrue("this is the lite edition's frame", BuildConfig.LITE)
    }

    // camelCase, not a backticked sentence: DEX before version 040 (minSdk 24) forbids spaces
    // in method names, and instrumented tests actually become DEX.
    @Test
    fun liteFrameIsTheAssetSurfaceAloneNoBarNoLedgerDoors() {
        // The asset surface is up…
        rule.waitUntil(15_000) {
            rule.onAllNodesWithContentDescription("اضافه کردن").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("اضافه کردن").assertIsDisplayed()
        // …with the report's one door, the action circle that stands in for the bar it lacks…
        rule.onNodeWithContentDescription("گزارش").assertIsDisplayed()
        // …and none of the full edition's destinations: no tab bar entry exists at all, and the
        // ledger's own door — the manual-transaction circle — is gone with it.
        for (label in listOf("خانه", "دفتر", "آینده", "دارایی", "گزارش")) {
            rule.onNode(isSelectable() and hasText(label)).assertDoesNotExist()
        }
        rule.onNodeWithContentDescription("تراکنش دستی").assertDoesNotExist()
    }
}
