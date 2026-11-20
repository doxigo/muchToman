# Plan 029: Android's ledger actions are pinned by tests, as the PWA's are

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- app/src/main/java/com/doxigo/muchtoman/AppVm.kt app/src/main/java/com/doxigo/muchtoman/Edits.kt pwa/test/derived.test.ts`
> Earlier plans are expected to have changed `AppVm.kt` (logging calls, `openDeck`,
> `readWhatLanded`). Find `categorise` by name; if its body differs from the excerpt below in
> more than the logging line, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW on Path A (tests only); LOW–MED on Path B (a move of production code)
- **Depends on**: none (best after 014/015, which touch `AppVm.kt`)
- **Category**: tests
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

**Open question for the operator — answer before Step 2: if `AppVm` cannot be built in a JVM
test without touching production code, is a small production seam acceptable?** The
recommended seam is Path B below: move `categorise`'s write sequence, unchanged, into a
top-level function that takes the two databases — the shape every other tested write in this
repo already has.

`AppVm.kt` is 3,100 lines and holds about a hundred public actions that write `durable.db`
decisions: filing, delete/restore, split, edit, "always" rules. Real logic sits inline in
them — `categorise` alone rejects an auto transfer link on refile, keeps stamps monotonic,
clears a split, writes a rule, and chooses between a one-row pin and a full derive. No test
constructs `AppVm`, so none of that is exercised on Android. The PWA's twin is: about twenty
"ledger actions" cases in `pwa/test/derived.test.ts`. "Same behaviour on both platforms" is
therefore asserted on one platform only, in the file with the highest churn in the app.

## Current state

- `app/src/main/java/com/doxigo/muchtoman/AppVm.kt` — `fun categorise(entry: LedgerEntry, categoryId: String, always: Boolean)`.
  Its body, inside `viewModelScope.launch { filing.withLock { withContext(Dispatchers.Default) { runCatching { … } } } }`:
  ```kotlin
  val session = loadSession(durable)
  val previous = durable.decisions().forRef(entry.txn.ref)
      .firstOrNull { it.kind == DecisionKind.CATEGORY }
  val now = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0L) + 1L)
  val rejecting = entry.transfer && categoryId != CAT_TRANSFER
  if (rejecting) {
      derived.links().touching(entry.txn.ref)
          .filter { it.kind == LinkKind.TRANSFER && it.auto }
          .forEach { link ->
              durable.linkDecisions().put(
                  linkDecision(link.aRef, link.bRef, LinkKind.TRANSFER, Verdict.REJECTED, now)
              )
          }
  }
  durable.decisions().put(TxnDecision(id = uuid7(now), ref = entry.txn.ref,
      kind = DecisionKind.CATEGORY, value = categoryId, createdAt = now, updatedAt = now,
      memberId = session?.member.orEmpty(),
      familyRef = entry.txn.familyRef.ifBlank { session?.let { familyTxnId(it.member, entry.txn.ref) }.orEmpty() }))
  putAnswer(durable, entry.txn, DecisionKind.SPLIT, null, session?.member.orEmpty())
  if (always) {
      val addrKey = durable.smsSource().addrKeyOf(entry.txn.srcHash).orEmpty()
      durable.rules().put(ruleFrom(entry.txn, categoryId, addrKey, now))
  }
  val pinned = !always && !rejecting && !needsDerive(derived, durable) &&
      pinClass(derived, entry.txn, categoryId)
  if (!pinned) derive(durable, derived, extraLookup(store.extraBankNumbers))
  publishLedger(durable, derived)
  requestFamilySync(silent = true)
  ```
  `categoriseAll(assignments)` follows it in the file with the same structure (rising stamps,
  one derive at the end).
- What building `AppVm(app)` does (read the constructor and `init {}` at the top of the file):
  1. `DurableDb.completePendingRestore(app)` and `Store(app)`.
  2. `init { refresh(); refreshStocksIfHeld(); refreshWallets(); scanSms(); runLedger(); … requestFamilySync(silent = true); scheduleDailySnapshot(app) }`.
     - `refresh()` calls `fetchRates(BuildConfig.RATES_URL, …)` — a real `HttpURLConnection`
       to `https://rates.muchtoman.com/rates` (the debug default in `app/build.gradle.kts`,
       overridable only at build time with `-Pmuchtoman.ratesUrl=…`). **No suite in this repo
       touches the real network.**
     - `scheduleDailySnapshot` calls `WorkManager.getInstance(context)`. `work-testing` is not
       a dependency.
  3. Every action reaches the databases through `DurableDb.get(app)` / `DerivedDb.get(app)` —
     process-wide singletons (`Ledger.kt:284`, `Derived.kt:142`) with **no reset**, so a second
     test method in the same JVM would get the first one's instance.
  4. Work runs on `viewModelScope` (Main) and hops to `Dispatchers.Default`; a test has to wait
     for it on real threads.
- How tested writes look everywhere else — `app/src/test/java/com/doxigo/muchtoman/PinClassTest.kt`:
  ```kotlin
  @RunWith(RobolectricTestRunner::class)
  @Config(sdk = [28])
  class PinClassTest {
      …
      val context = ApplicationProvider.getApplicationContext<Context>()
      val durable = Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()
      val derived = Room.inMemoryDatabaseBuilder(context, DerivedDb::class.java).build()
      try { seedBuiltins(durable, now); durable.smsSource().insertAll(…); derive(durable, derived, emptyMap(), now) … }
      finally { durable.close(); derived.close() }
  ```
  Note that it writes the `TxnDecision` itself — i.e. it re-types what `categorise` does, which
  is exactly the duplication this plan removes.
- `Edits.kt` is where the already-extracted write helpers live: `putAnswer`, `editRow`,
  `revertRow`, `splitRow` — `internal suspend fun …(durable: DurableDb, …)`.
- The cases to port, `pwa/test/derived.test.ts`, `describe('ledger actions', …)`:
  - `files one row, and «همیشه» files every one like it` — also asserts the monotonic stamp:
    a stored `updatedAt` far in the future is followed by exactly `ahead + 1`.
  - `rejects the transfer link when one leg is refiled as spending, and not when it is filed as a transfer`
  - `files the whole deck with rising stamps`
  - then, in order: the note (200 characters, blank takes it back), delete/restore of her own
    rows and never another member's, the typed row moved to another day, the after-the-fact
    "always" switch, the direction answer, edit and split.

Conventions: one flat package; test files are `<Concept>Test.kt` with backticked sentence
names and a KDoc saying what is pinned and why. Money is `Long` Rial. Golden SMS bodies come
from `app/src/test/resources/sms/*.json` via `CorpusFixtures.kt` where a real message is
wanted; short synthetic bodies like `"بانک سامان\nبرداشت مبلغ 1,000,000 ریال"` are used too.

## Commands you will need

| Purpose | Command | Expected |
|---|---|---|
| Env (once per shell) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| The new class | `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.LedgerActionsTest'` | BUILD SUCCESSFUL |
| Full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | BUILD SUCCESSFUL |

Add `--rerun` when a test task claims `UP-TO-DATE`. Confirm tests really ran by reading
`app/build/test-results/testFullDebugUnitTest/TEST-com.doxigo.muchtoman.LedgerActionsTest.xml`.

## Scope

**In scope**:
- `app/src/test/java/com/doxigo/muchtoman/LedgerActionsTest.kt` (create)
- Path B only, and only with the operator's yes: `AppVm.kt` (`categorise`, `categoriseAll`
  bodies become calls) and `Edits.kt` (the moved functions).

**Out of scope**:
- Any behaviour change. Path B is a move: the statements, their order and their arguments stay.
- `app/build.gradle.kts` — no new test dependency (`work-testing`, coroutines-test, a mock
  web server) without asking.
- `pwa/` — read its tests, do not edit them.
- Splitting `AppVm`'s class body in general (rejected in an earlier audit; this plan moves two
  write sequences and nothing else).

## Git workflow

Commit on the worktree's current branch. Path A: one commit. Path B: one commit for the move
(no test changes, full gate green), then one for the tests. Subjects in English only,
lower-case area prefix then a plain description (log example:
`test: follow the fixed-cost rename in the browser suite and the copy mirror`). No
`Co-Authored-By` or other trailer; no "she/her" in commit messages; do not push or merge.

## Steps

### Step 1: feasibility of Path A — build `AppVm` in a test (time-box: one attempt)

Write one throwaway Robolectric test that constructs `AppVm(ApplicationProvider.getApplicationContext())`
and waits for `state.value.ledger.ready`. Path A is viable only if **all** hold, with no
production change and no new dependency:

1. no real network request is attempted (prove it: the test passes with the machine offline,
   and `refresh()`'s failure path is what ran);
2. `WorkManager.getInstance` does not throw;
3. two test methods in one class each see an empty ledger at the start (the DB singletons do
   not leak state between them);
4. the test finishes in under 10 s, three runs in a row.

**Verify**: report each of the four as pass/fail with the evidence. If all pass → Step 2A.
If any fails → delete the throwaway test and go to Step 2B **only if the operator said yes to
the seam**; otherwise STOP.

### Step 2A: port the cases against `AppVm`

Create `LedgerActionsTest.kt`. One test per PWA case listed above, same sentence as its name,
in the same order, starting with the three `categorise` / `categoriseAll` cases. Seed
messages with `durable.smsSource().insertAll(…)` as `PinClassTest` does, run the action, wait
on `vm.state`, and assert on durable rows (`decisions()`, `linkDecisions()`, `rules()`) **and**
on `state.value.ledger` — both, as the PWA cases do.

### Step 2B: the seam, then the port (needs the operator's yes)

1. In `Edits.kt`, add
   `internal suspend fun fileRow(durable: DurableDb, derived: DerivedDb, entry: LedgerEntry, categoryId: String, always: Boolean, extra: Map<String, Bank>, clock: () -> Long = System::currentTimeMillis)`
   holding the statements quoted in "Current state" from `val session = loadSession(durable)`
   through `if (!pinned) derive(…)`, unchanged except `System.currentTimeMillis()` → `clock()`
   and `extraLookup(store.extraBankNumbers)` → `extra`. Do the same for `categoriseAll`'s
   write block as `fileRows(…)`.
2. `AppVm.categorise` keeps its `viewModelScope.launch { filing.withLock { withContext(Dispatchers.Default) { runCatching { … } } } }`
   shell and its last two calls; the shell's body becomes
   `fileRow(durable, derived, entry, categoryId, always, extraLookup(store.extraBankNumbers))`
   followed by `publishLedger(durable, derived)` and `requestFamilySync(silent = true)`.
3. Full gate must be green **before** any test is added (the move alone changes nothing).
4. Create `LedgerActionsTest.kt` against in-memory databases exactly as `PinClassTest` builds
   them, calling `fileRow` / `fileRows` and the existing `Edits.kt` helpers. Same cases, same
   order, same names as Step 2A.

**Verify (either path)**: the "new class" command → BUILD SUCCESSFUL, and the XML report lists
at least the three filing cases.

### Step 3: prove the tests bite

For each of the three filing cases, temporarily break the production line it guards (e.g.
change `+ 1L` to `+ 0L` in the stamp; drop the `categoryId != CAT_TRANSFER` half of
`rejecting`; remove the `putAnswer(… SPLIT, null …)` line), run the class, confirm the named
test fails, and restore the line.

**Verify**: `git diff -- app/src/main` after restoring shows only the Path B move (or nothing
on Path A); report which test failed for which mutation.

### Step 4: full gate

**Verify**: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → BUILD SUCCESSFUL.

## Done criteria

- [ ] `LedgerActionsTest.kt` exists; the full gate exits 0 for both flavours.
- [ ] At least these three tests exist and pass: one-row filing with «همیشه» and the monotonic
      stamp; transfer-link rejection on refile but not on filing as a transfer; whole-deck
      filing with rising stamps.
- [ ] Step 3's mutation table is in the report: three mutations, three failing tests.
- [ ] Path A: `git status --short` shows one new test file and nothing under `app/src/main`.
- [ ] Path B: `git diff --stat` shows only `AppVm.kt`, `Edits.kt` and the new test file, and
      `grep -n "fun fileRow" app/src/main/java/com/doxigo/muchtoman/Edits.kt` → one line.
- [ ] No test opens a network connection and no new dependency was added
      (`git diff -- app/build.gradle.kts gradle/libs.versions.toml` → empty).

## STOP conditions

- Step 1 fails on any of its four checks **and** the operator has not approved the seam.
- Path B's move cannot be made without changing a statement's meaning (for example because
  the block reads private `AppVm` state other than `store.extraBankNumbers`).
- A ported case fails against unchanged production code: that is a real Android/PWA
  difference — report it with both behaviours; do not "fix" either side or weaken the test.
- A case needs behaviour the Android app does not have (a PWA-only action such as pasting):
  skip it and list it, do not invent an equivalent.

## Maintenance notes

- New `AppVm` actions with logic of their own should be written as a top-level function over
  the two databases plus a thin `AppVm` shell — then a test costs ten lines.
- The remaining untested actions (notes, delete/restore, manual rows, budgets, goals) follow
  the same recipe; port them in the PWA file's order so the two suites can be read side by side.
- Reviewer: on Path B read the move as a diff of moved lines; any edit inside the moved block
  other than the two substitutions named in Step 2B is a defect.
