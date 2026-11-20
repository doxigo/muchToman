# Plan 019: The PWA's lock keeps one passkey

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- pwa/src/lock.tsx pwa/src/model.ts pwa/src/backup.ts`
> If any of these changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW (the lock hides balances; it guards no key, so no change here can lock data away — the escape for a deleted passkey is kept)
- **Depends on**: none (runs after plans 017 and 018 in the same worktree; no shared files)
- **Category**: bug
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

The PWA's app lock is a WebAuthn credential (a passkey) on the phone's own authenticator. Today,
after **one** refused unlock — a face that did not read, a sheet dismissed by accident — the next
tap on «باز کردن» skips asking for the existing passkey and enrols a **new** one. Every enrolment
uses a fresh random user handle, so each one is saved as another «چقدر تومن» passkey in the
phone's saved passwords and the previous one is orphaned. It is not a way round the lock (the
platform still asks for the face or passcode), but it litters the user's password manager and
makes the lock's prompt change shape after a single slip.

Confidence is **MED**: the code path is certain; whether passkeys actually pile up depends on how
the platform treats a repeated enrolment, which nobody has tested on an iPhone. So the change is
kept minimal: one stable handle per browser, and one more ordinary try before re-enrolling.

The phone's lock (`Lock.kt`) uses Android's BiometricPrompt and has no credential to enrol, so
there is no `app/` twin for this change. Say that in the commit body.

## Current state

- `pwa/src/lock.tsx` — the whole lock. The enrolment, with a random handle every time:

  ```ts
  // lock.tsx:30-44
  export async function enableLock(): Promise<boolean> {
    prompting = true;
    try {
      const credential = await navigator.credentials.create({
        publicKey: {
          challenge: challenge(),
          rp: { id: location.hostname, name: 'چقدر تومن' },
          // A random handle: the credential names no person, only this browser's lock.
          user: { id: crypto.getRandomValues(new Uint8Array(16)), name: 'چقدر تومن', displayName: 'چقدر تومن' },
          pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
          authenticatorSelection: { authenticatorAttachment: 'platform', userVerification: 'required', residentKey: 'discouraged' },
  ```

  Turning the lock off clears the credential id (`lock.tsx:61-67`):

  ```ts
  export function disableLock(): void {
    batch(() => {
      setPref('lockEnabled', false);
      setPref('lockCredential', '');
    });
    locked = false;
  }
  ```

  The unlock, which re-enrols after a single refused tap (`lock.tsx:72-73,92-107,119-125`):

  ```ts
  /** Taps on «باز کردن» the platform turned down since the lock last opened. */
  let refused = 0;
  // …
  async function unlock(tapped = false): Promise<void> {
    const id = pref('lockCredential');
    if (prompting || !id) {
      // A lock with no credential behind it is a lockout, not a lock (a restore never brings one).
      if (!id) setLocked(false);
      return;
    }
    if (tapped && refused > 0) {
      // The credential may be gone — deleted from the phone's saved passwords, or lost with the
      // browser's data — and the platform cannot say so apart from a cancel. Enrolling a new one
      // still asks for the face or the passcode, so this is the same door, never a way round it;
      // without it a missing passkey would lock her out of her own ledger for good.
      if (await enableLock()) { refused = 0; setLocked(false); }
      else await nothingToCheck();
      return;
    }
  // …in the catch of credentials.get():
      if (tapped) refused++;
  ```

  That comment is the documented escape for a deleted passkey. **It stays**, word for word; only
  the number of refusals before it applies changes.
- `pwa/src/model.ts` — prefs. `lockCredential: string;` is declared at line ~277 with the doc
  comment `/** WebAuthn credential id (base64url) the lock unlocks with. */`, and its default
  `lockCredential: '',` is at line ~325 in `PREF_DEFAULTS`.
- `pwa/src/backup.ts:52-55` — prefs that are this browser's own: never written into a backup and
  kept as they are on a restore:

  ```ts
  const LOCAL_PREFS: string[] = [
    'rates', 'budgetMarks', 'installmentMarks', 'quipsSeen', 'quietMark', 'syncSeq', 'syncRead', 'syncShareSms', 'syncPrimaryMember',
    'syncPullFirst', 'lockEnabled', 'lockCredential', 'onboarded', 'usdHistoryAt', 'pasteKey',
  ];
  ```

- `pwa/src/crypto.ts:30-36` — `toBase64Url(bytes: Uint8Array): string` and
  `fromBase64Url(value: string): Uint8Array<ArrayBuffer>`; `lock.tsx` already imports both.
- There is no test file for the lock. `pwa/e2e/app.spec.ts:121-143` has one browser test that
  writes `lockEnabled`/`lockCredential` straight into IndexedDB and checks the lock screen shows.

Conventions: TypeScript/TSX, no linter — match the file's hand style. Comments explain *why*.

## Commands you will need

| Purpose | Command (from repo root) | Expected on success |
|---|---|---|
| Install | `cd pwa && pnpm install --frozen-lockfile` | exit 0 |
| One test file | `cd pwa && pnpm exec vitest run test/lock.test.ts` | all pass |
| Full check | `cd pwa && pnpm run check` | typecheck, vitest and build all exit 0 |
| Browser tests | `cd pwa && PLAYWRIGHT_CHROMIUM_EXECUTABLE="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" pnpm run test:browser` | all pass |

## Scope

**In scope**:
- `pwa/src/lock.tsx`
- `pwa/src/model.ts` (one pref and its default)
- `pwa/src/backup.ts` (one name added to `LOCAL_PREFS`)
- `pwa/test/lock.test.ts` (create)

**Out of scope** (do NOT touch):
- `residentKey`, `authenticatorAttachment`, `userVerification`, `rp`, the user `name`/`displayName`
  — changing what kind of credential is made is a different change with its own device testing.
- `nothingToCheck`, `setLocked`, the `visibilitychange` re-arm, `LockScreen`'s markup and copy.
- `pwa/src/settings.tsx` (the switch that calls `enableLock`/`disableLock`).
- `pwa/test/backup.test.ts` — put the backup assertion in the new test file instead.
- Anything under `app/`.

## Git workflow

- Commit on the worktree's current branch; one commit for this plan.
- Subject in English only, lower-case area prefix, plain description. Example from the log:
  `ledger: let a hand-entered transaction name any bank account, app and PWA`.
  Suggested: `pwa: reuse one passkey handle for the lock and ask twice before re-enrolling`.
- No `Co-Authored-By` or any other trailer. Do not describe users as "she/her" in the message.
- Do not push, merge, or touch `main`.

## Steps

### Step 1: a pref for the handle (`model.ts`, `backup.ts`)

- In `pwa/src/model.ts`, directly under `lockCredential: string;` add:

  ```ts
  /** The user handle (base64url) every enrolment of this browser's lock reuses, so a new passkey replaces the old one. */
  lockUser: string;
  ```

  and `lockUser: '',` directly under `lockCredential: '',` in `PREF_DEFAULTS`.
- In `pwa/src/backup.ts`, add `'lockUser'` to `LOCAL_PREFS` right after `'lockCredential'`.

**Verify**: `cd pwa && pnpm run typecheck` → exit 0.

### Step 2: reuse the handle (`lock.tsx`)

Add above `enableLock`:

```ts
/**
 * This browser's handle for its lock, made once and kept even while the lock is off: an
 * authenticator holds one passkey per site and handle, so enrolling again under the same handle
 * replaces the old passkey instead of leaving it behind in her saved passwords.
 */
function lockUser(): Uint8Array<ArrayBuffer> {
  const kept = pref('lockUser');
  if (kept) return fromBase64Url(kept);
  const fresh = crypto.getRandomValues(new Uint8Array(16));
  setPref('lockUser', toBase64Url(fresh));
  return fresh;
}
```

In `enableLock`, change `user: { id: crypto.getRandomValues(new Uint8Array(16)), …` to
`user: { id: lockUser(), …` and reword the one-line comment above it to:
`// This browser's own random handle: the credential names no person, only this lock.`
Do **not** clear `lockUser` in `disableLock`.

**Verify**: `cd pwa && pnpm run typecheck` → exit 0.

### Step 3: one more ordinary try before re-enrolling (`lock.tsx`)

- Change `if (tapped && refused > 0) {` to `if (tapped && refused > 1) {`.
- Keep the four-line comment inside that block exactly as it is, and add one sentence in front
  of it: `// Only after two refusals in a row: one is a face that did not read or a sheet closed by accident.`
- Add `export` to `async function unlock` (the test calls it; `LockScreen` keeps calling it as before).

**Verify**: `cd pwa && pnpm run check` → exit 0.

### Step 4: the test, then the full gate

Create `pwa/test/lock.test.ts` per "Test plan".

**Verify**: `cd pwa && pnpm exec vitest run test/lock.test.ts` → all pass; then
`cd pwa && pnpm run check` → exit 0; then the browser tests → all pass (the existing
"the lock stands in front of what a shortcut opens" test must still pass unchanged).

## Test plan

`pwa/test/lock.test.ts` — vitest runs in Node, so stub what the browser provides. Setup per
test: `import 'fake-indexeddb/auto'`, `vi.resetModules()`, `globalThis.indexedDB = new IDBFactory()`
(as `pwa/test/storage.test.ts:1-12` does), then
`vi.stubGlobal('location', { hostname: 'example.test' })` and
`vi.stubGlobal('navigator', { credentials: { create, get } })` with `create`/`get` as `vi.fn()`s,
then dynamic `import('../src/lock')` and `import('../src/state')`. `create` resolves
`{ rawId: new Uint8Array([1, 2, 3]).buffer }`. Call `vi.unstubAllGlobals()` in `afterEach`.

1. *every enrolment of one browser uses the same handle*: `await enableLock()`, `disableLock()`,
   `await enableLock()`; read `create.mock.calls[n][0].publicKey.user.id` for both calls and
   expect the two byte arrays to be equal, 16 bytes long, and equal to
   `fromBase64Url(state.pref('lockUser'))`.
2. *the handle never rides a backup*: after `await enableLock()`,
   `(await (await import('../src/backup')).browserPayload()).prefs` has no `lockUser`,
   `lockCredential` or `lockEnabled` key.
3. *a single refused tap asks for the same passkey again*: `await enableLock()`, then make `get`
   reject (`new DOMException('no', 'NotAllowedError')`) and `create.mockClear()`.
   `await unlock(true)` twice → `get` called twice, `create` not called. A third
   `await unlock(true)` → `create` called once (the escape for a deleted passkey).
   This case must also stub
   `vi.stubGlobal('PublicKeyCredential', { isUserVerifyingPlatformAuthenticatorAvailable: async () => true })`
   before the import: without it `nothingToCheck` concludes the device has no lock and turns the
   lock off after the first refusal.

No new browser test: Chrome's virtual authenticator does not model passkey replacement, so it
could not assert the thing this plan changes.

## Done criteria

- [ ] `cd pwa && pnpm run check` exits 0
- [ ] `pwa/test/lock.test.ts` exists with the three cases and passes
- [ ] `grep -n "refused > 0" pwa/src/lock.tsx` returns nothing; `grep -c "lockUser" pwa/src/lock.tsx` ≥ 3
- [ ] `grep -n "'lockUser'" pwa/src/backup.ts` shows it inside `LOCAL_PREFS`
- [ ] The comment beginning `The credential may be gone` is still in `lock.tsx`, unchanged
- [ ] Browser tests pass
- [ ] `git status --short` lists only in-scope files (plus those of plans 017/018, if they ran first in this worktree)

## STOP conditions

- Any "Current state" excerpt does not match the live file.
- Importing `../src/lock` under vitest fails for a reason other than a missing browser global you
  can stub (for example a CSS or asset import) — then write only test cases 1 and 2 against
  whatever can be imported, or report that the test could not be written; do not restructure
  `lock.tsx` to make it testable.
- A change seems to need `residentKey`, `rp` or anything else in the "Out of scope" list.

## Maintenance notes

- Not verified on a device: that iOS replaces a passkey enrolled under the same handle. If a
  check on an iPhone shows it still adds a second entry, the remaining lever is
  `excludeCredentials` on `create()` — a separate, device-tested change.
- A browser that already enrolled once under a random handle gets its stable handle at its next
  enrolment; the passkey made before this change is not cleaned up and cannot be from the page.
- `lockUser` is deliberately kept when the lock is turned off, and deliberately in `LOCAL_PREFS`:
  restored into another browser it would point at a passkey that browser's device does not hold.
