# Plan 008: A small instrumented UI slice for the flows JVM tests cannot see

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: compare the "Current state" excerpts below
> against the live `app/build.gradle.kts` and `.github/workflows/release.yml`.
> On a mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: L
- **Risk**: MED (new test infrastructure; emulator steps are timing-sensitive — scoped to stay off the release-blocking path until proven)
- **Depends on**: none
- **Category**: tests
- **Planned at**: commit `26270fd`, 2026-09-10

## Why this matters

The repo's verification is JVM tests (fast, thorough on logic) plus a release-time emulator smoke
test that only asserts "the APK opens". Everything between — navigation actually reaching each
root tab, the two-tap destructive-action ritual, the lite flavour really hiding the ledger
screens, TalkBack semantics — is invisible to both layers. `DEVELOPMENT.md` states the rule this
plan follows: "a check that runs against `src/` cannot tell you the shipped build works." A small
Compose-instrumented suite, runnable on demand and wired into CI as a manually-triggerable
workflow first, buys device-level regression coverage for the highest-risk flows without
slowing every push.

## Current state

- `app/build.gradle.kts` — no `androidTest` source set exists, no instrumented-test dependencies,
  no `testInstrumentationRunner` configured. Flavours `full`/`lite` (dimension "edition"), build
  types `debug`/`dev`/`release`. `minSdk 24`.
- `app/src/androidTest/` — does not exist.
- `.github/workflows/release.yml` — the exemplar for every emulator decision (KVM udev rule, API
  30 `default` x86_64 image, `reactivecircus/android-emulator-runner` pinned by SHA, swiftshader,
  one-line-per-command script rules). Copy its choices; do not invent new ones.
- `.github/workflows/check.yml` — runs on every push; **must not slow down** (this plan adds a
  separate workflow instead).
- Compose BOM / versions come from `gradle/libs.versions.toml` — read it and add the instrumented
  artifacts through the same catalog (`androidx.compose.ui:ui-test-junit4`,
  `androidx.test:runner`, `androidx.test:core`, `androidx.compose.ui:ui-test-manifest`).
- UI structure for the tests: `MainActivity` hosts everything; root tabs live in `TabBar.kt`
  (`tabs` selects screens, `BuildConfig.LITE` gates the lite set); destructive actions arm on
  first tap (see `PRODUCT.md`: "Every destructive action is two taps; TalkBack hears the armed
  state"). Persian strings are the UI — select nodes by `testTag` where possible, falling back to
  text only for stable strings. Compose test tags do not exist yet; adding a few is in scope
  (tags are metadata, not behaviour).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Compile the suite | `./gradlew assembleFullDebugAndroidTest` | BUILD SUCCESSFUL |
| Run on a connected device/emulator | `./gradlew connectedFullDebugAndroidTest` | all pass (requires an emulator; see Step 4) |
| JVM gate untouched | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | BUILD SUCCESSFUL |
| Workflow YAML sanity | `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ui-check.yml'))"` | exit 0 |

## Scope

**In scope**:
- `app/build.gradle.kts` — instrumented-test wiring only (runner, dependencies, nothing else)
- `gradle/libs.versions.toml` — the new test artifacts
- `app/src/androidTest/java/com/doxigo/muchtoman/` (create; the suite)
- A handful of `Modifier.testTag("…")`/`testTagsAsResourceId` additions in UI files **only where a
  flow below cannot be reached by stable text** — each tag is one line; no other UI edits
- `.github/workflows/ui-check.yml` (create; `workflow_dispatch` + weekly `schedule`)

**Out of scope** (do NOT touch):
- `.github/workflows/check.yml` and `release.yml` — this suite joins the blocking path only after
  it has proven stable, and that promotion is a human decision.
- `Ui.kt`/`MainActivity.kt` logic (both carry uncommitted local changes — if a needed testTag
  lands inside either file, STOP and report instead of editing them).
- Any production behaviour.

## Git workflow

You are in a managed worktree; **do not commit or push** — the operator reviews and commits.

## Steps

### Step 1: Wire instrumented testing

In `gradle/libs.versions.toml`, add the test artifacts (versions consistent with the existing
Compose/test stack). In `app/build.gradle.kts`: set
`testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` in `defaultConfig`, add
`androidTestImplementation` deps (compose ui-test-junit4, test runner/core) and
`debugImplementation(libs.androidx.compose.ui.test.manifest)` equivalent.

**Verify**: `./gradlew assembleFullDebugAndroidTest` → BUILD SUCCESSFUL (an empty suite is fine at
this step).

### Step 2: The suite — five flows, full flavour

Create `app/src/androidTest/java/com/doxigo/muchtoman/UiSmokeTest.kt` using
`createAndroidComposeRule<MainActivity>()`. Flows (each its own test; keep each under ~30s):

1. **First frame shows the hero** — the home screen renders: the total card exists (pick a stable
   anchor — the settings door or the tab bar's five items).
2. **Every root tab opens** — tap each tab in the bar; assert one stable element per destination
   appears (دارایی list header, دفتر day list or its empty state, گزارش title, آینده title,
   تنظیمات title).
3. **Two-tap delete really takes two taps** — add a manual holding (or manual transaction —
   whichever flow is reachable without SMS permission), start a delete, assert the armed state is
   visible (the confirm affordance appears) and that a single tap did **not** delete; confirm and
   assert it is gone.
4. **The asset picker adds a holding** — open the add flow from دارایی, pick a type (gold gram),
   enter an amount with Persian digits, save, assert the row appears.
5. **Settings rooms open** — enter تنظیمات, open one door (پشتیبان‌گیری room), assert its
   paragraph is on screen, navigate back.

Rules: no network dependence (rates may be absent — assert on structure, never on prices); no
SMS permission flows (permission dialogs are device-modal); reset state per test where cheap
(each test tolerates existing data rather than assuming a fresh install).

Add `LiteSmokeTest.kt` guarded to the lite flavour (or a single test class using
`BuildConfig.LITE` to branch): on lite, assert the tab bar shows **only** the دارایی surface — the
ledger/report/budget tabs are absent (this is `EditionTest`'s claim, verified on a real frame).
Run it with `connectedLiteDebugAndroidTest`.

**Verify**: `./gradlew assembleFullDebugAndroidTest assembleLiteDebugAndroidTest` → BUILD
SUCCESSFUL.

### Step 3: Run it

If an emulator or device is reachable (`adb devices` lists one), run
`./gradlew connectedFullDebugAndroidTest` and fix flakiness (prefer
`composeTestRule.waitUntil` over sleeps). If none is reachable on this machine, say so in your
report — Step 4's workflow is then the first executor of the suite, and the operator runs it.

**Verify**: either `connectedFullDebugAndroidTest` → BUILD SUCCESSFUL, or an explicit note that
no device was available locally.

### Step 4: The workflow — on demand + weekly, never blocking

Create `.github/workflows/ui-check.yml`: `on: workflow_dispatch` plus
`schedule: [{cron: "0 3 * * 5"}]`; one job, copying `release.yml`'s KVM step and emulator-runner
pin verbatim (API 30, `default`, x86_64, swiftshader, no-window), whose script runs
`./gradlew connectedFullDebugAndroidTest connectedLiteDebugAndroidTest`. Upload
`app/build/reports/androidTests/` as an artifact on failure. `permissions: contents: read`.
Actions SHA-pinned with version comments, matching the repo convention.

**Verify**: YAML parses; every `uses:` is SHA-pinned; `grep -n "workflow_dispatch" .github/workflows/ui-check.yml` → present.

## Test plan

The suite **is** the test plan: 6–7 instrumented tests (five full-flavour flows + the lite
assertion). Model assertions on Compose testing idioms (`onNodeWithText`, `onNodeWithTag`,
`waitUntil`); every test must assert a user-visible outcome, not a state flag.

## Done criteria

- [ ] `./gradlew assembleFullDebugAndroidTest assembleLiteDebugAndroidTest` exits 0
- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` still exits 0
- [ ] Suite files exist with ≥6 tests total; `ui-check.yml` exists, SHA-pinned, non-blocking
      (`workflow_dispatch` + `schedule` only)
- [ ] `check.yml` and `release.yml` are byte-identical to before (`git diff --stat` shows neither)
- [ ] Any added `testTag` lines are outside `Ui.kt`/`MainActivity.kt`
- [ ] `plans/README.md` status row updated (unless a reviewer maintains the index)

## STOP conditions

Stop and report back (do not improvise) if:

- A flow above cannot be anchored without adding a `testTag` inside `Ui.kt` or `MainActivity.kt`
  (they carry uncommitted local changes) — report which flow and which composable.
- `createAndroidComposeRule<MainActivity>()` cannot start the activity on the emulator (report
  the logcat; that is itself a finding).
- The lite flavour's instrumented variant fails to build for flavour-wiring reasons.
- Total suite runtime exceeds ~10 minutes on the emulator — report rather than trimming
  assertions silently.

## Maintenance notes

- Promotion path (a human decision, deliberately not in this plan): once the weekly run has been
  green for a few weeks, move the job into `release.yml` before the publish step, where it
  guards releases the way the launch smoke test does.
- Flaky-test policy: a flow that flakes twice gets a `waitUntil` fix or is deleted — a flaky
  gate is worse than no gate.
- Deliberately deferred: TalkBack/semantics assertions (worth a follow-up once tags exist), SMS
  permission flows (need `GrantPermissionRule` and a seeded inbox — design work of its own).