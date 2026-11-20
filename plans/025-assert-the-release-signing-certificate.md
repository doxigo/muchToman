# Plan 025: A release is published only if it is signed with the certificate installed phones expect

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 3e6246e..HEAD -- .github/workflows/release.yml`
> If the file changed since this plan was written, compare the "Current state"
> excerpts against the live file before proceeding; on a mismatch, STOP.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (a wrong fingerprint fails the release job loudly before anything is published)
- **Depends on**: none — but **blocked on an operator input**, see below
- **Category**: dx / release safety
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

Android only installs an update over an existing app when both are signed with the same
certificate. The release job builds with whatever the four keystore secrets hold and publishes
the result. If a secret is ever rotated, mistyped or replaced, the job would publish a validly
signed APK that no installed phone can update to; the only way forward for a user is to
uninstall, which deletes their balances (the app is excluded from Android backup). Nothing
catches this today — the emulator smoke test in the `verify` job uses a throwaway key. One step
that compares the built APK's certificate with a pinned fingerprint turns that into a failed
job.

**Open question for the operator (this plan cannot start without it):** the SHA-256
fingerprint of the release signing certificate. It is public information — it is inside every
released APK — but the executor must not read `keystore.properties` or the keystore to get it.
The operator obtains it with either of:

```bash
# from any released APK (build-tools ships apksigner):
~/Library/Android/sdk/build-tools/36.0.0/apksigner verify --print-certs muchtoman-v1.4.8.apk | grep 'SHA-256'
# or from the keystore (prompts for the store password):
keytool -list -v -keystore muchtoman-release.jks -alias <alias> | grep 'SHA256:'
```

`apksigner` prints 64 lowercase hex characters; `keytool` prints colon-separated upper-case
pairs. The workflow below compares in the `apksigner` form, so a `keytool` value must have its
colons removed and be lower-cased.

## Current state

- `.github/workflows/release.yml`, job `build` (starts ~line 122). The relevant steps, in order:

  ```yaml
        - name: Recreate signing config
          env:
            KEYSTORE_B64: ${{ secrets.KEYSTORE_B64 }}
            …
        - name: Build signed release
          # Same tag arithmetic as the verify job — the two builds must carry one identity.
          run: |
            v=${GITHUB_REF_NAME#v}
            …
            ./gradlew assembleRelease \
              -Pmuchtoman.versionName=$v \
              -Pmuchtoman.versionCode=$((maj*1000000 + min*10000 + pat*100))

        - name: Remove signing material
          if: always()
          run: rm -f muchtoman-release.jks keystore.properties
  ```

  followed by `Prepare release artifacts`, which moves
  `app/build/outputs/apk/full/release/app-full-release.apk` and
  `app/build/outputs/apk/lite/release/app-lite-release.apk` to their published names.
- The job runs on `ubuntu-latest`, whose image provides the Android SDK at `$ANDROID_HOME`
  with `build-tools/<version>/apksigner`.
- The workflow's style: every step that can fail for a non-obvious reason carries a comment
  saying why it exists (read the comments above `Build signed release` and in the `verify` job
  before writing yours), and failures use `echo "::error::…"; exit 1`.
- The `verify` job (top of the file) signs with a one-day CI key on purpose. It is NOT the
  release key and must not be checked against the pinned fingerprint.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| YAML still parses | `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml'))" && echo yaml-ok` | `yaml-ok` |
| The step's shell logic, locally | see step 2 | as stated there |

There is no way to run the release workflow from a worktree; the real proof is the next tag.

## Scope

**In scope** (the only file you should modify):
- `.github/workflows/release.yml` — one new step in the `build` job, plus one `env` entry.

**Out of scope** (do NOT touch):
- The `verify` job, the signing-config step, the artifact names, the publish job.
- `keystore.properties`, `muchtoman-release.jks`, `local.properties` — do not open or print them.
- `.github/workflows/check.yml`, `ui-check.yml`.

## Git workflow

- Commit on the worktree's current branch, one commit.
- Subject in English only, lower-case area prefix then a plain description. Suggested:
  `release: fail the build when the APK is not signed with the release certificate`.
- No `Co-Authored-By` or any other trailer. Do not push, merge, tag or touch `main`.

## Steps

### Step 1: Pin the fingerprint

At the top level of the `build` job (sibling of `runs-on`), add:

```yaml
    env:
      # SHA-256 of the release signing certificate, as `apksigner verify --print-certs` prints
      # it. Public: it is inside every published APK. Android refuses an update signed by any
      # other certificate, and the app's data is not in Android's backup, so an APK signed with
      # the wrong key strands every installed phone. Change it only with the keystore itself.
      RELEASE_CERT_SHA256: "<the 64 hex characters the operator supplied>"
```

If the job already has an `env:` block, add the key to it instead of creating a second one.

**Verify**: `grep -c 'RELEASE_CERT_SHA256' .github/workflows/release.yml` → at least `1`, and
the value matches `^[0-9a-f]{64}$`.

### Step 2: Assert it right after the build

Insert a new step between `Build signed release` and `Remove signing material`:

```yaml
      # Before anything is renamed, summed or uploaded. apksigner also fails here on an
      # unsigned APK, which is what a missing keystore secret produces.
      - name: Check the signing certificate
        run: |
          apksigner=$(ls "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)
          for apk in app/build/outputs/apk/full/release/app-full-release.apk \
                     app/build/outputs/apk/lite/release/app-lite-release.apk; do
            got=$("$apksigner" verify --print-certs "$apk" \
              | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
            if [ "$got" != "$RELEASE_CERT_SHA256" ]; then
              echo "::error::$apk is signed by ${got:-nothing}, not the release certificate"
              exit 1
            fi
          done
```

**Verify** the shell logic locally against an APK the operator already has (ask for a path if
none is in the worktree; do not download one yourself):

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
apksigner=$(ls "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)
"$apksigner" verify --print-certs <path-to-released.apk> | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
```

→ prints exactly the pinned 64 characters. Then the YAML check from the commands table →
`yaml-ok`.

## Test plan

No automated test exists for workflows in this repo. The checks are: the YAML parses, the
`sed` expression extracts the digest from a real released APK, and the digest equals the pinned
value. The first tag after this lands is the end-to-end proof — watch that run.

## Done criteria

- [ ] `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))"` exits 0
- [ ] The new step sits after `Build signed release` and before `Remove signing material`
- [ ] `RELEASE_CERT_SHA256` is 64 lowercase hex characters supplied by the operator
- [ ] `git status --short` lists only `.github/workflows/release.yml`

## STOP conditions

- **The operator has not supplied the fingerprint.** Do not guess one, do not compute it from
  the keystore or `keystore.properties`, and do not commit a placeholder.
- The supplied value is not 64 hex characters after normalising.
- `apksigner verify --print-certs` on a released APK prints a line that the `sed` expression
  does not match (the output format differs) — report the actual line.
- The steps in "Current state" are not in the stated order any more.

## Maintenance notes

- If the app ever adopts key rotation (APK Signature Scheme v3 lineage), `Signer #1` is the
  newest key and this check needs the lineage's fingerprints instead.
- The pinned value lives beside the job, not in a secret, on purpose: a secret could drift
  together with the keystore secrets, which is exactly the failure this step exists to catch.
- Reviewer: confirm the step runs before `Remove signing material` only incidentally — it
  needs the APKs, not the keystore.
