# Plan 014: The open app shows a bank message the moment the background stores it

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- app/src/main/java/com/doxigo/muchtoman/Data.kt app/src/main/java/com/doxigo/muchtoman/Daily.kt app/src/main/java/com/doxigo/muchtoman/BankNotifications.kt app/src/main/java/com/doxigo/muchtoman/AppVm.kt app/src/main/java/com/doxigo/muchtoman/MainActivity.kt app/src/main/java/com/doxigo/muchtoman/Ui.kt`
> If any of these changed, compare the "Current state" excerpts against the
> live code before proceeding; on a mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW–MED (one visible behaviour change, named under Maintenance notes)
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

When a bank SMS (or a Blu notification) arrives while the app is on screen, the background
worker stores it, derives the ledger and posts «تراکنش تازه» — but nothing tells the screen.
The timeline, the review badge, the budgets and the total stay as they were until she leaves
the app and comes back, because the only refresh trigger is the activity's `ON_START`. Tapping
the note makes it worse: `onNewIntent` asks for the review deck immediately, and the deck is
decided from the stale ledger — so it either does not open or opens without the transaction the
note was about. After this plan a background ingest reaches the open screen within a second,
and a tapped note waits for a fresh ledger before choosing between deck and دفتر.

**Android only.** The PWA has no background ingest (a browser cannot read SMS; AGENTS.md lists
"background work and notifications while closed" among the standing exceptions to the
both-platforms rule). Do not touch `pwa/`.

## Current state

All Kotlin lives in one flat package, `app/src/main/java/com/doxigo/muchtoman/`.

- `Data.kt:720` — the process-wide coordination lives here:
  ```kotlin
  val ledgerGate = Mutex()
  ```
  Its KDoc (just above) says it is one coarse, **non-reentrant** Mutex.
- `Daily.kt` — `class LedgerWatchWorker`, `doWork()`. It runs in the app's own process (no
  `android:process` anywhere in the manifest). The three places that matter:
  ```kotlin
  val session = loadSession(durable)
  if (!announce && session == null) return Result.success()
  ```
  ```kotlin
  val watched = runCatching {
      ledgerGate.withLock {
          val added = ingestBankSms(app, durable, extra)
          if (added > 0 || needsDerive(derived, durable)) derive(durable, derived, extra)
          if (announce) { /* ledgerView + announceBudgets/Installments/Filing/Quiet */ }
      }
      Result.success()
  }.getOrElse { /* log, Result.retry() */ }
  ```
  ```kotlin
  val result = syncNow(durable, derived, session, assets = assets)
  // Pulled rows sit in durable until a derive folds them in, and the foreground
  // won't repeat it: this pull advanced the cursor, so its own sync receives nought.
  if (result.received > 0 || needsDerive(derived, durable)) derive(durable, derived, extra)
  ```
- `BankNotifications.kt` — `BankNotificationListener.take`:
  ```kotlin
  val added = ledgerGate.withLock {
      ingestNotifications(durable, notes, extra).also {
          if (it > 0) derive(durable, DerivedDb.get(app), extra)
      }
  }
  // The worker for what it says (budgets, «این چی بود؟») and the family sync.
  if (added > 0) watchLedgerSoon(app)
  ```
- `AppVm.kt`:
  - `publishLedger` (private, ~L180) is the only writer of `_state.ledger`. It takes
    `ledgerGate`, then calls `announceBudgets` / `announceInstallments` (both idempotent — they
    write a mark and say nothing when nothing is new) and **`markFilingSeen`**, which cancels
    the filing note and the family notes because "she is looking at the ledger".
  - `runLedger()` (~L235): `if (ledgerJob?.isActive == true) return`, else launches
    `runLedgerPipeline` (ingest → derive if needed → `publishLedger`) on `Dispatchers.Default`
    and keeps the job in `ledgerJob`.
  - `scanSms()` (~L732): folds new inbox messages into the bank balances; guarded by
    `if (scanJob?.isActive == true) return`. The worker never writes balances, so the total
    only moves through this.
  - `refreshIfStale()` (~L248): rates, stocks, wallets, `scanSms()`, `runLedger()`,
    `requestFamilySync(silent = true)`.
  - `openDeck` (~L2504):
    ```kotlin
    fun openDeck(wanted: Boolean) {
        if (!wanted || Tab.LEDGER !in tabs) return
        _state.update { it.copy(openDeck = true) }
    }
    ```
- `Ui.kt` ~L525 consumes it in the same frame, from whatever ledger is in state:
  ```kotlin
  LaunchedEffect(state.openDeck, state.ledger.ready) {
      if (state.openDeck && state.ledger.ready) {
          /* close overlays */ tab = Tab.LEDGER
          deck = state.ledger.review.isNotEmpty()
          vm.consumeOpenDeck()
      }
  }
  ```
- `Ui.kt` ~L555 — the only refresh trigger:
  ```kotlin
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
      val observer = LifecycleEventObserver { _, event ->
          when (event) {
              Lifecycle.Event.ON_STOP -> vm.relock()
              Lifecycle.Event.ON_START -> { vm.refreshIfStale(); canNote = canNotify(context) }
              else -> Unit
          }
      }
      ...
  ```
- `MainActivity.kt:84` — `onNewIntent` calls `appVm.openTab(...)`, then
  `appVm.openDeck(intent.getBooleanExtra(EXTRA_OPEN_DECK, false))`, with no refresh.
  `onCreate` (~L35) also calls `openDeck`, on a fresh `AppVm` whose `init` already ran
  `runLedger()`; `Ui.kt` waits there on `state.ledger.ready`. Leave that call as it is.

Conventions: one flat package, file-per-concept, comments explain **why** (read the comments
around the code you touch and match their register; the code comments call the user "she" —
keep that in code comments, never in commit messages). `lifecycle-runtime-ktx` 2.11 is already
a dependency (`app/build.gradle.kts:200`), so `androidx.lifecycle.repeatOnLifecycle` is
available. No test in the repo constructs `AppVm`.

## Commands you will need

| Purpose | Command | Expected |
|---|---|---|
| Env (once per shell) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| One test class | `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.LedgerLandedTest'` | BUILD SUCCESSFUL |
| Full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | BUILD SUCCESSFUL |

If a test task reports `UP-TO-DATE` after you changed code, add `--rerun`. Lint has crashed
spuriously before ("Unexpected failure during lint analysis"); rerun once before treating a
lint crash as a failure.

## Scope

**In scope** (the only files you may modify):
- `app/src/main/java/com/doxigo/muchtoman/Data.kt` (add the tick beside `ledgerGate`)
- `app/src/main/java/com/doxigo/muchtoman/Daily.kt` (three emits in `LedgerWatchWorker.doWork`)
- `app/src/main/java/com/doxigo/muchtoman/BankNotifications.kt` (one emit)
- `app/src/main/java/com/doxigo/muchtoman/AppVm.kt` (`readWhatLanded`, `openDeck`)
- `app/src/main/java/com/doxigo/muchtoman/Ui.kt` (the collector)
- `app/src/main/java/com/doxigo/muchtoman/MainActivity.kt` (`onNewIntent`)
- `app/src/test/java/com/doxigo/muchtoman/LedgerLandedTest.kt` (create)

**Out of scope**:
- `Notify.kt` — `announceFiling` / `markFilingSeen` stay exactly as they are.
- `publishLedger`, `runLedgerPipeline`, `scanSms`, `runLedger` bodies — call them, do not edit them.
- `DailySnapshotWorker`, `SmsReceiver`, the widget, anything under `pwa/`.

## Git workflow

Commit on the worktree's current branch, **one commit for this plan**. Subject in English only:
a lower-case area prefix, then a plain description — e.g. from the log:
`ledger: let a hand-entered transaction name any bank account, app and PWA`. Suggested:
`ledger: refresh the open app when a background ingest lands`. No `Co-Authored-By` or any
other trailer. Never write about users as "she/her" in a commit message. Do not push, merge or
touch `main`.

## Steps

### Step 1: add the tick in `Data.kt`

Directly under `val ledgerGate = Mutex()` add (import `kotlinx.coroutines.flow.MutableSharedFlow`):

```kotlin
/**
 * Raised by the background paths — [LedgerWatchWorker], [BankNotificationListener] — when a
 * bank message may have landed, and collected by the screen only while it is in front of her.
 *
 * No replay, on purpose. A publish takes the filing note down ([markFilingSeen]), which is
 * right while she is looking and wrong while she is not: a tick kept for a later collector
 * would cancel the very note the worker posted for her to find. Missed ticks cost nothing —
 * coming back to the app reads everything again anyway.
 */
val ledgerLanded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
```

**Verify**: `grep -n "val ledgerLanded" app/src/main/java/com/doxigo/muchtoman/Data.kt` → one line.

### Step 2: emit from the background

Use `ledgerLanded.tryEmit(Unit)` (non-suspending; never `emit`). Four places, all **outside**
any `ledgerGate.withLock { }` block:

1. `Daily.kt`, the early return — the worker has nothing to say, but the open app still wants
   the message: `if (!announce && session == null) { ledgerLanded.tryEmit(Unit); return Result.success() }`
2. `Daily.kt`, inside `runCatching`, on the line after the `ledgerGate.withLock { … }` block
   closes and before `Result.success()`.
3. `Daily.kt`, the family sync: turn the one-line `if (result.received > 0 || needsDerive(…)) derive(…)`
   into a block that derives and then emits.
4. `BankNotifications.kt`: `if (added > 0) { ledgerLanded.tryEmit(Unit); watchLedgerSoon(app) }`

Add one short why-comment at site 2 (the screen she has open reads what this just stored).

**Verify**: `grep -c "ledgerLanded.tryEmit(Unit)" app/src/main/java/com/doxigo/muchtoman/Daily.kt` → `3`;
same grep on `BankNotifications.kt` → `1`.

### Step 3: `AppVm` — read what landed, and a deck that waits

Add beside `refreshIfStale()`:

```kotlin
/**
 * A bank message was stored while she has the app open: the two local reads a return to the
 * foreground makes, without its network half — the worker runs the family sync itself.
 */
fun readWhatLanded() {
    scanSms()
    viewModelScope.launch {
        // runLedger declines while a pipeline is in flight, and that one may be past its read.
        ledgerJob?.join()
        runLedger()
    }
}
```

Replace `openDeck` with:

```kotlin
fun openDeck(wanted: Boolean, fresh: Boolean = false) {
    if (!wanted || Tab.LEDGER !in tabs) return
    if (!fresh) {
        _state.update { it.copy(openDeck = true) }
        return
    }
    // A tapped note on a running app: the row it is about was stored after the last publish,
    // and the deck is chosen from the ledger in state — so read first, then ask for it.
    scanSms()
    viewModelScope.launch {
        ledgerJob?.join()
        runLedger()
        ledgerJob?.join()
        _state.update { it.copy(openDeck = true) }
    }
}
```

Keep the existing KDoc above `openDeck` if there is one; `viewModelScope.launch` here stays on
its default (Main) dispatcher, as `ledgerJob` is only ever assigned from Main.

**Verify**: `./gradlew compileFullDebugKotlin` → BUILD SUCCESSFUL.

### Step 4: wire the screen and the intent

- `Ui.kt`, directly after the `DisposableEffect(lifecycleOwner) { … }` block shown above, add
  (import `androidx.lifecycle.repeatOnLifecycle`):
  ```kotlin
  // Only while she can see it: a publish takes the filing note down, and behind a closed
  // screen that would cancel the note the worker had just posted for her — see [ledgerLanded].
  LaunchedEffect(lifecycleOwner) {
      lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
          ledgerLanded.collect { vm.readWhatLanded() }
      }
  }
  ```
- `MainActivity.kt`, in `onNewIntent` only:
  `appVm.openDeck(intent.getBooleanExtra(EXTRA_OPEN_DECK, false), fresh = true)`.
  Do not change the `onCreate` call.

**Verify**: `./gradlew compileFullDebugKotlin compileLiteDebugKotlin` → BUILD SUCCESSFUL.

### Step 5: the one test

Create `app/src/test/java/com/doxigo/muchtoman/LedgerLandedTest.kt` (plain JUnit 4, no
Robolectric — model the file header on `UiStateCostTest.kt`). Two cases, using
`kotlinx.coroutines.runBlocking`, `withTimeoutOrNull`, `flow.first`, `async` with
`CoroutineStart.UNDISPATCHED`:

1. *a tick nobody is collecting is dropped, not kept*: `assertTrue(ledgerLanded.tryEmit(Unit))`,
   `assertTrue(ledgerLanded.replayCache.isEmpty())`, and
   `assertNull(withTimeoutOrNull(100) { ledgerLanded.first() })`.
2. *a tick reaches whoever is collecting*: start
   `async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(2_000) { ledgerLanded.first() } }`,
   then `ledgerLanded.tryEmit(Unit)`, then `assertNotNull(got.await())`.

Give the class a KDoc saying why no-replay is the property worth pinning (the note-cancel
reason from Step 1).

**Verify**: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.LedgerLandedTest'` →
BUILD SUCCESSFUL, 2 tests.

### Step 6: full gate

**Verify**: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → BUILD SUCCESSFUL.

## Done criteria

- [ ] Full gate above exits 0; `LedgerLandedTest` has 2 passing tests.
- [ ] `grep -rc "ledgerLanded.tryEmit(Unit)" app/src/main/java/com/doxigo/muchtoman/ | grep -v ":0$"` → `Daily.kt:3`, `BankNotifications.kt:1`; `grep -rn "ledgerLanded.collect" app/src/main/java` → one line, in `Ui.kt`.
- [ ] `grep -n "fresh = true" app/src/main/java/com/doxigo/muchtoman/MainActivity.kt` → exactly one line, inside `onNewIntent`.
- [ ] No `tryEmit` sits inside a `ledgerGate.withLock` block (read the four sites).
- [ ] `git status --short` lists only the seven in-scope files; nothing under `pwa/`.

## STOP conditions

- The excerpts in "Current state" do not match the live code.
- `repeatOnLifecycle` does not resolve — do not add a dependency; report.
- Making this work appears to need a change to `publishLedger`, `markFilingSeen` or any
  `Notify.kt` function.
- A verification fails twice after a reasonable fix attempt.

## Maintenance notes

- **Behaviour change a reviewer must know**: with the app in front, the worker's «تراکنش تازه»
  note is now taken down about a second after it is posted, because the publish this plan
  triggers ends in `markFilingSeen`. That is the existing rule ("seeing it is being told")
  applied sooner; budget, installment and cheque notes are unaffected. If the brief flash is
  unwanted, the follow-up is for the worker to skip `announceFiling` while the screen is
  started — deliberately not done here.
- Not covered by an automated test (no test can construct `AppVm`, see plan 029): the
  collector and `openDeck(fresh = true)`. Manual check on an emulator with the **`dev`** build
  (`./gradlew installFullDev`; never the release package, never a phone's real data): with the
  app open on دفتر, `adb emu sms send <a listed bank number> "<a corpus message>"` → the row
  appears within ~6 s (the worker's 5 s delay) without leaving the app.
- Anything new that stores ledger rows from outside `AppVm` must `tryEmit` the same tick.
