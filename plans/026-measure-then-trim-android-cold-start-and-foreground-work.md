# Plan 026: Measure Android's cold start and per-foreground work, then trim only what the numbers show

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- app/src/main/java/com/doxigo/muchtoman/AppVm.kt app/src/main/java/com/doxigo/muchtoman/Data.kt`
> Plans 014 and 015 are expected to have changed `AppVm.kt` (a new `readWhatLanded`, a changed
> `openDeck`, logging calls). Line numbers below are leads; find each site by the quoted text.

## Status

- **Priority**: P3
- **Effort**: M (S to measure; the fixes are sized by what the measurement finds)
- **Risk**: MED (the fixes touch startup state and the "one publisher" guarantee for budget alerts)
- **Depends on**: none (best after 014/015, which touch the same file)
- **Category**: perf
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

**Open question for the operator — this plan does not start until it is answered: which
device may be measured on, and what counts as worth fixing?** Every claim below was found by
reading, not by timing. The costs are plausible (a prefs file of several hundred KB parsed and
an ~80 KB rates blob decoded on the main thread before the first frame; two or three full
ledger publishes per app open on a paired phone) but nobody has a number, and each candidate
fix carries real risk: the first frame must not flash a zero total or the onboarding sheet,
and budget alerts must still be announced exactly once. So this is a measure-first plan. It
changes production code only for a cost the measurement proves, and it may legitimately end
with "nothing worth changing" recorded in the index.

Android only: these are `AppVm`/`Store` paths. The PWA's equivalents are plans of their own.

## Current state

One flat package: `app/src/main/java/com/doxigo/muchtoman/`.

**Claim A — main-thread work before the first frame.** `MainActivity.onCreate` builds the
view-model (`ViewModelProvider(this)[AppVm::class.java]`), whose constructor runs on Main:

```kotlin
class AppVm(app: Application) : AndroidViewModel(app) {
    // … ponytail: still on Main, a few hundred ms for a few-MB ledger on a slow phone; …
    private val restoredAtLaunch = DurableDb.completePendingRestore(app)
    private val store = Store(app)
    …
    private val _state = MutableStateFlow(
        UiState(
            holdings = store.holdings,
            loans = store.loans,
            rates = store.cachedRates,
            tse = store.cachedStocks,
            …
            history = store.history,
            rateHistory = store.rateHistory,
            …
            bankAccounts = store.bankAccounts,
```

`Store` (`Data.kt:936`) opens one `SharedPreferences` file, `"muchtoman"`; its first read
blocks until the whole XML is loaded. The same file holds `seenSms` and both blobs. The rates
getter decodes and sanitises on first use (`Data.kt`, `ratesBlob`):

```kotlin
decode = { sanitizeRates(JSON.decodeFromString<Rates>(it), BuildConfig.RATES_URL) },
```

and the comment above `CachedBlob` says "The rates blob alone is ~80KB". The stocks blob
(`stocksBlob`) holds the bourse snapshot.

**Claim B — repeated whole-ledger work per foreground.** `refreshIfStale()` (called on every
`ON_START`) ends with `scanSms(); runLedger(); requestFamilySync(silent = true)`.
`runLedgerPipeline` ends in `publishLedger`. `runFamilySyncOnce` then does, unconditionally:

```kotlin
val result = syncNow(durable, derived, session, assets = assets)
if (result.received > 0 || needsDerive(derived, durable)) {
    derive(durable, derived, extraLookup(store.extraBankNumbers))
}
publishLedger(durable, derived)
```

— a second full publish even when the sync sent and received nothing. And `recordSnapshot`
(called after every rates refresh, among others) ends with:

```kotlin
if (_state.value.family.sharesAssets) requestFamilySync(silent = true)
```

`requestFamilySync` coalesces: while a sync is running, further requests collapse into one
queued run (`queuedFamilySyncSilent`). So the worst case per foreground is two syncs, each
ending in a publish, plus the pipeline's own publish.

**Claim C — the home summary is rebuilt for every state.** `UiState.story` is
`by lazy { buildStory(ledger.entries, …) }`; `UiState` is immutable and every `_state.update`
makes a new instance, so the month walk reruns after any state change that is followed by a
read of `story` — including `loading`, `syncing` and wallet flags. `Reports.kt` says so itself
above `buildStory`: "this is built once for every state the app produces".
`UiStateCostTest.kt` pins the lazies (`effective`, `listHoldings`, `totals`) by identity and is
the pattern for any test here.

**The guarantee that must survive** (`AppVm.kt`, KDoc above `publishLedger`): "Every path that
changes the ledger ends here, and this is the only place that publishes it … *anything* that
moves them can be the thing that crosses a cap". `publishLedger` also lands the household's
report exclusions (`landReportExclusions`) and calls `markFilingSeen`. Skipping a publish is
only safe when provably nothing it reads has changed.

Tooling: DEVELOPMENT.md names no profiler or benchmark setup; there is no macrobenchmark
module and no baseline profile. `derive` already records its own duration
(`META_DERIVE_MS`, `Derived.kt`).

## Commands you will need

| Purpose | Command | Expected |
|---|---|---|
| Env (once per shell) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk" PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"` | — |
| Install the sandbox build | `ANDROID_SERIAL=<serial> ./gradlew installFullDev` | BUILD SUCCESSFUL |
| Cold start, one sample | `adb shell am force-stop com.doxigo.muchtoman.dev && adb shell am start -W -n com.doxigo.muchtoman.dev/com.doxigo.muchtoman.MainActivity` | prints `TotalTime: <ms>` |
| Full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | BUILD SUCCESSFUL |

## Scope

**In scope**:
- Step 1–2: temporary timing lines in `AppVm.kt` and `Data.kt`, **removed before the end**.
- Step 4, only for a proven cost: `AppVm.kt`, `Data.kt`, and a test beside `UiStateCostTest.kt`.
- `plans/026-…md` itself: write the measured numbers into a "Measurements" section at the bottom.

**Out of scope**:
- `publishLedger`'s body and every `announce*` / `mark*` function in `Notify.kt`.
- `derive` (its full rebuild is a recorded decision: "ponytail: full rebuild every time").
- Splitting the prefs file or moving keys between files — a storage migration is a separate plan.
- `pwa/`, the Workers.

## Git workflow

Commit on the worktree's current branch; one commit for the measurements written into this
plan file, and one per fix actually made. Subjects in English only, lower-case area prefix then
a plain description (log example: `ledger: let a hand-entered transaction name any bank account, app and PWA`).
No `Co-Authored-By` or other trailer; no "she/her" in commit messages; do not push or merge.

## Steps

### Step 1: get the operator's answers (blocking)

Ask, and wait for, three things: (a) the device — default recommendation is the `mt37clean`
emulator; (b) whether a ledger of realistic size is on it (the `dev` build's demo data,
`adb shell am start -n com.doxigo.muchtoman.dev/com.doxigo.muchtoman.MainActivity --ez com.doxigo.muchtoman.DEMO true`,
fills a year); (c) the threshold — recommended: fix a main-thread cost only above 50 ms at the
median, and a redundant publish only if it exceeds 30 ms.

**Never** run any of this against the package `com.doxigo.muchtoman` (the release app), never
`pm clear`, never uninstall, and do not install on a physical phone unless the operator names
it: on the operator's Pixel the `.dev` package holds real data.

**Verify**: the operator's three answers are written at the top of "Measurements".

### Step 2: measure

1. Cold start: run the "Cold start" command 10 times; record every `TotalTime` and the median.
2. Add temporary `android.os.SystemClock.elapsedRealtime()` deltas, logged at `Log.i` with the
   tag `muchtoman-perf` and **durations only**, around: `DurableDb.completePendingRestore`,
   `Store(app)`'s first read, each of `store.cachedRates`, `store.cachedStocks`,
   `store.history`, `store.bankAccounts` in the `UiState(…)` construction, and around each
   `publishLedger` call and each `syncNow` call (label which caller). For Claim C, count
   `buildStory` invocations and total time per foreground.
3. Capture `adb logcat -s muchtoman-perf` over 5 cold starts and 5 warm returns (HOME, reopen).

**Verify**: the "Measurements" section holds a table: site, median ms cold, median ms warm,
calls per foreground.

### Step 3: decide, in writing

For each of A, B, C write one line: over or under the operator's threshold, and therefore
"fix" or "leave". A debug build is slower than a release build (no R8, no baseline profile):
the numbers rank costs against each other; they are not what a user sees. Say so in the
section.

**Verify**: three decision lines exist. If all three are "leave", skip to Step 5.

### Step 4: fix only what Step 3 marked "fix"

- **A** (startup decode): build the first `UiState` from the cheap keys and load
  `cachedRates` / `cachedStocks` / `history` / `rateHistory` in a `Dispatchers.Default`
  launch, updating state when they land. The lock/splash frame must cover the gap: no frame
  may show a zero total, an empty asset list, or onboarding for an onboarded user.
- **B** (redundant publish): in `runFamilySyncOnce`, skip `publishLedger` only when
  `result.sent == 0 && result.received == 0`, no derive ran, **and** `landReportExclusions`
  would return null or the set already in `store.reportExcluded` — otherwise publish as today.
  Debouncing the snapshot-triggered sync is a second, separate change.
- **C** (story per state): key the computation on the fields `buildStory` reads rather than on
  the whole state — e.g. carry the built `HomeStory` forward in `copy()` when
  `ledger`, `bankAccounts`, `disabledBanks`, `reportExcluded`, `installmentReminder` are the
  same instances. Pin it with an identity test modelled on `UiStateCostTest`.

Each fix gets a test where one is possible, the full gate, and a re-run of Step 2's table.

**Verify**: full gate → BUILD SUCCESSFUL; the re-measured row for the fixed site is below the
threshold.

### Step 5: clean up

Remove every `muchtoman-perf` line.

**Verify**: `grep -rn "muchtoman-perf" app/src` → no output; full gate → BUILD SUCCESSFUL.

## Done criteria

- [ ] "Measurements" in this file has the operator's answers, the table, and three decisions.
- [ ] `grep -rn "muchtoman-perf" app/src` → no output.
- [ ] For each "fix": a before/after row, and the full gate exits 0.
- [ ] `git diff 3e6246e -- app/src/main/java/com/doxigo/muchtoman/Notify.kt` shows nothing from this plan.

## STOP conditions

- **No answer to Step 1** — stop there; do not pick a device or a threshold yourself.
- The only reachable device is a physical phone the operator did not name.
- A fix for A shows, even once, a frame with a zero total or onboarding on an onboarded install.
- A fix for B cannot be shown to preserve the alert guarantee (a budget crossed by a row the
  sync pulled must still be announced by that sync's publish).
- Any step would need a change to `publishLedger`'s body, `Notify.kt`, or `derive`.

## Maintenance notes

- If A is fixed, every later addition to `UiState(…)` in the constructor must be classified
  cheap or deferred; say so in a comment at the constructor.
- Splitting `rates`, `stocks` and `seenSms` into their own prefs files is the larger lever for
  A and was left out on purpose: it is a storage migration with backup/restore consequences
  (`EXPORTED_PREFS` / `EXCLUDED_PREFS` in `Data.kt`).
- A macrobenchmark module with a baseline profile would make these numbers repeatable in CI;
  not worth adding for one investigation.

## Measurements

(Empty until Step 1.)
