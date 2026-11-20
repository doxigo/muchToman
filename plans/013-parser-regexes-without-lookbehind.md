# Plan 013: The PWA's parser carries no regex lookbehind, and no digit-run search is quadratic

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 3e6246e..HEAD -- pwa/src/paste.ts pwa/src/settings.tsx app/src/main/java/com/doxigo/muchtoman/Sms.kt app/src/test/java/com/doxigo/muchtoman/MoneyTest.kt`
> If any of these changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (these regexes are the money parser; the golden corpus is the net)
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

`pwa/` is the app for iPhone users, and `pwa/src/data.ts:423` states its floor as iOS 15.4 — "the
last iOS for the iPhone 6s, 7 and first SE". But `pwa/src/paste.ts` holds nine regexes with a
lookbehind. Safari only learned lookbehind in 16.4, and a regex literal an engine cannot read is
a **parse-time SyntaxError for the whole script**. The PWA builds to one chunk
(`pwa/vite.config.ts:42`, target es2022, nothing down-levels a regex), so on iOS 15.x–16.3 the
bundle never runs: a white page with no words.

The decision is taken: keep the 15.4 floor and rewrite the nine regexes so none uses a
lookbehind, **without changing what the parser reads** — every case in the golden corpus must
come out exactly as it does today.

Two neighbours ride along because they are the same lines:

- `DASHED` and `SLASHED_REF` (both platforms) rescan a long run of digits from each of its digits
  — quadratic, the cost `MASKED` was already cured of ("4 s on a 39k-char body"). Kotlin gets the
  one-line lookbehind guard; the PWA twins get the same linear behaviour without one.
- `CompressionStream` (backup) is also Safari 16.4+. Once the app loads on iOS 15, the backup
  page must say so in words instead of failing with the wrong sentence.

## Current state

All excerpts were read at `3e6246e`.

### `pwa/src/paste.ts` — the nine lookbehinds and every use of each

```ts
// :84-85
const GROUPED = '[0-9۰-۹٠-٩]{1,3}(?:[,،٬][0-9۰-۹٠-٩]{3})+';
const SIGNED = new RegExp(`(?<![^\\s:\\p{L}])(?:([+-])(${GROUPED})|(${GROUPED})([+-]))(?![0-9۰-۹٠-٩])`, 'gu');

// :92-103 — the only use
function signedAmount(text: string): { value: number; divisor: number | null; plus: boolean } | null {
  for (const m of text.matchAll(SIGNED)) {
    const lineStart = text.lastIndexOf('\n', m.index! - 1) + 1;
    if (BALANCE_WORDS.some((w) => text.slice(lineStart, m.index).includes(w))) continue;
    return {
      value: Number(digitsOf(m[2] ?? m[3])),
      divisor: unitAfter(text, m.index! + m[0].length),
      plus: (m[1] ?? m[4]) === '+',
    };
  }
  return null;
}
```

```ts
// :117-118
const PRINTED_AT =
  /(?<![0-9۰-۹٠-٩.\/\-_])[0-9۰-۹٠-٩]{1,4}[/.][0-9۰-۹٠-٩]{1,2}(?:[/.][0-9۰-۹٠-٩]{1,2})?(?:[ _-]{1,3}[0-9۰-۹٠-٩]{1,2}:[0-9۰-۹٠-٩]{2}(?::[0-9۰-۹٠-٩]{2})?)?(?![0-9۰-۹٠-٩])/;

// :125-126 — exported; pwa/src/sms.ts:333 does `RUN_TOGETHER.exec(body)` and reads run[1], run[2], run[3] only
export const RUN_TOGETHER =
  /(?<![0-9۰-۹٠-٩./\-_])([0-9۰-۹٠-٩]{2})([0-9۰-۹٠-٩]{2})[ _-]{1,3}([0-9۰-۹٠-٩]{1,2}:[0-9۰-۹٠-٩]{2}(?::[0-9۰-۹٠-٩]{2})?)(?![0-9۰-۹٠-٩])/;

// :129, :138-139
const CLOCK = '[0-9۰-۹٠-٩]{1,2}:[0-9۰-۹٠-٩]{2}(?::[0-9۰-۹٠-٩]{2})?';
const CLOCK_AFTER = new RegExp(`^\\s*(${CLOCK})(?![0-9۰-۹٠-٩:])`);
const CLOCK_BEFORE = new RegExp(`(?<![0-9۰-۹٠-٩:])(${CLOCK})\\s*$`);

// :148-157 — the only use of PRINTED_AT (single) and CLOCK_BEFORE
function printedStampIn(rawBody: string): string {
  const date = PRINTED_AT.exec(rawBody);
  if (!date) return '';
  const stamp = date[0].trim();
  if (stamp.includes(':')) return stamp;
  const after = date.index + date[0].length;
  const time =
    CLOCK_AFTER.exec(rawBody.slice(after))?.[1] ?? CLOCK_BEFORE.exec(rawBody.slice(0, date.index))?.[1];
  return time === undefined ? stamp : `${stamp} ${time}`;
}

// :424-425, :435 — counted globally, built from .source, so they follow whatever the two become
const PRINTED_AT_ALL = new RegExp(PRINTED_AT.source, 'g');
const RUN_TOGETHER_ALL = new RegExp(RUN_TOGETHER.source, 'g');
  const stamps = (text.match(PRINTED_AT_ALL)?.length ?? 0) + (text.match(RUN_TOGETHER_ALL)?.length ?? 0);
```

```ts
// :352
const OTP_CODE = /(?<!\p{L})(?:رمز(?!\p{L})|کد ?(?:تایید|تأیید|تائید|یک ?بار|فعال ?سازی|ورود|پویا))/gu;

// :380 — as a yes/no
  return OTP.test(text) || (text.search(OTP_CODE) >= 0 && statedBalance(text) == null);
// :407-408 — only the END of each match is used
  for (const m of text.matchAll(OTP_CODE)) {
    const from = m.index! + m[0].length;
```

```ts
// :500, :617
const SLASHED_REF = /[0-9۰-۹٠-٩]{2,}\/[0-9۰-۹٠-٩]{4,}/;
  return SLASHED_REF.exec(text)?.[0] ?? '';

// :507-519
/**
 * Some banks mask a tail ("۱۲۳****"); Saman prints the number in full ("829-800-1092308-1").
 * Tried only where a run of digits starts (Sms.kt's MASKED): the same first match, linear not quadratic.
 */
const MASKED = /(?<![0-9۰-۹٠-٩])[0-9۰-۹٠-٩]*\*{2,}[0-9۰-۹٠-٩]*/;
const DASHED = /[0-9۰-۹٠-٩]{2,}(?:-[0-9۰-۹٠-٩]+)+/g;
function accountIn(text: string): string {
  const masked = MASKED.exec(text);
  if (masked) return masked[0];
  // A Jalali date written 1405-05-01 is digits and dashes too; an account number is longer.
  for (const m of text.matchAll(DASHED)) if (digitsIn(m[0]) >= 10) return m[0];
  return '';
}
```

```ts
// :522
const ACCOUNT_NO = /(?<!\p{L})حساب[^\S\n]*:?[^\S\n]*([0-9۰-۹٠-٩*][0-9۰-۹٠-٩*.\-]*)/gu;
// :530-543 — uses m[1], m.index (start of «حساب») and m.index + m[0].length (end)
export function accountNoIn(text: string): string {
  const found = new Set<string>();
  for (const m of text.matchAll(ACCOUNT_NO)) {
    const run = m[1];
    const end = m.index! + m[0].length;
    const label = text.slice(Math.max(0, m.index! - 8), m.index!);
    ...

// :548, :550
const CARD_NO = /(?<!\p{L})کارت[^\S\n]*:?[^\S\n]*([0-9۰-۹٠-٩][0-9۰-۹٠-٩*\-]*)/gu;
const OTHER_SIDE = /(?<!\p{L})(?:به|از)\s*$/u;
// :558-569 — uses m[1], m.index (start of «کارت»), m.index + m[0].length
export function cardNoIn(text: string): string {
  const found = new Set<string>();
  for (const m of text.matchAll(CARD_NO)) {
    const digits = digitsOf(m[1]);
    const end = m.index! + m[0].length;
    const tail = text.slice(end, end + 8).split('\n')[0];
    if (OTHER_SIDE.test(text.slice(0, m.index!)) || digits.length < 4) continue;
    ...
```

`grep -n '(?<[!=]' pwa/src/*.ts pwa/src/*.tsx` finds exactly these nine lines (85, 118, 126, 139,
352, 511, 522, 548, 550) and nothing else in `pwa/src`.

### `app/src/main/java/com/doxigo/muchtoman/Sms.kt` — the two unguarded twins

```kotlin
// :709-714
 * The lookbehind starts a try only where a run of digits starts: the leftmost match is the same,
 * but without it every digit of a long run rescanned the rest of the run — 4 s on a 39k-char body.
 */
private val MASKED = Regex("(?<![0-9۰-۹٠-٩])[0-9۰-۹٠-٩]*\\*{2,}[0-9۰-۹٠-٩]*")
private val DASHED = Regex("[0-9۰-۹٠-٩]{2,}(?:-[0-9۰-۹٠-٩]+)+")

// :911-912
/** خاورمیانه prints its reference as "020/000016703" with no label at all. */
private val SLASHED_REF = Regex("[0-9۰-۹٠-٩]{2,}/[0-9۰-۹٠-٩]{4,}")
```

Kotlin runs on the JVM, where lookbehind is fine. **Leave every other Kotlin regex alone** —
only these two lines change on the Android side.

### `app/src/test/java/com/doxigo/muchtoman/MoneyTest.kt:1032-1039` — the timing test to model on

```kotlin
    // The masked-tail search once rescanned a run of digits from each of its digits: 4 s on this.
    // The پیگیری keeps refIn off its own slashed-reference fallback, which is still quadratic.
    @Test(timeout = 2_000)
    fun `a long run of digits does not stall the masked-account search`() {
        val body = "برداشت مبلغ 500,000 ریال\nشماره پیگیری 771205\n" + "1".repeat(39_000) + "\n12****34"
        val m = parseBankSms("Bank Mellat", body, 1L)!!
        assertEquals("12****34", m.mask)
    }
```

The trailing `12****34` makes `MASKED` match (so `DASHED` never runs) and «پیگیری» returns
before `SLASHED_REF` — the two crutches the new test drops.

### `pwa/src/settings.tsx` — the backup page (for the CompressionStream note)

`pwa/src/backup.ts:124-128` calls `new CompressionStream('gzip')` / `new DecompressionStream('gzip')`
with no feature check. Where neither exists today:

- export: the `ReferenceError` lands in `runExport`'s catch (`settings.tsx:533-536`) and she reads
  «پشتیبان ساخته نشد. دوباره امتحان کن.» — trying again cannot help;
- restore: the `ReferenceError` is thrown inside `openBackup`'s `try` (`backup.ts:189-196`), which
  answers every failure `WRONG_PASSPHRASE_OR_CORRUPT`, so a correct passphrase reads
  «رمز اشتباهه یا فایل خرابه.» — a wrong diagnosis.

```tsx
// settings.tsx:539-563
function BackupPage() {
  useData();
  const ui = useBackupUi();
  const picker = useRef<HTMLInputElement>(null);
  const last = pref('lastBackupAt');
  return (
    <SettingsPage title="پشتیبان‌گیری">
      ...
      <Band>
        <DoorRow title="پشتیبان‌گیری از همه‌چیز" subtitle="پیامک‌ها، دسته‌بندی‌ها، موجودی‌ها و تنظیمات، توی یک فایل رمزدار"
          glyph="STACK" enabled={!ui.working} onClick={() => openSheet('exportPass')} />
        {/* The file first, so the passphrase is only ever asked about a file that exists. */}
        <DoorRow title="بازگردانی از پشتیبان" subtitle="همه‌چیز از روی فایل پشتیبان برمی‌گرده"
          glyph="TRAY" enabled={!ui.working} onClick={() => picker.current?.click()} />
      </Band>
      <input ref={picker} type="file" hidden onChange={(e) => { ... }} />
      <Note>فایل پشتیبان رمز داره. فایل و رمزش رو جای مطمئن نگه دار. این تاریخ فقط زمان ساخت فایله؛ برنامه نمی‌تونه موندن فایل در محل ذخیره رو بررسی کنه.</Note>
```

These two rows are the only doors to the `exportPass` and `restore` sheets. The page already
shows a conditional note in the same shape at `settings.tsx:501`:
`{available === false && <Note>برای فعال کردن قفل، اول توی تنظیمات گوشی یه قفل صفحه بذار.</Note>}`.

### Conventions that apply

- The golden corpus `app/src/test/resources/sms/*.json` pins **both** parsers: real bank messages
  kept verbatim. `pwa/test/paste.test.ts` reads it for the PWA, `SmsCorpusTest.kt` for Android.
  **Never edit a corpus message or expectation to make a test pass.**
- Comments explain *why*, in the file's own voice (see the excerpts). TypeScript has no linter
  or formatter — match the hand style around you (2-space indent, single quotes, semicolons).
- Do not write the characters `(?<!` or `(?<=` anywhere under `pwa/src`, **comments included** —
  the guard test in step 1 is a plain substring search. Say "lookbehind" in words.
- Persian copy: casual register, Persian digits, plain; a line earns its place only by telling
  her something she must act on. Copy the Persian strings in this plan byte for byte (they
  contain a zero-width non-joiner in «پشتیبان‌گیری»).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| PWA install (fresh worktree) | `cd pwa && pnpm install --frozen-lockfile` | exit 0 |
| One PWA test file | `cd pwa && pnpm exec vitest run test/lookbehind.test.ts` | all pass |
| PWA corpus test | `cd pwa && pnpm exec vitest run test/paste.test.ts` | all pass |
| PWA full gate | `cd pwa && pnpm run check` | exit 0 (typecheck, all tests, build) |
| Android env (every shell) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| One Android test class | `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.MoneyTest'` | BUILD SUCCESSFUL |
| Android full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | BUILD SUCCESSFUL |

Gradle's configuration cache can report a stale `UP-TO-DATE` for a test task: if a test task
says `UP-TO-DATE` after you changed code, add `--rerun` to it. A fresh worktree has no
`local.properties`; the `ANDROID_HOME` export above replaces it.

## Scope

**In scope** (the only files you should modify):
- `pwa/src/paste.ts`
- `pwa/src/settings.tsx` (step 6 only: one constant and the `BackupPage` function)
- `pwa/test/lookbehind.test.ts` (create)
- `app/src/main/java/com/doxigo/muchtoman/Sms.kt` (two lines and the comment above the first)
- `app/src/test/java/com/doxigo/muchtoman/MoneyTest.kt` (one new test, one comment line)

**Out of scope** (do NOT touch):
- `app/src/test/resources/sms/*.json` — the corpus is the judge, not a party.
- `pwa/test/paste.test.ts`, `pwa/test/mirror.test.ts` — another plan edits them.
- `pwa/src/sms.ts` — it only reads `RUN_TOGETHER`'s groups 1–3, which keep their numbers.
- `pwa/src/backup.ts` — no gzip fallback here (see Maintenance notes).
- Every Kotlin regex other than `DASHED` and `SLASHED_REF`.
- A body-length cap on what the parser reads: deferred on purpose, it would change parser
  behaviour (see Maintenance notes).

## Git workflow

- Commit on the worktree's current branch; one commit for this plan.
- Subject in English only, lower-case area prefix then a plain description, e.g. (from the log)
  `ledger: let a hand-entered transaction name any bank account, app and PWA`. Suggested:
  `parser: drop regex lookbehind from the PWA and bound two digit-run searches, app and PWA`.
- No `Co-Authored-By` or any other trailer. Do not write about users as "she/her" in the commit
  message.
- Do NOT push, merge, or touch `main`.

## Steps

### Step 1: Write the guard and timing tests first (they must fail)

Create `pwa/test/lookbehind.test.ts`:

```ts
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parsePasted } from '../src/paste';

const ROOT = join(__dirname, '..');

/**
 * Safari had no regex lookbehind before 16.4, and a regex an engine cannot read is a syntax error
 * for the whole bundle — a white page on the iPhone 6s, 7 and first SE, which stop at iOS 15.
 * Nothing the browser loads may carry one.
 */
describe('what the oldest iPhone can read', () => {
  it('ships no regex lookbehind', () => {
    const files = [
      ...readdirSync(join(ROOT, 'src'), { recursive: true }).map(String)
        .filter((f) => /\.tsx?$/.test(f)).map((f) => join('src', f)),
      'sw-template.js', 'index.html',
    ];
    // The path is right: a wrong one would read nothing and pass.
    expect(files.length).toBeGreaterThan(30);
    const found = files.filter((f) => /\(\?<[!=]/.test(readFileSync(join(ROOT, f), 'utf8')));
    expect(found, 'a lookbehind, in code or in a comment — say it another way').toEqual([]);
  });

  it('reads a long run of digits in linear time', () => {
    // No mask and no «پیگیری»: the dashed-account and slashed-reference searches both run.
    const body = `برداشت مبلغ 500,000 ریال\n${'1'.repeat(39_000)}`;
    const started = performance.now();
    const got = parsePasted(body);
    expect(performance.now() - started).toBeLessThan(2_000);
    expect(got.mask).toBe('');
    expect(got.refNo).toBe('');
  });
});
```

**Verify**: `cd pwa && pnpm exec vitest run test/lookbehind.test.ts` → the first test **fails**
listing `src/paste.ts`. The second sits right on its bound before the fix — the two old searches
took about 2,000 ms on this Mac when this plan was written, against 0.4 ms for the rewrites — so
it may fail on time or scrape through; note which in your report and carry on either way. If it
fails on `mask` or `refNo` instead — a value other than `''` — that is a STOP condition.

### Step 2: Add the two "what stands in front" helpers to `pwa/src/paste.ts`

Insert directly above the `GROUPED` / `SIGNED` doc comment (above current line 75):

```ts
/**
 * The character in front of a place in the text — two code units, so one outside the BMP arrives
 * whole. This is what a regex lookbehind used to ask. Safari had none before 16.4, and a regex it
 * cannot read stops the whole bundle from loading, so nothing in this file uses one
 * (test/lookbehind.test.ts keeps it that way): a regex either takes the character in front as part
 * of its match, or the caller asks here.
 */
const before = (text: string, at: number): string => text.slice(Math.max(0, at - 2), at);
const LETTER_LAST = /\p{L}$/u;
/** What may stand in front of a signed figure: nothing, whitespace, a colon or a letter. */
const SIGN_LEAD = /[\s:\p{L}]$/u;
```

**Verify**: `cd pwa && pnpm exec tsc --noEmit` → exit 0 (unused constants are not an error in this
config; if `tsc` complains they are unused, continue — steps 3–4 use them).

### Step 3: Rewrite the four regexes whose callers must change

Each rewrite below was reasoned to give the same matches as the original on every input, not only
on the corpus, and then compared against the original in Node (which has lookbehind) over every
corpus body, slices of them, and 60,000 generated strings of digits, signs, separators, Persian
letters and the labels themselves: no difference in any of the thirteen comparisons. The reason
for each is given so you can tell a correct edit from a near miss. Make exactly these edits —
character for character.

**3a. `SIGNED`** — drop the lookbehind, ask in the loop. (Inside a signed figure every character
is a digit, a separator or a sign, so a match refused here can never hide a good one.)

```ts
const SIGNED = new RegExp(`(?:([+-])(${GROUPED})|(${GROUPED})([+-]))(?![0-9۰-۹٠-٩])`, 'gu');
```

and as the first statement inside `signedAmount`'s `for` loop:

```ts
    if (m.index! > 0 && !SIGN_LEAD.test(before(text, m.index!))) continue;
```

Groups 1–4 and every `m.index` use below it stay exactly as they are.

**3b. `PRINTED_AT`** — take the character in front as part of the match, and capture the stamp as
group 1:

```ts
const PRINTED_AT =
  /(?:^|[^0-9۰-۹٠-٩.\/\-_])([0-9۰-۹٠-٩]{1,4}[/.][0-9۰-۹٠-٩]{1,2}(?:[/.][0-9۰-۹٠-٩]{1,2})?(?:[ _-]{1,3}[0-9۰-۹٠-٩]{1,2}:[0-9۰-۹٠-٩]{2}(?::[0-9۰-۹٠-٩]{2})?)?)(?![0-9۰-۹٠-٩])/;
```

(The body between the new `(` … `)` is the old pattern unchanged; only the lookbehind became
`(?:^|[^…])` and the group was added. Do not add the `m` flag — `^` must mean the start of the
text only.) Then `printedStampIn` reads group 1 and works out where the stamp itself starts:

```ts
function printedStampIn(rawBody: string): string {
  const date = PRINTED_AT.exec(rawBody);
  if (!date) return '';
  const stamp = date[1].trim();
  if (stamp.includes(':')) return stamp;
  const after = date.index + date[0].length;
  // The match may begin one character early, on whatever stands in front of the stamp.
  const from = after - date[1].length;
  const time =
    CLOCK_AFTER.exec(rawBody.slice(after))?.[1] ?? CLOCK_BEFORE.exec(rawBody.slice(0, from))?.[1];
  return time === undefined ? stamp : `${stamp} ${time}`;
}
```

`PRINTED_AT_ALL` (line ~424) is built from `PRINTED_AT.source` and only counted — leave it. (A
stamp ends in a digit, and a digit may not stand in front of the next stamp, so two stamps never
compete for one character and the count is unchanged.)

**3c. `MASKED`, `DASHED`** — same technique, group 1 is the number. This also makes `DASHED`
linear: a try that starts inside a run of digits now fails on its first character.

```ts
/**
 * Some banks mask a tail ("۱۲۳****"); Saman prints the number in full ("829-800-1092308-1").
 * Each is tried only where a run of digits starts, by taking the character in front of it (Sms.kt
 * guards the same two): the same matches, linear where every digit of a long run once rescanned
 * the rest of it.
 */
const MASKED = /(?:^|[^0-9۰-۹٠-٩])([0-9۰-۹٠-٩]*\*{2,}[0-9۰-۹٠-٩]*)/;
const DASHED = /(?:^|[^0-9۰-۹٠-٩])([0-9۰-۹٠-٩]{2,}(?:-[0-9۰-۹٠-٩]+)+)/g;
function accountIn(text: string): string {
  const masked = MASKED.exec(text);
  if (masked) return masked[1];
  // A Jalali date written 1405-05-01 is digits and dashes too; an account number is longer.
  for (const m of text.matchAll(DASHED)) if (digitsIn(m[1]) >= 10) return m[1];
  return '';
}
```

**3d. `SLASHED_REF`** — the same, for the same reason:

```ts
/** خاورمیانه prints its reference as "020/000016703" with no label at all. Tried only where a run of digits starts. */
const SLASHED_REF = /(?:^|[^0-9۰-۹٠-٩])([0-9۰-۹٠-٩]{2,}\/[0-9۰-۹٠-٩]{4,})/;
```

and in `refIn`: `return SLASHED_REF.exec(text)?.[1] ?? '';`

**Verify**: `cd pwa && pnpm exec vitest run test/paste.test.ts` → all pass (the corpus agrees on
`mask`, `refNo`, `printedAt`, `amountRial`, `direction`).

### Step 4: Rewrite the five regexes whose callers barely change

**4a. `RUN_TOGETHER`** — non-capturing prefix, so groups 1–3 keep their numbers and
`pwa/src/sms.ts` needs no edit:

```ts
export const RUN_TOGETHER =
  /(?:^|[^0-9۰-۹٠-٩./\-_])([0-9۰-۹٠-٩]{2})([0-9۰-۹٠-٩]{2})[ _-]{1,3}([0-9۰-۹٠-٩]{1,2}:[0-9۰-۹٠-٩]{2}(?::[0-9۰-۹٠-٩]{2})?)(?![0-9۰-۹٠-٩])/;
```

**4b. `CLOCK_BEFORE`** — group 1 is still the clock:

```ts
const CLOCK_BEFORE = new RegExp(`(?:^|[^0-9۰-۹٠-٩:])(${CLOCK})\\s*$`);
```

**4c. `OTP_CODE`** — a consumed non-letter in front. Both callers use only whether it matches and
where each match *ends*, and the end does not move, so neither caller changes. (A match ends in a
letter and the next must follow a non-letter, so two never compete for one character.)

```ts
const OTP_CODE = /(?:^|\P{L})(?:رمز(?!\p{L})|کد ?(?:تایید|تأیید|تائید|یک ?بار|فعال ?سازی|ورود|پویا))/gu;
```

**4d. `ACCOUNT_NO`, `CARD_NO`** — drop the lookbehind and ask in the loop. (Not the consumed
prefix here: a figure may run straight into the next «حساب», and the prefix would have been eaten
by the match before it. The loop check has no such gap — nothing after «حساب»/«کارت» in a match can
start another.)

```ts
const ACCOUNT_NO = /حساب[^\S\n]*:?[^\S\n]*([0-9۰-۹٠-٩*][0-9۰-۹٠-٩*.\-]*)/gu;
const CARD_NO = /کارت[^\S\n]*:?[^\S\n]*([0-9۰-۹٠-٩][0-9۰-۹٠-٩*\-]*)/gu;
```

and as the first statement inside the `for` loop of **both** `accountNoIn` and `cardNoIn`:

```ts
    // A letter in front makes the label part of another word: «صورتحساب» is not a «حساب».
    if (LETTER_LAST.test(before(text, m.index!))) continue;
```

Use that same comment line in both loops. Everything else in both loops stays as it is —
`m.index` is still the start of the label.

**4e. `OTHER_SIDE`**:

```ts
const OTHER_SIDE = /(?:^|\P{L})(?:به|از)\s*$/u;
```

Finally fix the doc comment above `SIGNED` if it mentions a lookbehind, and re-read every comment
you touched: none may contain the lookbehind syntax itself.

**Verify**:
- `grep -n '(?<[!=]' pwa/src/*.ts pwa/src/*.tsx` → no output.
- `cd pwa && pnpm exec vitest run test/lookbehind.test.ts test/paste.test.ts` → all pass,
  including the time bound.

### Step 5: Kotlin — guard `DASHED` and `SLASHED_REF`, with a test that fails first

Add to `MoneyTest.kt` directly under the existing
`` `a long run of digits does not stall the masked-account search` `` test:

```kotlin
    // The dashed-account search and the slashed-reference fallback rescanned a run of digits the
    // same way. No mask and no پیگیری here, so both run.
    @Test(timeout = 2_000)
    fun `a long run of digits stalls neither the dashed account nor the slashed reference`() {
        val body = "برداشت مبلغ 500,000 ریال\n" + "1".repeat(39_000)
        val m = parseBankSms("Bank Mellat", body, 1L)!!
        assertEquals("", m.mask)
        assertEquals("", m.refNo)
    }
```

Run it before the fix: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.MoneyTest'`
→ expected: this one test fails with a timeout (`TestTimedOutException`). If it passes, note that
in your report and carry on. If it fails for any other reason (a null reading, a non-empty
`mask`/`refNo`), STOP.

Then in `Sms.kt` change the two lines to:

```kotlin
private val DASHED = Regex("(?<![0-9۰-۹٠-٩])[0-9۰-۹٠-٩]{2,}(?:-[0-9۰-۹٠-٩]+)+")
```
```kotlin
private val SLASHED_REF = Regex("(?<![0-9۰-۹٠-٩])[0-9۰-۹٠-٩]{2,}/[0-9۰-۹٠-٩]{4,}")
```

Reword the KDoc sentence above `MASKED` so it covers both ("The lookbehinds start a try only
where a run of digits starts…"), add "Tried only where a run of digits starts, like [MASKED]." to
`SLASHED_REF`'s KDoc, and in `MoneyTest.kt` delete the now-false second comment line above the
*existing* test («The پیگیری keeps refIn off its own slashed-reference fallback, which is still
quadratic.»).

**Verify**: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.MoneyTest' --tests 'com.doxigo.muchtoman.SmsCorpusTest'`
→ BUILD SUCCESSFUL, both timing tests and every corpus case pass.

### Step 6: The backup page says so where the browser cannot gzip

In `pwa/src/settings.tsx`, add above `BackupPage`'s doc comment:

```tsx
/** Safari before 16.4 has neither stream, and without them a backup can be neither made nor read. */
const canBackup = typeof CompressionStream === 'function' && typeof DecompressionStream === 'function';
```

In `BackupPage`: change both `DoorRow`s to `enabled={canBackup && !ui.working}`, and replace the
one `<Note>فایل پشتیبان رمز داره. …</Note>` line with:

```tsx
      {canBackup
        ? <Note>فایل پشتیبان رمز داره. فایل و رمزش رو جای مطمئن نگه دار. این تاریخ فقط زمان ساخت فایله؛ برنامه نمی‌تونه موندن فایل در محل ذخیره رو بررسی کنه.</Note>
        : <Note>این مرورگر پشتیبان‌گیری رو نداره. روی آیفون، iOS ۱۶٫۴ یا بالاتر لازمه.</Note>}
```

(The first string is the existing one, unchanged — move it, do not retype it.) Nothing else on
the page changes: the reminder card and «خروجی اکسل» stay, and CSV export works everywhere.

**Verify**:
- `grep -c 'این مرورگر پشتیبان‌گیری رو نداره' pwa/src/settings.tsx` → `1`
- `grep -c 'canBackup' pwa/src/settings.tsx` → `4`
- `cd pwa && pnpm exec tsc --noEmit` → exit 0

### Step 7: Full gates

**Verify**:
- `cd pwa && pnpm run check` → exit 0.
- `grep -c '(?<[!=]' pwa/dist/assets/*.js` → `0` for every file (the built bundle carries none).
- `PLAYWRIGHT_CHROMIUM_EXECUTABLE="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" pnpm run test:browser`
  (in `pwa/`) → all pass.
- `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → BUILD SUCCESSFUL.

## Test plan

- `pwa/test/lookbehind.test.ts` (new): the source-scan guard, and the linear-time bound on
  `parsePasted` with `mask`/`refNo` asserted empty.
- `MoneyTest.kt`: one new timing test, modelled on the existing one directly above it.
- The regression net is what already exists and must stay green **unedited**:
  `pwa/test/paste.test.ts` (the corpus, the one-time-code cases, `severalMessages`, the card and
  account cases), `pwa/test/derived.test.ts`, `SmsCorpusTest.kt`, `MoneyTest.kt`.
- No new corpus cases: this plan changes no reading.

## Done criteria

- [ ] `grep -rn '(?<[!=]' pwa/src pwa/sw-template.js pwa/index.html` returns nothing
- [ ] `cd pwa && pnpm run check` exits 0, with `test/lookbehind.test.ts` present and passing
- [ ] `grep -c '(?<[!=]' pwa/dist/assets/*.js` prints only zeros
- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` is BUILD SUCCESSFUL, including
      `` `a long run of digits stalls neither the dashed account nor the slashed reference` ``
- [ ] `git diff --stat 3e6246e -- app/src/test/resources/sms pwa/test/paste.test.ts pwa/src/sms.ts pwa/src/backup.ts` is empty
- [ ] `git status --short` lists only the five in-scope files
- [ ] `plans/README.md` status row updated (unless your reviewer maintains the index)

## STOP conditions

Stop and report back (do not improvise) if:

- Any excerpt in "Current state" does not match the live file.
- **Any corpus case changes its reading** on either platform after a step — report the case id
  and field. Do not adjust a regex beyond what this plan prescribes to chase it, and never edit
  the corpus or `paste.test.ts`.
- A timing test still exceeds its bound after its step's fix. Report the measured time; some
  other search is quadratic and finding it is not this plan.
- A new test fails on a value (`mask`, `refNo`, a null reading) rather than on time.
- `grep '(?<[!=]'` finds a lookbehind in `pwa/` outside `paste.ts` (a file added since this plan
  was written).
- A fix appears to need `pwa/src/sms.ts`, `pwa/src/backup.ts`, or any Kotlin regex other than
  the two named.

## Maintenance notes

- **New parser regexes in `pwa/src` must not use lookbehind**; the guard test fails the build if
  one appears, comments included. Use the `before()` helper or a consumed prefix. Choose the
  consumed prefix only when a match can never end on the character the next match needs in front
  of it; otherwise check in the loop (see 4d for the case that decides it).
- The Kotlin side keeps its lookbehinds — they are correct there. The two parsers now differ in
  technique, not in result; the corpus is what holds them together. A reviewer should read each
  of the nine rewrites against the reasons given in steps 3–4, not only trust green tests.
- **Not proven on a device.** This removes the known parse-time failure; whether the PWA then
  runs cleanly on iOS 15.4–16.3 needs one launch on such an iPhone or simulator. Known remaining
  gaps there: `color-mix()` in the CSS (Safari 16.2+, degrades to missing tints rather than
  failing) and backup (step 6 says so in words).
- **Deferred — a gzip fallback for backup on iOS < 16.4.** Phones that cannot update past iOS 15
  now have no backup at all, only the CSV. A real fix is a small inflate/deflate in script (or a
  dependency), with the format unchanged; it was left out because it is new code on the restore
  path and deserves its own plan and tests.
- **Deferred — a cap on the body length the parser reads.** It would bound every search at once,
  but it changes what a very long message parses to, so it needs corpus evidence first.
- `DESIGN.md`/`data.ts` state the floor as iOS 15.4; if that floor is ever raised to 16.4 the
  rewrites can stay — they cost nothing.
