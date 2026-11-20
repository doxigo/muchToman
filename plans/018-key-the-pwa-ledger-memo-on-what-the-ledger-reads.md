# Plan 018: Key the PWA's ledger memo on what the ledger reads

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- pwa/src/state.ts pwa/src/derived.ts pwa/src/reports.ts`
> If plan 017 ran before this one in the same worktree, `state.ts` will show
> its `unsaved()` change — that is expected. Anything else that differs from
> the "Current state" excerpts is a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (a missed input would show a stale ledger; the test in step 3 exists to prevent exactly that)
- **Depends on**: none (runs after plan 017 in the same worktree; both touch `state.ts` in different places)
- **Category**: perf
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

`ledger()` is the PWA's whole derive: it re-parses every stored bank message, re-links and
re-classifies every transaction. It is memoised on one global counter that every write bumps —
including writes that cannot change the ledger: a price refresh, the day's history point, a
holdings edit, a notification mark, even locking and unlocking the app (which runs an empty
batch only to re-render). Each of those throws the ledger away, and every screen memo keyed on
the ledger's identity (`home.tsx`, `budgetUi.tsx`, `report.tsx`) recomputes with it. Cost grows
with the size of the ledger, and since the iOS Shortcut now files every bank message
automatically, that is thousands of rows within a year. After this plan the ledger is rebuilt
only when one of its own inputs changes. No number on any screen changes.

This is internal to the browser's store; there is no `app/` twin (the phone keeps its derived
ledger in a database). Say that in the commit body.

## Current state

- `pwa/src/state.ts` — the in-memory store. One counter, bumped by every write:

  ```ts
  // state.ts:10-11 (header comment)
   * ponytail: one version for everything, so any write re-renders every mounted screen. Per-table
   * versions are the upgrade if a profile ever shows it.
  // state.ts:21
  let version = 0;
  // state.ts:35-38
  function changed(external = false): void {
    version++;
    for (const l of listeners) l(external, syncWriting > 0);
  }
  // state.ts:48
  export const dataVersion = (): number => version;
  ```

  Rows change in exactly four places in this file: `putAll` (~line 149, every `put` goes through
  it), `erase` (~line 158), `readDisk` (~line 208, used by `load` and by another tab's reload —
  it clears and refills every table) and `restore` (~line 263, replaces every table).
- `pwa/src/derived.ts:564-587` — the memo and everything the ledger reads from the store:

  ```ts
  let memo: { key: string; view: LedgerView } | null = null;

  /** The ledger as the store holds it now, derived once per data version and shared by every reader. */
  export function ledger(): LedgerView {
    const now = Date.now();
    const key = `${dataVersion()}:${sessionVersion}:${tehranDay(now)}`;
    if (memo?.key === key) return memo.view;
    const view = ledgerView({
      sources: rows('sources'),
      manual: rows('manual'),
      familyTxns: rows('familyTxns'),
      familyMembers: rows('familyMembers'),
      decisions: rows('decisions'),
      links: rows('links'),
      categories: rows('categories'),
      rules: rows('rules'),
      anchors: rows('anchors'),
      ...session,
      disabledBanks: pref('disabledBanks'),
      now,
    }, pref('ledgerStartsOn'));
    memo = { key, view };
    return view;
  }
  ```

  So the inputs are **nine tables** — `sources`, `manual`, `familyTxns`, `familyMembers`,
  `decisions`, `links`, `categories`, `rules`, `anchors` — **two prefs** — `disabledBanks`,
  `ledgerStartsOn` — the household `session` (already tracked by `sessionVersion`) and the
  Tehran day. `grep -n "rows(\|pref(" pwa/src/derived.ts` shows no other store read in the file.
  The tables `goals`, `familyAssets` and `publications` are **not** inputs.
- Writes that do not touch any of those and still invalidate today: `pwa/src/lock.tsx:76-79`
  (`batch(() => {})` on every lock and unlock), `pwa/src/data.ts:796` (`setPref('rates', …)`) and
  `pwa/src/data.ts:634-635` (`setPref('history', …)`, `setPref('rateHistory', …)`), which also
  call `ledger()` in between through `bankToman` (`pwa/src/main.tsx:99`).
- `pwa/src/reports.ts:65-70` and `:945` — a helper that exists because a spread has an argument
  ceiling, and one caller that still spreads:

  ```ts
  /** The smallest of [values] mapped, or null — a loop, since a spread has an argument ceiling. */
  function minOf<T>(values: T[], of: (v: T) => number): number | null {
  // …
    const first = entries.length ? Math.min(...entries.map((e) => e.txn.day)) : today;
  ```

- `pwa/test/plans.test.ts:45-47,126-130` reads `state.dataVersion()` to assert that an unchanged
  edit writes nothing. `dataVersion` must keep its meaning (bumped on every change).

Conventions: TypeScript, no linter — match the file's hand style. Comments explain *why*. A
deliberate simplification is marked with a `ponytail:` comment, as the existing header does.

## Commands you will need

| Purpose | Command (from repo root) | Expected on success |
|---|---|---|
| Install | `cd pwa && pnpm install --frozen-lockfile` | exit 0 |
| One test file | `cd pwa && pnpm exec vitest run test/ledgerMemo.test.ts` | all pass |
| Full check | `cd pwa && pnpm run check` | typecheck, vitest and build all exit 0 |
| Browser tests | `cd pwa && PLAYWRIGHT_CHROMIUM_EXECUTABLE="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" pnpm run test:browser` | all pass |

## Scope

**In scope**:
- `pwa/src/state.ts`
- `pwa/src/derived.ts` (only the memo block quoted above)
- `pwa/src/reports.ts` (only line ~945)
- `pwa/src/home.tsx`, `pwa/src/budgetUi.tsx`, `pwa/src/timeline.tsx` — only the dependency arrays of the
  three `readPlans` memos named in step 4b, plus the `dataVersion` import
- `pwa/test/ledgerMemo.test.ts` (create)

**Out of scope** (do NOT touch):
- `ledgerView` and all code above line 550 of `derived.ts` — the derive itself must not change
  (the one header-comment sentence named in step 2 is the only edit up there).
- `pwa/src/lock.tsx` — its empty batch still re-renders the app, which is its job; after this
  plan it no longer costs a derive. Plan 019 owns that file.
- `useData()` and `dataVersion()` semantics — screens re-render on the global version as before.
- Every other screen memo — those that only read the `view` they are keyed on (and tables the
  ledger itself reads) hold correctly once the ledger's identity is stable. Only the three in
  step 4b read other tables.
- Per-pref version counters, caching `parseToRows` per message — not needed for this fix.

## Git workflow

- Commit on the worktree's current branch; one commit for this plan.
- Subject in English only, lower-case area prefix, plain description. Example from the log:
  `ledger: let a hand-entered transaction name any bank account, app and PWA`.
  Suggested: `pwa: rebuild the ledger only when one of its own tables or prefs changes`.
- No `Co-Authored-By` or any other trailer. Do not describe users as "she/her" in the message.
- Do not push, merge, or touch `main`.

## Steps

### Step 1: a counter per table (`pwa/src/state.ts`)

- Beside `let version = 0;` add:

  ```ts
  /** One counter a table, for memos that read only some of them — the ledger above all. */
  const tableVersions = Object.fromEntries(TABLES.map((t) => [t, 0])) as Record<TableName, number>;
  export const tableVersion = (table: TableName): number => tableVersions[table];
  ```

- Bump the table's counter in `putAll` (once per call, after the early return for an empty list)
  and in `erase` (after the early return for a missing id).
- Bump **every** table's counter at the end of `readDisk` and in `restore` where memory is
  replaced (`for (const t of TABLES) tableVersions[t]++;`).
- Update the header's `ponytail:` note so it stays true: the global version still re-renders
  every mounted screen; per-table counters exist for the ledger's memo.

**Verify**: `cd pwa && pnpm run typecheck` → exit 0.

### Step 2: key the memo on the inputs (`pwa/src/derived.ts`)

- Import `tableVersion` instead of `dataVersion` from `./state`.
- Add the list next to the memo and build the key from it. The two prefs are small (a short
  string list and a number), so their values go into the key directly:

  ```ts
  /** Every table `ledger()` hands to `ledgerView`. A table added there and not here is a ledger that goes stale. */
  export const LEDGER_TABLES = ['sources', 'manual', 'familyTxns', 'familyMembers', 'decisions', 'links', 'categories', 'rules', 'anchors'] as const;
  // …inside ledger():
  const key = [...LEDGER_TABLES.map(tableVersion), JSON.stringify(pref('disabledBanks')), pref('ledgerStartsOn'), sessionVersion, tehranDay(now)].join(':');
  ```

- Reword the doc comment on `ledger()`: derived once per change to its own inputs.
- The file header (lines 11-12) says `ponytail: full re-derive on every data version.` Reword that
  one sentence to say the derive runs again when one of its inputs changes; keep the rest of the note.
- Leave the `ledgerView({...})` call exactly as it is.

**Verify**: `cd pwa && pnpm run check` → exit 0 (all existing tests still pass — in particular
`test/derived.test.ts` and `test/plans.test.ts`).

### Step 3: the test (`pwa/test/ledgerMemo.test.ts`)

Create it; see "Test plan". **Verify**: `cd pwa && pnpm exec vitest run test/ledgerMemo.test.ts` → all pass.

### Step 4: the spread (`pwa/src/reports.ts:945`)

Replace the line with the file's own helper:

```ts
const first = minOf(entries, (e) => e.txn.day) ?? today;
```

**Verify**: `cd pwa && pnpm exec vitest run test/reports.test.ts` → all pass;
`grep -n "Math.min(\.\.\.entries" pwa/src/reports.ts` → no output.

### Step 4b: three memos that read past the ledger

**Revised 2026-11-20 after a first execution stopped here.** Until now the ledger object changed
identity on every write, so a memo keyed on `[view]` was really keyed on "anything changed".
Three memos relied on that: they call `readPlans(…)`, which reads `goals` and the tables behind
`stored(now)` in `pwa/src/plans.ts` — tables the ledger does not read. With a stable ledger they
go stale (the browser tests "a budget counts this month against its cap" and "a bill has no
count to ask and says this month's on its card" fail: the new card never appears).

Give exactly these three their old trigger back by adding the global version to the
dependency array (`dataVersion` is exported from `./state`; add it to that file's existing
import from `'./state'`):

- `pwa/src/home.tsx` ~line 301:
  `const plans = useMemo(() => readPlans(view.allEntries, view.entries), [view, dataVersion()]);`
- `pwa/src/budgetUi.tsx` ~line 66: the same line, the same change.
- `pwa/src/timeline.tsx` ~line 842, the `paidPlan` memo: `[payable, view, txn.ref]` →
  `[payable, view, txn.ref, dataVersion()]`.

Above the first one, one comment in the file's own register saying why: the plans are read
from tables the ledger does not read, so the ledger's identity alone no longer says they moved.

Do not touch any other memo. `home.tsx`'s `story` and `due` memos already depend on `plans`.

**Verify**:

```bash
cd pwa && grep -n "readPlans(" src/*.tsx
```

→ every hit inside a `useMemo` has `dataVersion()` in its dependency array (three hits in
memos); then the browser tests → all pass, including the two named above.

### Step 5: full gate

**Verify**: `cd pwa && pnpm run check` → exit 0, and the browser tests → all pass.

## Test plan

`pwa/test/ledgerMemo.test.ts`, modelled on the `describe('ledger actions', …)` block at
`pwa/test/derived.test.ts:521-532` (same `beforeEach`: `vi.resetModules()`, a fresh
`IDBFactory`, dynamic imports of `../src/state`, `../src/derived`, `../src/ledger`, then
`(await import('../src/rules')).seedBuiltins(1)`). Cases:

1. *writes the ledger does not read leave it alone*: take `const before = derived.ledger()`;
   then `state.setPref('themeMode', 'DARK')`, `state.setPref('lastBackupAt', 5)`,
   `state.batch(() => {})`, and one write to the `goals` table (copy a goal literal from
   `pwa/test/plans.test.ts`, e.g. its `cap(...)` helper's shape); after each,
   `expect(derived.ledger()).toBe(before)`.
2. *each kind of input rebuilds it*: after each of the following, expect `derived.ledger()` **not**
   `toBe` the previous view: re-`put` an existing category (`state.put('categories', state.rows('categories')[0])`);
   re-`put` an existing rule; add a hand-typed transaction with
   `ledger.addManualTxn(-3_000_000, 'cat_health', 'داروخانه', '', Date.now())` (the call
   `test/derived.test.ts:643` uses); `state.setPref('ledgerStartsOn', 1)`;
   `state.setPref('disabledBanks', ['SAMAN'] as never)`; `derived.setMineId('abcdef0123456789')`.
3. *every table's counter moves on a write*: for each name in `TABLES` (from `../src/model`),
   read `state.tableVersion(t)`, `state.put(t, { id: 'x' } as never)`, expect the counter to
   have increased; then `state.erase(t, 'x')` and expect it to increase again. (Do not call
   `ledger()` in this case — the rows are not real.)
4. *the list is complete*: read `src/derived.ts` as text (`readFileSync`, as
   `pwa/test/nav.test.ts` reads a file), collect every `rows('…')` name with a regex, and expect
   the set to equal `new Set(derived.LEDGER_TABLES)`. This is the check that fails when someone
   adds an input to `ledgerView` and forgets the key.

## Done criteria

- [ ] `cd pwa && pnpm run check` exits 0
- [ ] `pwa/test/ledgerMemo.test.ts` exists with the four cases and passes
- [ ] `grep -n "dataVersion" pwa/src/derived.ts` returns nothing
- [ ] `grep -n "Math.min(\.\.\.entries" pwa/src/reports.ts` returns nothing
- [ ] Browser tests pass
- [ ] `git status --short` lists only in-scope files (plus plan 017's, if it ran first in this worktree)

## STOP conditions

- The `ledger()` block in `derived.ts` reads a table or pref not in the list above (the code
  drifted) — report the new input instead of guessing whether it belongs in the key.
- Any existing test fails after step 2 and the cause is a screen showing a stale ledger: that
  means some code changes a ledger input without going through `putAll`/`erase`/`setPref`/
  `readDisk`/`restore`. Report where; do not add a manual invalidation call.
- You find a place that mutates a row object returned by `rows()` in place.
- A browser test still fails after step 4b: report which memo or cache reads a table outside
  the ledger's inputs — do not widen the ledger's key to make it pass.

## Maintenance notes

- Adding a table or pref to what `ledgerView` receives means adding it to `LEDGER_TABLES` or to
  the key; test case 4 catches a forgotten table, nothing catches a forgotten pref — reviewers
  should look for new `pref(` calls in `ledger()`.
- `health.derivedAt` and the "quiet account" flags are computed from `now` at derive time; the
  ledger is now rebuilt less often within a day, exactly as it already was in an idle app. The
  Tehran day stays in the key, so a new day still rebuilds.
- Deferred: caching `parseToRows` per source id (the existing comment at `derived.ts:5-12`
  suggests it) and windowing the دفتر list (plan 027).
