# Plan 009: Split Ui.kt and MainActivity.kt along their existing seams

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **PRECONDITION (hard)**: `git status --short` must show **no** uncommitted
> changes to `app/src/main/java/com/doxigo/muchtoman/Ui.kt`,
> `MainActivity.kt`, `Report.kt`, `Reports.kt`, `Budget.kt`, `BudgetUi.kt`,
> `Daily.kt`, or `Derived.kt`. At planning time all of these carried
> uncommitted local edits — splitting files under someone's feet manufactures
> merge conflicts. If the tree is not clean there, STOP immediately.
>
> **Drift check**: after the precondition passes, re-derive the symbol
> inventory of Step 1 from the live files (the line numbers below are from
> 2026-09-10 and will have moved — the *names* are the contract).

## Status

- **Priority**: P3
- **Effort**: L
- **Risk**: MED (large mechanical moves; zero intended behaviour change — the risk is transcription, held down by compile+test gates per move)
- **Depends on**: none (but see the hard precondition)
- **Category**: tech-debt
- **Planned at**: commit `26270fd`, 2026-09-10

## Why this matters

`Ui.kt` is 4,137 lines and `MainActivity.kt` 2,441 — four to eight times the size of the files
around them (the repo's own style is file-per-concept: `Home.kt`, `Settings.kt`, `Timeline.kt`,
`BudgetUi.kt`…). Both have clean internal seams that file search currently hides: `Ui.kt` holds
the app scaffold, a shared component kit, the banks sheet, the whole asset surface, and the
update sheets; `MainActivity.kt` holds `AppVm` (the entire view-model) plus the activity glue.
Splitting along those seams shrinks merge conflicts and makes ownership legible, with zero
behaviour change. This is the lowest-priority plan of its batch — correctness and coverage land
first — but it pays rent on every future change.

## Current state

Everything is in one flat package `com.doxigo.muchtoman`; moving top-level declarations between
files in the same package changes **no import anywhere else** — except that Kotlin `private`
top-level declarations are file-scoped, so a moved symbol still used by its old file must become
`internal`. That visibility bump is the only source-text change allowed besides the moves.

Symbol inventory (line numbers from the planned-at working tree; re-derive before starting):

- `Ui.kt` clusters:
  1. **Scaffold (stays in `Ui.kt`)**: `panel`, `Panel`, `ScreenTitle`, `SheetTitle`, `bandShape`
     (L188–263), `AppRoot` (L264), `TransientNoticeBand` (L314), `WidthCap` (L382),
     `AppScreens` (L394–1230).
  2. **HomeUi.kt (create)**: `HeroPanel` (L1231), `HomeTopBar` (L1271), `HeroCard` (L1315),
     `HeroScopeToggle` (L1521), `ReportLink` (L1558), `ActionCircle` (L1585), `ChangePill`
     (L1677), `TrendCaret` (L1741), `BarsIcon` (L1923).
  3. **UpdateUi.kt (create)**: `openNotificationSettings` (L1771), `openUrl` (L1783),
     `UpdateNote` (L1792), `UpdateSheet` (L1849).
  4. **BanksUi.kt (create)**: `bankNote` (L2065), `BankSheet` (L2098), `BankAccountRow` (L2290),
     `BankLogo` (L3337), `WalletNetworkLogo` (L3397).
  5. **AssetsUi.kt (create)**: `AssetIcon` (L1951), `SectionHead` (L2464), `RowTitle` (L2517),
     `RowAmount` (L2534), `shortWalletAddress` (L2573), `HoldingRow` (L2576), `FamilyAssetBand`
     (L2760), `EmptyHint` (L2800), `MissingNote` (L2843), `PickTypeSheet` (L2879), `PickRow`
     (L3004), `WalletNetworkChoice` (L3268), `EditSheet` (L3446–end).
  6. **Kit (stays in `Ui.kt`)**: `SheetLabel` (L3068), `PillButton` (L3092), `ChipChoice`
     (L3143), `SegmentedChoice` (L3202).
- `MainActivity.kt`: `AppVm` (L50–~2380) → **AppVm.kt (create)**; the file keeps the `MainActivity`
  class (~L2380–end) and whatever top-level helpers only the activity uses (re-derive: anything
  above `class AppVm` and between the two classes).

Conventions that must survive the move:
- Every file in this repo opens with a KDoc block saying what the file *is*. Write one for each
  new file, in the existing voice (see the top of `Home.kt` or `Timeline.kt` for the register).
- Keep declaration order within each moved cluster exactly as it was (reviewability of the diff
  is the whole game: the reviewer should see pure moves).
- KDoc cross-references (`[AppScreens]`, `[notifyBudget]`) resolve package-wide — do not rewrite
  them.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Compile fast | `./gradlew compileFullDebugKotlin` | BUILD SUCCESSFUL |
| Full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint assembleDebug` | BUILD SUCCESSFUL |
| Move sanity | `git diff --stat` | only the expected files; total insertions ≈ deletions (± file headers and visibility bumps) |

## Scope

**In scope**:
- `app/src/main/java/com/doxigo/muchtoman/Ui.kt` (shrinks)
- `app/src/main/java/com/doxigo/muchtoman/MainActivity.kt` (shrinks)
- New files: `HomeUi.kt`, `UpdateUi.kt`, `BanksUi.kt`, `AssetsUi.kt`, `AppVm.kt` (same package,
  same directory)

**Out of scope** (do NOT touch):
- Any other file. If a `private` symbol in a *different* file blocks a move, STOP — that means
  the inventory drifted.
- Any signature, any body, any string, any `remember` key, any lifecycle call — this plan moves
  text; it does not improve it. Resist every temptation; note candidates in your report instead.
- `app/proguard-rules.pro` — nothing here is reflection-reachable by name except `MainActivity`
  (manifest) and it does not move packages.

## Git workflow

You are in a managed worktree; **do not commit or push** — the operator reviews and commits.
If the operator asked for commits, one commit per step below, message style
`ui: <what moved>` matching `git log`'s voice.

## Steps

One cluster per step; after each: compile, then the moved-cluster sanity greps.

### Step 1: `UpdateUi.kt` (smallest — proves the mechanics)

Create the file with its header KDoc; cut `openNotificationSettings`, `openUrl`, `UpdateNote`,
`UpdateSheet` from `Ui.kt` verbatim; bump `private` → `internal` only where the compiler demands
(callers left behind in `Ui.kt`). 
**Verify**: `./gradlew compileFullDebugKotlin` → BUILD SUCCESSFUL;
`grep -c "fun UpdateSheet" app/src/main/java/com/doxigo/muchtoman/Ui.kt` → 0.

### Step 2: `HomeUi.kt`

Move cluster 2. **Verify**: compile; `wc -l app/src/main/java/com/doxigo/muchtoman/Ui.kt` shrinks
by roughly the cluster's size.

### Step 3: `BanksUi.kt`

Move cluster 4. Note `BankLogo`/`WalletNetworkLogo` are used by other files already (they are
`internal`/`private` — re-check which); keep their visibility working package-wide.
**Verify**: compile.

### Step 4: `AssetsUi.kt`

Move cluster 5 (the big one, ~1,500 lines). **Verify**: compile;
`./gradlew testFullDebugUnitTest` → green.

### Step 5: `AppVm.kt`

Move `class AppVm` out of `MainActivity.kt` whole, with any top-level helpers used **only** by it
(re-derive; candidates sit between the imports and the class). `MainActivity.kt` keeps the
activity. **Verify**: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint assembleDebug`
→ BUILD SUCCESSFUL.

### Step 6: The ledger of moves

In your report, list every symbol moved per file and every visibility bump made. 
**Verify**: `git diff --stat` matches the scope list exactly; `Ui.kt` ≤ ~1,900 lines;
`MainActivity.kt` ≤ ~150 lines beyond the activity class.

## Test plan

No new tests — the invariant is "nothing changed". The gate is the full existing suite plus
`assembleDebug` for both flavours (R8 does not run on debug; `lint` catches dead-code and
visibility surprises). If any test fails, the move broke something real: STOP rather than patch.

## Done criteria

- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint assembleDebug` exits 0
- [ ] All five new files exist, each with a header KDoc; `Ui.kt` and `MainActivity.kt` contain
      only their stay-behind clusters
- [ ] `git diff` contains **no hunk that edits a statement** — only whole-declaration moves,
      `private`→`internal` bumps, imports, and file headers
- [ ] The moved-symbol ledger in the executor report matches the diff
- [ ] `plans/README.md` status row updated (unless a reviewer maintains the index)

## STOP conditions

Stop and report back (do not improvise) if:

- The hard precondition fails (uncommitted changes in the named files).
- The live symbol inventory differs from Step 1's list by more than moved line numbers — e.g. a
  cluster gained a symbol you'd have to re-home by judgement. Report the delta first.
- A move demands editing a file outside the scope list.
- Any test or lint failure survives one honest look — do not "fix" behaviour to make a move
  compile.

## Maintenance notes

- Future screens should land in their own files from the start (the repo mostly already does
  this; `Ui.kt` was the accretion point).
- Reviewer: diff with `git diff --color-moved=dimmed-zebra` — moved blocks render dimmed; anything
  bright inside a moved block is an edit that shouldn't be there.
- Deliberately deferred: splitting `AppVm` itself (persistence/ingest/wallet/family concerns) —
  that is a real refactor with judgement calls, not a mechanical move; propose separately if
  wanted.