# Plan 027: The PWA's دفتر list stops costing the whole ledger on every tap

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- pwa/src/timeline.tsx pwa/src/timeline.css pwa/src/app.tsx`
> If any of these changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S for steps 1–2; M if step 4 turns out to be needed
- **Risk**: MED (step 2 changes how off-screen rows are laid out, which the saved scroll positions depend on)
- **Depends on**: plan 018 (without it the ledger object changes on every write and step 1 saves nothing)
- **Category**: perf
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

**Open question — the operator must check step 2 on a real iPhone before it ships (the
checklist is in step 3). Nothing in CI runs on iOS Safari, and the two things step 2 can break —
coming back to the same place in the list, and landing on the newest row after a search — only
show there.**

The دفتر tab renders every transaction in the ledger as DOM, and every visited tab stays mounted
while hidden. So opening a sheet, a notice appearing or timing out, a tab switch or one
keystroke in the search field re-builds and re-diffs the whole list. The phone uses a lazy list
for the same screen. With the iOS Shortcut now filing every bank message, and a household's
Android members syncing theirs, that is thousands of rows within a year. This plan removes that
cost in the cheapest order: first stop re-diffing a list that did not change, then let the
browser skip laying out days that are off screen, and only if a measurement still says so,
render the list incrementally.

The phone already has this (`LazyColumn` in `Timeline.kt`), so there is no `app/` change. Say
that in the commit body.

## Current state

- `pwa/src/timeline.tsx:340-354` — the rows are already memoised as data:

  ```tsx
  const everything = useMemo(() => view.entries.filter((e) => !e.duplicate), [view.entries]);
  // …
  const visible = useMemo(() => ledgerRows(everything, lens, filter, query, memberId, accountId),
    [everything, lens, filter, query, memberId, accountId]);
  const grouped = useMemo(() => {
    const days = new Map<number, LedgerEntry[]>();
    for (const e of visible) { /* group by e.txn.day */ }
    return [...days].sort((a, b) => b[0] - a[0]);
  }, [visible]);
  ```

- `pwa/src/timeline.tsx:416-427` — but the elements are rebuilt on every render of the screen,
  each row with a fresh `onClick` closure:

  ```tsx
  <div class="ledger-list">
    {grouped.map(([day, entries]) => (
      <section key={day}>
        <DayHeading day={day} today={today} rows={entries} />
        {/* Ref and category: the parts of a split row share the ref. */}
        {entries.map((e) => <TimelineRow key={`${e.txn.ref}:${e.categoryId}`} entry={e} onClick={() => openTxn(e.txn.ref)} />)}
      </section>
    ))}
    {/* Where the list stops because she chose where it starts — … */}
    {view.health.startsOn > 0 && <p class="ledger-aside">{setAsideFa(view.health.startsOn)}</p>}
  </div>
  ```

  `TimelineRow` (`timeline.tsx:535-575`) is a plain function component. In a household each row
  also renders `MemberFace` (`:518-527`), which builds an `<img src="data:image/jpeg;base64,…">`
  from the owner's avatar string on every render.
- `pwa/src/timeline.tsx:357-363` — after a real narrowing the screen jumps to the newest row:

  ```tsx
  const narrowed = useRef(false);
  useEffect(() => {
    if (!narrowed.current) narrowed.current = true;
    else if (visible.length) scrollTo(0, 0);
  }, [lens, filter, query, memberId, accountId]);
  ```

- `pwa/src/app.tsx:62-77` — `useScrollPerView` saves `scrollY` per tab and per page and calls
  `scrollTo(0, saved)` in a layout effect when the view changes. `pwa/src/timeline.tsx:260-267`
  (`usePageTop`) restores the list's offset when a page closes. Both assume the list is as tall
  when it comes back as it was when it was left.
- `pwa/src/app.tsx:79-108` — `App` calls `useData()` and `useNav()`, so it re-renders on every
  store change and every navigation change; every visited tab is rendered inside a
  `<div hidden={…}>`. Each screen also subscribes itself (`useLedger()` → `useData()`), so a
  wrapper that skipped hidden tabs in `App` would not stop them re-rendering — that is why this
  plan does not touch `app.tsx`.
- `pwa/src/timeline.css` has no rule for `.ledger-list` or its `section`s. Nothing in `pwa/src`
  uses `content-visibility` today.
- Preact skips diffing a subtree when a render returns the **same element object** as last time;
  that is the mechanism step 1 uses.

Conventions: Preact with hooks, TSX, no linter — match the file's hand style. Comments say *why*.

## Commands you will need

| Purpose | Command (from repo root) | Expected on success |
|---|---|---|
| Install | `cd pwa && pnpm install --frozen-lockfile` | exit 0 |
| Full check | `cd pwa && pnpm run check` | typecheck, vitest and build all exit 0 |
| Browser tests | `cd pwa && PLAYWRIGHT_CHROMIUM_EXECUTABLE="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" pnpm run test:browser` | all pass |
| Sync Worker (serves the PWA) | `cd sync && pnpm install --frozen-lockfile && pnpm run check` (after the PWA build) | exit 0 |
| Dev server with a demo ledger | `cd pwa && pnpm run dev`, then open `http://localhost:5173/?demo` | the app with demo rows |

## Scope

**In scope**:
- `pwa/src/timeline.tsx` (only `TimelineScreen`'s list block)
- `pwa/src/timeline.css` (one rule)

**Out of scope** (do NOT touch):
- `pwa/src/app.tsx`, `useScrollPerView`, `usePageTop` — see "Current state" for why.
- `TimelineRow`, `MemberFace`, `DayHeading` — they are used by other screens (`home.tsx`
  imports `TimelineRow`); after step 1 they simply run less often.
- `pwa/src/derived.ts`, `pwa/src/state.ts` — plan 018.
- Any virtual-list dependency. If step 4 is reached, it is written by hand.

## Git workflow

- Commit on the worktree's current branch; one commit per step that changes code.
- Subject in English only, lower-case area prefix, plain description. Example from the log:
  `ledger: let a hand-entered transaction name any bank account, app and PWA`.
- No `Co-Authored-By` or any other trailer. Do not describe users as "she/her" in the message.
- Do not push, merge, or touch `main`.

## Steps

### Step 1: build the list's elements only when the list changes (`timeline.tsx`)

In `TimelineScreen`, lift the `<div class="ledger-list">…</div>` block into a memo and render
the memoised value in its place:

```tsx
// The same element object on a render that changed nothing here, which Preact does not diff:
// a sheet opening or a notice timing out no longer walks every row of the ledger.
const list = useMemo(() => (
  <div class="ledger-list">
    {/* …the block exactly as it is today… */}
  </div>
), [grouped, today, view.health.startsOn, showRial()]);
```

`openTxn` is a module-level function, so the row closures need no dependency of their own.
`showRial()` (from `./format`) is in the list because a row's figure and unit are read live from
that pref when the row renders (`format.ts:66-72`), not from the ledger: without it, switching
between تومان and ریال in تنظیمات would leave the list showing the old unit. A category's mark is
also read at render (`CategoryDisc`, `timeline.tsx:581-582`, calls `ledger()`), but categories are
ledger inputs, so a changed mark arrives as a new `grouped`.
Declare the memo with the other hooks, above the early JSX; do not put a hook inside the
conditional.

**Verify**: `cd pwa && pnpm run check` → exit 0; browser tests → all pass.

### Step 2: let the browser skip days that are off screen (`timeline.css`)

Add:

```css
/* Off-screen days are not laid out or painted. `auto` keeps the height a day had the last time
   it was on screen, so the page's length — and with it every saved scroll offset — holds. */
.ledger-list > section { content-visibility: auto; contain-intrinsic-size: auto 240px; }
```

Safari supports this from iOS 18; older versions ignore the rule and behave as today.

**Verify**: `cd pwa && pnpm run check` → exit 0; browser tests → all pass. Then **STOP**: the
commit for this step must not be handed over as done until step 3 is.

### Step 3: the operator's check on an iPhone (cannot be automated)

On an iPhone with iOS 18 or later, with a ledger of at least a few hundred rows (a real one, or
`?demo` on a dev build), in Safari **and** from the Home Screen icon:

1. دفتر → scroll about two hundred rows down → open a transaction → go back. The same row is
   under the thumb.
2. From that depth → another tab → back to دفتر. Same place.
3. From that depth → type a search that matches few rows. The list jumps to its top and shows
   the newest match; clearing the search does not leave a blank stretch.
4. From that depth → open the filter sheet, pick a category, close. Top of the narrowed list.
5. Scroll fast to the very bottom. No visible jumping of the scrollbar; the «…» aside (if the
   ledger has a start date set) is reachable.
6. With VoiceOver on, swipe through three days of rows. Headings and rows are read in order.
7. Lock the phone, unlock, return to the app. Same place.

If any of 1–5 or 7 fails, revert step 2 only (keep step 1) and record which check failed in
`plans/README.md`. If 6 fails, same.

### Step 4 (only if needed): render the list incrementally

Measure first, in Safari's Web Inspector (Timelines → record → switch tabs and open a sheet on
دفتر) on the operator's real ledger. If a tab switch still spends more than about 50 ms in
script after steps 1–2, render `grouped` in slices: keep a `shown` count in state (start at 30
days), render `grouped.slice(0, shown)`, and grow it from an `IntersectionObserver` on a
sentinel after the last rendered day. A search, a lens or a filter change resets `shown`. Scroll
restore must then grow `shown` far enough before `scrollTo` — which is the hard part and the
reason this is last. If it is reached, write it as its own plan.

## Test plan

- No new unit test: the change is which elements are rebuilt, not what they are. The existing
  browser tests that open دفتر, file a pasted message, open, delete and restore a transaction
  (`pwa/e2e/app.spec.ts`: "a manual transaction is filed, opened, deleted and brought back",
  "a pasted bank message lands in دفتر…") must pass unchanged — they are what proves step 1
  did not freeze the list.
- Manual checks for step 1 in the dev server: with دفتر open, add a manual transaction — the new
  row appears without a reload; switch the money unit between تومان and ریال in تنظیمات — every
  row's figure follows; change a category's mark — its rows follow; change the text size — the
  list follows.
- Step 3's checklist is the test for step 2.

## Done criteria

- [ ] `cd pwa && pnpm run check` exits 0; browser tests pass; `cd sync && pnpm run check` exits 0
- [ ] `grep -n "content-visibility" pwa/src/timeline.css` matches exactly one rule
      (or none, if step 3 failed and step 2 was reverted — then the reason is in `plans/README.md`)
- [ ] `grep -n "const list = useMemo" pwa/src/timeline.tsx` matches
- [ ] Step 3's seven checks are recorded as passed, with the iOS version, in `plans/README.md`
- [ ] `git status --short` lists only `pwa/src/timeline.tsx` and `pwa/src/timeline.css`

## STOP conditions

- **Step 3 has not been done by the operator** — steps 1 and 2 may be committed, but the plan
  stays IN PROGRESS and step 2 must not be released.
- After step 1, any browser test shows a stale list (a row that should have appeared or gone).
  That means something the list shows depends on a value not in the memo's dependencies —
  report which value; do not add `view` itself as a dependency (that would undo the step).
- Plan 018 is not DONE: step 1 would be correct but pointless. Report and wait.
- The fix seems to need a change in `app.tsx` or a new dependency.

## Maintenance notes

- Anything new that a row or a day heading reads at render from outside `grouped` (a pref, a
  rate, the text size as a prop rather than a CSS variable) must be added to the memo's
  dependencies, as `showRial()` is. This is the price of step 1; review new row code for it.
- `contain-intrinsic-size`'s 240px is only the first guess for a day never yet on screen; if
  day sections get much taller on average, raise it so the scrollbar's first estimate is closer.
- The avatar `<img>` per household row is rebuilt only when the list is; if a profile still
  shows it, draw the photo from one object URL per member instead of a data URI per row.
