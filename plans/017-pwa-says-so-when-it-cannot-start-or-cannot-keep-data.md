# Plan 017: The PWA says so when it cannot start or cannot keep data

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- pwa/src/main.tsx pwa/src/rules.ts pwa/src/state.ts pwa/src/app.tsx pwa/src/app.css pwa/src/onboarding.tsx pwa/src/settings.tsx pwa/src/pasteUi.tsx`
> If any of these changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW (new failure paths only; the success path must stay byte-for-byte the same on screen)
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

`pwa/` is the app for iPhone users, and its ledger lives only in the browser's IndexedDB. Today
three failures are silent. (1) If the store cannot be opened at launch, `start()` rejects with no
catch and the page stays white, with no words and no way to retry. (2) If any component throws
while rendering (one bad row is enough, because every tab stays mounted), the whole app unmounts
to a white page on every launch, and the only way out — clearing site data — deletes the ledger.
(3) If the browser may evict the store, or a write did not reach disk, nothing on screen says so
after a six-second notice. After this plan each of the three has a screen or a standing line, and
the render-failure screen still lets the user take a backup file out.

This is one of the areas a browser does differently from the phone (storage that can be evicted
or fail to open), so there is no `app/` twin to change. Say that in the commit body.

## Current state

- `pwa/src/main.tsx` — the entry. No catch anywhere on the start path:

  ```ts
  // main.tsx:80-83
  async function start(): Promise<void> {
    watchForUpdates();
    void navigator.storage?.persist?.().catch(() => false);
    await ensureSeeded();
  // main.tsx:142-146
    render(<App />, document.getElementById('app')!);
    await takePairing();
  }

  void start();
  ```

  The same file wires the write-error notice (line 21):
  `setWriteErrorHandler(() => showNotice('ذخیره نشد. فضای مرورگر رو چک کن و دوباره امتحان کن.'));`

- `pwa/src/rules.ts:430-434` — a rejected load is cached for the whole session:

  ```ts
  let seeding: Promise<void> | null = null;
  /** Seeds once per session, after the store has loaded — seeding before would put back her renames. */
  export function ensureSeeded(): Promise<void> {
    return (seeding ??= load().then(() => seedBuiltins()));
  }
  ```

- `pwa/src/db.ts:40-73` — `open()` rejects on `request.onerror` and already clears its cached
  handle on a failure. It has no `onblocked` handler; a blocked or hung open never settles.
  **Do not edit db.ts** — the deadline in step 2 covers a hung open.
- `pwa/src/state.ts:77,119-127` — failed writes are counted and retried, announced only once:

  ```ts
  /** Failed flushes in a row; the first says so, the retries back off quietly. */
  let failures = 0;
  // …inside flush():
        sending = [];
        failures = 0;
        tabs?.postMessage(null);
  // …
    } catch (error) {
      queue = [...sending, ...queue];
      sending = [];
      reopen();
      if (failures++ === 0) onWriteError(error);
  ```

  `restore()` in the same file also sets `failures = 0;` (line ~281).
- `pwa/src/app.tsx:79-127` — `App`, which calls `useData()` (re-renders on every store change)
  and returns `<>{gate && …}<div hidden={!!gate} …> …tabs, page, sheet, notice… </div><NoticeSaid/></>`.
- `pwa/src/onboarding.tsx:19-20,85-87` — the installed check and the only eviction wording:

  ```tsx
  const installed = (): boolean =>
    matchMedia('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true;
  // …
  <p class="muted" style={body}>
    چقدر تومن رو به صفحهٔ اصلی گوشی اضافه کن. این‌جوری مرورگر دفترت رو پاک نمی‌کنه.
  </p>
  ```

- `pwa/src/settings.tsx:540-575` — `BackupPage`. After the two door rows it renders
  `<Note>فایل پشتیبان رمز داره. …</Note>`. `Note` is `settings.tsx:62` and takes
  `tone?: 'strong' | 'error'`.
- `pwa/src/backup.ts:276` — `exportBackup(passphrase)` reads `snapshot()` from the store and
  never calls `ledger()`, so it works even when deriving the ledger throws. It throws if
  `passphrase.length < BACKUP_MIN_PASSPHRASE` (exported from the same file).
- `pwa/src/pasteUi.tsx:318-335` — `pasteFromHash()` removes the `#paste=` hash from the address
  (line 324, `history.replaceState(...)`) and only then, for a bundle, does `await ensureSeeded()`.
  A failed load therefore loses the message an iOS Shortcut handed over.
- `pwa/src/ui.tsx` — `PillButton({ label, onClick, voice, block, disabled })` and
  `TextField({ label, value, onInput, type, onEnter, autoFocus })`. Use these; do not build new buttons.
- No error boundary exists: `grep -rn "useErrorBoundary\|componentDidCatch" pwa/src` is empty.

Conventions: Preact with hooks only (no class components anywhere), TSX, no linter — match the
hand style of the file you are in. Comments say *why*, briefly. Persian copy is the interface:
casual register, plain, no cute lines. Use the exact strings given below. The page's CSP is
`script-src 'self'`; add no inline script and no third-party request.

## Commands you will need

| Purpose | Command (from repo root) | Expected on success |
|---|---|---|
| Install | `cd pwa && pnpm install --frozen-lockfile` | exit 0 |
| One test file | `cd pwa && pnpm exec vitest run test/startup.test.ts` | all pass |
| Full check | `cd pwa && pnpm run check` | typecheck, vitest and build all exit 0 |
| Browser tests | `cd pwa && PLAYWRIGHT_CHROMIUM_EXECUTABLE="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" pnpm run test:browser` | all pass |
| Sync Worker (serves the PWA, checks headers) | `cd sync && pnpm install --frozen-lockfile && pnpm run check` (after the PWA build) | exit 0 |

## Scope

**In scope** (the only files you may modify or create):
- `pwa/src/main.tsx`, `pwa/src/rules.ts`, `pwa/src/state.ts`, `pwa/src/app.tsx`, `pwa/src/app.css`
- `pwa/src/onboarding.tsx`, `pwa/src/settings.tsx`, `pwa/src/pasteUi.tsx`
- `pwa/src/stuck.tsx` (create)
- `pwa/test/startup.test.ts` (create), `pwa/e2e/app.spec.ts` (append one test)

**Out of scope** (do NOT touch):
- `pwa/src/db.ts` — the open path is shared with sync and the v2 migration.
- `pwa/src/backup.ts` — only import from it.
- `pwa/src/derived.ts`, `pwa/src/lock.tsx`, `pwa/src/reports.ts` — plans 018 and 019 own them.
- `pwa/index.html`, `pwa/public/_headers`, anything under `sync/` or `app/`.
- The default of `backupReminderEnabled` — changing it is a product decision.

## Git workflow

- Commit on the worktree's current branch; one commit for this plan.
- Subject in English only, lower-case area prefix, plain description. Example from the log:
  `ledger: let a hand-entered transaction name any bank account, app and PWA`.
  Suggested: `pwa: show a screen when the app cannot start or render, and say when data is not kept`.
- No `Co-Authored-By` or any other trailer. Do not describe users as "she/her" in the message.
- Do not push, merge, or touch `main`.

## Steps

### Step 1: `ensureSeeded` forgets a failed load

In `pwa/src/rules.ts`, make a rejection clear the cached promise so a later call tries again:

```ts
export function ensureSeeded(): Promise<void> {
  return (seeding ??= load().then(() => seedBuiltins()).catch((error: unknown) => { seeding = null; throw error; }));
}
```

**Verify**: `cd pwa && pnpm run typecheck` → exit 0.

### Step 2: the stuck screen and the boundary (`pwa/src/stuck.tsx`)

Create the file with two exports:

- `Stuck({ canBackUp }: { canBackUp: boolean })` — a full page using existing classes
  (`<div class="screen no-tabs">`, `<h1 class="screen-title">`, `<p class="muted">`, `class="stack"`):
  - heading: `برنامه باز نشد`
  - line: `دوباره امتحان کن. اگه چقدر تومن توی پنجرهٔ دیگه‌ای هم بازه، اول اون رو ببند.`
  - `<PillButton label="دوباره امتحان کن" voice="primary" block onClick={() => location.reload()} />`
  - only when `canBackUp`: the line `اگه باز هم نشد، قبل از هر کار دیگه‌ای از دفترت پشتیبان بگیر.`,
    two `TextField type="password"` with labels `رمز فایل پشتیبان` and `دوباره همون رمز`, and
    `<PillButton label="ساختن فایل پشتیبان" block disabled={…} onClick={…} />`. The button is
    disabled until `pass.length >= BACKUP_MIN_PASSPHRASE && pass === again`. On click call
    `exportBackup(pass)`; on success show `پشتیبان ساخته شد. فایل و رمزش رو جای امن نگه دار.`,
    on a throw show `پشتیبان ساخته نشد. دوباره امتحان کن.` in `<p class="set-note error">`.
  - It must not import `./derived`, `./nav` or any screen module, and must not call `ledger()`.
- `Guard({ children })` — the one boundary:

  ```tsx
  export function Guard({ children }: { children: ComponentChildren }) {
    const [error] = useErrorBoundary((e) => console.error('render failed', e));
    // The store is loaded by the time anything renders, so the backup can still be taken out.
    return error ? <Stuck canBackUp /> : <>{children}</>;
  }
  ```

  (`useErrorBoundary` is from `preact/hooks`; it is the hooks form of `componentDidCatch`.)

**Verify**: `cd pwa && pnpm run typecheck` → exit 0.

### Step 3: catch the start, with a deadline (`pwa/src/main.tsx`)

- Add two module flags above `start()`: `let storeLoaded = false;` and `let mounted = false;`.
- Replace `await ensureSeeded();` with a 15-second race, then set the flag:

  ```ts
  // A blocked or hung IndexedDB open never settles; past this the page says so instead of staying white.
  await Promise.race([ensureSeeded(), new Promise<never>((_, reject) => setTimeout(() => reject(new Error('store did not open')), 15_000))]);
  storeLoaded = true;
  ```

- Replace the render line with `mounted = true; render(<Guard><App /></Guard>, document.getElementById('app')!);`.
- Replace `void start();` with:

  ```ts
  void start().catch((error: unknown) => {
    console.error('start failed', error);
    // After the app is up, a late failure (a pairing link) must not replace it.
    if (!mounted) render(<Stuck canBackUp={storeLoaded} />, document.getElementById('app')!);
  });
  ```

**Verify**: `cd pwa && pnpm run check` → exit 0.

### Step 4: keep the Shortcut's hand-off when the load fails (`pwa/src/pasteUi.tsx`)

In `pasteFromHash()`, move the store load in front of the address change, for every hand-off:
put `await ensureSeeded();` immediately **before** the `history.replaceState(...)` line and delete
the now-redundant `await ensureSeeded();` inside `if (bundle) {`. Keep that block's comment,
moved up with the call. Nothing else in the function changes. On success the order of visible
events is the same (the app does not render before the store loads anyway); on failure the hash
is still in the address when the user taps «دوباره امتحان کن».

**Verify**: browser tests (command above) → all pass, including the four tests whose names
contain "paste", "one-time code", "shortcut's bundle" and "sender no table knows".

### Step 5: a standing line while writes are not on disk

- `pwa/src/state.ts`: add `export const unsaved = (): boolean => failures > 0;`. In `flush()`'s
  catch, after `failures++` ran for the first time, and at **both** places that set
  `failures = 0`, call `changed()` only when the answer flips (0→1 and N→0), so mounted screens
  re-render. Shape: in the catch, `if (failures++ === 0) { onWriteError(error); changed(); }`;
  at the resets, `if (failures) { failures = 0; changed(); }`.
- `pwa/src/app.tsx`: import `unsaved` and render, as the first child inside the
  `<div hidden={!!gate} …>`:

  ```tsx
  {unsaved() && <p class="unsaved" role="status">تغییرهای آخرت هنوز ذخیره نشده. فضای مرورگر رو چک کن.</p>}
  ```

- `pwa/src/app.css`: add one rule beside `.notice`:

  ```css
  .unsaved { position: sticky; top: 0; z-index: 40; padding: calc(env(safe-area-inset-top) + var(--s)) var(--edge) var(--s); background: var(--error-container); color: var(--on-error-container); font-size: calc(13px * var(--text)); font-weight: 600; text-align: center; }
  ```

  Leave the existing six-second notice in `main.tsx` line 21 as it is.

**Verify**: `cd pwa && pnpm run check` → exit 0.

### Step 6: say when the browser may clear the ledger (`onboarding.tsx`, `settings.tsx`)

- `pwa/src/onboarding.tsx`: add `export` to `installed`, and lift the sentence into
  `export const INSTALL_KEEPS_FA = 'چقدر تومن رو به صفحهٔ اصلی گوشی اضافه کن. این‌جوری مرورگر دفترت رو پاک نمی‌کنه.';`
  used by the existing `<p>` (rendered text must not change).
- `pwa/src/settings.tsx`, in `BackupPage`: read the browser's answer once when the page opens and
  show the same sentence directly above the existing `<Note>فایل پشتیبان رمز داره…` while the
  store is neither persistent nor installed:

  ```tsx
  // null until the browser answers; a browser without the API cannot promise anything.
  const [kept, setKept] = useState<boolean | null>(navigator.storage?.persisted ? null : false);
  useEffect(() => { void navigator.storage?.persisted?.().then(setKept, () => {}); }, []);
  // …
  {kept === false && !installed() && <Note tone="strong">{INSTALL_KEEPS_FA}</Note>}
  ```

  Check first that `onboarding.tsx` does not import `settings.tsx` (it does not at `3e6246e`).

**Verify**: `cd pwa && pnpm run check` → exit 0; browser tests → all pass (the first-run test
still finds the onboarding screen's text).

### Step 7: tests

See "Test plan". **Verify**: all four command-table checks pass.

## Test plan

- `pwa/test/startup.test.ts` (create; model the setup on `pwa/test/storage.test.ts` lines 1-12:
  `import 'fake-indexeddb/auto'`, `vi.resetModules()`, `globalThis.indexedDB = new IDBFactory()`):
  1. *a failed load is not remembered*: set `globalThis.indexedDB = { open() { throw new Error('closed'); } } as never`,
     import `../src/rules`, expect `ensureSeeded()` to reject; then set
     `globalThis.indexedDB = new IDBFactory()` and expect `ensureSeeded()` to resolve and
     `rows('categories').length` (from `../src/state`) to be greater than 0.
  2. *a write that cannot land is reported until it does*: after `await ensureSeeded()`, call
     `reopen()` from `../src/db`, swap in the throwing `indexedDB`, `setPref('name', 'x' as never)`,
     `await settled()`, expect `unsaved()` to be `true`; restore `new IDBFactory()`, write once
     more, `await settled()`, expect `unsaved()` to be `false`.
- `pwa/e2e/app.spec.ts` (append one test, using the file's existing `page.addInitScript` pattern):
  *a store that will not open gets a screen, not a blank page* — init script
  `IDBFactory.prototype.open = () => { throw new DOMException('closed', 'UnknownError'); };`, then
  `page.goto('/')`, expect heading `برنامه باز نشد` and button `دوباره امتحان کن` to be visible, and
  expect no password field (the store never loaded).
- Manual check line for the reviewer (cannot be automated here): in `pnpm run dev`, throw inside
  any screen component, confirm the stuck screen offers the backup and the file downloads.

## Done criteria

- [ ] `cd pwa && pnpm run check` exits 0
- [ ] `pwa/test/startup.test.ts` exists with the two cases and passes
- [ ] Browser tests pass, including the new "will not open" case
- [ ] `cd sync && pnpm run check` exits 0 (run after the PWA build)
- [ ] `grep -n "void start();" pwa/src/main.tsx` returns nothing; `grep -c "Guard" pwa/src/main.tsx` ≥ 2
- [ ] `grep -rn "ledger()" pwa/src/stuck.tsx` returns nothing
- [ ] `git status --short` lists only in-scope files

## STOP conditions

- Any "Current state" excerpt does not match the live file.
- `useErrorBoundary` is not exported by the installed `preact/hooks` (then report; do not add a
  dependency or `preact/compat`).
- Step 4 makes any existing paste browser test fail twice — revert step 4 only, finish the rest,
  and report it as skipped.
- Test 2 in `startup.test.ts` cannot be made deterministic in two attempts (fake-indexeddb and
  the retry timer) — keep test 1, drop test 2, and say so in the report.
- A step seems to need a change in `db.ts`, `backup.ts` or any file outside scope.

## Maintenance notes

- `Stuck` must stay free of `ledger()` and of screen imports: it is what runs when those throw.
- `changed()` now also fires when write failures start and stop; `main.tsx`'s subscriber will
  schedule one extra announce/sync on those two moments, which is harmless.
- Deferred on purpose: an `onblocked` handler in `db.ts` (the 15 s deadline covers it), defaulting
  the backup reminder to on (product decision), and validating restored rows by shape
  (`backup.ts:213`), which would make the boundary fire less often.
- If another change raises `BACKUP_MIN_PASSPHRASE` or adds passphrase rules inside `sealBackup`,
  the stuck screen follows the constant automatically; a stricter rule there shows as
  «پشتیبان ساخته نشد».
