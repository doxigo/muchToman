# Plan 028: A bank number she added by hand can be taken back off

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- app/src/main/java/com/doxigo/muchtoman/AppVm.kt app/src/main/java/com/doxigo/muchtoman/BanksUi.kt app/src/main/java/com/doxigo/muchtoman/Sms.kt app/src/main/java/com/doxigo/muchtoman/Ui.kt pwa/src/pasteUi.tsx pwa/src/model.ts pwa/src/ledger.ts`
> Earlier plans are expected to have touched `AppVm.kt` and `Ui.kt`; find each site by the
> quoted text, not by line number. A mismatch in the quoted code itself is a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M (both platforms, new Persian copy, a destructive action)
- **Risk**: LOW–MED (removal re-derives the ledger and forgets one bank's read balances)
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

**Open question for the operator — nothing here is built until both are answered:
(1) the Persian copy drafted under "Draft copy" needs a yes or a rewrite; (2) whether the PWA
should also drop the messages a forgotten sender already filed (Step 5), which needs one new
optional field on stored messages.**

The app's built-in bank senders are a strict allowlist, but the bank sheet offers any unknown
sender whose message parses as money — ordinary mobile numbers included — and one confirmation
teaches it for good. From then on that number's messages set balances, raise notifications
and, with sharing on, sync to the family. There is no way back: `addBankNumber` only appends,
and the code says so ("a number cannot be taken back off a bank once it is on one"). A wrong
tap, or a fake "deposit" SMS from a personal number, can only be undone by clearing the app's
data. The taught list also travels in backups (`"extraBankNumbers"` is in `EXPORTED_PREFS`).
After this plan every hand-added number is listed and can be removed with the app's two-tap.

## Current state

Android — one flat package, `app/src/main/java/com/doxigo/muchtoman/`:

- `Data.kt` — `var extraBankNumbers: Map<String, List<String>>` in prefs: bank enum name → the
  senders she added. `Sms.kt:1129` `extraLookup(numbers)` turns it into `senderKey → Bank`.
- `AppVm.kt` `addBankNumber` — append-only:
  ```kotlin
  fun addBankNumber(bank: String, sender: String) {
      if (runCatching { Bank.valueOf(bank) }.getOrNull() == null || sender.isBlank()) return
      val cur = store.extraBankNumbers
      store.extraBankNumbers = cur + (bank to ((cur[bank] ?: emptyList()) + sender).distinct())
      restartScan { store.smsScannedTo = 0L }
      viewModelScope.launch { rewindLedger() }
  }
  ```
  No function removes an entry (`grep -n "extraBankNumbers = " AppVm.kt` → that one line).
- `Derived.kt` `parseToRows` resolves the bank **at derive time**:
  `parseBankSms(source.sender, source.body, at, extra) ?: return emptyList()`. So once a sender
  leaves `extra`, the next `derive` writes no rows for its stored messages — the messages stay
  in `sms_source` as evidence, the transactions leave the ledger, and the family copies are
  tombstoned by the normal sweep. The ledger half of removal is therefore "remove, derive,
  publish" — the sequence every edit in `AppVm` already ends in.
- The balance half is separate. Balances live in prefs (`store.bankAccounts`), folded by
  `applyBankSms` (`Sms.kt` ~L1136), and each `BankAccount` records `sender` — the sender of the
  message that last set it (`sender = sms.sender`). A counted message is never re-read
  (`seenSms`). Two existing ways to undo a balance:
  - `forgetBankAccount(key)` — "Drops an account that was read wrong. The messages behind it
    stay marked as seen on purpose … It starts again, from zero and unanchored, at the next
    message that bank sends."
  - `rescanSms()` — forgets **every** read balance and the whole seen-list. Its sibling comments
    warn this loses figures she anchored by hand, "the one figure no rescan can rebuild".
- `BanksUi.kt` `BankSheet(accounts, reads, disabled, onOpen, onAddNumber, loadSenders, onRescan, onDismiss)`
  — lists accounts, then «اضافه کردن شمارهٔ بانک از پیامک‌ها», the candidates, and an
  `ArmedButton` for «دوباره خوندن همه پیامک‌ها». Nothing lists the numbers already added. The
  candidate card's KDoc: "Adding is its own tap after that, because a number cannot be taken
  back off a bank once it is on one." Wired in `Ui.kt`: `onAddNumber = vm::addBankNumber`.
- `Ui.kt` `ArmedButton(label, armedLabel, onConfirmed, modifier, voice, enabled, block, key)` —
  "The app's two-tap, for every act that cannot be taken back".

PWA — `pwa/src/`:

- There is no inbox, so the twin of a taught sender is `senderBanks: Record<string, string>`
  (`model.ts:271`): a Shortcut's sender name the tables do not know → the bank she said it is.
  `pasteUi.tsx` `bankFor(sender)` reads it; `PasteSheet` writes it when she answers.
- `ShortcutSheet` (`pasteUi.tsx` ~L292) already lists them and forgets **all** at once, in one tap:
  ```tsx
  <p class="howto muted">اگه بانکی رو اشتباه گفتی، فراموششون کن تا دفعه‌ی بعد دوباره بپرسه.</p>
  <PillButton label="فراموش کن" block onClick={() => setPref('senderBanks', {})} />
  ```
- A stored message (`model.ts` `interface Source { id; bank; body; at; ingestedAt }`) keeps the
  **bank it was filed under, not its sender**. So forgetting an answer cannot find the messages
  that sender already filed; they stay under the wrong bank until deleted row by row.
- `ui.tsx:87` has the same `ArmedButton({ label, armedLabel, onConfirmed, voice, block, disabled })`.

Rules that bind this change (quoted from AGENTS.md / PRODUCT.md / DESIGN.md):
- "Every feature ships on both: `app/` and `pwa/`, in the same change … Anything else that
  cannot be ported gets said in the change, never silently dropped."
- "Every destructive action is two taps; TalkBack hears the armed state."
- "Persian copy is the interface: casual register, Persian digits, words carry warnings and
  colour only confirms." A caption earns its place only by saying what will happen; no cute or
  explanatory filler.
- "Never bump `SMS_SCHEMA`; never add a destructive migration to `durable.db`." This plan
  needs neither — if you find yourself reaching for one, STOP.

## Draft copy (needs the operator's approval — do not ship unapproved)

| Where | Draft |
|---|---|
| Android section label above the list | «شماره‌هایی که خودت اضافه کردی» |
| Row | the number (`bidi(...)`, Persian digits) and the bank's Persian name |
| Button | «حذف این شماره» |
| Armed line | «پیامک‌های این شماره دیگه خونده نمی‌شن و تراکنش‌هاش از دفتر می‌ره. دوباره بزن» |
| Android, when the bank's balance came from it | add to the armed line: «موجودی این بانک هم با پیامک بعدی دوباره خونده می‌شه.» |
| Candidate card caution (mobile-shaped senders only) | «این یه شمارهٔ موبایله، نه سرشمارهٔ بانک.» |
| PWA per-sender button | «فراموش کن» / armed: «دفعهٔ بعد دوباره می‌پرسه. دوباره بزن» |

## Commands you will need

| Purpose | Command | Expected |
|---|---|---|
| Env (once per shell) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| Android gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | BUILD SUCCESSFUL |
| PWA gate | `cd pwa && pnpm install --frozen-lockfile && pnpm run check` | exit 0 |
| PWA browser tests | `cd pwa && PLAYWRIGHT_CHROMIUM_EXECUTABLE="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" pnpm run test:browser` | all pass |

## Scope

**In scope**: `AppVm.kt`, `BanksUi.kt`, `Ui.kt` (wiring only), `Sms.kt` (one pure helper),
a new `app/src/test/java/com/doxigo/muchtoman/TaughtSenderTest.kt`; `pwa/src/pasteUi.tsx`,
`pwa/src/ledger.ts`, `pwa/src/model.ts` (Step 5 only), `pwa/test/` and `pwa/e2e/app.spec.ts`
for the matching tests.

**Out of scope**: `Data.kt`'s `SMS_SCHEMA`; any Room entity or migration; `rescanSms`,
`forgetBankAccount`, `applyBankSms` bodies (call them, do not change them); the built-in
sender lists in `Sms.kt`; `seenSms` handling.

## Git workflow

Commit on the worktree's current branch, one commit for the plan. Subject in English only,
lower-case area prefix then a plain description (log example:
`ledger: let a hand-entered transaction name any bank account, app and PWA`). Suggested:
`banks: let a hand-added sender be removed, app and PWA`. No `Co-Authored-By` or other
trailer; no "she/her" in commit messages; do not push or merge.

## Steps

### Step 1: operator sign-off (blocking)

Get an explicit yes (or replacement text) for every row of "Draft copy", and a yes/no on
Step 5. **Verify**: the answers are quoted in your report.

### Step 2: Android — the pure half, test first

In `Sms.kt` add a pure function next to `extraLookup`, and test it in `TaughtSenderTest.kt`
(plain JUnit, modelled on the sender tests in `MoneyTest.kt`):

```kotlin
/** [numbers] without [sender] on [bank]; a bank left with none drops out of the map. */
fun withoutBankNumber(numbers: Map<String, List<String>>, bank: String, sender: String): Map<String, List<String>>
```

Compare by `senderKey`, so «+98 912…» removes «0912…». Cases: removes one of two; removes the
last and drops the bank key; an unknown sender or bank returns the map unchanged; after
removal `extraLookup(result)` no longer contains `senderKey(sender)`.

**Verify**: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.TaughtSenderTest'` → passes.

### Step 3: Android — `AppVm.removeBankNumber(bank, sender)`

1. `store.extraBankNumbers = withoutBankNumber(…)`.
2. Balances: inside one `restartScan { … }`, drop every non-`manual` account whose `bank` is
   this bank **and** whose `sender` has the removed sender's `senderKey` — the same thing
   `forgetBankAccount` does for one key (update `_state`, `recordSnapshot()`). Messages stay
   seen; the account restarts at that bank's next real message. Do **not** call `rescanSms()`.
3. Ledger: `derive(durable, derived, extraLookup(store.extraBankNumbers))`, `publishLedger`,
   `requestFamilySync(silent = true)` — copy the shape of an existing edit action in `AppVm`
   (e.g. the block ending `deleteTxn failed`), including its `runCatching`/logging.

**Verify**: `./gradlew compileFullDebugKotlin` → BUILD SUCCESSFUL.

### Step 4: Android — the list and the two-tap

`BankSheet` gains the added numbers (a `List<Pair<Bank, String>>` parameter, read from the
store when the sheet opens) and `onRemoveNumber`. Render them under the add pill as rows with
an `ArmedButton` each (`key` = the sender, so a recycled row never wakes armed), using the
approved copy. Add the approved caution to `SenderCandidateCard` when
`senderKey(candidate.sender)` is ten digits starting with `9`. Rewrite the KDoc sentence that
says a number cannot be taken back. Wire `onRemoveNumber = vm::removeBankNumber` in `Ui.kt`.

**Verify**: Android gate → BUILD SUCCESSFUL.

### Step 5: PWA — forget one sender (and, if approved, its messages)

- Always: replace the forget-all pill with an `ArmedButton` per listed sender that deletes
  only that key from `senderBanks`.
- Only with the operator's yes: add `sender?: string` to `Source` (optional, so existing rows
  and restored backups stay valid), set it in `addPastedBundle` (`ledger.ts`), and on forget
  erase the `sources` rows carrying that sender in the same `batch`. Rows filed before this
  change carry no sender and stay; the armed line must then say so in approved words.
- With a no: say in the commit body that the PWA forgets the answer but cannot find the
  messages already filed, because a stored message keeps its bank and not its sender.

Extend the e2e test «a sender no table knows is asked once, then filed like the rest»
(`pwa/e2e/app.spec.ts` ~L326) with: forget that sender → the same hand-off asks again.

**Verify**: PWA gate and browser tests pass.

## Done criteria

- [ ] `grep -n "fun removeBankNumber" app/src/main/java/com/doxigo/muchtoman/AppVm.kt` → one line.
- [ ] `grep -n "cannot be taken back off a bank" app/src/main/java/com/doxigo/muchtoman/BanksUi.kt` → no output.
- [ ] `grep -n "rescanSms()" app/src/main/java/com/doxigo/muchtoman/AppVm.kt` shows no call inside `removeBankNumber`.
- [ ] `TaughtSenderTest` passes; Android gate and PWA gate exit 0; the extended e2e case passes.
- [ ] `git diff` touches no Room entity, no migration, and not `SMS_SCHEMA`.
- [ ] Every new Persian string is one the operator approved in Step 1.

## STOP conditions

- **Step 1 has no answer** — stop; do not ship draft copy.
- Removal appears to need `seenSms` edited, a re-fold of other banks, or `rescanSms()`.
- A test shows another bank's balance, or a `manual` account, moving when a sender is removed.
- The PWA half would need an IndexedDB version bump or a change to the backup format.

## Maintenance notes

- Rejected on purpose: re-folding the bank's balance from the inbox after removal. It needs
  the seen-list cleared, and whether older messages are then double-counted rests on
  `applyBankSms` refusing older stamps — an invariant with known soft spots (the seen-list
  keeps only the newest 2,000 keys). Forgetting the account is the existing, honest path.
- A restored backup brings its `extraBankNumbers` with it; the new list is where she can see
  and prune them.
- Accounts fed only by taught senders could carry the existing `inferred` mark; deferred until
  someone asks.
