# Plan 015: Release builds log what failed and the exception's class, never its text or a figure

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- app/src/main/java/com/doxigo/muchtoman/`
> Plan 014 may have landed before this one in the same worktree; it adds no logging calls, so
> the counts below still hold, but **line numbers in this plan are leads — find every site with
> the greps given, not by line number**. If the count in Step 1 differs, that is a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (logging only; debug builds print exactly what they print today)
- **Depends on**: none (runs after 014 only because both touch `AppVm.kt`, `Daily.kt`, `BankNotifications.kt`)
- **Category**: security
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

The app promises that a crash report carries "class names and stack frames, and nothing else",
because exception messages are where data leaks: a `NumberFormatException` quotes the amount it
choked on, a parse error quotes the SMS. Logcat does not keep that promise. Sixty-eight call
sites write `"… failed: $it"` — the exception's full `toString()`, message included — on
release builds, and one line prints an account id and two real balances in Rial. Logcat is
readable over adb, rides along in the bug reports people forward when asking for help, and is
visible to OEM diagnostic services. After this plan a release build logs the label and the
exception's class only; a debug build logs exactly what it logs now.

**Android only.** The PWA's `console.warn` lines are not readable by other apps or carried in
system bug reports; they are out of scope and unchanged.

## Current state

One flat package: `app/src/main/java/com/doxigo/muchtoman/`. Every logging call is written
fully qualified as `android.util.Log.<level>("muchtoman", …)`; there is no `import android.util.Log`,
no `println`, no `printStackTrace` anywhere in `app/src/main`.

Count at `3e6246e` (`grep -c "android.util.Log" *.kt`, non-zero files): `AppVm.kt` 51,
`Notify.kt` 11, `Daily.kt` 2, `Ledger.kt` 2, and 1 each in `BankNotifications.kt`, `Csv.kt`,
`Data.kt`, `Sync.kt`, `SmsInbox.kt`, `UpdateUi.kt` — **72 in all**.

The rule already exists twice in the code:

- `Diagnostics.kt` (~L113), above `fun crashReport`:
  ```kotlin
   * Class names and stack frames, and nothing else. Exception messages are left out on purpose:
   * that is where data leaks. A NumberFormatException quotes the amount it choked on, a parse error
   * quotes the SMS.
  ```
- `Data.kt` `stashCorrupt` (~L1323) — the one site that already obeys it:
  ```kotlin
  android.util.Log.w(
      "muchtoman",
      "prefs blob '$key' failed to decode (${cause.javaClass.simpleName}); raw kept at '$key.corrupt'",
  )
  ```

The typical site (68 of them), e.g. `AppVm.kt`:
```kotlin
}.onFailure { android.util.Log.w("muchtoman", "categorise failed: $it") }
```
The interpolated value is always a `Throwable`, named `it`, `e` or `error`.

The sites that are **not** that shape:

| Where (find by text) | Today | What to do |
|---|---|---|
| `AppVm.kt` `"tse ok: ${fresh.stocks.size} instruments"` | a count | leave untouched |
| `AppVm.kt` `"ledger: +$added messages, $rows transactions"` | two counts | leave untouched |
| `Data.kt` `stashCorrupt` | already class-name-only | leave untouched |
| `AppVm.kt` `"ledger disagrees on ${balance.accountId} over the same messages: " + "fold=$foldedRial derived=${balance.rial} Rial (…)"` (multi-line, in `runLedgerPipeline`) | account id + two balances | rewrite — Step 3 |
| `AppVm.kt` `"backup open failed: ${(e as? BackupException)?.fault ?: e}"` | enum, else the exception | Step 2, special form |
| `AppVm.kt` `"$what failed: $it"` | label is a variable | Step 2: `logFailure("$what failed", it)` |
| `AppVm.kt` `"rates fetch failed: $e"` | has a comment claiming the log is "the only place the real reason survives" | Step 2, and amend that comment |

`BackupFault` (`Export.kt:95`) is an enum (`NOT_A_BACKUP`, `NEWER_FORMAT`,
`WRONG_PASSPHRASE_OR_CORRUPT`, …) — safe to print.

Conventions: file-per-concept, comments explain **why**. Unit tests are plain JUnit 4 unless
they need Android types; `DiagnosticsTest.kt` is plain JUnit. `android.util.Log` is **not**
mocked in plain JUnit tests (`unitTests.isReturnDefaultValues` is not set), so the new test
must exercise a pure function, not the call into `Log`.

## Commands you will need

| Purpose | Command | Expected |
|---|---|---|
| Env (once per shell) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| Count sites | `grep -c "android.util.Log" app/src/main/java/com/doxigo/muchtoman/*.kt \| grep -v ":0$"` | see steps |
| One test class | `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.DiagnosticsTest'` | BUILD SUCCESSFUL |
| Full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | BUILD SUCCESSFUL |

Add `--rerun` if a test task claims `UP-TO-DATE` after a change. Rerun lint once before
treating a lint crash ("Unexpected failure during lint analysis") as a failure.

## Scope

**In scope** (the only files you may modify — in each, **only the logging call and, where
named, its adjacent comment**):
- `app/src/main/java/com/doxigo/muchtoman/Diagnostics.kt` (the helper)
- `AppVm.kt`, `Notify.kt`, `Daily.kt`, `Ledger.kt`, `BankNotifications.kt`, `Csv.kt`,
  `Sync.kt`, `SmsInbox.kt`, `UpdateUi.kt` (same directory)
- `app/src/test/java/com/doxigo/muchtoman/DiagnosticsTest.kt` (two new tests)

**Out of scope**:
- `Data.kt` — `stashCorrupt` already complies.
- `app/proguard-rules.pro` — do not strip `Log` with R8 rules; the lines are wanted, shortened.
- The Persian strings beside the logging calls, any control flow, any `runCatching` shape.
- `pwa/`, `worker/`, `sync/`.

## Git workflow

Commit on the worktree's current branch, **one commit for this plan**. Subject in English only,
lower-case area prefix then a plain description (log example:
`ledger: let a hand-entered transaction name any bank account, app and PWA`). Suggested:
`logs: keep exception text and balances out of release logcat`. No `Co-Authored-By` or any
other trailer; never refer to users as "she/her" in a commit message; do not push, merge or
touch `main`.

## Steps

### Step 1: confirm the starting count

**Verify**: the "Count sites" command prints exactly:
`AppVm.kt:51`, `BankNotifications.kt:1`, `Csv.kt:1`, `Daily.kt:2`, `Data.kt:1`, `Ledger.kt:2`,
`Notify.kt:11`, `SmsInbox.kt:1`, `Sync.kt:1`, `UpdateUi.kt:1` (72). Otherwise STOP.

### Step 2: add the helper, then convert the 68 failure sites

In `Diagnostics.kt`, directly above `fun crashReport` (so the two statements of the rule sit
together), add:

```kotlin
/**
 * The line a failure leaves in logcat: what failed, and the exception's class. [crashReport]'s
 * rule, for the same reason — the message is where an amount or a message body leaks, and
 * logcat rides along in every bug report she forwards. A debug build keeps the message: that
 * is the developer's own phone, and the reason is the point of looking.
 */
internal fun failureLine(what: String, cause: Throwable, debug: Boolean = BuildConfig.DEBUG): String =
    if (debug) "$what: $cause" else "$what: ${cause.javaClass.simpleName}"

/** Every failure this app logs goes through here — see [failureLine]. */
fun logFailure(what: String, cause: Throwable) {
    android.util.Log.w("muchtoman", failureLine(what, cause))
}
```

(`"$what: $cause"` is byte-for-byte what `"X failed: $it"` prints today.)

Then convert mechanically, in all nine files:

`android.util.Log.w("muchtoman", "TEXT: $x")` → `logFailure("TEXT", x)`

where `x` is `it`, `e` or `error`. Keep `TEXT` exactly as it is (`"crash report not sent"`,
`"skipped a malformed pulled record"` and `"notification settings unavailable"` do not end in
"failed" — that is fine). The three special forms:

- `"$what failed: $it"` → `logFailure("$what failed", it)`
- backup open → `logFailure("backup open failed (${(e as? BackupException)?.fault ?: "other"})", e)`
- rates fetch → `logFailure("rates fetch failed", e)`, and amend the comment above it so it stays
  true: the real reason is in the log on a debug build; a release build names the exception's
  class only (point at `[logFailure]`).

**Verify**:
- `grep -c "android.util.Log" app/src/main/java/com/doxigo/muchtoman/*.kt | grep -v ":0$"` →
  exactly `AppVm.kt:3`, `Data.kt:1`, `Diagnostics.kt:1`.
- `grep -nE 'Log\.[a-z]\([^)]*\$\{?(it|e|error|cause)\b' app/src/main/java/com/doxigo/muchtoman/*.kt` → no output.
- `./gradlew compileFullDebugKotlin` → BUILD SUCCESSFUL.

### Step 3: take the figures out of the fold/ledger disagreement line

In `AppVm.kt` `runLedgerPipeline`, replace the multi-line `android.util.Log.w(…)` inside
`if (foldedRial != balance.rial) { … }` with:

```kotlin
// No figure, and the account only on a debug build: this line reaches logcat on her phone.
android.util.Log.w(
    "muchtoman",
    "ledger and fold disagree on an anchored balance over the same messages" +
        if (BuildConfig.DEBUG) " (${balance.accountId})" else "",
)
```

Leave the surrounding `for` loop, its conditions and the long comment above it untouched. If
`foldedRial` is still used by the `if`, keep it; do not remove variables the condition needs.

**Verify**: `grep -n 'fold=\|derived=\$' app/src/main/java/com/doxigo/muchtoman/AppVm.kt` → no output.

### Step 4: pin the rule

Add two tests to `DiagnosticsTest.kt` (same style as the existing
`` `a report is class names and frames, never a message` `` test in that file):

1. *a release failure line names the class and never the message*:
   `failureLine("categorise failed", NumberFormatException("For input string: \"4,666,251,136\""), debug = false)`
   equals `"categorise failed: NumberFormatException"`, and does not contain `"4,666"`.
2. *a debug failure line keeps the message*: same call with `debug = true` contains
   `"4,666,251,136"` and starts with `"categorise failed: java.lang.NumberFormatException"`.

**Verify**: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.DiagnosticsTest'` →
BUILD SUCCESSFUL, the two new tests among those run.

### Step 5: full gate

**Verify**: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → BUILD SUCCESSFUL.

## Done criteria

- [ ] Full gate exits 0; `DiagnosticsTest` includes the two new passing tests.
- [ ] `grep -c "android.util.Log" …/*.kt | grep -v ":0$"` → `AppVm.kt:3`, `Data.kt:1`, `Diagnostics.kt:1` and nothing else.
- [ ] `grep -nE 'Log\.[a-z]\([^)]*\$\{?(it|e|error|cause)\b' …/*.kt` → no output.
- [ ] `grep -n 'fold=\|derived=\$' …/AppVm.kt` → no output.
- [ ] `grep -c "logFailure(" app/src/main/java/com/doxigo/muchtoman/*.kt` sums to 69 (68 call sites + the definition).
- [ ] `git diff --stat` lists only the ten main files named in Scope plus `DiagnosticsTest.kt`; `git diff` shows no changed line that is not a logging call, its adjacent comment, or the helper.

## STOP conditions

- Step 1's count is not 72 with that per-file breakdown (the code has drifted — list what you found).
- A site interpolates something that is not a `Throwable` and is not in the table above.
- Converting a site would require changing control flow (for example a `Log` call whose
  result is used, or one inside an expression you would have to restructure).
- A verification fails twice after a reasonable fix attempt.

## Maintenance notes

- New code should call `logFailure("<what> failed", throwable)`; a raw `android.util.Log`
  call that interpolates an exception or a figure will reappear unnoticed — the two greps in
  "Done criteria" are the review check. A lint rule would be the upgrade if it recurs.
- Release class names are R8-renamed for the app's own exceptions; platform ones
  (`IOException`, `SQLiteException`) read as they are. `retrace` with that release's
  `mapping.txt` turns the rest back (DEVELOPMENT.md § Releasing).
- Field debugging on a release build loses the message (e.g. an HTTP status). That is the
  trade this plan makes on purpose; the `dev` build type keeps everything.
- Deferred: the two count-only info lines and `stashCorrupt` were left as they are because
  they print no content.
