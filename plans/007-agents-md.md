# Plan 007: Write AGENTS.md — the operating contract for coding agents

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: confirm no `AGENTS.md` or `CLAUDE.md` exists at
> the repo root (`ls AGENTS.md CLAUDE.md` → both missing). If one exists, STOP.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (a new documentation file; no code changes)
- **Depends on**: none
- **Category**: dx
- **Planned at**: commit `26270fd`, 2026-09-10

## Why this matters

This repo is unusually well documented for humans (`DEVELOPMENT.md` is 31 KB of hard-won
operational knowledge), but there is no agent-facing entry point. An agent (or a new contributor)
has to mine prose to learn the four verification commands, the deploy ordering rule, and the
three or four "never do this" rules whose violation destroys user data (`SMS_SCHEMA`, durable
migrations, deploy-worker-first). A short root `AGENTS.md` that states the contract and points
into the long docs makes every future automated change safer and cheaper.

## Current state

- No `AGENTS.md`/`CLAUDE.md` at the repo root; `.claude/launch.json` exists but only configures
  dev servers (pwa on 5173, a static server on 8788).
- The authoritative sources to distill (read them before writing):
  - `DEVELOPMENT.md` — layout, build/test commands, editions, deploy rules, ledger architecture,
    parser-fix protocol, release protocol.
  - `DESIGN.md` — the visual language; ground truth is `Theme.kt`.
  - `PRODUCT.md` — who it's for, non-negotiables (no gamification, truncation not rounding, …).
  - `plans/README.md` — the improve-skill plan index and its status conventions.
- Verified commands (all green on this machine as of the planned-at commit):
  - Android: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` (`./gradlew test` runs
    more variants; CI runs exactly those three tasks plus `assembleDebug`)
  - Worker: `cd worker && npm ci && npm run check`
  - PWA: `cd pwa && npm ci && npm run check` (also `npm run test:browser` for Playwright)
  - Sync: `cd pwa && npm run build` first (sync serves `../pwa/dist`), then
    `cd sync && npm ci && npm run check`

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Confirm file renders as plain Markdown | open/read it | headings + tables look right |
| No accidental code changes | `git status --short` | only `AGENTS.md` (+ index row) |

## Scope

**In scope**:
- `AGENTS.md` (create, repo root)

**Out of scope** (do NOT touch):
- `DEVELOPMENT.md`, `DESIGN.md`, `PRODUCT.md`, `README.md` — AGENTS.md points at them, never
  duplicates their prose.
- Any source or workflow file.

## Git workflow

You are in a managed worktree; **do not commit or push** — the operator reviews and commits.

## Steps

### Step 1: Write `AGENTS.md`

Keep it under ~90 lines. Structure and required content (write in the repo's plain, direct voice;
no marketing tone):

1. **What this is** — two sentences: Persian-UI Android money app + two Cloudflare Workers + a
   PWA companion; users are in Iran; privacy is the product.
2. **Layout** — the four-line table from `DEVELOPMENT.md` (`app/`, `worker/`, `sync/`, `pwa/`).
3. **Verify your change** — the exact commands above, one per package, stated as "run the ones
   for every package you touched". Note the sync←pwa build dependency and that `pwa`'s tests read
   the golden SMS corpus out of `app/` (so parser changes need both sides run).
4. **Hard rules (data loss / user harm)** — verbatim-spirit distillation, each with a one-line why
   and a pointer:
   - Never bump `SMS_SCHEMA`; never add a destructive migration to `durable.db`. Hand-written,
     tested migrations only. (`DEVELOPMENT.md` § The ledger; `PersistenceTest` opens schemas 1–10.)
   - `derived.db` is the opposite by design: bump `PARSER_VERSION` in `Derived.kt` to reparse.
   - Money is `Long` Rial end-to-end; Toman is a display transform; displayed figures truncate,
     never round.
   - A new sync record `kind` ships **server first** (`sync/`), then clients — an unknown kind is
     a 400 for the whole batch on old servers.
   - Never point anything at `workers.dev` (DNS-sinkholed in Iran); the custom domains are the
     product's lifeline.
   - Persian copy is the interface: casual register, Persian digits, words before colour; new
     user-facing strings follow `DESIGN.md` and live beside the pure text functions they belong to.
   - No analytics, no accounts, no gamification — `PRODUCT.md` non-negotiables.
   - Secrets: signing keys live in `keystore.properties`/CI secrets; never commit or echo them.
5. **Conventions** — one flat Kotlin package with file-per-concept and why-comments; TS packages
   have no linter, match the hand style; tests are plain JVM (Robolectric where Android types are
   needed); the golden corpus in `app/src/test/resources/sms/*.json` pins both parsers — an
   unknown key fails the run.
6. **Deploy** — one paragraph: `npm run deploy` per worker, sync before app releases when record
   kinds change; Android releases go by annotated tag (Persian message) per `DEVELOPMENT.md`.
7. **Plans** — `plans/` holds improve-skill implementation plans; read `plans/README.md` before
   picking one up; honor STOP conditions.

**Verify**: `wc -l AGENTS.md` → ≤ ~100; every command in it copy-paste-runs from the repo root
(spot-check at least `./gradlew testFullDebugUnitTest --tests "com.doxigo.muchtoman.MoneyTest"`
and `cd worker && npm run typecheck`).

### Step 2: Cross-check against the sources

Re-read `DEVELOPMENT.md`'s "Shipping a parser fix", "The ledger", and "Releasing" sections and
confirm nothing in AGENTS.md contradicts them (especially: the SMS_SCHEMA prohibition, the
deploy-first rule, and the tag → versionCode arithmetic). Fix any drift in AGENTS.md, not in the
sources.

**Verify**: `git status --short` → only `AGENTS.md`.

## Test plan

None (docs). The verification is the command spot-checks in Step 1.

## Done criteria

- [ ] `AGENTS.md` exists at the repo root, ≤ ~100 lines, containing: the four verification
      command blocks, the `SMS_SCHEMA` prohibition, the deploy-worker-first rule, the
      `workers.dev` prohibition, and the Long-Rial rule
- [ ] Every command in it verified runnable (or explicitly marked as CI-only)
- [ ] Only `AGENTS.md` created; nothing else modified (`git status --short`)
- [ ] `plans/README.md` status row updated (unless a reviewer maintains the index)

## STOP conditions

Stop and report back (do not improvise) if:

- An `AGENTS.md`/`CLAUDE.md` already exists (someone got here first — reconcile instead).
- A verification command from "Current state" fails on this machine (report which; do not
  document a command you could not run).

## Maintenance notes

- AGENTS.md is a pointer file: when `DEVELOPMENT.md` changes a protocol, the pointer usually
  survives; only the distilled rules need a sync check in review.
- Reviewer: the risk in a file like this is drift-by-duplication — push back on any sentence that
  restates prose `DEVELOPMENT.md` already owns instead of linking to it.