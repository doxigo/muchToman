# Plan 011: Send the member profile only when it is new or changed

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: this plan is written to run in the same
> worktree after plan 010, which touches other lines of the same two files.
> Compare the "Current state" excerpts below against the live
> `app/src/main/java/com/doxigo/muchtoman/Sync.kt` and `pwa/src/sync.ts`; on a
> mismatch in the excerpted lines, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (a publication mark decides only whether to re-send bytes the server already holds)
- **Depends on**: none logically; runs after `plans/010-family-gate-honours-hand-typed-rows-bank.md` in the same worktree
- **Category**: perf
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

Every family sync uploads the member's own profile record — name, SMS-sharing flag and avatar —
whether or not anything changed. The avatar alone may be 24,000 base64 characters before
sealing. The server compares stamps and throws the copy away, so the upload is pure waste, on
metered mobile data, on every foreground, every edit and every bank message. It also makes the
sync report say one record was sent when nothing was.

Every other record kind already avoids this with a *publication mark*: a row remembering the
content hash of what was last sent. This plan gives the profile the same mark, on both clients.

## Current state

**Android** — `app/src/main/java/com/doxigo/muchtoman/Sync.kt`, inside
`private suspend fun outgoingRecords(...)` (~L1159–1189):

```kotlin
    val profilePayload = SYNC_JSON.encodeToString(
        SyncMemberPayload(
            memberId = member.id,
            name = member.name,
            sharesSms = member.sharesSms,
            avatar = member.avatar,
        )
    )
    val outgoing = mutableListOf(
        PreparedRecord(
            wireRecord(
                session,
                memberRecordId(member.id),
                "member",
                member.id,
                member.updatedAt,
                profilePayload,
            )
        )
    )

    // Every row, not the newest so many: ...
    val ledger = ledgerEntries(derived, durable, limit = Int.MAX_VALUE)
    val categoryById = ledger.categories.associateBy { it.id }
    val categoryDecisions = ledger.categoryDecisions.values.toList()
    val categoryDecisionByRef = ledger.categoryDecisions
    val publications = durable.syncPublications().all().associateBy { it.id }
    val activeIds = mutableSetOf<String>()
```

The pattern to copy is the دارایی (asset) record a little further down in the same function
(~L1267–1288):

```kotlin
    val assetId = assetRecordId(session.member)
    val assetPublication = publications[assetId]
    ...
        val contentHash = sha256Hex(payload)
        if (assetPublication == null || assetPublication.deleted || assetPublication.contentHash != contentHash) {
            val updatedAt = nextStamp(assetPublication?.updatedAt, now)
            outgoing += PreparedRecord(
                wireRecord(session, assetId, "asset", session.member, updatedAt, payload),
                SyncPublication(assetId, "asset", contentHash, updatedAt, deleted = false),
            )
        }
```

Supporting facts (all read at `3e6246e`):

- `PreparedRecord(val wire: WireRecord, val publication: SyncPublication? = null, val settle: … = null)` (~L1089).
- `SyncPublication(id, sourceKind, contentHash, updatedAt, deleted)` is the Room entity
  `sync_publication` in `Ledger.kt` (~L417–425). `sourceKind` is free text; no migration is
  needed to store a new value in it.
- The push loop in `syncNow` writes a chunk's publications **only after the server answered
  200** (~L2362–2365), so a mark never exists for a record that was not accepted.
- The sweep that tombstones stale publications is opt-in by kind
  (`if (publication.sourceKind !in setOf("sms", "manual")) continue`, ~L1245), and
  `formerMemberIds` (~L1065–1073) reads only `"sms"`, `"manual"` and `"asset"`. A mark with
  `sourceKind = "member"` is ignored by both.
- `buryHousehold` (~L998–1012) marks **every** publication `deleted = true` when the phone
  joins, leaves or renews a household, so the profile is sent again to the next household.
  A re-pair gets a new member id and therefore a new record id (`member:<id>`), with no mark.
- The member record's wire stamp is the profile row's own `member.updatedAt` (not
  `nextStamp`). Keep that: the server's last-write-wins compares it.

**PWA** — `pwa/src/sync.ts`, inside `export async function outgoingRecords(p: PublishInput)`
(~L623–638). It has the same behaviour, stated in a comment:

```ts
  const { session, now } = p;
  const me = session.member;
  const excludedBanks = new Set(p.excludedBanks);
  const out: Draft[] = [];

  // The profile rides every sync; the server keeps the stored one when the stamp has not moved.
  out.push({
    id: memberRecordId(p.member.id), kind: 'member', owner: p.member.id, updatedAt: p.member.updatedAt,
    payload: JSON.stringify({ kind: 'member', memberId: p.member.id, name: p.member.name, sharesSms: p.member.sharesSms, avatar: p.member.avatar }),
  });

  const categoryById = new Map(p.categories.filter((c) => !c.archived).map((c) => [c.id, c]));
  const categoryDecisions = p.decisions.filter((d) => d.kind === 'category' && !d.deleted);
  const categoryDecisionByRef = new Map(categoryDecisions.map((d) => [d.ref, d]));
  const publications = new Map(p.publications.map((pub) => [pub.id, pub]));
```

- `Draft` has an optional `publication?: Publication` that the final `Promise.all(out.map(...))`
  passes through to the `PreparedRecord` (~L818–820), and `syncNow` stores it after a
  successful push (~L1308–1311), exactly like Android.
- `Publication` is `{ id, sourceKind, contentHash, updatedAt, deleted }` (`pwa/src/model.ts` ~L172).
- The PWA's `buryHousehold` (~L1605–1622) also marks every publication deleted; its sweep
  (~L686) and its former-member scan (~L1613–1614) read only `'sms'`, `'manual'`, `'asset'`.

**Existing tests that encode the old behaviour** (they will need a one-line update each):

- `pwa/test/sync.test.ts` ~L564–566:
  ```ts
      // Nothing changed, so the next sync sends the profile alone.
      serve([]);
      expect((await sync.syncNow(session, NOW + 1)).sent).toBe(1);
  ```
- Tests that call `sync.outgoingRecords(input(...))` with `publications: []` (or with only a
  transaction's publication) still get the `member` record first — there is no profile mark in
  their input — and must stay green **unchanged**.

**Conventions**: comments explain why. TypeScript has no linter; match the hand style. The two
clients mirror each other function for function — keep the same shape and say the same thing
in both comments.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Android env (once per shell) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| One Android test class | `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.<Class>'` | `BUILD SUCCESSFUL` |
| Android full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | `BUILD SUCCESSFUL` |
| PWA install | `cd pwa && pnpm install --frozen-lockfile` | exit 0 |
| One PWA test file | `cd pwa && pnpm exec vitest run test/sync.test.ts` | all pass |
| PWA full gate | `cd pwa && pnpm run check` | exit 0 |

If a Gradle test task reports `UP-TO-DATE` after a code change, re-run it with `--rerun`.

## Scope

**In scope**:
- `app/src/main/java/com/doxigo/muchtoman/Sync.kt` (only the profile block of `outgoingRecords`)
- `app/src/test/java/com/doxigo/muchtoman/FamilyBudgetSyncTest.kt` (append one test)
- `pwa/src/sync.ts` (only the profile block of `outgoingRecords`)
- `pwa/test/sync.test.ts` (one new test; the one assertion quoted above)
- Any other existing test **only** where its failure is exactly "the profile was not re-sent on
  a later sync with nothing changed" — change that assertion and nothing else.

**Out of scope**:
- `sync/` (the server), the wire format, `SyncMemberPayload`.
- `Ledger.kt` / the Room schema — `source_kind` already holds free text. No migration.
- `buryHousehold`, `formerMemberIds`, the tombstone sweep — they already ignore the new mark.
- Adding a `settle` callback for the profile; it has none today and needs none.

## Git workflow

- Commit on the worktree's current branch; one commit for this plan.
- Subject in English only, lower-case area prefix then a plain description. Suggested:
  `sync: send the member profile only when it is new or changed, app and PWA`.
- No `Co-Authored-By` or any other trailer. Do not describe users as "she/her" in the message.
- Do NOT push, merge, or touch `main`.

## Steps

### Step 1: Android — mark the profile and skip it when unchanged

In `Sync.kt` `outgoingRecords`, replace the `val outgoing = mutableListOf(PreparedRecord(...))`
block with the shape below, and **delete** the later line
`val publications = durable.syncPublications().all().associateBy { it.id }` (it moves up; the
rest of the function keeps using the same `publications` value):

```kotlin
    val publications = durable.syncPublications().all().associateBy { it.id }
    val outgoing = mutableListOf<PreparedRecord>()
    // The profile goes when it is new to this household or its content moved. It used to ride
    // every sync for the server to drop on an equal stamp — an avatar's worth of upload each time.
    val profileId = memberRecordId(member.id)
    val profileHash = sha256Hex(profilePayload)
    val profileMark = publications[profileId]
    if (profileMark == null || profileMark.deleted || profileMark.contentHash != profileHash) {
        outgoing += PreparedRecord(
            wireRecord(session, profileId, "member", member.id, member.updatedAt, profilePayload),
            SyncPublication(profileId, "member", profileHash, member.updatedAt, deleted = false),
        )
    }
```

Keep `profilePayload` as it is. Keep the wire stamp `member.updatedAt`.

**Verify**: `./gradlew compileFullDebugKotlin` → `BUILD SUCCESSFUL`, and
`grep -c "syncPublications().all().associateBy" app/src/main/java/com/doxigo/muchtoman/Sync.kt` → the same count as before your edit (the line moved, it was not duplicated; check with `git diff`).

### Step 2: Android test

Append to `FamilyBudgetSyncTest.kt` (it has a two-phone harness with a fake server whose stored
records are `home.rows`, each with a `seq` that moves only when the server accepts a write):

```kotlin
    @Test
    fun `the profile goes once, again when it changes, and not in between`() = runBlocking {
        Household().use { home ->
            val a = home.phone('a')
            val id = "member:${a.session.member}"
            a.sync()
            val first = home.rows.getValue(id).getLong("seq")
            assertEquals(0, a.sync().sent)
            assertEquals(first, home.rows.getValue(id).getLong("seq"))
            a.durable.familyMembers().put(FamilyMember(a.session.member, "سهیل", avatar = AVATAR_MAN, updatedAt = System.currentTimeMillis()))
            assertEquals(1, a.sync().sent)
            assertTrue(home.rows.getValue(id).getLong("seq") > first)
            assertEquals(0, a.sync().sent)
        }
    }
```

**Verify**: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.FamilyBudgetSyncTest'` → `BUILD SUCCESSFUL`.
If `assertEquals(0, a.sync().sent)` fails with a number other than 0 or 1, read what was pushed
before changing anything — see STOP conditions.

### Step 3: PWA — the same change

In `pwa/src/sync.ts` `outgoingRecords`, replace the comment + `out.push({ … kind: 'member' … })`
block with the shape below and **delete** the later
`const publications = new Map(p.publications.map((pub) => [pub.id, pub]));` line (it moves up):

```ts
  const publications = new Map(p.publications.map((pub) => [pub.id, pub]));
  // The profile goes when it is new to this household or its content moved. It used to ride
  // every sync for the server to drop on an equal stamp — an avatar's worth of upload each time.
  const profileId = memberRecordId(p.member.id);
  const profile = JSON.stringify({ kind: 'member', memberId: p.member.id, name: p.member.name, sharesSms: p.member.sharesSms, avatar: p.member.avatar });
  const profileHash = sha256Hex(profile);
  const profileMark = publications.get(profileId);
  if (!profileMark || profileMark.deleted || profileMark.contentHash !== profileHash) {
    out.push({
      id: profileId, kind: 'member', owner: p.member.id, updatedAt: p.member.updatedAt, payload: profile,
      publication: { id: profileId, sourceKind: 'member', contentHash: profileHash, updatedAt: p.member.updatedAt, deleted: false },
    });
  }
```

The JSON key order of the payload must stay exactly as it was (`kind, memberId, name,
sharesSms, avatar`) — an existing test compares the decoded payload.

**Verify**: `cd pwa && pnpm run typecheck` → exit 0.

### Step 4: PWA tests

In `pwa/test/sync.test.ts`:

1. Update the quoted assertion (~L564–566): the comment becomes
   `// Nothing changed, so the next sync sends nothing at all.` and the expectation `.toBe(0)`.
2. In the `describe('publishing', …)` block add one test using the existing `input` helper:
   - `const first = await sync.outgoingRecords(input([]))` → kinds `['member']`, and
     `first[0].publication` matches `{ id: \`member:${ME}\`, sourceKind: 'member', deleted: false }`.
   - `await sync.outgoingRecords(input([], { publications: [first[0].publication!] }))` → `[]`.
   - with the same publication but `member: { …, name: 'مریم جان' }` → kinds `['member']`.
   - with the same publication marked `deleted: true` (what a household burial leaves) → kinds `['member']`.

**Verify**: `cd pwa && pnpm exec vitest run test/sync.test.ts` → all pass.

### Step 5: Full gates

**Verify**: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → `BUILD SUCCESSFUL`;
`cd pwa && pnpm run check` → exit 0.

If another existing test fails, read it: if it asserts that a second, unchanged sync pushes the
`member` record (or counts it in `sent`), update that one assertion and note the file and line
in your report. Any other failure is a STOP condition.

## Test plan

- New Android test in `FamilyBudgetSyncTest.kt`: sent once, silent when unchanged, sent again
  on a profile edit (server `seq` moves), silent again.
- New PWA test in `sync.test.ts` `publishing`: unmarked → sent with a mark; marked and unchanged
  → nothing; changed → sent; buried mark → sent.
- Updated: the `sent).toBe(1)` assertion quoted above.

## Done criteria

- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → `BUILD SUCCESSFUL`
- [ ] `cd pwa && pnpm run check` exits 0
- [ ] `grep -n "rides every sync" pwa/src/sync.ts` → no output
- [ ] `grep -n '"member", profileHash' app/src/main/java/com/doxigo/muchtoman/Sync.kt` → one line
- [ ] `git status --short` (before commit) lists only in-scope files
- [ ] One commit, no trailer

## STOP conditions

- The excerpts in "Current state" do not match the live code.
- A test fails because a record **other than** the profile stopped or started being sent.
- A test shows another phone no longer receiving a changed name or avatar.
- You find code that reads `sync_publication` rows and would treat `sourceKind == "member"` as a
  transaction or asset (search `sourceKind` in `Sync.kt` and `pwa/src/sync.ts` before Step 1;
  at `3e6246e` there is none).
- The change seems to need a Room migration, a server change, or a new record kind.

## Maintenance notes

- A profile edit must change the payload **and** advance `FamilyMember.updatedAt`, as it does
  today: the hash decides whether to send, the stamp decides whether the server keeps it.
- Phones updating to this build have no profile mark yet, so they send the profile once more and
  are quiet from then on.
- A future field added to `SyncMemberPayload` changes the hash, which re-sends every member's
  profile once — expected, not a bug.
- The sync result's `sent` now reads 0 for an idle sync; any UI line built on it («… مورد
  فرستادیم») becomes truthful without further change.
