# Security, correctness, and performance audit

Date: 2026-08-04

## Executive summary

The reviewed working tree has no known critical finding. I found and addressed two high-impact
issues and six medium-impact issues. The main risks were release-key exposure in CI, automatic
backup of financial/SMS-derived data, an unauthenticated Worker cache bypass, unbounded network
bodies, insufficient validation of network-controlled financial data, and SMS state races.

The Android fixes are built and installed on the connected Pixel 9. Worker fixes are complete in
the working tree but are not active on `rates.muchtoman.com` until the Worker is deployed.

One medium residual risk remains open: `POST /wallet-balance` is intentionally public and has no
deployment-level rate limit. A hard-coded app secret would be extractable and would not solve
this. Configure Cloudflare rate limiting after choosing a threshold that accounts for mobile
carrier NAT traffic.

## Scope and assumptions

- Reviewed Android/Kotlin code, resources, merged manifests, Gradle configuration, Cloudflare
  Worker code, npm dependencies, release CI, and project documentation.
- Ran static checks, unit tests, minified release builds, local Worker runtime checks, and a real
  device install/cold launch.
- Treated the README promise that holdings and SMS-derived data stay on the phone as a hard
  privacy requirement. This is why backup and device transfer are disabled.
- Did not mutate the production Worker, Cloudflare account settings, GitHub secrets, releases, or
  repository state. Account-level WAF/rate-limit rules were therefore outside the writable scope.
- Did not audit the security or correctness of third-party market/RPC operators themselves.

## Fixed findings

### SEC-01: Financial and SMS-derived state was eligible for backup

- Severity: High privacy impact
- Status: Fixed, built, and installed
- Location: `app/src/main/AndroidManifest.xml:29`,
  `app/src/main/res/xml/backup_rules.xml:2`,
  `app/src/main/res/xml/data_extraction_rules.xml:2`
- Risk: Holdings, public wallet links, bank balances derived from SMS, and app preferences were
  stored in SharedPreferences while `allowBackup` was enabled. That contradicted the documented
  on-device-only privacy model and could place the data in cloud backup or device transfer.
- Fix: Disabled backup, excluded every storage domain from both legacy backup and Android 12+
  cloud/device-transfer rules, and disabled cleartext traffic in the main manifest. Both merged
  release and dev manifests were inspected after the build.
- Tradeoff: Automatic restore and device-to-device migration no longer carry app data. This is
  deliberate. Android documents that explicit data extraction rules are needed to control cloud
  backup and device transfer: https://developer.android.com/identity/data/autobackup

### SEC-02: Release signing material shared a job with a mutable third-party action

- Severity: High
- Status: Fixed in workflow; exercised locally where possible, not yet run by GitHub
- Location: `.github/workflows/release.yml:7`, `.github/workflows/release.yml:11`,
  `.github/workflows/release.yml:98`, `.github/workflows/release.yml:171`
- Risk: The release job had repository write permission throughout, used mutable action tags, and
  ran the emulator action on the same runner after the real signing key had been reconstructed.
  Compromise of the action or tag could expose the release key or release token.
- Fix: Defaulted the workflow to read-only, pinned every action to a full commit SHA, disabled
  checkout credential persistence, and split verification, signing, and publishing. The emulator
  now uses a disposable CI key in a job that never receives the real signing secrets. The real key
  is created with mode 600, used only in the signed-build job, and deleted immediately afterward.
  Only the final publishing job receives `contents: write`.
- Reference: GitHub recommends full-length action SHAs as the only immutable action reference:
  https://docs.github.com/en/actions/reference/security/secure-use

### SEC-03: Public cache bypass and unbounded Worker I/O enabled resource abuse

- Severity: Medium
- Status: Fixed locally; production Worker deployment required
- Location: `worker/src/index.ts:24`, `worker/src/index.ts:74`,
  `worker/src/index.ts:86`, `worker/src/index.ts:1048`, `worker/src/index.ts:1084`
- Risk: Any caller could use `?fresh=1` to force the full upstream price fan-out repeatedly.
  Request and upstream response bodies were not consistently bounded, and the old timeout stopped
  protecting the request after response headers arrived. Oversized or stalled bodies could waste
  memory, subrequests, and execution time.
- Fix: Removed public cache bypass, introduced a versioned canonical cache key, added full-body
  timeouts, request/JSON/HTML/image size limits, response-body cancellation, content-type checks,
  bounded error messages, and strict unknown-data parsing. Oversized wallet requests now return
  413. The Worker still follows Cloudflare's Cache API behavior and caches successful rates only:
  https://developers.cloudflare.com/workers/runtime-apis/cache/
- Reference: Cloudflare documents runtime resource limits and recommends explicit limits around
  external data: https://developers.cloudflare.com/workers/platform/limits/

### SEC-04: Coin images could leak client IPs to network-controlled hosts

- Severity: Medium
- Status: App-side fix installed; Worker proxy deployment required for logos to render
- Location: `app/src/main/java/com/doxigo/muchtoman/Data.kt:286`,
  `worker/src/index.ts:476`, `worker/src/index.ts:827`
- Risk: Image URLs arrived in the rates payload and were loaded directly by the phone. A
  compromised or malformed feed could make clients contact an arbitrary tracking host, exposing
  their IP address and request metadata.
- Fix: The Worker now emits only fixed-origin `/coin-icon` URLs and proxies a tightly validated
  CoinGecko path. It rejects redirects, unexpected MIME types, traversal, and images over 1 MiB.
  The app independently permits only `/coin-icon` on the configured or official Worker origin.

### SEC-05: Wallet text was sent before local format validation

- Severity: Medium privacy impact
- Status: Client fix installed; duplicate Worker validation awaits deployment
- Location: `app/src/main/java/com/doxigo/muchtoman/Data.kt:247`,
  `app/src/main/java/com/doxigo/muchtoman/Data.kt:623`, `worker/src/index.ts:608`,
  `worker/src/index.ts:780`
- Risk: Pasting a recovery phrase, private key, unsupported contract, or malformed address into
  the public-address field caused it to reach the Worker before rejection. Common private-key and
  seed formats do not match supported public-address formats and should never leave the phone.
- Fix: Added network-specific address and contract validation in the UI/network client and kept
  independent validation at the Worker boundary. Input is length-limited and redirects are
  disabled. This reduces accidental secret disclosure but does not turn the app into a secret-key
  manager; users must still enter public addresses only.

### SEC-06: Network-controlled financial values and links lacked complete validation

- Severity: Medium integrity impact
- Status: App fix installed; Worker-side numeric hardening awaits deployment
- Location: `app/src/main/java/com/doxigo/muchtoman/Data.kt:115`,
  `app/src/main/java/com/doxigo/muchtoman/Data.kt:278`,
  `app/src/main/java/com/doxigo/muchtoman/Data.kt:329`, `worker/src/index.ts:649`
- Risk: NaN, infinity, overflow, far-future timestamps, oversized catalogues, arbitrary update
  links, or huge numeric strings could poison totals, suppress future refreshes, create misleading
  UI, or consume excessive CPU during BigInt parsing.
- Fix: Reject non-finite and non-positive rates, cap counts and string lengths, bound timestamps,
  allow only the project's GitHub release path, restrict icon origins, validate wallet responses,
  prevent total overflow, and cap on-chain integer/decimal syntax before BigInt conversion.

### SEC-07: SMS deduplication and scan races could corrupt displayed balances

- Severity: Medium integrity impact
- Status: Fixed, tested, and installed
- Location: `app/src/main/java/com/doxigo/muchtoman/Sms.kt:539`,
  `app/src/main/java/com/doxigo/muchtoman/MainActivity.kt:358`,
  `app/src/main/java/com/doxigo/muchtoman/MainActivity.kt:431`,
  `app/src/main/java/com/doxigo/muchtoman/MainActivity.kt:490`
- Risk: A 32-bit body hash omitted the sender, so equal messages from different banks or a hash
  collision could suppress a legitimate transaction. Concurrent scans could also write stale
  balances after SMS was disabled, restore a dismissed sender, or overwrite a manual balance.
- Fix: Dedup keys now use SHA-256 over normalized sender, timestamp, and full body while still
  recognizing legacy keys. Scan completion rechecks permission/feature state and current
  dismissals. Mutations that can race with a scan are serialized through cancellation and join.
  The existing SMS schema was deliberately retained so the migration does not erase manual
  anchors.

### SEC-08: Wrangler dependency tree contained a high-severity undici advisory

- Severity: Medium project exposure; upstream advisory severity was High
- Status: Fixed locally
- Location: `worker/package.json:11`, `worker/package-lock.json`
- Risk: The baseline `npm audit` reported a high-severity issue through Wrangler's transitive
  `undici`. This is a development/deployment dependency rather than Worker runtime code, which
  lowers direct production exposure but still matters on developer and CI machines.
- Fix: Updated Wrangler, TypeScript, and Workers types, pinned the remediated undici resolution,
  regenerated the lockfile, and added strict typecheck/deployment-dry-run scripts. Final
  `npm audit --audit-level=high` reports 0 vulnerabilities.

## Remaining risks

### SEC-09: Public wallet RPC relay has no deployment-level rate limit

- Severity: Medium
- Status: Open
- Location: `worker/src/index.ts:1048`
- Risk: An attacker can send many individually valid public-wallet requests, consuming Worker
  invocations and upstream RPC quotas. Per-request validation and limits reduce amplification but
  do not limit request count.
- Recommendation: Add a Cloudflare rate-limiting binding or WAF rule, monitor 429s and upstream
  failures, then tune by real traffic. Do not guess a low per-IP threshold: Iranian mobile carrier
  NAT can place many legitimate users behind one address. Cloudflare's binding supports explicit
  keys and limits: https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/

### SEC-10: Stock users contact TSETMC directly

- Severity: Low privacy limitation
- Status: Accepted and documented
- Location: `app/src/main/java/com/doxigo/muchtoman/Tse.kt:143`
- Risk: Opening the stock picker or holding a stock exposes the phone's IP to TSETMC. The call is
  HTTPS and sends no portfolio contents, but it is a direct third-party connection.
- Reason retained: TSETMC rejects the existing Cloudflare path. The app now avoids this request
  unless the stock picker is opened or a stock is held.

### SEC-11: Gradle artifacts do not use dependency verification metadata

- Severity: Low defense-in-depth gap
- Status: Open
- Location: `gradle/wrapper/gradle-wrapper.properties`, missing
  `gradle/verification-metadata.xml`
- Risk: The Gradle distribution itself has a pinned SHA-256 checksum, but Maven artifacts are not
  locked by Gradle dependency verification. Repository compromise is a low-probability supply
  chain risk.
- Recommendation: Bootstrap dependency verification in a trusted environment and independently
  review the initial metadata before enforcing it. Blindly trusting the current cache and checking
  in generated hashes would create false confidence. Reference:
  https://docs.gradle.org/current/userguide/dependency_verification.html

## Performance and correctness improvements

- TSETMC is no longer fetched on every app launch. It is fetched on demand for the picker or when
  a stock is held, cached for 10 minutes, and fetched concurrently with daily rates only when
  needed (`MainActivity.kt:121`, `Daily.kt:25`).
- The large TSETMC response is capped at 16 MiB and only its required section is traversed with a
  sequence instead of splitting and retaining the entire multi-section feed (`Tse.kt:95`).
- Wallet refreshes are capped at four concurrent requests, preventing connection storms while
  still parallelizing normal portfolios (`MainActivity.kt:49`, `MainActivity.kt:274`).
- Compose now has one lifecycle-aware StateFlow subscription instead of duplicate collectors
  (`MainActivity.kt:681`).
- Android rates and wallet responses are bounded at 2 MiB and 64 KiB. Worker JSON, HTML, request,
  image, and log messages are also bounded.
- The dev variant uses a stable high version code and `-dev` version suffix, fixing in-place update
  failures without uninstalling or deleting test data (`app/build.gradle.kts:71`,
  `app/build.gradle.kts:98`).
- Lint accessibility fixes added explicit widget descriptions, marked decorative images correctly,
  and removed deprecated density/state APIs. Warnings fell from 85 to 71.

## Verification evidence

- Android: `./gradlew test lint assembleRelease installDev` passed.
- Tests: 135 executed, 0 failures, 0 errors.
- Lint: 0 errors, 71 warnings. Remaining warnings are 28 dynamically/conditionally used resources,
  17 newer-API attributes, 13 style-only KTX suggestions, and 13 vector complexity warnings.
- Release: R8 minification and resource shrinking completed successfully.
- Merged release and dev manifests both contain `allowBackup=false`, backup/data extraction rules,
  and `usesCleartextTraffic=false`.
- Worker: strict TypeScript check and Wrangler deployment dry-run passed; upload is 31.59 KiB,
  9.12 KiB gzip; npm audit reports 0 vulnerabilities; npm reports no outdated package.
- Worker runtime: cold `/rates` returned 200 with 741 rates and 249 coins; cache hits returned in
  milliseconds; icon proxy and invalid-route/request responses behaved as designed. A controlled
  12-way warm cache burst returned twelve 200 responses and kept the simulator alive.
- Device: `com.doxigo.muchtoman.dev` version `1.0-dev` / code `2000000000` installed on Pixel 9
  without clearing data. Final cold launch was 2.889 seconds, the activity remained resumed and
  focused, and no app crash, ANR, StrictMode, AndroidRuntime exception, or crash-buffer entry was
  found.
- Repository: `git diff --check` passed and a targeted scan found no common private-key/API-token
  signatures in tracked source.

## Deployment note

The production Worker was inspected read-only and still serves the old behavior. Until the local
Worker changes are deployed, the installed dev app will intentionally reject old direct CoinGecko
image URLs and may show ticker-letter fallbacks instead of coin logos. Client-side validation and
Android privacy fixes are already active on the phone.
