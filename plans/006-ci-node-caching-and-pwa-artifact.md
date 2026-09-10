# Plan 006: Cache npm in CI and stop building the PWA twice

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: compare the "Current state" excerpt below
> against the live `.github/workflows/check.yml`. On a mismatch, treat it as a
> STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (CI-only; a wrong cache key or artifact wiring shows up as a red check, never in a shipped build)
- **Depends on**: none
- **Category**: dx
- **Planned at**: commit `26270fd`, 2026-09-10

## Why this matters

`check.yml` runs on every push and PR. Today every Node job cold-installs (`npm ci` with no
dependency cache), and the `sync` job — which only needs `pwa/dist` to exist because
`sync/wrangler.jsonc` points its assets at `../pwa/dist` — checks out again, installs the PWA's
dependencies again, and **rebuilds the PWA that the `pwa` job just built and tested**. That
lengthens the critical path (sync waits on pwa, then repeats its work) and burns CI minutes on
every push.

## Current state

`.github/workflows/check.yml` (four jobs: `android`, `worker`, `pwa`, `sync`). The Node jobs all
use `actions/setup-node@39370e3970a6d050c480ffad4ff0ed4d3fdee5af # v4` with only `node-version: "22"`
— no `cache` input anywhere (verify: `grep -n "cache" .github/workflows/check.yml` → nothing).
The tail of the file:

```yaml
  sync:
    # The Worker's own build needs pwa/dist to exist, so the assets are built first.
    needs: pwa
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false
      - uses: actions/setup-node@39370e3970a6d050c480ffad4ff0ed4d3fdee5af # v4
        with:
          node-version: "22"
      - name: Build the PWA it serves
        working-directory: pwa
        run: npm ci && npm run build
      - name: Sync Worker
        working-directory: sync
        # Real Durable Objects under workerd, not a mock: this is the first stateful thing in
        # the repo and it holds people's ledgers.
        run: npm ci && npm run check
```

The `pwa` job already runs `npm ci --no-audit && npm run check` (whose `check` script ends in
`vite build`, producing `pwa/dist`), then installs Playwright and runs browser tests.

Repo conventions to preserve:
- **Actions are pinned by full commit SHA with a `# vN` comment.** Any action you add must follow
  that (use `actions/upload-artifact` and `actions/download-artifact` pinned to their current
  release SHAs — look the SHA up from the action's GitHub releases page; the repo's Dependabot
  config tracks `github_actions`, so an exact current pin is fine).
- Comments in the workflow explain *why*; keep the existing ones and write new ones in that voice.
- `permissions: contents: read` and the concurrency group stay untouched.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| YAML sanity | `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/check.yml'))"` | exit 0 (PyYAML is available; if not, use `ruby -ryaml -e "YAML.load_file('.github/workflows/check.yml')"`) |
| Local job dry-run | none available (no `act` assumed) | — |

Verification is structural (YAML parses, keys correct); the real gate is the next push's CI run,
which the operator watches.

## Scope

**In scope**:
- `.github/workflows/check.yml`

**Out of scope** (do NOT touch):
- `.github/workflows/release.yml` — tag-time workflow; its economics are different (one run per
  release) and it must stay boring.
- Any `package.json`/`package-lock.json`.
- The `android` job (Gradle caching is already handled by `gradle/actions/setup-gradle`).

## Git workflow

You are in a managed worktree; **do not commit or push** — the operator reviews and commits.

## Steps

### Step 1: npm dependency caching on all three Node jobs

On each `actions/setup-node` step (`worker`, `pwa`, `sync` jobs), add:

```yaml
        with:
          node-version: "22"
          cache: npm
          cache-dependency-path: <job's lockfile>   # worker/package-lock.json, pwa/package-lock.json, or sync/package-lock.json
```

For the `sync` job the cache must cover **only** `sync/package-lock.json` after Step 2 (the PWA
install disappears from that job).

**Verify**: the YAML-parse command above → exit 0; `grep -c "cache: npm" .github/workflows/check.yml` → 3.

### Step 2: Hand `pwa/dist` from the pwa job to the sync job

In the `pwa` job, after the `npm run check` step (which builds `dist`) and **before** the browser
steps (order within the job doesn't matter for correctness, but placing it right after the build
keeps the artifact upload from waiting on Playwright), add:

```yaml
      - name: Hand the built assets to the sync job
        uses: actions/upload-artifact@<pinned SHA> # vN
        with:
          name: pwa-dist
          path: pwa/dist
          retention-days: 1
          if-no-files-found: error
```

In the `sync` job, replace the whole "Build the PWA it serves" step with:

```yaml
      - name: Fetch the PWA it serves
        uses: actions/download-artifact@<pinned SHA> # vN
        with:
          name: pwa-dist
          path: pwa/dist
```

and update the job's top comment: the Worker's build still needs `pwa/dist` to exist — it now
arrives as the artifact the `pwa` job already built and tested, instead of a second build whose
output nothing had tested.

Keep `needs: pwa` (it is what makes the artifact available).

**Verify**: YAML parses; `grep -n "npm run build" .github/workflows/check.yml` returns **no** line
in the sync job (the only PWA build left is inside the pwa job's `npm run check`).

### Step 3: Playwright browser caching (small, same spirit)

In the `pwa` job, the `npx playwright install --with-deps chromium` step re-downloads the browser
every run. Cache it: add `actions/cache@<pinned SHA> # vN` before that step with
`path: ~/.cache/ms-playwright` and
`key: playwright-${{ runner.os }}-${{ hashFiles('pwa/package-lock.json') }}`. Keep
`--with-deps` (system deps are not cached).

**Verify**: YAML parses; the cache step precedes the install step.

## Test plan

No test files — the verification is the YAML sanity check plus the operator watching the next CI
run: all four jobs green, the sync job's log shows the artifact download instead of a vite build,
and second-run times drop on the Node jobs.

## Done criteria

- [ ] YAML parses (command above exits 0)
- [ ] `grep -c "cache: npm" .github/workflows/check.yml` → 3
- [ ] `grep -c "upload-artifact\|download-artifact" .github/workflows/check.yml` → 2
- [ ] The sync job contains no `npm ci` under `working-directory: pwa` and no PWA build
- [ ] Every added action is SHA-pinned with a version comment, matching the file's existing style
- [ ] Only `.github/workflows/check.yml` modified (`git status --short`)
- [ ] `plans/README.md` status row updated (unless a reviewer maintains the index)

## STOP conditions

Stop and report back (do not improvise) if:

- The live `check.yml` no longer matches the excerpt (drift).
- You cannot determine a current pinned SHA for `upload-artifact`/`download-artifact`/`cache`
  (no network): report and leave placeholders — do **not** use floating tags like `@v4`; an
  unpinned action breaks the file's security convention.
- The sync Worker's check turns out to read anything from `pwa/` other than `pwa/dist`.

## Maintenance notes

- Dependabot already tracks `github_actions`, so the new pins will ride the same update stream.
- If the PWA build ever becomes flavour-dependent (e.g. env-specific `dist`), the artifact must
  carry that variant explicitly — revisit the single `pwa-dist` name.
- Deliberately deferred: caching in `release.yml` (runs once per release; correctness beats speed
  there) and any job-level parallelisation beyond removing the duplicated build.