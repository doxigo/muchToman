# Plan 001: Make monthly cash flow a first-class report

> **Executor instructions**: Follow this plan step by step. Run every verification command and
> confirm the expected result before moving on. If anything in "STOP conditions" occurs, stop and
> report it. Do not improvise. When done, update this plan's row in `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat 55fb4c8..HEAD -- app/src/main/java/com/doxigo/muchtoman/Reports.kt app/src/main/java/com/doxigo/muchtoman/Report.kt app/src/main/java/com/doxigo/muchtoman/Ui.kt app/src/test/java/com/doxigo/muchtoman/ReportsTest.kt`
>
> If any in-scope file changed since this plan was written, compare the "Current state" excerpts
> against the live code. Stop if the report architecture or monthly calculation contracts changed.

## Status

- **Priority**: P1
- **Effort**: L, about 3 focused engineering days including device QA
- **Risk**: MED, because report routing and financial claims both change
- **Depends on**: none
- **Category**: direction
- **Planned at**: commit `55fb4c8`, 2026-08-08

## Why this matters

The full app has evolved from a portfolio tracker into a finance manager, but its Reports tab still
opens as `گزارش دارایی`. The cash-flow report already exists, but it is appended below the asset
chart and only knows the current month. Most users will reasonably conclude that the tab contains
asset reporting only.

Fix the information architecture, not just the chart. The Reports root must present `دخل و خرج`
and `دارایی` as peer modes. The full app should default to monthly cash flow because assets already
have their own root tab. The lite edition must retain its current asset-only report.

## Product decision

The first release is monthly only. Do not add the reference app's weekly/monthly/yearly sheet yet.
The ledger retains roughly a year of data, but existing users may have only accumulated data since
the ledger was enabled. Weekly reporting is a separate navigation and comparison problem, while a
meaningful yearly report needs enough complete history. Adding either now would enlarge the UI
without fixing the hierarchy problem.

The target report hierarchy is:

1. Neutral screen title: `گزارش‌ها` in the full app.
2. Persistent two-option selector: `دخل و خرج` and `دارایی`.
3. In `دخل و خرج`: selected Jalali month, summary, six-month paired-bar context, category detail,
   then month-specific insights.
4. In `دارایی`: the current asset change, history chart, and composition report, unchanged in
   meaning.

The cash-flow content order is:

| Order | Surface | Required answer |
|---|---|---|
| 1 | Month navigator | Which Jalali month am I reading? |
| 2 | Income and expense summary | What came in and what went out? |
| 3 | Net result | What remained, or how large was the deficit? |
| 4 | Six-month grouped bar chart | Is this month unusual relative to nearby months? |
| 5 | `خرج` / `درآمد` category selector | Where did each side come from? |
| 6 | Insights | What changed from the previous month, stated truthfully? |

## Current state

### Relevant files

- `app/src/main/java/com/doxigo/muchtoman/Report.kt` renders the report screen. Asset reporting is
  the primary content and cash flow is appended at the bottom.
- `app/src/main/java/com/doxigo/muchtoman/Reports.kt` contains deterministic cash-flow math and
  narrative generation.
- `app/src/main/java/com/doxigo/muchtoman/Ui.kt` owns root tab state and all entry points into the
  report.
- `app/src/test/java/com/doxigo/muchtoman/ReportsTest.kt` is the regression suite for financial
  report claims.
- `app/src/main/java/com/doxigo/muchtoman/Home.kt` already renders the current month summary and
  accepts an `onOpen` callback. It should not need structural changes.

### Evidence

`Report.kt:119-185` identifies the entire root as an asset report, renders the asset window and
chart first, and only then appends the current month's story:

```kotlin
Text("گزارش دارایی", ...)
WindowPicker(selected, ::available) { selected = it }
// asset change figure and HistoryChart
if (story.month.transactions > 0) {
    MonthStory(story)
}
```

`Reports.kt:49-74` already calculates one exact Jalali month and excludes transfers and hidden
duplicates through `spendable`. This is the source of truth to reuse:

```kotlin
val inMonth = spendable(entries).filter { it.txn.day in from until until }
if (signed > 0) income += signed else spent += -signed
```

`Reports.kt:250-262` hard-wires the story to the month containing `today`, so historical selection
cannot currently reuse the full story safely:

```kotlin
val here = jalaliOf(today)
val month = monthReport(entries, here.year, here.month)
```

`Reports.kt:137-139` and `Reports.kt:180-182` apply a lower bound but no upper bound when attaching
transaction references to insights. That is safe only while the selected month is always current.
Historical reports must use a closed-open month range or their evidence will include later months.

`Ui.kt:467-480` has one undifferentiated `ReportScreen` route. `Ui.kt:530` passes the same
`onReport = { tab = Tab.REPORT }` callback to the hero, and `Ui.kt:895-946` uses it for both the
asset change pill and the cash-flow month band. The destination therefore cannot select the report
mode that matches the entry point.

`Reports.kt:56-71` groups only spending categories. The codebase now has distinct income categories
in `Rules.kt:139-150`, so the report currently throws away useful categorization on the income side.

### Existing conventions to preserve

- Financial math is pure and deterministic in `Reports.kt`; composables render precomputed values.
- A transfer or hidden duplicate is neither income nor spending. `spendable` remains the one gate.
- Toman values are derived from stored Rial with `tomanOf`; do not change storage units.
- Jalali boundaries come from `jalaliDay`, `jalaliOf`, and `jalaliMonthLength` in `Jalali.kt`.
- Charts run left to right even though the app shell is RTL. `Report.kt:400-403` records this decision.
- `SegmentedChoice` in `Ui.kt:2642-2697` is the app's only control for choosing exactly one peer
  option. Reuse it for report mode and category direction.
- Figures use `ModamFigures`, `faCompact`, `faNumber`, and `bidi`; do not introduce Latin digits.
- At least 48 dp touch targets, text plus color for meaning, and selected semantics are mandatory.
- The only direct edition flag remains the `tabs` declaration in `TabBar.kt`. Do not read
  `BuildConfig.LITE` in report code. Derive available report modes from `tabs` or the existing
  asset-only navigation contract.

## Commands you will need

This Mac has JDK 21 inside Android Studio but no `java` on the shell PATH. Use the explicit
`JAVA_HOME` prefix below locally. CI already configures JDK 21.

| Purpose | Command | Expected on success |
|---|---|---|
| Focused tests | `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testFullDebugUnitTest --tests com.doxigo.muchtoman.ReportsTest --tests com.doxigo.muchtoman.JalaliTest` | exit 0, all focused tests pass |
| Full Android gate | `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint assembleDebug` | exit 0, both editions compile and all tests/lint pass |
| Install dev build | `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew installFullDev` | exit 0 and full dev APK installs on the selected device |
| Diff hygiene | `git diff --check` | no output, exit 0 |
| Scope check | `git status --short -- app/src/main/java/com/doxigo/muchtoman/Reports.kt app/src/main/java/com/doxigo/muchtoman/Report.kt app/src/main/java/com/doxigo/muchtoman/Ui.kt app/src/test/java/com/doxigo/muchtoman/ReportsTest.kt` | only intentional in-scope files are listed |

The focused test command was run against `55fb4c8` and passed before this plan was written.

## Suggested executor toolkit

- Use the `better-layout` skill, if available, to review the pinned report-mode selector, scroll
  ownership, and small-screen layout.
- Run the `accessibility` skill, if available, after the Compose UI is complete. The bar chart and
  arrow controls need explicit semantics because a Canvas is otherwise silent to TalkBack.

## Scope

### In scope

- `app/src/main/java/com/doxigo/muchtoman/Reports.kt`
- `app/src/main/java/com/doxigo/muchtoman/Report.kt`
- `app/src/main/java/com/doxigo/muchtoman/Ui.kt`
- `app/src/test/java/com/doxigo/muchtoman/ReportsTest.kt`

### Out of scope

- Existing uncommitted user work in `Derived.kt`, `Sms.kt`, `MoneyTest.kt`, and the Saman, Blu, and
  Khavarmianeh SMS fixtures. Preserve it and do not stage it with this feature.
- Changing transfer, duplicate, category, balance, or transaction derivation semantics.
- Reading historical SMS from before the ledger's existing watermark or adding a backfill prompt.
- Any Room schema or data migration.
- Weekly, yearly, or arbitrary date-range reports.
- Budgets, forecasts, exports, recurring-transaction detection, or category drill-down navigation.
- Worker, sync, PWA, asset valuation, daily snapshot, or tab-bar architecture changes.
- Replacing the established theme palette with the purple/gold palette from the reference image.
  Copy the interaction model, not another product's visual identity.

## Git workflow

- Suggested branch: `advisor/001-first-class-monthly-cash-flow-reports`
- Use small logical commits. Existing subjects use concise imperative forms such as
  `report: show what the total is made of` and `tabbar: swap the household slot for the report`.
- Do not commit any pre-existing dirty files. At planning time these include `Derived.kt`, `Sms.kt`,
  `MoneyTest.kt`, and three SMS corpus fixtures.
- Do not push or open a PR unless the operator explicitly requests it.

## Steps

### Step 1: Generalize the monthly report model without changing current-home behavior

Work in `Reports.kt` and `ReportsTest.kt` first.

1. Add a small, validated Jalali month value type, for example `ReportMonth(year, month)`, with:
   - `startDay`;
   - `previous()` and `next()` with correct Farvardin/Esfand rollover;
   - a Persian display label containing month and year;
   - ordering based on `year * 12 + month` or `startDay`.
2. Add a pure `availableReportMonths(entries, today)` function:
   - end at the current Jalali month;
   - start at the earliest month containing a non-duplicate, non-transfer entry;
   - if no such entry exists, return only the current month;
   - include empty months between the first observed month and the current month;
   - never synthesize future months.
3. Change `MonthReport.byCategory` into explicit direction-aware data:
   - `spendingByCategory`, built from negative signed amounts;
   - `incomeByCategory`, built from positive signed amounts;
   - both sorted descending and both derived from the same `inMonth` list.
4. Add a pure builder for an arbitrary selected month. Keep the existing three-argument
   `buildStory(entries, liquidRial, today)` as a current-month wrapper so `UiState.story` and the
   home screen retain their API and behavior.
5. Bound every insight reference to `[selectedMonth.startDay, nextMonth.startDay)`. Do not allow a
   historical insight to cite later transactions.
6. Make narrative copy month-aware:
   - current month may say `این ماه`;
   - historical months must name the month, for example `در تیر ۱۴۰۵`;
   - replace the current `اولین ماهی...` win with a claim supported by the actual comparison, such
     as `درآمد این ماه از خرجت بیشتر شد`, unless the implementation proves it is the first across
     all available history.
7. Compute the cash buffer only for the current month. Current liquid balance cannot truthfully be
   presented as historical runway.
8. Return up to six chronologically ordered `MonthReport` values around the selected month for the
   chart. Clamp the window to the available-month bounds. Do not include future placeholders.

Add or update unit tests for:

- Farvardin to Esfand and Esfand to Farvardin navigation;
- no-entry availability returning current month only;
- an earliest observed month through current month including an empty gap;
- income and expense category aggregation remaining separate;
- transfers and duplicates excluded from totals, categories, and chart months;
- six-month chart-window order and clamping;
- historical insight references excluding both earlier and later months;
- historical copy naming the selected month;
- historical stories omitting current-balance runway;
- the existing current-month totals and savings behavior remaining unchanged.

**Verify**:
`env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testFullDebugUnitTest --tests com.doxigo.muchtoman.ReportsTest --tests com.doxigo.muchtoman.JalaliTest`
must exit 0.

### Step 2: Split the report root into two peer modes and route entry points correctly

Work in `Report.kt` and `Ui.kt`.

1. Introduce a two-value report mode, named clearly, for example:

   ```kotlin
   enum class ReportMode(val fa: String) {
       CASH_FLOW("دخل و خرج"),
       ASSETS("دارایی"),
   }
   ```

2. Hoist the selected report mode and selected month into `AppScreens` using saveable primitive
   state. Store the enum name as a `String` and the selected month start as a `Long` if Compose
   cannot save the custom types directly.
3. Split `ReportScreen` into a shared shell plus two content composables:
   - `AssetReportContent` contains the existing asset window, change figure, chart, and composition;
   - `CashFlowReportContent` contains the new monthly experience;
   - remove the old appended `MonthStory` from `AssetReportContent`, so cash flow appears once.
4. In the full app, render `گزارش‌ها` and a pinned `SegmentedChoice` above the independently
   scrollable content. Each report mode must retain or reset its own scroll position deliberately;
   do not switch modes while leaving the user halfway down the other report.
5. In the lite edition, expose only `ASSETS`, keep `گزارش دارایی`, hide the meaningless one-option
   segment, and preserve the existing `برگشت` behavior. Derive this from the `tabs` capability or
   existing `onBack` contract. Do not add a new `BuildConfig.LITE` read.
6. Replace the single `onReport` route in `HeroField` with explicit destinations:
   - asset change pill and `گزارش دارایی` link select `ASSETS`;
   - `HeroMonth` selects `CASH_FLOW`;
   - entering the Reports root from the full tab bar selects `CASH_FLOW`, because the separate
     Assets root already owns asset-first navigation;
   - lite's report action selects `ASSETS`.
7. Keep the bottom tab bar unchanged. A sixth root tab is not part of this fix.

**Verify**:
`env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew compileFullDebugKotlin compileLiteDebugKotlin`
must exit 0.

### Step 3: Build the selected-month header and financial summary

In `CashFlowReportContent`:

1. Add a 48 dp month navigator with previous and next controls and the full label, for example
   `مرداد ۱۴۰۵`.
2. Disable previous at the earliest available month and next at the current month. Disabled state
   must be visible and exposed through semantics.
3. Label the current month as partial, for example `مرداد ۱۴۰۵، تا امروز`. Historical months must
   not use that qualifier.
4. Reuse the established `MonthFlow` visual language for exact income and spending totals.
5. Add one explicit net outcome below it:
   - non-negative: label `مانده این ماه` and show `netRial`;
   - negative: label `کسری این ماه` and show the absolute value;
   - never render a negative value under a "remaining" label.
6. Use `tomanOf`, `faCompact`, exact secondary figures where space allows, and `ModamFigures`.
7. Empty states:
   - current month plus SMS off: reuse the intent of `QuietStart`;
   - available historical month with zero movement: state plainly that no transactions were
     recorded in that named month;
   - never render zero totals as if a complete pre-ledger month was observed.

**Verify**:
`env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testFullDebugUnitTest lintFullDebug`
must exit 0.

### Step 4: Add a truthful six-month income-versus-expense chart

1. Add one grouped vertical bar pair per month from the pure series built in step 1.
2. Use a common scale across every bar in the visible window. Scaling each month independently
   would make incomparable values look equal.
3. Render chronological time left to right, matching the existing asset chart decision.
4. Use the app's palette:
   - established income green for income;
   - a theme-derived neutral or primary tone for spending;
   - text labels and a legend, so color is never the only distinction.
5. Mark the selected month structurally with label weight and/or a container treatment, not just a
   different color.
6. Make month groups tappable to select that month. Each must have a TalkBack description containing
   month, income, and spending. Keep each interaction target at least 48 dp wide/high even when its
   bar is visually short.
7. Handle all-zero series with a clear empty treatment and no divide-by-zero path.
8. Do not add line/bar toggles. Cash flow is a discrete monthly quantity, so paired bars are the
   right default and a second chart mode adds control cost without another user question.

**Verify**:
`env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintFullDebug assembleFullDebug`
must exit 0.

### Step 5: Give income and spending equal category detail

1. Replace the current expense-only `خرج‌هات` list with a `SegmentedChoice` for `خرج` and `درآمد`.
2. Default to `خرج` unless spending is zero and income is non-zero.
3. Render all non-zero categories for the selected direction, descending by amount. The dedicated
   report no longer needs the old `take(6)` truncation.
4. For every category row show:
   - category icon and name;
   - amount in Toman;
   - share of the selected direction's total;
   - a common-scale progress bar within that direction.
5. Show a direction-specific empty message if the selected month has no income or no spending.
6. Render month-aware insights after category detail. Historical reports may compare with the
   immediately previous month only when that previous month has transactions.
7. Show cash runway only on the current month, as decided in step 1.

Category-to-ledger drill-down is intentionally deferred. Do not expand `TimelineScreen` filtering
inside this plan.

**Verify**:
`env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testFullDebugUnitTest --tests com.doxigo.muchtoman.ReportsTest lintFullDebug`
must exit 0.

### Step 6: Validate both editions and the real-device experience

Run the full Android gate first:

`env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint assembleDebug`

Expected: exit 0, both editions compile, all tests pass, and lint reports no errors.

Then install the full dev build and verify on a physical phone:

`env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew installFullDev`

Manual acceptance checklist:

- Reports tab opens `دخل و خرج`, not an asset-only page.
- `دخل و خرج` and `دارایی` are equally visible at the top of Reports.
- Tapping the home month band opens cash flow; tapping an asset change/report affordance opens assets.
- Switching modes never shows duplicate cash-flow content under the asset report.
- Current month is selected by default and next month is disabled.
- Previous/next navigation crosses Nowruz correctly and never reaches a future month.
- Tapping a chart month selects it and updates every total, category, and insight together.
- Income, spending, and net values match the ledger. Transfers and duplicates affect none of them.
- A historical report names its month and never shows today's liquid-balance runway.
- Current partial month says `تا امروز`.
- Long Toman figures do not wrap or collide at the system's largest practical font size.
- Dark and light themes retain contrast; selection is understandable without color.
- TalkBack announces report mode, selected month, disabled month buttons, and every chart month.
- The lite dev build still opens an asset-only report with `برگشت` and no cash-flow selector.

If no physical device is visible to `adb devices -l`, stop the device-QA portion and report that
explicitly. Do not mark the plan DONE based only on unit tests and screenshots.

Finally run `git diff --check` and the scope check from the commands table.

## Test plan

Use `ReportsTest.kt` as the main test file because report figures are financial claims and must stay
under plain JVM coverage. Follow its existing `entry(...)` fixture pattern.

Required automated coverage:

- selected-month totals and direction category totals;
- transfer and duplicate exclusion in every aggregation;
- current/previous month rollover;
- earliest/current availability and empty gaps;
- chart-window selection, ordering, and six-month cap;
- current versus historical copy context;
- exact historical insight reference bounds;
- current-only runway;
- zero-income savings rate remaining null;
- both full and lite build variants compiling.

There is no Compose UI-test framework in the repo today. Do not add a new test stack only for this
feature. Cover pure state and financial logic on the JVM, then use the physical-device checklist for
layout, routing, RTL, theme, and TalkBack behavior.

## Done criteria

All must hold:

- [ ] Full Reports defaults to `دخل و خرج` and exposes `دارایی` as a peer mode.
- [ ] Lite Reports remains asset-only without a direct `BuildConfig.LITE` branch outside
      `TabBar.kt`.
- [ ] Users can move through every available Jalali month but cannot navigate into the future.
- [ ] Selected-month income, spending, net, chart, category lists, and insights come from one
      precomputed report state.
- [ ] Income categories and spending categories are both reportable.
- [ ] Transfers and duplicates are excluded everywhere through `spendable`.
- [ ] Historical insight references are bounded to the selected month.
- [ ] Historical reports never combine past spending with today's cash balance.
- [ ] `testFullDebugUnitTest testLiteDebugUnitTest lint assembleDebug` exits 0.
- [ ] Physical-device checklist passes for full and lite builds.
- [ ] `git diff --check` exits 0.
- [ ] No file outside the in-scope list is modified by this implementation.
- [ ] The plan row in `plans/README.md` is updated.

## STOP conditions

Stop and report back if:

- Any current-state excerpt no longer matches the live report architecture.
- Accurate historical month availability requires reading SMS from before the existing ledger
  watermark or adding persistent coverage metadata. That is a separate privacy and migration
  decision.
- A proposed historical metric requires historical account balances. Never substitute today's
  balance.
- The implementation appears to require changing transfer, duplicate, or category classification.
- A new `BuildConfig.LITE` branch outside `TabBar.kt` seems necessary.
- The feature cannot compile without touching any unrelated file that was already dirty when this
  plan was written.
- A verification command fails twice after a reasonable correction.
- Device QA cannot run because no phone/emulator is visible. Report the missing gate instead of
  calling the work complete.

## Maintenance notes

- The ledger's first ingest begins at activation time, not at the start of that month. The earliest
  displayed month can therefore be partial. Do not later market this as a complete year of history
  unless coverage metadata or explicit backfill is added.
- If weekly/yearly ranges are added, define their date boundaries and comparison semantics in pure
  functions first. Do not overload the monthly selector with an untested range switch.
- Category drill-down is the highest-value follow-up: tapping a category should eventually open the
  ledger filtered by selected month, direction, and category. It is deferred here because the
  current timeline has no date/category route contract.
- Reviewers should scrutinize unit conversion, selected-month range bounds, year rollover, and any
  claim containing `این ماه`, `ماه قبل`, `مانده`, or `اولین`.
