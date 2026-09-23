package com.doxigo.muchtoman

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
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

    /** For a line that is a run inside a longer one — a date with a clock after it. */
    private fun waitForTextIn(text: String, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Scrolls the screen's own list until [matcher] matches, and fails if nothing ever does.
     *
     * The lazy lists are why this exists: an item below the fold is not merely off screen, it is
     * not composed, so `onNodeWithText(...)` cannot see it and `performScrollTo()` has nothing
     * to scroll to.
     *
     * Selected by its vertical range rather than by having a scroll action at all: the slicers
     * on these screens — the report's span chips, the timeline's lenses — are scrollable rows,
     * and «the scrollable thing» is two nodes wherever one of those is on screen.
     *
     * And the *last* of those, because a sheet is a window of its own: the screen it covers
     * keeps its own list in the tree, so over a sheet there are two vertical scrollers and the
     * frontmost one is the one being looked at. Roots are walked in the order they were
     * registered, which puts the newest window last. On a plain screen there is only one, and
     * last is that one.
     *
     * And on past where `performScrollToNode` stops, to the top of the list or as near as its
     * end allows. It stops the moment the node is inside the list's bounds, but the lists run
     * on under the floating tab bar, so a row stopped near the bottom is «in view» under the
     * bar and a tap on it lands on a tab. Whether it stops there turned on how many lines the
     * hero's total spelled out in words took, which is the price of gold that day. The bar's
     * height is the list's bottom padding, so even the last row, at the end, clears it.
     */
    private fun scrollPageTo(matcher: SemanticsMatcher) {
        val page = rule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .onLast()
        page.performScrollToNode(matcher)
        val list = page.fetchSemanticsNode()
        val node = list.firstMatch(matcher) ?: return
        page.performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, node.boundsInRoot.top - list.boundsInRoot.top)
        }
    }

    private fun SemanticsNode.firstMatch(matcher: SemanticsMatcher): SemanticsNode? =
        children.firstNotNullOfOrNull { if (matcher.matches(it)) it else it.firstMatch(matcher) }

    /**
     * Walks the picker into an [EditSheet] for [typeFa] and saves [amountFa] — typed in Persian
     * digits, because that is what her keyboard produces and what [parseAmount] must read.
     */
    private fun addHolding(typeFa: String, amountFa: String) {
        rule.onNodeWithContentDescription("اضافه کردن").performClick()
        waitForText("چی می‌خوای اضافه کنی؟")
        // Searched for, not scrolled to. The رمزارز band above طلا is filled by the rates fetch,
        // and on a fresh install that fetch can land between the scroll and the tap: a dozen
        // rows arrive above the one in view, the list keeps its first row still, and the type
        // is pushed out of composition. The search list holds only what matches, so nothing the
        // network brings can land in front of it. Its field is the one text field in the tree.
        rule.onNode(hasSetTextAction()).performTextInput(typeFa)
        // The field now carries the same words; the row is the match that is not the field.
        rule.onNode(hasText(typeFa) and !hasSetTextAction()).performClick()
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
        // The rows sit under the hero and the action circles in the screen's own lazy list,
        // so it takes a scroll to bring the new one into composition.
        scrollPageTo(hasText("طلای ۱۸ عیار"))
        rule.onNodeWithText("طلای ۱۸ عیار").assertIsDisplayed()
    }

    @Test
    fun deletingAHoldingReallyTakesTwoTaps() {
        // Its own holding, its own type — مثقال طلا collides with nothing the picker test
        // leaves behind, so every match below is unambiguous whatever the test order.
        addHolding("مثقال طلا", "۳")
        tab("دارایی").performClick()
        scrollPageTo(hasText("مثقال طلا"))
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

    /**
     * Fills the «تراکنش دستی» sheet and saves it, leaving a row named [merchantFa] on دفتر.
     *
     * The two text fields are taken by tree order rather than by their labels: the sheet holds
     * four of them — مبلغ, بابت چی, ساعت, توضیحات — and only the first carries a description of
     * its own. Order here is composition order, which is the order they are read in.
     */
    private fun addManualTxn(merchantFa: String, amountFa: String, categoryFa: String) {
        tab("خانه").performClick()
        rule.onNodeWithContentDescription("تراکنش دستی").performClick()
        waitForText("ثبت تراکنش")
        rule.onNodeWithContentDescription("مبلغ به تومان").performTextInput(amountFa)
        rule.onAllNodes(hasSetTextAction())[1].performTextInput(merchantFa)
        rule.onNodeWithText(categoryFa).performScrollTo().performClick()
        rule.onNodeWithText("ثبت تراکنش").performScrollTo().performClick()
        waitForTextGone("ثبت تراکنش")
    }

    /**
     * A day typed in wrong, corrected on the transaction's own page.
     *
     * The arithmetic of moving a day is checked on the JVM; what only a real frame can show is
     * whether the pill is wired to it at all, whether it stays quiet until the stepper actually
     * disagrees with the ledger, and whether the date on screen afterwards is the one that was
     * stored. The written-out date is asserted through [faWeekdayDate] — `JalaliTest` is what
     * pins that function to a real calendar, so what this adds is that the screen prints it.
     */
    @Test
    fun theDayOfAHandEnteredRowCanBeCorrected() {
        // Read once. Two days back from *this* day is what the two taps below must land on, and
        // re-reading the clock mid-test would let a midnight move the target under the test.
        val today = tehranDay(System.currentTimeMillis())
        val merchant = "میوه‌فروشی آزمون"
        addManualTxn(merchant, "۴۵۰۰۰۰", "خواربار")

        tab("دفتر").performClick()
        waitForText(merchant)
        rule.onNodeWithText(merchant).performClick()

        // The page is a LazyColumn, so the section has to be scrolled into composition before
        // it can be found at all — `performScrollTo` only reaches nodes that already exist.
        waitForTextIn("دسته‌بندی")
        scrollPageTo(hasText("روز قبل"))
        // Nothing is offered to save yet: the stepper opens on the day the row is filed under.
        rule.onNodeWithText("ذخیره تاریخ").assertDoesNotExist()

        rule.onNodeWithText("روز قبل").performClick()
        rule.onNodeWithText("روز قبل").performClick()
        waitForText("ذخیره تاریخ")
        rule.onNodeWithText("ذخیره تاریخ").performClick()

        // The pill leaving is the receipt: nothing is pending, so what is on screen is what the
        // ledger holds.
        waitForTextGone("ذخیره تاریخ")

        // And the ledger really moved it — asserted off دفتر rather than off this page, because
        // the stepper would print the picked day whether or not anything was ever stored. The
        // row now sits under a day band two days back, named the way a transaction's date is
        // named everywhere it is written out: weekday first.
        scrollPageTo(hasText("برگشت"))
        rule.onNodeWithText("برگشت").performClick()
        waitForText("دفتر")
        scrollPageTo(hasText(faWeekdayDate(today - 2)))
        scrollPageTo(hasText(merchant))
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
