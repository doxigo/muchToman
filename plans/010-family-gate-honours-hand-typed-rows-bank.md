# Plan 010: The family's bank switch covers hand-typed rows on that bank

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 3e6246e..HEAD -- app/src/main/java/com/doxigo/muchtoman/Sync.kt app/src/main/java/com/doxigo/muchtoman/Derived.kt pwa/src/sync.ts pwa/src/derived.ts`
> If any of these changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: MED (rows already published from a set-aside bank get tombstoned on the next sync — correct, but visible to the family)
- **Depends on**: none
- **Category**: bug (privacy)
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

In خانواده a member can switch a bank off so nothing about it reaches the household. The page
says so in words: «از بانک خاموش، نه تراکنشی فرستاده می‌شه نه موجودی.» and, on the switched-off
row, «فقط روی همین گوشی می‌مونه».

The veto reads `txn.bank`. A transaction typed by hand always carries `bank = "MANUAL"` and
keeps the account it names in `accountId` — and since commit `3e6246e` the hand-entry sheet lets
a typed row name **any** bank account. So a row typed against a set-aside bank passes the veto
and is published (amount, merchant, category, note), while that bank's balance is correctly
withheld. The fix is one small helper that answers "which bank is this row at?" and is used at
every family gate, on both platforms.

## Current state

All Kotlin lives in one flat package: `app/src/main/java/com/doxigo/muchtoman/`.

**How a typed row is shaped** — `Derived.kt`, `manualToRow` (~L421–448):

```kotlin
/** A hand-entered transaction, in the same shape as one read from a message. */
fun manualToRow(row: ManualTxn): Txn = Txn(
    ref = manualRef(row.id),
    ...
    bank = "MANUAL",
    accountId = row.accountId ?: "MANUAL",
```

- `manualRef(id)` is `"m:$id"` (`Ledger.kt:779`), so every typed row's `ref` starts with `m:`.
- `ManualTxn.accountId` is the picked account's **key**, or null for «نقد» (cash):
  `ManualTxnUi.kt:235` `account = a.key`, `:237` `account = ""` for cash, `:351`
  `onSave(..., account?.ifEmpty { null })`.
- An account key is the bank's enum name for its first account and `BANK:1234` for a later one
  (`Sms.kt:203`: `val key: String get() = id.ifEmpty { bank }`).
- So a typed row's `accountId` is `"MELLAT"`, `"MELLAT:0457"`, or `"MANUAL"` (cash / no account).

**An existing reader that already does this** — `Timeline.kt:1812` (use it as the pattern):

```kotlin
if (txn.ref.startsWith("m:") && txn.accountId != "MANUAL") add("حساب" to bankNameOf(txn.accountId.substringBefore(':')))
```

**The three Kotlin gates that read `txn.bank`:**

`Sync.kt`, inside `outgoingRecords` (~L1196–1198):

```kotlin
        // A bank she set aside: its rows never leave, and ones already out are swept below —
        // dropping out of activeIds is what turns yesterday's share into today's tombstone.
        if (txn.bank in excludedBanks) continue
```

`Sync.kt`, `decisionMayLeave` (~L1509–1521), last line:

```kotlin
    if (!shareSms && (txn.sourceKind == "sms" || decision.ref.startsWith("s:"))) return false
    return txn.bank !in excludedBanks
```

`Derived.kt`, inside `ledgerEntries` (~L810–812):

```kotlin
            sharedWithFamily = txn.familyRef.isNotBlank() ||
                (currentMemberId.isNotBlank() && txn.bank !in excludedBanks &&
                    (txn.sourceKind != "sms" || sharesSms)),
```

**The PWA twins** (`pwa/src/`):

- `derived.ts:255–256` already has the key→bank helper:
  ```ts
  /** The bank an account key is at: `MELLAT` for `MELLAT` and for `MELLAT:0457`. */
  export const bankOfKey = (key: string): string => key.split(':')[0];
  ```
- `derived.ts` `manualToRow` (~L43–52) sets `bank: 'MANUAL'`, `accountId: row.accountId ?? 'MANUAL'`.
- `derived.ts` ~L499–500:
  ```ts
      sharedWithFamily: txn.familyRef !== '' ||
        (mineId !== '' && !excluded.has(txn.bank) && (txn.sourceKind !== 'sms' || input.sharesSms)),
  ```
- `sync.ts` `decisionMayLeave` (~L575–581), last line: `return !excludedBanks.has(txn.bank);`
- `sync.ts` inside `outgoingRecords` (~L650): `if (excludedBanks.has(txn.bank)) continue;`
- `sync.ts:25` already imports from derived: `import { familyLocalRef, ledger } from './derived';`
- `timeline.tsx:194` is the PWA's existing pattern:
  `if (txn.ref.startsWith('m:') && txn.accountId !== 'MANUAL') out.push(['حساب', bankFa(bankOfKey(txn.accountId))]);`

**Rows that must keep behaving as today:**

- A family member's row (`ref` starts `f:`, `familyRef` not blank) has
  `accountId = "family:<owner>:<bank>"`. Every gate above already returns/continues on
  `familyRef` before it reaches the bank test, and the helper below only looks inside `m:` rows.
- A cash row (`accountId == "MANUAL"`) belongs to no bank and is never vetoed.
- The **payload** still says `bank = txn.bank` (`"MANUAL"`) for typed rows — do not change it.

**Repo conventions**: comments explain *why*, in plain sentences (see the excerpts). One flat
Kotlin package, no new files for a helper this small. TypeScript has no linter or formatter —
match the hand style around you (long single-line expressions are normal in `sync.ts`).
Repo rule (AGENTS.md): every user-facing change ships in `app/` **and** `pwa/` in the same change.

## Commands you will need

A fresh worktree has no `local.properties` and no `node_modules`.

| Purpose | Command | Expected on success |
|---|---|---|
| Android env (once per shell) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| One Android test class | `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.<Class>'` | `BUILD SUCCESSFUL` |
| Android full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | `BUILD SUCCESSFUL` |
| PWA install | `cd pwa && pnpm install --frozen-lockfile` | exit 0 |
| One PWA test file | `cd pwa && pnpm exec vitest run test/<file>` | all pass |
| PWA full gate | `cd pwa && pnpm run check` | exit 0 |

Gradle's configuration cache can report a stale `UP-TO-DATE` for a test task; if a test task
says `UP-TO-DATE` after you changed code, re-run with `--rerun`.

## Scope

**In scope** (the only files you should modify):
- `app/src/main/java/com/doxigo/muchtoman/Derived.kt`
- `app/src/main/java/com/doxigo/muchtoman/Sync.kt`
- `app/src/test/java/com/doxigo/muchtoman/ManualRowBankGateTest.kt` (create)
- `app/src/test/java/com/doxigo/muchtoman/FamilyBudgetSyncTest.kt` (append one test)
- `pwa/src/derived.ts`
- `pwa/src/sync.ts`
- `pwa/test/manualBank.test.ts` (create)

**Out of scope** (do NOT touch):
- The `bank` field of the published payload (`SyncEntry(bank = txn.bank, …)` in `Sync.kt`,
  `bank: txn.bank` in `pwa/src/sync.ts`). Changing it re-hashes and republishes every typed row
  and would tell the family which bank a typed row is at.
- `ledgerView`'s `disabledBanks` filter (`Derived.kt` ~L856, `pwa/src/derived.ts` ~L508) — see
  Maintenance notes.
- `sync/` (the server) — nothing here changes the wire format or adds a record kind.
- UI files and Persian copy.

## Git workflow

- Commit on the worktree's current branch; one commit for this plan.
- Subject in English only, lower-case area prefix then a plain description, e.g. from the log:
  `ledger: let a hand-entered transaction name any bank account, app and PWA`. Suggested:
  `family: keep hand-typed rows on a set-aside bank at home, app and PWA`.
- No `Co-Authored-By` or any other trailer. Do not describe users as "she/her" in the message.
- Do NOT push, merge, or touch `main`.

## Steps

### Step 1: Add the Kotlin helper

In `Derived.kt`, directly above the KDoc of `manualToRow`, add:

```kotlin
/**
 * The bank a row's money sits at. A hand-typed row carries the "MANUAL" placeholder as its bank
 * and names its account in [Txn.accountId] (`MELLAT`, `MELLAT:0457`), so anything that asks
 * «is this row at a bank she set aside?» has to ask here, not [Txn.bank]. Cash names no account.
 */
internal fun bankOfRow(txn: Txn): String =
    if (txn.ref.startsWith("m:") && txn.accountId != "MANUAL") txn.accountId.substringBefore(':') else txn.bank
```

**Verify**: `./gradlew compileFullDebugKotlin` → `BUILD SUCCESSFUL`.

### Step 2: Use it at the three Kotlin gates

- `Sync.kt` `outgoingRecords`: `if (txn.bank in excludedBanks) continue` → `if (bankOfRow(txn) in excludedBanks) continue`
- `Sync.kt` `decisionMayLeave`: `return txn.bank !in excludedBanks` → `return bankOfRow(txn) !in excludedBanks`
- `Derived.kt` `ledgerEntries`: `txn.bank !in excludedBanks` → `bankOfRow(txn) !in excludedBanks`

Change nothing else on those lines; keep the comments.

**Verify**: `grep -n "txn.bank in excludedBanks\|txn.bank !in excludedBanks" app/src/main/java/com/doxigo/muchtoman/Sync.kt app/src/main/java/com/doxigo/muchtoman/Derived.kt` → no output.

### Step 3: Kotlin tests

Create `app/src/test/java/com/doxigo/muchtoman/ManualRowBankGateTest.kt` — plain JUnit (no
Robolectric runner), modelled on `ManualTxnTest.kt` (same imports style, backticked test names,
a class KDoc that says what is pinned). Build rows with
`manualToRow(ManualTxn(id = "a", at = 1L, day = 0L, amountRial = -5_000L, accountId = …, createdAt = 1L, updatedAt = 1L))`
and decisions with `TxnDecision(id = "note:m:a", ref = "m:a", kind = "note", value = "x", createdAt = 1, updatedAt = 1, memberId = "me")`
(`TxnDecision` is in `Rules.kt`). Cases:

1. `bankOfRow` → `"MELLAT"` for accountId `"MELLAT"`; `"MELLAT"` for `"MELLAT:0457"`;
   `"MANUAL"` for accountId `null`.
2. `bankOfRow` leaves a non-typed row alone: `manualToRow(...).copy(ref = "s:h:0", bank = "SAMAN", accountId = "SAMAN:1")` → `"SAMAN"`;
   and a family row `.copy(ref = "f:x", bank = "MANUAL", accountId = "family:him:MANUAL")` → `"MANUAL"`.
3. `decisionMayLeave(decision, row, shareSms = false, member = "me", excludedBanks = setOf("MELLAT"))`
   is `false` for a typed row on `"MELLAT"` and on `"MELLAT:0457"`, `true` for a cash row, and
   `true` for a typed row on `"MELLAT"` when `excludedBanks = setOf("SAMAN")`.

Then append one test to `FamilyBudgetSyncTest.kt`, directly after the existing test
`` `excluding a bank and disabling SMS sharing retracts its spending on both phones` `` (it
already has the two-phone `Household`/`Phone` harness against a fake server — reuse it, do not
copy it):

```kotlin
    @Test
    fun `a hand-typed row on a bank set aside from the family stays home`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val b = home.phone('b')
            val now = System.currentTimeMillis()
            fun typed(id: String, merchant: String, account: String?) = ManualTxn(
                id = id, at = now, day = tehranDay(now), amountRial = -7_000_000, accountId = account,
                merchant = merchant, createdAt = now, updatedAt = now,
            )
            a.durable.manual().put(typed("typed-refah", "بقالی", "REFAH"))
            a.durable.manual().put(typed("typed-cash", "تاکسی", null))
            suspend fun seenByB() = b.durable.familyTxns().all().map { it.merchant }.toSet()
            a.sync(); b.sync()
            assertEquals(setOf("بقالی", "تاکسی"), seenByB())
            a.durable.meta().put(DurableMeta(META_SYNC_EXCLUDED_BANKS, "REFAH"))
            a.sync(); b.sync()
            assertEquals(setOf("تاکسی"), seenByB())
            assertEquals(
                mapOf("بقالی" to false, "تاکسی" to true),
                ledgerEntries(a.derived, a.durable).entries.associate { it.txn.merchant to it.sharedWithFamily },
            )
            a.durable.meta().put(DurableMeta(META_SYNC_EXCLUDED_BANKS, ""))
            a.sync(); b.sync()
            assertEquals(setOf("بقالی", "تاکسی"), seenByB())
        }
    }
```

**Verify**: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.ManualRowBankGateTest' --tests 'com.doxigo.muchtoman.FamilyBudgetSyncTest'` → `BUILD SUCCESSFUL`.
To prove the tests bite: temporarily revert Step 2's `Sync.kt` `outgoingRecords` line, re-run,
confirm the new sync test FAILS, then restore the line and confirm it passes.

### Step 4: The PWA helper and its three gates

In `pwa/src/derived.ts`, directly under `bankOfKey` (~L256), add:

```ts
/**
 * The bank a row's money sits at (Derived.kt `bankOfRow`): a hand-typed row carries the MANUAL
 * placeholder as its bank and names its account, so the family's bank veto has to ask here.
 */
export const bankOfRow = (txn: Txn): string => (txn.ref.startsWith('m:') && txn.accountId !== 'MANUAL' ? bankOfKey(txn.accountId) : txn.bank);
```

(`Txn` is already imported as a type in `derived.ts`; if not, add it to the existing
`import type { … } from './model'`.) Then:

- `derived.ts` ~L500: `!excluded.has(txn.bank)` → `!excluded.has(bankOfRow(txn))`
- `sync.ts:25`: `import { familyLocalRef, ledger } from './derived';` → add `bankOfRow` to it
- `sync.ts` `decisionMayLeave`: `return !excludedBanks.has(txn.bank);` → `return !excludedBanks.has(bankOfRow(txn));`
- `sync.ts` `outgoingRecords`: `if (excludedBanks.has(txn.bank)) continue;` → `if (excludedBanks.has(bankOfRow(txn))) continue;`

**Verify**: `cd pwa && pnpm run typecheck` → exit 0, and
`grep -n "excludedBanks.has(txn.bank)\|excluded.has(txn.bank)" pwa/src/sync.ts pwa/src/derived.ts` → no output.

### Step 5: PWA tests

Create `pwa/test/manualBank.test.ts`. Copy the module setup from the top of
`pwa/test/sync.test.ts` (the imports, the `let state/sync/crypt/db/derived/session`
declarations, the `HID/ME/MY_DEVICE/THEM/NOW` constants and the whole `beforeEach`) — it is what
gives `outgoingRecords` a session with a real key. Then, mirroring the Kotlin cases:

1. `derived.bankOfRow` for a typed row on `'MELLAT'`, on `'MELLAT:0457'`, on `null` (→ `'MANUAL'`),
   and for a non-typed row (`{ ...row, ref: 's:h:0', bank: 'SAMAN', accountId: 'SAMAN:1' }` → `'SAMAN'`).
   Build rows with `derived.manualToRow({ id, at: NOW - 60_000, day: 0, amountRial: -5_000, accountId, categoryId: null, merchant: 'بقالی', note: '', createdAt: 0, updatedAt: 0, deleted: false })`.
2. `sync.decisionMayLeave(note, row, false, ME, new Set(['MELLAT']))` is `false` for the two
   bank rows and `true` for the cash row (note shape: see `sync.test.ts` ~L334,
   `{ id: 'note:m:a', ref: 'm:a', kind: 'note', value: 'نه', createdAt: 1, updatedAt: 1, deleted: false, memberId: ME, familyRef: '' }`).
3. `sync.outgoingRecords(...)` with one typed row on `'MELLAT'` and one cash row and
   `excludedBanks: ['MELLAT']` publishes exactly one `transaction` record, whose id is
   `sync.familyTxnId(ME, <the cash row's ref>)`. Use the `entry`/`input` helper shapes from
   `sync.test.ts` ~L262–275 (copy them into the new file).
4. `derived.ledgerView(...)` with `manual: [bankRow, cashRow]`, `mineId: 'me'`,
   `excludedBanks: ['MELLAT']` gives `sharedWithFamily` `false` for the bank row and `true` for
   the cash row. The `DeriveInput` defaults are in `pwa/test/derived.test.ts` ~L365–370
   (`input(o)`); copy that helper and import `BUILTIN_CATEGORIES`, `BUILTIN_RULES` from `../src/rules`.

**Verify**: `cd pwa && pnpm exec vitest run test/manualBank.test.ts` → all pass.

### Step 6: Full gates

**Verify**: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → `BUILD SUCCESSFUL`;
`cd pwa && pnpm run check` → exit 0.

## Test plan

- New: `ManualRowBankGateTest.kt` (helper + `decisionMayLeave`), one end-to-end case in
  `FamilyBudgetSyncTest.kt` (publish, retract on exclusion, republish on un-exclusion),
  `pwa/test/manualBank.test.ts` (the same four gates).
- Existing tests that must stay green unchanged: `pwa/test/derived.test.ts` «shares with the
  household exactly what the phone shares» (a cash row stays shared when `SAMAN` is excluded),
  `pwa/test/sync.test.ts` «sends no answer about a row it can no longer find».

## Done criteria

- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → `BUILD SUCCESSFUL`
- [ ] `cd pwa && pnpm run check` exits 0
- [ ] Both greps in Steps 2 and 4 print nothing
- [ ] `grep -c "bankOfRow" app/src/main/java/com/doxigo/muchtoman/Sync.kt` → `2`; `grep -c "bankOfRow" pwa/src/sync.ts` → `3`
- [ ] `git status --short` lists only the in-scope files
- [ ] One commit, no trailer

## STOP conditions

- The excerpts in "Current state" do not match the live code.
- An existing test fails after Step 2 or Step 4 for any reason other than a typo of yours — in
  particular if a cash row (`accountId` null) stops being shared.
- The new end-to-end test cannot see typed rows on phone `b` even before the exclusion is set
  (the harness does not carry manual rows the way this plan assumes) — report what `b` holds.
- The fix seems to need a change to the published payload, the server, or a new record kind.

## Maintenance notes

- **What happens in the field**: a household where someone already typed rows against a
  set-aside bank will see those rows disappear from the other members' ledgers on that phone's
  next sync (the publish loop stops listing them, and its sweep sends the tombstones). That is
  the promise being kept late; mention it in the release note.
- **Deferred, on purpose**: the settings switch that turns a bank off altogether
  (`ledgerView`'s `disabledBanks`, `Derived.kt` ~L856 and `pwa/src/derived.ts` ~L508) also
  tests `txn.bank`, so a typed row naming a switched-off bank stays in دفتر. Whether it should
  leave is a product question, not this bug: the copy for a bank kept by hand says only its
  balance leaves (`bankSwitchFa` in `Data.kt`), and a typed row on a bank whose messages are
  read is the user's own entry. If that is decided, `bankOfRow` is the helper to use.
- Any new gate that asks "is this row at bank X" must call `bankOfRow`, never `txn.bank`.
