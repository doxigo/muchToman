package com.doxigo.muchtoman

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * The flows the JVM tests cannot see, run against a real frame (plan 008). Every assertion here
 * is a user-visible outcome — a word on screen, a row appearing, a tab lighting up — never a
 * state flag. Nodes are selected by the stable Persian strings that *are* the UI (per
 * `PRODUCT.md`, the words are the product and do not churn), so the suite needs no testTags and
 * therefore no edits to production files.
 *
 * Rules the suite lives by:
 *  - No network dependence: rates may be absent on the emulator, so nothing asserts a price —
 *    only structure.
 *  - No SMS flows: the permission dialog is device-modal and would hang the run.
 *  - Each test tolerates existing data (a holding another test left behind) rather than
 *    assuming a fresh install; the one test that deletes deletes only what it itself created.
 */
class UiSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        /**
         * The first-run sheet has its one turn before anything else the app shows — which on a
         * clean test install would be every single test. It is skipped the same way the app
         * itself skips it (the [Store] flag), written before the first activity ever launches:
         * \@BeforeClass runs outside the rule, and the rule is what starts the activity.
         */
        @BeforeClass
        @JvmStatic
        fun skipTheFirstRunSheet() {
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences("muchtoman", Context.MODE_PRIVATE)
                .edit().putBoolean("onboarded", true).commit()
        }
    }

    @Before
    fun onlyTheFullEdition() {
        // These are the full edition's flows; the lite build has no tab bar to walk. On
        // connectedLiteDebugAndroidTest this whole class steps aside for [LiteSmokeTest].
        assumeFalse("full-edition flows; lite has no tab bar", BuildConfig.LITE)
    }

    /** The one root tab bar entry with this label — the titles are headings, tabs are not. */
    private fun tab(label: String): SemanticsNodeInteraction =
        rule.onNode(isSelectable() and hasText(label))

    private fun waitForText(text: String, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTextGone(text: String, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Walks the picker into an [EditSheet] for [typeFa] and saves [amountFa] — typed in Persian
     * digits, because that is what her keyboard produces and what [parseAmount] must read.
     */
    private fun addHolding(typeFa: String, amountFa: String) {
        rule.onNodeWithContentDescription("اضافه کردن").performClick()
        waitForText("چی می‌خوای اضافه کنی؟")
        rule.onNodeWithText(typeFa).performClick()
        // The picker plays itself out before the edit sheet is the only sheet standing; waiting
        // on both keeps the amount field the one text field in the tree.
        waitForText("ذخیره")
        waitForTextGone("چی می‌خوای اضافه کنی؟")
        rule.onNode(hasSetTextAction()).performTextInput(amountFa)
        rule.onNodeWithText("ذخیره").performScrollTo().performClick()
        waitForTextGone("ذخیره")
    }

    // Plain camelCase names, unlike the JVM tests' backticked sentences: these methods become
    // DEX method names, and DEX before version 040 (minSdk 24) forbids spaces in them.
    @Test
    fun firstFrameIsTheHomeSurfaceWhole() {
        // The door to تنظیمات at the top…
        rule.waitUntil(15_000) {
            rule.onAllNodesWithContentDescription("تنظیمات").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("تنظیمات").assertIsDisplayed()
        // …and the bar's five rooms at the bottom: the frame is complete.
        for (label in listOf("خانه", "دفتر", "آینده", "دارایی", "گزارش")) {
            tab(label).assertIsDisplayed()
        }
    }

    @Test
    fun everyRootTabOpensItsOwnScreen() {
        // گزارش deliberately last: its screen contains a selectable «دارایی» segment (the
        // report mode switch), which would make the دارایی tab ambiguous if visited after it.
        tab("دفتر").performClick()
        rule.onNode(isHeading() and hasText("دفتر")).assertIsDisplayed()

        tab("آینده").performClick()
        rule.onNode(isHeading() and hasText("آینده")).assertIsDisplayed()

        tab("دارایی").performClick()
        tab("دارایی").assertIsSelected()
        // The portfolio surface: the add circle stands, and the home-only manual-transaction
        // circle is gone — which is what tells this tab apart from خانه whatever data exists.
        rule.onNodeWithContentDescription("اضافه کردن").assertIsDisplayed()
        rule.onNodeWithContentDescription("تراکنش دستی").assertDoesNotExist()

        tab("گزارش").performClick()
        rule.onNodeWithText("گزارش‌ها").assertIsDisplayed()

        tab("خانه").performClick()
        rule.onNodeWithContentDescription("تراکنش دستی").assertIsDisplayed()
    }

    @Test
    fun assetPickerAddsAHoldingThatLandsOnTheList() {
        addHolding("طلای ۱۸ عیار", "۲۵٫۵")
        tab("دارایی").performClick()
        waitForText("طلای ۱۸ عیار")
        rule.onNodeWithText("طلای ۱۸ عیار").assertIsDisplayed()
    }

    @Test
    fun deletingAHoldingReallyTakesTwoTaps() {
        // Its own holding, its own type — مثقال طلا collides with nothing the picker test
        // leaves behind, so every match below is unambiguous whatever the test order.
        addHolding("مثقال طلا", "۳")
        tab("دارایی").performClick()
        waitForText("مثقال طلا")
        rule.onNodeWithText("مثقال طلا").performClick()
        waitForText("حذف این دارایی")

        rule.onNodeWithText("حذف این دارایی").performScrollTo().performClick()
        // One tap armed it and deleted nothing: the confirm affordance is up, the “deleted”
        // notice is not.
        rule.onNodeWithText("مطمئنی؟ برای حذف دوباره بزن").assertIsDisplayed()
        rule.onNodeWithText("دارایی پاک شد").assertDoesNotExist()

        // The second tap is the delete.
        rule.onNodeWithText("مطمئنی؟ برای حذف دوباره بزن").performClick()
        waitForText("دارایی پاک شد")
        waitForTextGone("مثقال طلا")
    }

    @Test
    fun settingsOpensTheBackupRoomAndBackComesBack() {
        rule.onNodeWithContentDescription("تنظیمات").performClick()
        rule.onNode(isHeading() and hasText("تنظیمات")).assertIsDisplayed()

        rule.onNodeWithText("پشتیبان‌گیری").performScrollTo().performClick()
        waitForText("پشتیبان‌گیری از همه‌چیز")
        rule.onNodeWithText("پشتیبان‌گیری از همه‌چیز").assertIsDisplayed()
        // The room's paragraph — the passphrase warning — is on screen with it.
        rule.onNodeWithText("فایل پشتیبان رمز داره", substring = true).assertExists()

        rule.onNodeWithText("برگشت").performClick()
        rule.onNode(isHeading() and hasText("نگهداری")).assertIsDisplayed()
    }
}
