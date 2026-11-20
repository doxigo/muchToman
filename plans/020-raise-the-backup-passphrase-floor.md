# Plan 020: A new backup needs a passphrase of 8, or 10 when it is only digits

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 3e6246e..HEAD -- app/src/main/java/com/doxigo/muchtoman/Export.kt app/src/main/java/com/doxigo/muchtoman/Settings.kt app/src/test/java/com/doxigo/muchtoman/ExportTest.kt pwa/src/backup.ts pwa/src/settings.tsx pwa/test/backup.test.ts`
> If any of these changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (new exports only; every existing file still opens)
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

The backup file (`.mtbak`) holds every stored bank message and the whole ledger, and it exists to
leave the phone. Its only lock is a passphrase, stretched with PBKDF2 at 600,000 rounds. The
floor today is six characters with no other rule, so `123456` is accepted: a million candidates,
which that key-stretching cannot defend. After this plan a **new** backup needs at least 8
characters, and a passphrase made only of digits needs at least 10. Nothing changes for opening
a file: a backup sealed last year with a six-character passphrase must still restore.

## Current state

All excerpts were read at `3e6246e`.

**Android — `app/src/main/java/com/doxigo/muchtoman/Export.kt`**

```kotlin
// :40-45
const val BACKUP_MAGIC = "MTBAK1"
const val BACKUP_FORMAT_VERSION = 1
const val BACKUP_KDF_ALGO = "PBKDF2WithHmacSHA256"
const val BACKUP_CIPHER_ALGO = "AES/GCM/NoPadding"
const val BACKUP_KDF_ITERATIONS = 600_000
const val BACKUP_MIN_PASSPHRASE = 6

// :115-124 — the only place the minimum is enforced in code
fun sealBackup(
    payload: BackupPayload,
    passphrase: String,
    ...
): ByteArray {
    require(passphrase.length >= BACKUP_MIN_PASSPHRASE) { "passphrase too short" }
```

`openBackup` (the restore path) has no length check — `grep -n BACKUP_MIN_PASSPHRASE` finds the
constant only at lines 45 and 124. Keep it that way.

**Android — `app/src/main/java/com/doxigo/muchtoman/Settings.kt`** (the export sheet; the lite
edition uses the same code)

```kotlin
// :2423
                label = { Text("رمز — دست‌کم ۶ حرف") },
// :2462-2467
                    when {
                        pass.length < BACKUP_MIN_PASSPHRASE -> problem = "رمز کوتاهه — دست‌کم ۶ حرف باشه."
                        again != pass -> problem = "دوتا رمز یکی نیستن."
                        else -> onDone(pass)
                    }
```

**PWA — `pwa/src/backup.ts`**

```ts
// :31-32
export const BACKUP_KDF_ITERATIONS = 600_000;
export const BACKUP_MIN_PASSPHRASE = 6;
// :131-133
export async function sealBackup(payload: object, passphrase: string, createdAt: number, appVersionCode = 0,
  iterations = BACKUP_KDF_ITERATIONS, formatVersion = BACKUP_FORMAT_VERSION): Promise<Uint8Array<ArrayBuffer>> {
  if (passphrase.length < BACKUP_MIN_PASSPHRASE) throw new Error('passphrase too short');
```

`openBackup` / `readBackupFile` have no length check, and `RestoreSheet` only refuses an empty
passphrase (`settings.tsx:648`). Keep it that way.

**PWA — `pwa/src/settings.tsx`**

```tsx
// :17
import { applyRestore, backupFaultFa, BACKUP_MIN_PASSPHRASE, exportBackup, readBackupFile } from './backup';
// :607-617 (ExportPassSheet)
  const done = () => {
    // Focus lets go first: the keyboard was covering the line that says why not.
    (document.activeElement as HTMLElement | null)?.blur();
    if (pass.length < BACKUP_MIN_PASSPHRASE) setProblem('رمز کوتاهه — دست‌کم ۶ حرف باشه.');
    else if (again !== pass) setProblem('دوتا رمز یکی نیستن.');
    else { closeSheet(); void runExport(pass); }
  };
// :624
        <TextField type="password" label="رمز — دست‌کم ۶ حرف" value={pass} onInput={(v) => { setPass(v); setProblem(null); }} autoFocus />
```

**Tests that already seal with a passphrase** (all still valid under the new rule — do not edit
their passphrases):

- `ExportTest.kt`: default `"قند و نبات"` (10 characters), `"شش کاراکتر"` (10 characters) in
  `` `the shipped parameters work end to end` ``, and `"کوتاه"` (5) in
  `` `a short passphrase is refused at sealing time` `` (expects `IllegalArgumentException`).
- `pwa/test/backup.test.ts`: `'secret-1'` (8 characters, exactly the new floor) everywhere, and
  `'12345'` in `'refuses a short passphrase at sealing'`.

**The copy mirror.** `pwa/test/mirror.test.ts` ("Persian copy") reads every Persian string
literal out of `app/src/main/java/**.kt` and fails unless the PWA's source contains each one
**word for word** (a missing zero-width non-joiner or a different space counts as drift). So the
three strings below must be byte-identical on both sides, and must be plain literals with no
`$`-template in Kotlin and no `${}` in TypeScript.

### Conventions that apply

- AGENTS.md: "Every feature ships on both: `app/` and `pwa/`, in the same change… same Persian
  copy." "Persian copy is the interface: casual register, Persian digits… New user-facing strings
  … live beside the pure text functions they belong to."
- The rule is one pure function per platform, beside the constants, returning the Persian line
  or nothing — the same shape as `backupFaultFa` in both files.
- Comments explain *why*. TypeScript has no linter: match the hand style around you.

### The three strings (copy byte for byte; «دست‌کم» contains a zero-width non-joiner)

| Use | String |
|---|---|
| Field label | `رمز — دست‌کم ۸ حرف` |
| Too short | `رمز کوتاهه — دست‌کم ۸ حرف باشه.` |
| Digits only, under 10 | `رمزِ فقط عدد باید دست‌کم ۱۰ رقم باشه، یا حرف هم داشته باشه.` |

To be safe against retyping, take «دست‌کم» from the existing line in the file you are editing and
change only the digit.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Android env (every shell) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| One Android test class | `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.ExportTest'` | BUILD SUCCESSFUL |
| Android full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | BUILD SUCCESSFUL |
| PWA install (fresh worktree) | `cd pwa && pnpm install --frozen-lockfile` | exit 0 |
| Two PWA test files | `cd pwa && pnpm exec vitest run test/backup.test.ts test/mirror.test.ts` | all pass |
| PWA full gate | `cd pwa && pnpm run check` | exit 0 |

Gradle's configuration cache can report a stale `UP-TO-DATE` for a test task; add `--rerun` when
a test task claims `UP-TO-DATE` after you changed code. A fresh worktree has no
`local.properties`; the `ANDROID_HOME` export replaces it.

## Scope

**In scope** (the only files you should modify):
- `app/src/main/java/com/doxigo/muchtoman/Export.kt`
- `app/src/main/java/com/doxigo/muchtoman/Settings.kt` (the two lines in the export sheet)
- `app/src/test/java/com/doxigo/muchtoman/ExportTest.kt`
- `pwa/src/backup.ts`
- `pwa/src/settings.tsx` (`ExportPassSheet` and the import line only)
- `pwa/test/backup.test.ts`

**Out of scope** (do NOT touch):
- `openBackup` / `readBackupFile` / `RestoreSheet` on either side — an old file with a short
  passphrase must still open.
- `BACKUP_KDF_ITERATIONS`, the envelope format, `BACKUP_FORMAT_VERSION` — a different KDF is a
  format change and not this plan.
- `pwa/test/mirror.test.ts` — it must pass **unedited**; another plan edits it.
- `BackupPage` in `pwa/src/settings.tsx` — another plan edits it.

## Git workflow

- Commit on the worktree's current branch; one commit for this plan.
- Subject in English only, lower-case area prefix then a plain description, e.g. (from the log)
  `ledger: let a hand-entered transaction name any bank account, app and PWA`. Suggested:
  `backup: ask eight characters of a new passphrase, ten when it is digits only, app and PWA`.
- No `Co-Authored-By` or any other trailer. Do not write about users as "she/her" in the commit
  message.
- Do NOT push, merge, or touch `main`.

## Steps

### Step 1: Android — the rule as one pure function in `Export.kt`

Replace the `BACKUP_MIN_PASSPHRASE` line with:

```kotlin
const val BACKUP_MIN_PASSPHRASE = 8
/** Digits alone are a PIN: ten of them is the least that key-stretching can still defend. */
const val BACKUP_MIN_DIGIT_PASSPHRASE = 10

/**
 * Why a passphrase will not do for a new backup, in words, or null when it will — backup.ts
 * backupPassphraseProblem says the same. Asked only when sealing: a file made under an older,
 * shorter rule must still open, so nothing on the restore path calls this.
 */
fun backupPassphraseProblem(passphrase: String): String? = when {
    passphrase.length < BACKUP_MIN_PASSPHRASE -> "رمز کوتاهه — دست‌کم ۸ حرف باشه."
    passphrase.length < BACKUP_MIN_DIGIT_PASSPHRASE && passphrase.all(Char::isDigit) ->
        "رمزِ فقط عدد باید دست‌کم ۱۰ رقم باشه، یا حرف هم داشته باشه."
    else -> null
}
```

(`Char::isDigit` is true for Persian and Arabic digits too, which is what is wanted.) In
`sealBackup` replace the `require` line with:

```kotlin
    require(backupPassphraseProblem(passphrase) == null) { "passphrase too weak" }
```

**Verify**: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.ExportTest'` →
BUILD SUCCESSFUL (every existing test still passes: their passphrases are 10 characters, and
«کوتاه» is still refused).

### Step 2: Android — the sheet asks the function

In `Settings.kt`, change the label at ~`:2423` to `Text("رمز — دست‌کم ۸ حرف")`, and the `when` at
~`:2462` to:

```kotlin
                    val weak = backupPassphraseProblem(pass)
                    when {
                        weak != null -> problem = weak
                        again != pass -> problem = "دوتا رمز یکی نیستن."
                        else -> onDone(pass)
                    }
```

**Verify**:
- `grep -n 'دست‌کم ۶' app/src/main/java/com/doxigo/muchtoman/*.kt` → no output.
- `grep -n 'BACKUP_MIN_PASSPHRASE' app/src/main/java/com/doxigo/muchtoman/Settings.kt` → no output.

### Step 3: Android — tests

Add to `ExportTest.kt`, next to `` `a short passphrase is refused at sealing time` ``:

```kotlin
    @Test
    fun `a new passphrase needs eight characters, or ten when it is only digits`() {
        assertNotNull(backupPassphraseProblem("هفت‌حرف"))            // 7 with its ZWNJ
        assertNull(backupPassphraseProblem("قند و نبات"))
        assertNull(backupPassphraseProblem("abcdefgh"))               // exactly eight
        val short = backupPassphraseProblem("1234567")
        val digits = backupPassphraseProblem("123456789")             // nine digits
        assertNotNull(short)
        assertNotNull(digits)
        assertTrue("the digits line is its own sentence", short != digits)
        assertNotNull(backupPassphraseProblem("۱۲۳۴۵۶۷۸۹"))          // Persian digits are digits
        assertNull(backupPassphraseProblem("1234567890"))             // ten digits pass
        assertNull(backupPassphraseProblem("1234567a"))               // one letter is enough
    }

    @Test
    fun `sealing refuses what the sheet refuses`() {
        try {
            seal(passphrase = "123456789")
            fail("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            // Nine digits: long enough by count, still only a PIN.
        }
    }

    @Test
    fun `a file sealed under the old six-character rule still opens`() {
        // openBackup asks nothing of the passphrase's length; only sealing does.
        val sealed = sealBrowserFile("""{"pwa":1,"prefs":{"smsEnabled":true},"tables":{}}""", "123456")
        assertEquals(BackupFault.BROWSER_BACKUP, faultOf { openBackup(sealed, "123456") })
    }
```

The third test leans on two things already in the file: `sealBrowserFile` (`ExportTest.kt:142`),
which seals with `deriveBackupKey` directly and so never meets `sealBackup`'s check — the only
way left to make a file with a six-character passphrase — and the JSON and fault that
`` `a browser's backup is named, not blamed on the passphrase` `` (`:157`) uses. Reaching
`BROWSER_BACKUP` rather than `WRONG_PASSPHRASE_OR_CORRUPT` proves the file decrypted: nothing on
the way in asked how long the passphrase was. The file imports `assertNull`, `assertTrue`,
`assertEquals` and `fail` already; add `import org.junit.Assert.assertNotNull` in alphabetical
place. «هفت‌حرف» is 7 characters with its ZWNJ and 6 without — refused either way.

**Verify**: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.ExportTest'` →
BUILD SUCCESSFUL with the three new tests run (check
`app/build/test-results/testFullDebugUnitTest/TEST-com.doxigo.muchtoman.ExportTest.xml` names them).

### Step 4: PWA — the same function in `backup.ts`

Replace the `BACKUP_MIN_PASSPHRASE` line with:

```ts
export const BACKUP_MIN_PASSPHRASE = 8;
/** Digits alone are a PIN: ten of them is the least that key-stretching can still defend. */
export const BACKUP_MIN_DIGIT_PASSPHRASE = 10;

/**
 * Why a passphrase will not do for a new backup, in words, or null when it will (Export.kt
 * backupPassphraseProblem). Asked only when sealing: a file made under an older, shorter rule
 * must still open, so nothing on the restore path calls this.
 */
export function backupPassphraseProblem(passphrase: string): string | null {
  if (passphrase.length < BACKUP_MIN_PASSPHRASE) return 'رمز کوتاهه — دست‌کم ۸ حرف باشه.';
  if (passphrase.length < BACKUP_MIN_DIGIT_PASSPHRASE && /^\p{Nd}+$/u.test(passphrase)) {
    return 'رمزِ فقط عدد باید دست‌کم ۱۰ رقم باشه، یا حرف هم داشته باشه.';
  }
  return null;
}
```

(`\p{Nd}` is the same set of characters Kotlin's `Char.isDigit` accepts.) In `sealBackup` replace
the length check with:

```ts
  if (backupPassphraseProblem(passphrase) != null) throw new Error('passphrase too weak');
```

### Step 5: PWA — the sheet asks the function

In `pwa/src/settings.tsx`: in the import on line 17 replace `BACKUP_MIN_PASSPHRASE` with
`backupPassphraseProblem` (keep the list alphabetical the way it is: `applyRestore,
backupFaultFa, backupPassphraseProblem, exportBackup, readBackupFile`). In `ExportPassSheet`'s
`done`:

```tsx
    const weak = backupPassphraseProblem(pass);
    if (weak) setProblem(weak);
    else if (again !== pass) setProblem('دوتا رمز یکی نیستن.');
    else { closeSheet(); void runExport(pass); }
```

and change the field label to `label="رمز — دست‌کم ۸ حرف"`.

**Verify**:
- `grep -rn 'دست‌کم ۶' pwa/src` → no output.
- `cd pwa && pnpm exec tsc --noEmit` → exit 0.

### Step 6: PWA — tests

In `pwa/test/backup.test.ts` add `backupPassphraseProblem` to the import from `'../src/backup'`,
and next to `'refuses a short passphrase at sealing'` add:

```ts
  it('asks eight characters of a new passphrase, ten when it is only digits', async () => {
    expect(backupPassphraseProblem('1234567')).not.toBeNull();
    expect(backupPassphraseProblem('abcdefgh')).toBeNull();
    expect(backupPassphraseProblem('123456789')).not.toBeNull();
    expect(backupPassphraseProblem('123456789')).not.toBe(backupPassphraseProblem('1234567'));
    expect(backupPassphraseProblem('۱۲۳۴۵۶۷۸۹')).not.toBeNull();
    expect(backupPassphraseProblem('1234567890')).toBeNull();
    expect(backupPassphraseProblem('1234567a')).toBeNull();
    await expect(sealBackup(payload, '123456789', 1, 0, ROUNDS)).rejects.toThrow();
  });
```

**Verify**: `cd pwa && pnpm exec vitest run test/backup.test.ts test/mirror.test.ts` → all pass.
`mirror.test.ts` passing **unedited** is the proof that the three strings are identical on both
sides. If its "Persian copy" tests fail naming one of the three strings, the two sides differ by
a character — fix the string, never the mirror test's allowlists.

### Step 7: Full gates

**Verify**:
- `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → BUILD SUCCESSFUL.
- `cd pwa && pnpm run check` → exit 0.

## Test plan

- `ExportTest.kt`: three new tests (the rule's edges; sealing enforces it; an old six-character
  file still reaches the real answer). Pattern: the existing
  `` `a short passphrase is refused at sealing time` `` beside them.
- `pwa/test/backup.test.ts`: one new test with the same edges, plus `sealBackup` refusing nine
  digits. Pattern: `'refuses a short passphrase at sealing'`.
- Existing tests must pass with their passphrases unchanged — that they do is the evidence that
  ordinary passphrases are unaffected.

## Done criteria

- [ ] `grep -rn 'دست‌کم ۶' app/src/main pwa/src` returns nothing
- [ ] `grep -c 'fun backupPassphraseProblem' app/src/main/java/com/doxigo/muchtoman/Export.kt` → 1,
      and `grep -c 'export function backupPassphraseProblem' pwa/src/backup.ts` → 1
- [ ] `grep -n 'backupPassphraseProblem' app/src/main/java/com/doxigo/muchtoman/Export.kt pwa/src/backup.ts`
      shows it called only from `sealBackup` in each file (never from `openBackup`)
- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` is BUILD SUCCESSFUL
- [ ] `cd pwa && pnpm run check` exits 0
- [ ] `git diff --stat 3e6246e -- pwa/test/mirror.test.ts` is empty
- [ ] `git status --short` lists only the six in-scope files
- [ ] `plans/README.md` status row updated (unless your reviewer maintains the index)

## STOP conditions

Stop and report back (do not improvise) if:

- Any excerpt in "Current state" does not match the live file.
- An existing test seals with a passphrase the new rule refuses (other than `"کوتاه"` and
  `'12345'`, which are meant to be refused) — report which; do not weaken the rule or quietly
  change the test's passphrase.
- `mirror.test.ts` fails for a reason other than a character mismatch in one of the three
  strings.
- Making an old six-character file open appears to need a change to `openBackup` — it should
  need none.
- `BACKUP_MIN_PASSPHRASE` turns out to be used anywhere not listed in "Current state".

## Maintenance notes

- The rule lives in `backupPassphraseProblem` on each side and the two must stay the same
  function. If plan 023 has landed, `BACKUP_MIN_PASSPHRASE` is pinned across the two by
  `pwa/test/mirror.test.ts`; add `BACKUP_MIN_DIGIT_PASSPHRASE` to that pinned map then (it could
  not be pinned before this plan created it).
- The Persian digits in the three strings (۸, ۱۰) are written out, not built from the constants,
  because the copy mirror compares literals. Changing a constant means changing its string.
- Reviewer: confirm no call to `backupPassphraseProblem` crept onto a restore path, and read the
  three strings on a phone-sized sheet — the digits line is the longest error this sheet shows.
- Deferred: a memory-hard KDF (Argon2/scrypt) would be the stronger defence, but it needs a
  dependency on both platforms and a new `BACKUP_FORMAT_VERSION`; it is a separate decision.
