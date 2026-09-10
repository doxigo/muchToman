# Plan 004: Pin pairing links to the configured sync origin

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: compare the "Current state" excerpts below
> against the live `app/src/main/java/com/doxigo/muchtoman/Sync.kt`. On a
> mismatch, treat it as a STOP condition. (The repo had uncommitted changes
> when this was planned; `Sync.kt` was **not** among them.)

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: MED (a wrong allowlist breaks the legitimate join flow — the tests below exist to prevent exactly that)
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `26270fd`, 2026-09-10

## Why this matters

Joining a family works by scanning a QR / opening a deep link (`muchtoman://join#…` or
`https://sync.muchtoman.com/join#…`). The link's fragment carries `url=` — the server the app
will send the join request to — and `parsePairingLink` accepts **any** http/https URL there.
A crafted link can therefore steer the join flow to an attacker's server: the victim who
confirms joins an attacker-controlled "household" whose scope key the attacker minted, and
anything they later choose to share syncs to that attacker. The app only ever talks to one sync
service — `BuildConfig.SYNC_URL` — so the fix is to refuse a pairing link whose `url=` names any
other origin.

## Current state

- `app/src/main/java/com/doxigo/muchtoman/Sync.kt`:
  - `parsePairingLink` (~L530–542) — the gate to change:

    ```kotlin
    fun parsePairingLink(value: String): PairingInvite? = runCatching {
        val uri = Uri.parse(value)
        val fragment = uri.encodedFragment ?: return null
        val params = Uri.parse("https://pairing.local/?$fragment")
        val base = params.getQueryParameter("url")?.trimEnd('/') ?: return null
        val hid = params.getQueryParameter("hid") ?: return null
        val code = params.getQueryParameter("pair") ?: return null
        val scope = params.getQueryParameter("scope") ?: return null
        val key = params.getQueryParameter("k")?.let(::unb64Url) ?: return null
        val baseUrl = URL(base)
        if (baseUrl.protocol !in setOf("https", "http") || hid.length !in 16..64 || key.size != 32) return null
        PairingInvite(base, hid, code, scope, key)
    }.getOrNull()
    ```

  - `pairingUrl(session, code)` (~L516–521) builds the outgoing QR link:
    `"${session.base}/join#url=${Uri.encode(session.base)}&hid=$hid&pair=$code&scope=…&k=…"` —
    so a legitimate link's `url=` always equals the base the session already uses.
  - `pairHousehold(link)` (~L545) and the rejoin path both start from `parsePairingLink`.
  - `joinHousehold(link, durable, memberName)` (~L581) and `rejoinHousehold(…)` (~L609) are the
    public entry points; `MainActivity.kt:1220` (`acceptPairing`) calls `parsePairingLink(link)`
    directly to classify the link before asking her to confirm.
- `app/build.gradle.kts` (~L110–140): `SYNC_URL` is a `buildConfigField` on every build type,
  default `https://sync.muchtoman.com`, overridable with `-Pmuchtoman.syncUrl=http://localhost:8788`
  for dev installs (documented in `DEVELOPMENT.md`). **The allowlist must key off
  `BuildConfig.SYNC_URL` so dev overrides keep working.**
- There are currently **no** tests for `parsePairingLink`
  (`grep -rn parsePairingLink app/src/test` → nothing).
- Error-copy convention: `acceptPairing` shows `"کد خانواده معتبر نیست."` when the parse returns
  null — a refused origin should land in that same message; no new UI copy is needed.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Unit tests | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest` | BUILD SUCCESSFUL |
| One class | `./gradlew testFullDebugUnitTest --tests "com.doxigo.muchtoman.PairingLinkTest"` | passes |
| Lint | `./gradlew lint` | BUILD SUCCESSFUL |

## Scope

**In scope**:
- `app/src/main/java/com/doxigo/muchtoman/Sync.kt` — `parsePairingLink` only (plus the two
  internal call sites if a parameter must be threaded).
- `app/src/test/java/com/doxigo/muchtoman/PairingLinkTest.kt` (create)

**Out of scope** (do NOT touch):
- `MainActivity.kt` (uncommitted local changes exist; the default parameter keeps its call site
  source-compatible — verify, don't edit).
- `sync/` (the Worker), `pwa/` (the browser side reads the same fragment but only ever talks to
  its own origin — `readPairing` defaults `url` to `location.origin`; no change needed).
- `pairingUrl` — the outgoing link format must not change (old phones must keep scanning new QRs).

## Git workflow

You are in a managed worktree; **do not commit or push** — the operator reviews and commits.

## Steps

### Step 1: Add the origin gate

Change `parsePairingLink` to take the allowed base as a parameter with the build's default, and
refuse any `url=` whose **origin** (protocol + host + effective port) differs:

```kotlin
fun parsePairingLink(
    value: String,
    allowedBase: String = BuildConfig.SYNC_URL,
): PairingInvite? = runCatching {
    … // existing parsing unchanged
    val baseUrl = URL(base)
    if (!sameOrigin(baseUrl, URL(allowedBase.trimEnd('/'))) || hid.length !in 16..64 || key.size != 32) return null
    PairingInvite(base, hid, code, scope, key)
}.getOrNull()

/** Origin equality with default ports made explicit, so `https://x` == `https://x:443`. */
private fun sameOrigin(a: URL, b: URL): Boolean {
    fun port(u: URL) = if (u.port == -1) u.defaultPort else u.port
    return a.protocol == b.protocol &&
        a.host.equals(b.host, ignoreCase = true) &&
        port(a) == port(b)
}
```

Notes:
- The old `baseUrl.protocol in setOf("https", "http")` check is subsumed: the allowed base's own
  protocol is the only one that can match. Keep the `hid`/`key` checks exactly as they are.
- Add a short comment in the file's voice explaining *why* (a link names the server the join is
  sent to; the app has exactly one sync service, so any other origin is someone else's server).
- Thread nothing through `joinHousehold`/`rejoinHousehold`/`pairHousehold` — they call
  `parsePairingLink(link)` and the default argument does the work. **Exception**: they must be
  callable from tests against a local fake server, so give each of `joinHousehold`,
  `rejoinHousehold`, and `pairHousehold` the same optional `allowedBase: String = BuildConfig.SYNC_URL`
  parameter, passed down to `parsePairingLink`. (Plan 005's lifecycle tests depend on this seam.)

**Verify**: `./gradlew compileFullDebugKotlin` → BUILD SUCCESSFUL.

### Step 2: Tests

Create `app/src/test/java/com/doxigo/muchtoman/PairingLinkTest.kt` (plain JUnit4, no Robolectric
needed if `Uri.parse` requires it — if `Uri` returns nulls under plain JVM, use the Robolectric
header from `SyncRecoveryTest.kt`). Helper: build links with the same shape `pairingUrl` emits:

```kotlin
private fun link(base: String, hid: String = "a".repeat(32)) =
    "$base/join#url=${Uri.encode(base)}&hid=$hid&pair=code123&scope=family:home" +
        "&k=${b64Url(ByteArray(32))}"
```

Cases (allowedBase passed explicitly as `https://sync.muchtoman.com`):
1. Legitimate link (same origin) → parses; `base`, `hid`, `scope` round-trip.
2. `url=https://evil.example` → null.
3. Same host, different scheme (`http://sync.muchtoman.com`) → null.
4. Same host, explicit default port (`https://sync.muchtoman.com:443`) → **parses** (origin
   equality, not string equality).
5. Same host, non-default port (`https://sync.muchtoman.com:8443`) → null.
6. Host case difference (`https://SYNC.muchtoman.com`) → parses.
7. Dev override works: `allowedBase = "http://localhost:8788"` with `url=http://localhost:8788` → parses.
8. Existing invariants hold: bad `hid` length → null; `k` not 32 bytes → null; missing `url=` → null.

**Verify**: `./gradlew testFullDebugUnitTest --tests "com.doxigo.muchtoman.PairingLinkTest"` → all pass.

### Step 3: Full gate

**Verify**: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → BUILD SUCCESSFUL
(existing suites must stay green — in particular `SyncRecoveryTest`, `SyncCoordinationTest`,
`FamilySyncTest`, `FamilyBudgetSyncTest`, which construct sessions directly and must be
unaffected).

## Test plan

Step 2's eight cases in a new `PairingLinkTest.kt`. No existing test constructs pairing links
today, so nothing else should need updating — if an existing test fails after Step 1, treat it as
a STOP condition, not something to patch around.

## Done criteria

- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` exits 0
- [ ] `PairingLinkTest` exists with the eight cases above, all passing
- [ ] `grep -n "sameOrigin" app/src/main/java/com/doxigo/muchtoman/Sync.kt` shows the gate wired into `parsePairingLink`
- [ ] `joinHousehold`, `rejoinHousehold`, `pairHousehold` each accept `allowedBase` with default `BuildConfig.SYNC_URL`
- [ ] Only `Sync.kt` and the new test file are modified (`git status --short`)
- [ ] `plans/README.md` status row updated (unless a reviewer maintains the index)

## STOP conditions

Stop and report back (do not improvise) if:

- `parsePairingLink` in the live code differs from the excerpt (drift — someone got here first).
- `BuildConfig` is not resolvable from `Sync.kt`'s unit-test compilation for the default
  parameter (report the compile error; do not substitute a hardcoded URL).
- Any existing test fails after the change — that means a legitimate flow embeds a foreign
  origin, which invalidates this plan's core assumption.
- `android.net.Uri` misbehaves under plain JVM **and** under Robolectric.

## Maintenance notes

- If a second legitimate deployment ever exists (self-hosters), the gate needs a settings-backed
  allowlist instead of one BuildConfig origin — that is a product decision, not a bug fix.
- Reviewer: confirm the refusal message shown for a foreign-origin link is the existing
  «کد خانواده معتبر نیست.» path in `acceptPairing` (null parse → that message), i.e. no new copy.
- Plan 005 (sync-client lifecycle tests) uses the `allowedBase` seam added here — keep the
  parameter names as specified.