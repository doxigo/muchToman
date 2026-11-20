# Plan 023: Four things kept equal by memory are kept equal by a test

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 3e6246e..HEAD -- pwa/test/mirror.test.ts pwa/test/paste.test.ts app/src/test/java/com/doxigo/muchtoman/SmsCorpusTest.kt sync/src/index.ts`
> If any of these changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (tests only; no production file changes)
- **Depends on**: none
- **Category**: tests
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

The app (`app/`, Kotlin) and the PWA (`pwa/`, TypeScript) are two implementations of one
product, and a sync server (`sync/`) sits between them. Three tests already hold the two sides
together — the golden SMS corpus, `pwa/test/mirror.test.ts`, and the server's own suite — but
four seams still rest on someone remembering:

1. Constants written twice. `SYNC_READ_VERSION` is "kept equal" by a comment; when it was not
   bumped once, older clients lost records for five days. Six more money and sync constants are
   duplicated the same way and pinned by nothing.
2. Record kinds. A client that sends a kind the server does not list gets a 400 for the whole
   batch — all sync stops from that phone. The server spells its list twice; the clients spell
   kinds as scattered literals.
3. The corpus keys. The Kotlin corpus test fails on an unknown key (a typo asserts nothing);
   the PWA's reads a hand-listed set and stays green, so a key added on the Kotlin side goes
   silently unasserted in TypeScript.
4. Banks nobody has a real message for. Eight banks the README lists as read have a sender id
   and no corpus case. That gap is invisible; it should be an explicit list that only shrinks.

This plan adds tests for all four and changes no production code.

## Current state

All excerpts were read at `3e6246e`.

### (a) `pwa/test/mirror.test.ts` — how it reads Kotlin, and what it pins

The file reads every `.kt` under `app/src/main/java/com/doxigo/muchtoman` as text.
`ktConst(name)` returns the value of `const val name` (any visibility, underscores and a
trailing `L` handled, `48L * 60 * 60 * 1000` evaluated) and **throws if the constant is gone**.
`kt('Sync.kt')` returns that file's source with comments blanked and string literals intact.
`tsSource('sync.ts')` returns a `pwa/src` file's raw text.

```ts
// :4-16 — imports from ../src (excerpt)
import { BUDGET_CLOSE_PERCENT, BUDGET_NEAR_PERCENT } from '../src/budget';
import { INSTALLMENT_REMINDER_DAYS, INSTALLMENT_REMINDER_DEFAULT, remindAwake } from '../src/installments';
import { DAY_MS, TEHRAN_OFFSET_MS } from '../src/jalali';

// :424-434
describe('the constants both sides decide by', () => {
  it('are the same numbers', () => {
    const pwa: Record<string, unknown> = {
      BUDGET_NEAR_PERCENT, BUDGET_CLOSE_PERCENT, QUIET_AFTER_DAYS,
      TRANSFER_FEE_MAX_RIAL, TRANSFER_WINDOW_MS, SLOW_RAIL_WINDOW_MS,
      DUPLICATE_WINDOW_MS, DUPLICATE_REFNO_WINDOW_MS, DUPLICATE_BALANCE_WINDOW_MS,
      INSTALLMENT_REMINDER_DEFAULT, TEHRAN_OFFSET_MS, DAY_MS,
    };
    for (const [name, value] of Object.entries(pwa)) expect(value, name).toBe(ktConst(name));
    expect(INSTALLMENT_REMINDER_DAYS).toEqual(ktList('Installments.kt', 'val INSTALLMENT_REMINDER_DAYS'));
  });

  it('and the ones the PWA keeps private, read off its source', () => {
    const tsConst = (file: string, name: string): string => {
      const m = new RegExp(`\\bconst ${name}\\s*=\\s*([^;\\n]+)`).exec(tsSource(file));
      if (!m) throw new Error(`const ${name} is not in ${file} any more`);
      return m[1].trim();
    };
    expect(Number(tsConst('quips.ts', 'QUIET_GIVE_UP_DAYS')), 'QUIET_GIVE_UP_DAYS').toBe(ktConst('QUIET_GIVE_UP_DAYS'));
```

The constants to add, each confirmed on both sides with the same name and unit:

| Constant | Kotlin | PWA (exported) |
|---|---|---|
| `SYNC_READ_VERSION` | `Sync.kt:68` `const val SYNC_READ_VERSION = 1` | `sync.ts:61` `= 1` |
| `MAX_SYNC_STAMP_SKEW_MS` | `Sync.kt:340` `internal const val … = 48L * 60 * 60 * 1000` | `sync.ts:40` `= 48 * 60 * 60 * 1000` |
| `MAX_PLAUSIBLE_RIAL` | `Derived.kt:298` `= 100_000_000_000_000L` | `sms.ts:141` `= 100_000_000_000_000` |
| `AT_FUTURE_SLACK_MS` | `Ledger.kt:697` `= 48 * 60 * 60 * 1000L` | `sms.ts:145` `= 48 * 60 * 60 * 1000` |
| `MAX_INSTALLMENTS` | `Installments.kt:40` `= 120` | `installments.ts:44` `= 120` |
| `WORTH_IT_PER_WEEK` | `Goals.kt:345` `= 2` | `goals.ts:142` `= 2` |
| `BACKUP_MIN_PASSPHRASE` | `Export.kt:45` | `backup.ts:32` |
| `BACKUP_KDF_ITERATIONS` | `Export.kt:44` `= 600_000` | `backup.ts:31` `= 600_000` |

One more is private on both sides and in different units, so it is compared by arithmetic:
`Data.kt:606` `private const val SPOOF_FLOOR_TOMAN = 100_000_000.0 // 1e9 Rial` and
`pwa/src/derived.ts:200` `const SPOOF_FLOOR_RIAL = 1_000_000_000;` — the same threshold, ×10.

Other test files already import these modules statically with no IndexedDB shim
(`paste.test.ts:6` imports `../src/sync`; `installments.test.ts:16` imports `../src/sms`;
`backup.test.ts` imports `../src/backup`), so importing them in `mirror.test.ts` is safe.

### (b) Record kinds — the server's two spellings and the clients' literals

```ts
// sync/src/index.ts:197 (inside `interface PushRecord`)
  kind: 'legacy' | 'member' | 'transaction' | 'category' | 'asset' | 'note' | 'goal' | 'exclusion';
// sync/src/index.ts:223
    if (!['legacy', 'member', 'transaction', 'category', 'asset', 'note', 'goal', 'exclusion'].includes(kind)) {
      throw new SyncError('invalid_kind', 400);
```

Android sends through one function, `wireRecord(session, id, kind, …)` (`Sync.kt:1115`), whose
third argument is the kind. It has twelve call sites and **every one passes a string literal**,
on one line or three:

```kotlin
// Sync.kt:1229
            wireRecord(session, id, "transaction", session.member, updatedAt, payload),
// Sync.kt:1169-1172
            wireRecord(
                session,
                memberRecordId(member.id),
                "member",
```

The kinds it sends: `member`, `transaction`, `asset`, `goal`, `exclusion`, `category`, `note`
(seven; `legacy` is only what an unlabelled incoming record defaults to).

The PWA builds outgoing records as objects — always `kind: '<kind>', owner` — and seals them at
one variable call (`sync.ts:819`, `wireRecord(session, d.id, d.kind, …)`); two more calls pass a
literal:

```ts
// pwa/src/sync.ts:680
    out.push({ id, kind: 'transaction', owner: me, updatedAt, payload, publication: { … } });
// pwa/src/sync.ts:1550
    const tombstone = await wireRecord(active, recordId, 'member', memberId, stamp, tombstonePayload(recordId), true);
```

The same seven kinds. `pwa/` tests already read `../app` as text; reading `../sync/src/index.ts`
the same way needs nothing new (CI's `pwa` job checks out the whole repository).

### (c) `pwa/test/paste.test.ts:41-66` — keys asserted by hand, unknown keys ignored

```ts
  it('agrees with the android parser on every field it can see', () => {
    let checked = 0;
    for (const c of cases()) {
      // A declined case is declined for who sent it, and this side never sees a sender.
      if (c.expect === null) continue;
      const got = parsePasted(c.body.join('\n'));
      const where = `${c.id} — ${c.why}`;

      if ('amountRial' in c.expect) { … }
      if ('direction' in c.expect) { … }
      if ('balanceRial' in c.expect) { … }
      for (const key of ['printedAt', 'mask', 'instrument', 'merchant', 'refNo', 'channel', 'unitPrinted'] as const) { … }
      if ('account' in c.expect) expect(got.accountNo, `${where} (account)`).toBe(c.expect.account);
      if ('feeRial' in c.expect) expect(got.feeRial, `${where} (feeRial)`).toBe(c.expect.feeRial ?? null);
      if ('inferred' in c.expect) expect(got.inferred, `${where} (inferred)`).toBe(c.expect.inferred);
      checked++;
    }
```

Thirteen keys asserted; `bank` is skipped on purpose (a paste has no sender). The Kotlin side:

```kotlin
// app/src/test/java/com/doxigo/muchtoman/SmsCorpusTest.kt:84-85
        val unknown = expect.keys - EXPECT_KEYS
        assertTrue("$where asserts nothing: unknown keys $unknown", unknown.isEmpty())
// :144-148
/** Fields a case may assert. Anything else is a typo, and typos assert nothing silently. */
private val EXPECT_KEYS = setOf(
    "bank", "mask", "direction", "amountRial", "balanceRial", "inferred",
    "feeRial", "merchant", "refNo", "printedAt", "channel", "instrument", "unitPrinted", "account",
)
```

Fourteen keys: the PWA's thirteen plus `bank`. They agree today; nothing keeps them agreeing.

### (d) Banks with a sender and no corpus case

`enum class Bank(val fa: String, val numbers: List<String>)` (`Sms.kt:25`) has eighteen entries
with a non-empty `numbers`. The corpus (`app/src/test/resources/sms/*.json`, 59 cases) has
senders that resolve to ten of them: BLU, SAMAN, REFAH, PASARGAD, EGHTESAD_NOVIN, KHAVARMIANEH,
SADERAT, RESALAT, MELLAT, MELLI. The eight with a sender and no case:
**PARSIAN, DEY, TEJARAT, SEPAH, KESHAVARZI, POST_BANK, MASKAN, MEHR_IRAN.**

`SmsCorpusTest.kt` already has what the tripwire needs: `corpusFiles()`, `load(file)`, and a
`CorpusCase` with `sender`, `extra` (`Map<String, List<String>>`) and `expect` (`JsonObject?`,
null for a declined case). `bankOf(sender, extra)` (`Sms.kt:157`) and `extraLookup(map)`
(`Sms.kt:1129`) are in the main source set; the existing `check` calls
`extraLookup(case.extra)` the same way. `Bank.entries` is used elsewhere in the tests
(`MoneyTest.kt:783`).

### Conventions that apply

- Tests explain *why* in a comment in the file's own voice; a tripwire says what silent failure
  it guards against (see `SmsCorpusTest.kt:51-53`: "Not a target, a tripwire…").
- A reader that cannot find what it is looking for must **throw or fail**, never yield an empty
  set and pass — `mirror.test.ts` is built on this rule ("A Kotlin table that cannot be found
  throws rather than yielding zero rows"). Every extraction below has a count check for it.
- Never edit a corpus message or expectation to make a test pass.
- TypeScript has no linter: match the hand style around you.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| PWA install (fresh worktree) | `cd pwa && pnpm install --frozen-lockfile` | exit 0 |
| The two PWA test files | `cd pwa && pnpm exec vitest run test/mirror.test.ts test/paste.test.ts` | all pass |
| PWA full gate | `cd pwa && pnpm run check` | exit 0 |
| Android env (every shell) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| One Android test class | `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.SmsCorpusTest'` | BUILD SUCCESSFUL |
| Android full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | BUILD SUCCESSFUL |

Gradle's configuration cache can report a stale `UP-TO-DATE` for a test task; add `--rerun` when
a test task claims `UP-TO-DATE` after you changed code. A fresh worktree has no
`local.properties`; the `ANDROID_HOME` export replaces it.

## Scope

**In scope** (the only files you should modify):
- `pwa/test/mirror.test.ts`
- `pwa/test/paste.test.ts`
- `app/src/test/java/com/doxigo/muchtoman/SmsCorpusTest.kt`

**Out of scope** (do NOT touch):
- Every file under `app/src/main`, `pwa/src`, `sync/src` — if a new test fails, that is a
  finding to report, not a production fix to make here.
- `app/src/test/resources/sms/*.json`.
- The "Persian copy" section of `mirror.test.ts` and its allowlists (`ANDROID_ONLY`,
  `SAME_WORDS`, `KNOWN_DRIFT`).

## Git workflow

- Commit on the worktree's current branch; one commit for this plan.
- Subject in English only, lower-case area prefix then a plain description, e.g. (from the log)
  `test: follow the fixed-cost rename in the browser suite and the copy mirror`. Suggested:
  `test: pin the sync read version, record kinds, corpus keys and unpinned banks across app, PWA and server`.
- No `Co-Authored-By` or any other trailer. Do not write about users as "she/her" in the commit
  message.
- Do NOT push, merge, or touch `main`.

## Steps

### Step 1: (a) Pin the duplicated constants in `mirror.test.ts`

Add imports (keep the list alphabetical by module, as it is; extend the existing
`../src/installments` line rather than adding a second):

```ts
import { BACKUP_KDF_ITERATIONS, BACKUP_MIN_PASSPHRASE } from '../src/backup';
import { WORTH_IT_PER_WEEK } from '../src/goals';
import { INSTALLMENT_REMINDER_DAYS, INSTALLMENT_REMINDER_DEFAULT, MAX_INSTALLMENTS, remindAwake } from '../src/installments';
import { AT_FUTURE_SLACK_MS, MAX_PLAUSIBLE_RIAL } from '../src/sms';
import { MAX_SYNC_STAMP_SKEW_MS, SYNC_READ_VERSION } from '../src/sync';
```

In `'are the same numbers'` add to the `pwa` map, as a new group after `DAY_MS,`:

```ts
      // What each side can read off the wire. Sync.kt says "kept equal" in a comment; an older
      // client that reads less drops what it cannot open and moves its cursor past it.
      SYNC_READ_VERSION, MAX_SYNC_STAMP_SKEW_MS,
      MAX_PLAUSIBLE_RIAL, AT_FUTURE_SLACK_MS, MAX_INSTALLMENTS, WORTH_IT_PER_WEEK,
      BACKUP_MIN_PASSPHRASE, BACKUP_KDF_ITERATIONS,
```

In `'and the ones the PWA keeps private, read off its source'`, after the `QUIET_GIVE_UP_DAYS`
line:

```ts
    // The same threshold in each side's own unit: Toman on the phone's fold, Rial here.
    expect(Number(tsConst('derived.ts', 'SPOOF_FLOOR_RIAL').replace(/_/g, '')), 'SPOOF_FLOOR_RIAL')
      .toBe((ktConst('SPOOF_FLOOR_TOMAN') as number) * 10);
```

**Verify**: `cd pwa && pnpm exec vitest run test/mirror.test.ts` → all pass. Then prove the pin
bites: temporarily change `SYNC_READ_VERSION` to `2` in `pwa/src/sync.ts`, re-run → the
`'are the same numbers'` test **fails** naming `SYNC_READ_VERSION`; **revert the change**
(`git checkout -- pwa/src/sync.ts`) and confirm `git status --short pwa/src` is empty.

### Step 2: (b) A contract test for record kinds, in `mirror.test.ts`

Add a new `describe` between `'the constants both sides decide by'` and the
`// ─── Persian copy ───` banner (give it its own banner line in the same style):

```ts
// ─────────────────────────── sync record kinds ───────────────────────────

/**
 * A kind the server does not list is a 400 for the whole batch, and all sync stops from that
 * phone — AGENTS.md's "a new sync record kind ships server first". Held here so a kind added to
 * a client and not to sync/ is a red test rather than a rule somebody remembered.
 */
describe('sync record kinds', () => {
  const server = readFileSync(join(__dirname, '../../sync/src/index.ts'), 'utf8');
  const quoted = (s: string): string[] => [...s.matchAll(/['"](\w+)['"]/g)].map((m) => m[1]).sort();
  const listed = /\[([^\]]+)\]\.includes\(kind\)/.exec(server);
  const typed = /\bkind:\s*((?:'\w+'\s*\|\s*)+'\w+');/.exec(server);

  it('are spelled the same in both places the server lists them', () => {
    expect(listed, 'the accepted-kinds list is not in sync/src/index.ts any more').not.toBeNull();
    expect(typed, "PushRecord's kind union is not in sync/src/index.ts any more").not.toBeNull();
    expect(quoted(listed![1]).length).toBeGreaterThanOrEqual(8);
    expect(quoted(typed![1])).toEqual(quoted(listed![1]));
  });

  it('every kind either client sends is one the server accepts', () => {
    const accepted = quoted(listed![1]);
    // Sync.kt: the kind is wireRecord's third argument, a literal at every call.
    const code = kt('Sync.kt');
    const android = [...code.matchAll(/\bwireRecord\(\s*[^,]+,\s*[^,]+,\s*"(\w+)"/g)].map((m) => m[1]);
    const calls = (code.match(/\bwireRecord\(/g) ?? []).length - (code.match(/\bfun wireRecord\(/g) ?? []).length;
    expect(android.length, 'a wireRecord call in Sync.kt no longer names its kind as a literal').toBe(calls);
    // sync.ts: every outgoing record is written `kind: '…', owner`, bar two calls with a literal.
    const ts = tsSource('sync.ts');
    const pwa = [
      ...[...ts.matchAll(/\bkind: '(\w+)', owner\b/g)].map((m) => m[1]),
      ...[...ts.matchAll(/\bwireRecord\(\s*[^,]+,\s*[^,]+,\s*'(\w+)'/g)].map((m) => m[1]),
    ];
    expect(new Set(android).size, 'too few kinds read out of Sync.kt').toBeGreaterThanOrEqual(7);
    expect(new Set(pwa).size, 'too few kinds read out of sync.ts').toBeGreaterThanOrEqual(7);
    for (const kind of new Set([...android, ...pwa])) expect(accepted, `«${kind}» is sent but sync/ refuses it`).toContain(kind);
    expect([...new Set(pwa)].sort(), 'the app and the PWA send different kinds').toEqual([...new Set(android)].sort());
  });
});
```

**Verify**: `cd pwa && pnpm exec vitest run test/mirror.test.ts` → all pass, the two new tests
among them. Then prove it bites: temporarily delete `'note', ` from the array at
`sync/src/index.ts:223`, re-run → both new tests **fail** (the two spellings differ; «note» is
sent but refused); **revert** (`git checkout -- sync/src/index.ts`) and confirm
`git status --short sync` is empty.

### Step 3: (c) Unknown corpus keys fail on the PWA side too

In `pwa/test/paste.test.ts`, below the `cases()` function, add:

```ts
/** Every field this file asserts. `bank` is the one it cannot: a paste has no sender. */
const ASSERTED = [
  'amountRial', 'direction', 'balanceRial', 'printedAt', 'mask', 'instrument', 'merchant', 'refNo',
  'channel', 'unitPrinted', 'account', 'feeRial', 'inferred',
];

/** SmsCorpusTest.kt's EXPECT_KEYS, read off its source: the fields a case may assert at all. */
function androidKeys(): string[] {
  const source = readFileSync(join(__dirname, '../../app/src/test/java/com/doxigo/muchtoman/SmsCorpusTest.kt'), 'utf8');
  const m = /private val EXPECT_KEYS = setOf\(([\s\S]*?)\)/.exec(source);
  if (!m) throw new Error('EXPECT_KEYS is not in SmsCorpusTest.kt any more — point this test at wherever it went');
  return [...m[1].matchAll(/"(\w+)"/g)].map((k) => k[1]);
}
```

Add a test inside `describe('quick paste', …)`, after `'reads the corpus at all'`:

```ts
  it('asserts every field the android side does, bar the bank it cannot see', () => {
    // A key added to EXPECT_KEYS and not handled below would be asserted on one side only.
    expect([...ASSERTED, 'bank'].sort()).toEqual(androidKeys().sort());
  });
```

and inside the corpus loop of `'agrees with the android parser on every field it can see'`,
directly after the `const where = …` line:

```ts
      // A typo asserts nothing: "balanceRail" must fail here as it does in SmsCorpusTest.
      const unknown = Object.keys(c.expect).filter((k) => k !== 'bank' && !ASSERTED.includes(k));
      expect(unknown, `${where} asserts nothing: unknown keys`).toEqual([]);
```

Do not restructure the existing per-key assertions.

**Verify**: `cd pwa && pnpm exec vitest run test/paste.test.ts` → all pass. Then prove it bites:
temporarily add `"zzz"` to `EXPECT_KEYS` in `SmsCorpusTest.kt`, re-run → the new
`'asserts every field…'` test **fails**; **revert** (`git checkout -- app/src/test/java/com/doxigo/muchtoman/SmsCorpusTest.kt`
— do this **before** step 4 edits that file).

### Step 4: (d) The banks still waiting for a real message, as a list that must match

In `SmsCorpusTest.kt`, add a test after `` `every case id is unique across the whole corpus` ``:

```kotlin
    @Test
    fun `a bank with a sender and no corpus case is on the list of those still waiting`() {
        // A bank is read by the generic shapes until a message of its own is pinned here. That
        // gap used to be invisible. Both directions fail on purpose: a new bank with a sender
        // and no case must be added to the list knowingly, and a bank that gains its first case
        // must come off it.
        val pinned = corpusFiles().flatMap { load(it).cases }
            .filter { it.expect != null }
            .mapNotNull { bankOf(it.sender, extraLookup(it.extra)) }
            .toSet()
        assertTrue("the corpus resolved no senders at all", pinned.size >= 10)
        val waiting = Bank.entries.filter { it.numbers.isNotEmpty() && it !in pinned }.map { it.name }.toSet()
        assertEquals("banks with a sender and no real message in the corpus", UNPINNED_BANKS, waiting)
    }
```

and beside `EXPECT_KEYS` at the bottom of the file:

```kotlin
/**
 * Banks the app reads by sender but has no verbatim message for. Not a to-do to clear by guessing:
 * a case goes in only when a real message arrives, and the bank comes off this list with it.
 */
private val UNPINNED_BANKS = setOf(
    "PARSIAN", "DEY", "TEJARAT", "SEPAH", "KESHAVARZI", "POST_BANK", "MASKAN", "MEHR_IRAN",
)
```

**Verify**: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.SmsCorpusTest'` →
BUILD SUCCESSFUL, three tests run (check
`app/build/test-results/testFullDebugUnitTest/TEST-com.doxigo.muchtoman.SmsCorpusTest.xml`).

### Step 5: Full gates

**Verify**:
- `cd pwa && pnpm run check` → exit 0.
- `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → BUILD SUCCESSFUL.
- `git status --short` → only the three in-scope files.

## Test plan

This plan *is* tests. New, by file:

- `pwa/test/mirror.test.ts`: eight constants added to the pinned map, one arithmetic pin
  (`SPOOF_FLOOR`), and `describe('sync record kinds')` with two tests.
- `pwa/test/paste.test.ts`: one test tying its key list to Kotlin's `EXPECT_KEYS`; an
  unknown-key assertion inside the existing corpus loop.
- `SmsCorpusTest.kt`: one tripwire test and its allowlist.

Each of steps 1–3 includes a "prove it bites" check (break, see red, revert). A test added here
that cannot be made to fail is not done.

## Done criteria

- [ ] `cd pwa && pnpm run check` exits 0
- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` is BUILD SUCCESSFUL
- [ ] `grep -c 'SYNC_READ_VERSION' pwa/test/mirror.test.ts` ≥ 2 (import and map)
- [ ] `grep -c "describe('sync record kinds'" pwa/test/mirror.test.ts` → 1
- [ ] `grep -c 'androidKeys' pwa/test/paste.test.ts` ≥ 2
- [ ] `grep -c 'UNPINNED_BANKS' app/src/test/java/com/doxigo/muchtoman/SmsCorpusTest.kt` → 2
- [ ] `git diff --stat 3e6246e -- app/src/main pwa/src sync/src app/src/test/resources` is empty
      (every temporary break was reverted)
- [ ] `git status --short` lists only the three in-scope files
- [ ] `plans/README.md` status row updated (unless your reviewer maintains the index)

## STOP conditions

Stop and report back (do not improvise) if:

- Any excerpt in "Current state" does not match the live file.
- **A new test fails on the unmodified production code** — a constant that differs between the
  two sides, a kind one client sends and the other does not, a `wireRecord` call without a
  literal kind, a bank list that is not the eight named. That is a real finding: report it with
  the values. Do not change the production code, and do not loosen the test or edit the
  allowlist to get green.
- `ktConst` throws "defined twice in app/ with different values" for a constant in the table.
- Importing one of the five `../src` modules into `mirror.test.ts` fails at import time (for
  example for want of IndexedDB). Report which; do not add a shim to `mirror.test.ts`.
- A "prove it bites" check stays green after the deliberate break.

## Maintenance notes

- **Adding a sync record kind** now has an order the tests enforce: add it to both server
  spellings in `sync/src/index.ts` first (and deploy `sync/` before any client release that
  sends it), then to the clients. The contract test reads source text, so it proves the code
  agrees, not that production is deployed — that last step is still a human one.
- **`SYNC_READ_VERSION`** is now pinned equal on both sides, which catches one side bumped
  without the other. It does not catch a forgotten bump on both; that remains a review item
  whenever a client learns to read a new record shape.
- **The first real message from one of the eight banks**: add its corpus file under
  `app/src/test/resources/sms/` and remove the bank from `UNPINNED_BANKS` in the same change —
  the test fails until both are done.
- If plan 020 lands, add `BACKUP_MIN_DIGIT_PASSPHRASE` to the pinned map in `mirror.test.ts` (it
  does not exist before that plan).
- Considered and left out, so nobody re-derives it: a shared *sequence* fixture (ordered
  messages → per-account balance) to hold Android's balance fold and the PWA's together. It is
  worth doing, but it is a new fixture format read by both suites and deserves its own plan.
  The classification of backup preference keys (`EXPORTED_PREFS` / `EXCLUDED_PREFS`) is likewise
  a separate test.
- Reviewer: the regexes in the kinds test are deliberately strict (they count call sites). If a
  refactor of `wireRecord` or of the PWA's outgoing-record shape turns them red with the kinds
  themselves unchanged, update the extraction — do not delete the count checks.
