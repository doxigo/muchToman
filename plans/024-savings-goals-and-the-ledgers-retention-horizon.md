# Plan 024: Savings goals stop losing months to the ledger's retention horizon

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- app/src/main/java/com/doxigo/muchtoman/Goals.kt app/src/main/java/com/doxigo/muchtoman/Ledger.kt app/src/main/java/com/doxigo/muchtoman/BudgetUi.kt pwa/src/goals.ts`
> If any of these changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S for step 1 (tests only); M–L for the fix, depending on the option chosen
- **Risk**: MED (any fix changes or re-labels a figure people watch; option B adds a `durable.db` migration)
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

**Open question — the operator must choose before step 2: option A, B or C below. Step 1 can
run without the answer; nothing after it can.**

A savings goal's progress is computed live: income minus spending over every ledger row since
the day the goal started. But the Android app deletes stored bank messages older than about
fifteen months (13 Jalali months plus 62 days of grace) on every ingest, and the ledger is
rebuilt from those messages. So a goal with no deadline («بی‌مهلت»), or one kept or extended past
fifteen months, quietly loses its earliest month every month: 50 million Toman saved in month 1
is gone from the bar in month 16, while the card still says «از ۱ مرداد ۱۴۰۵». The app already
solved this exact problem for instalments by copying each payment's amount onto its link
"because the ledger forgets"; goals have no equivalent. The PWA never prunes, so the same shared
goal can read differently on a household's Android phone and iPhone, against the repo rule that
both show the same numbers.

## Current state

- `app/src/main/java/com/doxigo/muchtoman/Goals.kt:265-277` — progress is a live sum from `startsOn`:

  ```kotlin
  fun goalProgress(goal: Goal, entries: List<LedgerEntry>, today: Long, mineId: String = "", ownerName: String = ""): GoalProgress {
      val net = scopedTo(spendable(entries), mineId, goal.shared)
          .filter { it.txn.day >= goal.startsOn }
          .sumOf { it.txn.signedRial ?: 0L }
      val current = net.coerceAtLeast(0L)
  ```

  (Signature shown on one line; in the file each parameter is on its own line with a doc comment.)
  `Goals.kt:192-198` offers `GoalHorizon.OPEN(null, "بی‌مهلت")` — a goal with no end.
- `pwa/src/goals.ts:92-101` — the same function, mirrored:

  ```ts
  const net = scopedTo(spendable(entries), mineId, goal.shared)
    .filter((e) => e.txn.day >= goal.startsOn)
    .reduce((sum, e) => sum + (e.txn.signedRial ?? 0), 0);
  ```

- `app/src/main/java/com/doxigo/muchtoman/Ledger.kt:667-694` — retention:

  ```kotlin
  const val SOURCE_HORIZON_MONTHS = 13
  const val SOURCE_GRACE_DAYS = 62L
  fun sourceHorizon(now: Long): Long =
      tehranDayStart(jalaliMonthsBack(tehranDay(now), SOURCE_HORIZON_MONTHS))
  /** Below this, a stored message is past the horizon plus its grace and can go. */
  fun sourcePruneFloor(now: Long): Long = sourceHorizon(now) - SOURCE_GRACE_DAYS * DAY_MS
  ```

  and `Ledger.kt:858-862`, inside `ingestBankSms`, on every ingest:

  ```kotlin
  if (!brokenClock) {
      db.withTransaction {
          db.smsSource().deleteBefore(sourcePruneFloor(now))
          db.smsSource().trimToNewest(SOURCE_HARD_CAP)
      }
  }
  ```

- `app/src/main/java/com/doxigo/muchtoman/Derived.kt:519,543` — `derive` rebuilds every
  transaction from `durable.smsSource().allOldestFirst()` plus `durable.manual().all()`. Pruned
  messages therefore leave the ledger; hand-typed rows are never pruned.
- `app/src/main/java/com/doxigo/muchtoman/Sync.kt:1238-1262` — a shared transaction that is no
  longer in the owner's ledger is tombstoned for the household on the next sync, so an Android
  member's prune also removes those rows from every other member's phone, the PWA included.
- `app/src/main/java/com/doxigo/muchtoman/Installments.kt:69-72` — the precedent:

  ```kotlin
   * The amount is copied onto the link when she makes it, and progress is summed from these rather
   * than from the transactions, because the ledger forgets: sources older than
   * [SOURCE_HORIZON_MONTHS] are pruned, and a two-year loan counted off live rows would start reading
   * as overdue in its fourteenth month, for payments the app itself watched her make.
  ```

- The PWA has no retention at all: `grep -rn "HORIZON\|deleteBefore\|trimToNewest" pwa/src`
  finds nothing but comments that mention the phone's.
- The card states the start date as fact — `BudgetUi.kt:802-807` and `pwa/src/goals.ts:205-209`:

  ```kotlin
  /** «از ۱ مرداد ۱۴۰۵», and the deadline where there is one. */
  private fun goalWindowFa(progress: GoalProgress): String {
      val from = "از ${faDate(progress.goal.startsOn)}"
  ```

- Schema facts for option B: `DURABLE_DB_VERSION = 11` (`Ledger.kt:40`); the `goal` table is the
  `Goal` entity at `Goals.kt:63`; migrations are hand-written objects in `Ledger.kt` (the newest
  is `MIGRATION_10_11`, ~line 270) and `PersistenceTest` opens every old schema through them. A
  goal travels to the household as the explicit `SyncGoalPayload` (`Sync.kt:230`), so a new
  column on the entity does not reach the wire by itself.
- Tests today: `app/src/test/java/com/doxigo/muchtoman/GoalsTest.kt` and `pwa/test/goals.test.ts`
  (its first line: "GoalsTest.kt, literally") share helpers `entry(day, signedRial)` and
  `goal(target, endsOn)`. Neither has a case about retention.

Repo rules that bind this plan (from `AGENTS.md`): money is `Long` Rial end to end; every
user-facing change ships in `app/` and `pwa/` in the same change; never add a destructive
migration to `durable.db` — hand-written, tested migrations only; never bump `SMS_SCHEMA`;
Persian copy is casual, plain, with Persian digits.

## Commands you will need

| Purpose | Command (from repo root) | Expected on success |
|---|---|---|
| Android env | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| One Android test class | `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.GoalsTest'` | BUILD SUCCESSFUL |
| Android gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | BUILD SUCCESSFUL |
| PWA one file | `cd pwa && pnpm install --frozen-lockfile && pnpm exec vitest run test/goals.test.ts` | all pass |
| PWA gate | `cd pwa && pnpm run check` | exit 0 |

## Scope

**In scope for step 1** (tests only):
- `app/src/test/java/com/doxigo/muchtoman/GoalsTest.kt`
- `pwa/test/goals.test.ts`

**In scope after the operator chooses** — named per option in step 2.

**Out of scope** (do NOT touch):
- `SOURCE_HORIZON_MONTHS`, `SOURCE_GRACE_DAYS`, `deleteBefore`, `trimToNewest` — retention itself stays.
- `SMS_SCHEMA`, and any existing migration object.
- Caps and budgets (`Budget.kt`, `pwa/src/budget.ts`): their windows are a week or a month and
  never reach the horizon.
- How two goals share one net (the `ponytail:` note at `Goals.kt:257`) — a different question.

## Git workflow

- Commit on the worktree's current branch; one commit for step 1, one for the chosen fix.
- Subject in English only, lower-case area prefix, plain description. Example from the log:
  `ledger: let a hand-entered transaction name any bank account, app and PWA`.
- No `Co-Authored-By` or any other trailer. Do not describe users as "she/her" in the message.
- Do not push, merge, or touch `main`.

## Steps

### Step 1: pin today's behaviour on both sides (no decision needed)

Add one test to each file, same name, same numbers.

- `GoalsTest.kt` — `a goal older than the ledger's memory counts only what the ledger still holds`:
  pick `now = tehranDayStart(first) + 12 * 3600_000L`; `floorDay = tehranDay(sourcePruneFloor(now))`;
  a goal with `startsOn = floorDay - 60` and no deadline; three rows: `entry(floorDay - 30, 500_000_000)`
  (income before the floor), `entry(floorDay + 10, 200_000_000)`, `entry(floorDay + 20, -50_000_000)`.
  Assert, with all three rows, `currentRial == 650_000_000`; then with the rows a derive would
  still produce (`rows.filter { it.txn.at >= sourcePruneFloor(now) }`), `currentRial == 150_000_000`.
  Put a comment above it: this test documents a known defect (plan 024) and is expected to be
  **rewritten**, not deleted, when the fix lands.
- `pwa/test/goals.test.ts` — the same three rows relative to a `floorDay` constant chosen in the
  test (the PWA has no floor function): assert `650_000_000` with all rows, and add the comment
  that the browser never prunes, so this is the figure it keeps showing while the phone's drops.

**Verify**: the two single-file commands above → pass. Then **STOP and report** unless the
operator's choice is recorded in this plan's row in `plans/README.md`.

### Step 2: the fix — one of three options (operator's decision)

**Option A — say the truth about the date (S).** Keep the sum as it is; make the card say where
counting really starts. Add `fun countedFrom(goal: Goal, now: Long): Long = maxOf(goal.startsOn, tehranDay(sourcePruneFloor(now)))`
in `Goals.kt`, pass the result to `goalWindowFa` (`BudgetUi.kt`) and print it instead of
`startsOn`. The PWA has no floor, so `pwa/src/goals.ts` gains the same optional parameter and
its callers pass nothing. *Consequence*: the label stops lying, but an old open goal's figure
still shrinks every month on Android, and still differs from the PWA's.

**Option B — carry the past forward (M–L, recommended).** Give each savings goal a stored
carried-forward net, local to each device:
- Two columns on `goal`: `carried_rial INTEGER NOT NULL DEFAULT 0` and
  `carried_through INTEGER NOT NULL DEFAULT 0` (a Tehran day; 0 = nothing carried). A hand-written
  `MIGRATION_11_12` (two `ALTER TABLE goal ADD COLUMN …`), `DURABLE_DB_VERSION = 12`, and a
  `PersistenceTest` case opening schema 11 through it. Not added to `SyncGoalPayload`.
- Whenever the ledger is published, for every live savings goal whose `startsOn` is before the
  horizon day (`tehranDay(sourceHorizon(now))`): add to `carried_rial` the goal's scoped net over
  rows with `max(startsOn, carried_through) <= day < horizonDay`, and set `carried_through = horizonDay`.
  The 62 days of grace between the horizon and the prune floor are what make this safe: the
  rows being summed still exist when the checkpoint moves.
- `goalProgress` returns `carried_rial + net over rows with day >= max(startsOn, carried_through)`.
- The PWA mirrors the two fields on its `goals` rows and the same two functions (its own rows
  are never pruned, but a household's Android rows are tombstoned out from under it). The two
  fields stay out of the synced goal record on both platforms, and stay in a backup.
- *Consequence*: figures stop shrinking from the day it ships. Months already pruned before it
  ships are not recovered. A phone that joins a household later carries less than one that was
  there, so two members can still differ on a very old shared goal.

**Option C — give the PWA the phone's retention (M).** Prune `sources` in the browser at the
same floor. *Consequence*: the two platforms agree, by making the PWA lose the same months —
and a pasted message exists nowhere else, unlike an SMS still sitting in a phone's inbox.

**Recommendation: B.** The code already decided this once, for instalments, in the comment
quoted above, and the grace window exists; A leaves the number wrong and only relabels it; C
deletes the only copy of data to make two wrong numbers match. If a migration is not wanted
now, do A as a stopgap and leave this plan open for B.

**Verify (any option)**: both gates in the command table pass; the step-1 tests are rewritten
to assert the new behaviour (for B: the three-row case reads `650_000_000` before and after the
old rows are dropped, once the checkpoint has run).

## Test plan

- Step 1: the two characterization tests above.
- Option A: `countedFrom` cases (start after the floor → start; start before → floor day), and
  the card-words tests in both files updated.
- Option B: a migration test in `PersistenceTest` (model after the newest existing migration
  case there); checkpoint tests in `GoalsTest.kt` and `pwa/test/goals.test.ts` with identical
  numbers — carry advances once, is idempotent on a second publish, ignores rows before
  `startsOn`, respects `scopedTo` for a shared and a private goal; and one `ExportTest` /
  `pwa/test/backup.test.ts` assertion that the carried figure survives a backup and restore.
- Option C: a prune test in `pwa/test/derived.test.ts` and a backup round-trip.

## Done criteria

- [ ] Step 1's two tests exist, pass, and carry the comment naming this plan
- [ ] The operator's choice is recorded in `plans/README.md`
- [ ] For the chosen option: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` and
      `cd pwa && pnpm run check` both succeed
- [ ] Option B only: `grep -n "DURABLE_DB_VERSION = 12" app/src/main/java/com/doxigo/muchtoman/Ledger.kt`
      matches, and `PersistenceTest` has a schema-11 case
- [ ] `git status --short` lists only the files the chosen option names

## STOP conditions

- **No option has been chosen by the operator** — after step 1, stop. Do not pick one.
- Step 1's Android test does not show `150_000_000` for the surviving rows: the mechanism this
  plan describes is not what the code does; report what you saw.
- Option B: the migration needs anything other than two additive columns, or `PersistenceTest`
  fails on any older schema.
- Option B: the checkpoint cannot be computed at publish time without re-reading the whole
  ledger a second time — report the cost instead of adding a second derive.
- Any step seems to need a change to retention constants or to `SMS_SCHEMA`.

## Maintenance notes

- Under option B, a parser fix that changes an old row's amount no longer reaches what was
  already carried — the same ceiling `InstallmentLink` names. Say so in a `ponytail:` comment.
- Under any option, changing `SOURCE_HORIZON_MONTHS` or `SOURCE_GRACE_DAYS` now interacts with
  goals; the tests from this plan are what will say so.
- The PWA's unbounded store (it never prunes, and the iOS Shortcut files every bank message) is
  a separate performance question; see plans 018 and 027.
