# Plan 005: Test the Android sync client's household lifecycle

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: compare the "Current state" excerpts below
> against the live `app/src/main/java/com/doxigo/muchtoman/Sync.kt`. This plan
> assumes plan 004 (pairing-link origin allowlist) has landed: `joinHousehold`,
> `rejoinHousehold`, and `parsePairingLink` accept an `allowedBase` parameter
> defaulting to `BuildConfig.SYNC_URL`. If that parameter does not exist, STOP
> (execute plan 004 first).

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW (test-only; no production changes expected)
- **Depends on**: plans/004-pairing-link-origin-allowlist.md
- **Category**: tests
- **Planned at**: commit `26270fd`, 2026-09-10

## Why this matters

The sync *server* is thoroughly tested (48 real-Durable-Object tests in `sync/test/sync.test.ts`),
and the record exchange has `SyncRecoveryTest`/`SyncCoordinationTest`/`FamilyBudgetSyncTest`. But
the Kotlin client's **household lifecycle** — claim, join by link, rejoin (replace), leave,
remove a member, renew (cryptographic eviction) — has zero tests. These flows mutate durable
identity (session token, scope key, member rows) and publish membership changes; a regression can
strand a household or leak a stale session, and today it would ship green. This plan adds a
lifecycle suite against a local fake server, pinning both the HTTP requests made and the durable
state left behind.

## Current state

All lifecycle code is in `app/src/main/java/com/doxigo/muchtoman/Sync.kt`:

- `claimHousehold(base, durable, memberName)` (~L454): calls `claimFreshHousehold` →
  `POST <base>/v1/claim?hid=<32-hex>` with body `{"scopes":["family:<hid>"],"memberId":…,"deviceId":…}`
  (no auth token), expects `{"secret":"<hex>"}`; then `saveSession` writes meta rows
  (`META_SYNC_BASE/TOKEN/TOKEN_AT/DEVICE/MEMBER/SCOPE/KEY`), `resetFamilySharing`, and puts her
  own `FamilyMember` row (`sharesSms = false`).
- `invite(session, durable)` (~L504): `POST /v1/invite` with the session token, returns a code.
- `pairingUrl(session, code)` (~L516): builds the QR link (fragment carries `url,hid,pair,scope,k`).
- `joinHousehold(link, durable, memberName, allowedBase = BuildConfig.SYNC_URL)` (~L581, after
  plan 004): `parsePairingLink` → `pairHousehold` (`POST /v1/pair` with placeholder token
  `<hid>.000…64…0`, body `{"code":…,"memberId":…,"deviceId":…}`, expects `{"secret":…}`) →
  `commitJoin` (saves session, resets sharing, puts own member row).
- `rejoinHousehold(link, durable, memberName, allowedBase)` (~L609): network pair **first**, then
  in one Room transaction `buryHousehold(keepMember = null)` + `commitJoin` — a dead code must
  leave the old household untouched.
- `leaveFamily(session, durable)` (~L667): `POST /v1/leave` with a sealed member tombstone; only
  after success buries the household **and deletes** `META_SYNC_BASE/TOKEN/SCOPE/KEY` (“loadSession
  treats any stored base as a session to resume, so the keys must go”).
- `removeFamilyMember(session, durable, memberId)` (~L630): requires `memberId != active.member`;
  `POST /v1/remove` with `{member, record: <tombstone>}`; on success marks the local member row
  `deleted = true`. On server failure (non-2xx) the local row must stay undeleted.
- `renewHousehold(durable)` (~L708): `claimFreshHousehold(old.base, old.member, old.device)` — a
  **fresh hid/token/key, same member id** — then in one transaction `saveSession` +
  `buryHousehold(keepMember = session.member)`.
- `buryHousehold` (~L726): seq reset to "0", `META_SYNC_IDENTITY_OK = "false"`, publications
  tombstoned, other members' rows tombstoned, family txns/assets tombstoned, her own kept
  (sharing off), her goals land back on personal (`shared = false`), others' goals tombstoned.
- `loadSession(durable)` (~L478): null once base/token/scope/key are gone.
- `request(url, method, token, body)` (~L335, private): plain `HttpURLConnection`; throws on
  non-2xx (read it before writing the fake server — note what error type carries the HTTP status).

**The test exemplar to copy**: `app/src/test/java/com/doxigo/muchtoman/SyncRecoveryTest.kt` —
`@RunWith(RobolectricTestRunner::class)` `@Config(sdk = [28])`, `com.sun.net.httpserver.HttpServer`
on `127.0.0.1:0`, `Room.inMemoryDatabaseBuilder(context, DurableDb::class.java).build()`, a
`SyncSession("http://127.0.0.1:<port>", "household.secret", device, member, "family:home", key)`
constructed directly, JSON built with `org.json.JSONObject`. Follow its style: one behaviour per
test, backtick test names that read as sentences.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| The new class | `./gradlew testFullDebugUnitTest --tests "com.doxigo.muchtoman.SyncLifecycleTest"` | passes |
| Full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | BUILD SUCCESSFUL |

## Scope

**In scope**:
- `app/src/test/java/com/doxigo/muchtoman/SyncLifecycleTest.kt` (create)

**Out of scope** (do NOT touch):
- `Sync.kt` and every other production file. If a flow cannot be tested without a production
  seam beyond plan 004's `allowedBase`, STOP and report which seam is missing.
- Existing test files.

## Git workflow

You are in a managed worktree; **do not commit or push** — the operator reviews and commits.

## Steps

### Step 1: A scripted fake sync server

In the new test file, build a small helper around `com.sun.net.httpserver.HttpServer` (copy the
setup/teardown shape from `SyncRecoveryTest`): it records every request (method, path, query,
auth header, body) into a list and answers from a scripted queue or per-path handler. Provide
canned handlers: `/v1/claim` → `{"secret":"<64 hex>"}`, `/v1/pair` → `{"secret":…}`,
`/v1/invite` → `{"code":"deadbeefdeadbeefdeadbeefdeadbeef"}`, `/v1/identity` → `{}`,
`/v1/remove` / `/v1/leave` → `{}` or a scripted 503.

**Verify**: `./gradlew compileFullDebugUnitTestKotlin` (or the first test run) → compiles.

### Step 2: Claim, join, and the pairing round-trip

Tests:
1. **claim writes a resumable session** — `claimHousehold(base, durable, "مریم")`: the request hit
   `/v1/claim` with `hid=` 32 hex chars, no auth header, scopes `["family:<hid>"]`;
   `loadSession(durable)` round-trips base/token/scope; her member row exists with
   `sharesSms == false`; the token is `<hid>.<returned secret>`.
2. **invite + pairingUrl + joinHousehold completes on a second "phone"** — with a first durable DB
   claimed, call `invite(...)` (assert the request carried the session token), build
   `pairingUrl(session, code)`, then on a **fresh second DurableDb** call
   `joinHousehold(link, durable2, "رضا", allowedBase = base)`. Assert `/v1/pair` was called with the
   placeholder token `<hid>.` + 64 zeros and the code from the link; `loadSession(durable2)`
   has the same `hid` prefix and scope, and the scope **key bytes equal** the first session's
   (the key rides the link, never the server).
3. **join refuses a foreign origin** — same link but `allowedBase = "https://sync.muchtoman.com"`
   → the call throws (or returns failure per `joinHousehold`'s contract — read it) **and** the
   fake server saw **zero** `/v1/pair` requests.

### Step 3: Leave and remove — server-first ordering

Tests:
4. **leave clears the session only after the server said yes** — claimed session, `/v1/leave` →
   200: `loadSession` returns null afterwards; the leave request carried a `record` whose
   `deleted == true` and id `member:<member>`.
5. **a failed leave keeps the session** — script `/v1/leave` → 503: the call throws;
   `loadSession(durable)` still returns the session (nothing buried). This is the regression the
   ordering comment in `leaveFamily` exists for.
6. **remove tombstones the target, not the caller** — seed `durable.familyMembers()` with a second
   member; `/v1/remove` → 200: request body's `member` equals the target id; local row
   `deleted == true`; calling `removeFamilyMember` with the caller's own id fails
   (`require(memberId != active.member)`) without any request.
7. **a failed remove leaves the local row alive** — script 503; assert the member row is not
   deleted.

### Step 4: Renew — cryptographic eviction

Tests:
8. **renew claims fresh, keeps the person** — claimed session with: a second member row, one
   `familyTxns` row, a shared goal owned by her (`ownerMemberId = member`), a shared goal owned by
   the other member. `renewHousehold(durable)` → second `/v1/claim` request; new session has a
   **different** hid+token+key but the **same** member and device ids; the other member's row is
   `deleted`, hers survives with `sharesSms == false`; the family txn is tombstoned; her goal is
   `shared == false` with `ownerMemberId == member`; the other's goal is `deleted`;
   `META_SYNC_IDENTITY_OK` is `"false"` (read via `durable.meta().get("syncIdentityOk")` — check
   the actual constant value in `Sync.kt` first and use whatever string the constant holds).
9. **rejoin buries only after a live pair** — claimed session; a link for a *different* household
   (mint it from a second claimed session + invite); `rejoinHousehold(link, durable, name, allowedBase = base)`
   → old scope gone, new scope present, and with `/v1/pair` scripted 503 the old session survives
   untouched.

**Verify** (steps 2–4): `./gradlew testFullDebugUnitTest --tests "com.doxigo.muchtoman.SyncLifecycleTest"` → ≥9 tests pass.

### Step 5: Full gate

**Verify**: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → BUILD SUCCESSFUL.

## Test plan

The nine tests above in one new file, `SyncLifecycleTest.kt`, modelled on `SyncRecoveryTest.kt`.
Each asserts both sides: the HTTP conversation (paths, auth, ordering, bodies) and the durable
state (`loadSession`, member rows, goals, meta flags).

## Done criteria

- [ ] `SyncLifecycleTest.kt` exists; ≥9 tests, all passing
- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` exits 0
- [ ] `git status --short` shows only the new test file
- [ ] No production file changed
- [ ] `plans/README.md` status row updated (unless a reviewer maintains the index)

## STOP conditions

Stop and report back (do not improvise) if:

- Plan 004's `allowedBase` parameter is absent from `joinHousehold`/`rejoinHousehold` (dependency
  not landed).
- `withFamilySync` or `request` behaves in a way that blocks a local-server test (e.g. enforces
  HTTPS): report; do not add production seams yourself.
- A flow's real behaviour contradicts an assertion above (e.g. leave buries before the request) —
  that is a **finding**, possibly a real bug: report it with the observed order instead of
  adjusting the assertion to match.
- Robolectric/Room fixture setup fails in ways `SyncRecoveryTest` does not exhibit.

## Maintenance notes

- These tests pin the client–server lifecycle contract. When a lifecycle endpoint changes shape,
  update `sync/test/sync.test.ts` (server) and this suite (client) in the same change — the
  deploy-worker-first rule in `DEVELOPMENT.md` applies.
- Reviewer: watch for tests that assert only on the fake server's recorded requests — every test
  must also assert durable state, otherwise it tests the mock.
- Deliberately deferred: token-rotation recovery paths (`recoverTokenRotation`,
  `rotateTokenIfStale`) — already covered by `SyncCoordinationTest`.