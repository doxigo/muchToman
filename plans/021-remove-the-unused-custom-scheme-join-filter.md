# Plan 021: Remove the `muchtoman://join` deep-link filter nothing produces

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- app/src/main/AndroidManifest.xml`
> If the file changed since this plan was written, compare the "Current state"
> excerpt against the live file before proceeding; on a mismatch, STOP.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

`MainActivity` is exported and answers two browsable links: the verified App Link
`https://sync.muchtoman.com/join…` and a custom scheme, `muchtoman://join`. A custom scheme
cannot be verified: any web page or app can fire it to raise the join / replace-household
prompt, and another app can claim the same scheme. Nothing produces that link any more — the
Android app has only ever built `https://…/join#…` invites, and the PWA stopped redirecting to
`muchtoman://join` on 2026-09-26 (commit `846d7a5`, before v1.2.6). The in-app QR scanner and
the App Link cover every real flow, so the filter is attack surface with no user.

## Current state

- `app/src/main/AndroidManifest.xml` — the launcher activity's intent filters (lines ~104-129):

  ```xml
              <intent-filter>
                  <action android:name="android.intent.action.MAIN" />
                  <category android:name="android.intent.category.LAUNCHER" />
              </intent-filter>
              <intent-filter>
                  <action android:name="android.intent.action.VIEW" />
                  <category android:name="android.intent.category.DEFAULT" />
                  <category android:name="android.intent.category.BROWSABLE" />
                  <data android:scheme="muchtoman" android:host="join" />
              </intent-filter>
              <!--
                  Verified against pwa/public/.well-known/assetlinks.json, which the sync Worker
                  serves. Unverified, Android 12+ hands this link to the browser every time and the
                  QR a phone's own camera reads opens the PWA instead of the app. …
              -->
              <intent-filter android:autoVerify="true">
                  …
                  <data
                      android:scheme="https"
                      android:host="sync.muchtoman.com"
                      android:pathPrefix="/join" />
              </intent-filter>
  ```

- `app/src/main/java/com/doxigo/muchtoman/Sync.kt`, `pairingUrl` (~line 689) — the only place
  an invite link is built:

  ```kotlin
  fun pairingUrl(session: SyncSession, code: String): String {
      val hid = session.token.substringBefore('.')
      return "${session.base}/join#url=${Uri.encode(session.base)}&hid=$hid&pair=$code" +
          "&scope=${Uri.encode(session.scope)}&k=${b64Url(session.key)}"
  }
  ```

- `parsePairingLink` in the same file reads the link's fragment and does not care about the
  scheme, so a scanned QR keeps working whatever its scheme; only the manifest decides which
  links Android hands to the app. Do not change `parsePairingLink`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Java/SDK for Gradle (this Mac; a fresh worktree has no `local.properties`) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| Lint + both debug builds | `./gradlew lint assembleDebug` | `BUILD SUCCESSFUL` |
| Unit tests | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest` | `BUILD SUCCESSFUL` |

Lint has crashed spuriously on this project before (an internal K2 resolve error unrelated to
the change). Rerun once before treating a lint crash as a failure.

## Scope

**In scope** (the only file you should modify):
- `app/src/main/AndroidManifest.xml`

**Out of scope** (do NOT touch):
- The `https` App Link filter and its comment — it is the verified entry point.
- `Sync.kt`, `MainActivity.kt` — link parsing and intent handling stay as they are.
- `plans/004-pairing-link-origin-allowlist.md` — it mentions the scheme as history; leave it.

## Git workflow

- Commit on the worktree's current branch, one commit for this plan.
- Subject in English only, lower-case area prefix then a plain description, like the log's
  `ledger: let a hand-entered transaction name any bank account, app and PWA`. Suggested:
  `manifest: drop the unused muchtoman://join link filter`.
- No `Co-Authored-By` or any other trailer. Do not push, merge or touch `main`.

## Steps

### Step 1: Confirm nothing in the tree produces or documents the scheme as live

Run:

```bash
grep -rn "muchtoman://" app/src pwa/src pwa/public pwa/index.html sync/src worker/src worker/public README.md README.fa.md DEVELOPMENT.md
```

**Verify**: the only hit is the `<data android:scheme="muchtoman" …>` line's absence of a
literal `muchtoman://` — i.e. the command prints nothing. If it prints any line in source
(not a plan file), STOP: something still emits the scheme.

### Step 2: Delete the custom-scheme filter

Remove exactly the second `<intent-filter>` block shown above (the five lines from its opening
tag to its closing tag, whose `<data>` is `android:scheme="muchtoman" android:host="join"`).
Leave the MAIN/LAUNCHER filter, the comment and the `autoVerify` filter untouched.

**Verify**: `grep -n 'android:scheme="muchtoman"' app/src/main/AndroidManifest.xml` prints
nothing, and `grep -c 'android:scheme="https"' app/src/main/AndroidManifest.xml` prints `1`.

### Step 3: Build and test

**Verify**: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint assembleDebug` →
`BUILD SUCCESSFUL`.

## Test plan

No new test: the manifest is not reachable from the JVM suite, and the two greps in step 2 are
the check. The existing pairing tests (`SyncLifecycleTest`, the origin matrix from plan 004)
must stay green — they are part of step 3.

## Done criteria

- [ ] `grep -n 'android:scheme="muchtoman"' app/src/main/AndroidManifest.xml` returns no match
- [ ] The `https` / `sync.muchtoman.com` / `/join` filter is still present
- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint assembleDebug` exits 0
- [ ] `git status --short` lists only `app/src/main/AndroidManifest.xml`

## STOP conditions

- Step 1's grep finds a live emitter of `muchtoman://` in source.
- The manifest no longer matches the excerpt (the filter is already gone, or moved to another
  activity).
- A flavour manifest (`app/src/full`, `app/src/lite`, `app/src/dev`) declares the same scheme —
  report it rather than editing files outside scope.

## Maintenance notes

- A QR printed or screenshotted from the PWA before 2026-09-26 would no longer open the app
  from the system camera. Invites are ten-minute one-time codes, so no such QR can still work.
- App Link verification goes through Google and can fail from inside Iran; that is why the
  family page has its own scanner. If a second entry point is ever wanted again, prefer the
  scanner over a custom scheme.
