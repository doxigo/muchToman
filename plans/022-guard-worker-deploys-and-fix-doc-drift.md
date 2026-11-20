# Plan 022: A Worker deploy ships a clean, freshly built tree, and the docs say what the code does

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 3e6246e..HEAD -- sync/package.json worker/package.json DEVELOPMENT.md AGENTS.md README.md README.fa.md app/build.gradle.kts pwa/test/mirror.test.ts tools`
> If any of these changed since this plan was written, compare the "Current
> state" excerpts against the live files before proceeding; on a mismatch, STOP.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (the deploy scripts change; nothing is deployed by this plan)
- **Depends on**: none
- **Category**: dx / docs
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

Both Workers are deployed by hand with a bare `wrangler deploy`. The sync Worker serves
`../pwa/dist` as the iPhone app, and nothing rebuilds that directory or checks the tree is
clean first: a stale or half-edited build can go to every iPhone user with no record of which
commit it was. Separately, a handful of lines in the docs now say something the code does not
do, and one of them (`FLAG_SECURE`) overstates a privacy protection. After this plan a deploy
rebuilds the PWA, refuses uncommitted changes, and records its commit; and the docs match.

**This plan must never run a real deploy.** Every verification uses `--dry-run` or the guard
script alone.

## Current state

- `sync/package.json` scripts: `"deploy": "wrangler deploy"`, and
  `"check": "pnpm run typecheck && pnpm run test && wrangler deploy --dry-run"`.
- `worker/package.json` scripts: the same two lines.
- `sync/wrangler.jsonc` has `"assets": { "directory": "../pwa/dist", … }`; `pwa/dist/` is
  gitignored. `pwa/package.json` has `"build": "tsc --noEmit && vite build"`.
- All three Node packages are pnpm (`"packageManager": "pnpm@10.30.2"`). Always `pnpm run
  deploy` — a bare `pnpm deploy` is pnpm's own unrelated command.
- `wrangler deploy --help` lists `--message  A descriptive message for this Worker Version`.
- `tools/` holds small repo scripts (`tools/make-font.py`, `tools/site/assets.mjs`); a new
  shell script belongs there.
- Doc lines that disagree with the code (verified at the planned commit):
  1. `DEVELOPMENT.md` ~line 65 — a fenced block containing only `./gradlew test`. CI's own
     comment (`.github/workflows/check.yml`) says plain `test` runs the same suite six times;
     the gate is `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint`.
  2. `DEVELOPMENT.md` ~lines 707-711, under `### Tests`:

     ```bash
     ./gradlew test                    # every JVM test, no device
     cd sync && pnpm run check         # real Durable Objects under workerd
     cd pwa  && pnpm run check         # incl. the shared-corpus agreement check
     ```

     `sync`'s check needs `pwa/dist`, which only `pwa`'s check (it ends in `vite build`)
     creates, so on a fresh clone the second line fails.
  3. `DEVELOPMENT.md` ~lines 406-407:

     ```
     - `FLAG_SECURE` is set while the app lock is on, so `adb screencap` returns black — that is
       the flag working, not a bug.
     ```

     `app/src/main/java/com/doxigo/muchtoman/MainActivity.kt` ~lines 61-76 does this instead:

     ```kotlin
     LaunchedEffect(state.lockEnabled) {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             setRecentsScreenshotEnabled(!state.lockEnabled)
         } else if (state.lockEnabled) {
             window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
         } else {
             window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
         }
     }
     ```

     with a comment explaining the choice: `FLAG_SECURE` blocks every screen capture, which
     also stops sending a screenshot to whoever helps with the phone, so on Android 13+ only
     the app-switcher thumbnail is hidden.
  4. `DEVELOPMENT.md` ~lines 720-723, under `### Deploying`:

     ```bash
     cd pwa  && pnpm run build         # sync/ serves this as its assets
     cd sync && pnpm run deploy
     ```
  5. `app/build.gradle.kts` ~line 82: `// Release signing. Create keystore.properties yourself
     (see README) — it is gitignored`. The instructions are in `DEVELOPMENT.md` § "Signing
     setup (once)", not the README.
  6. `pwa/test/mirror.test.ts` ~line 473: `'Diagnostics.kt': 'the crash sheet, not ported yet',`
     while `DEVELOPMENT.md` (§ "Usage and crash reports") says: "The PWA does not send crash
     reports: a browser error does not end the app the way it does on Android, extensions and
     failed fetches throw their own, and the bundle ships without source maps, so the reports
     would be noise." It is a decision, not a backlog item.
  7. `AGENTS.md` ~line 51: `- **Money is `Long` Rial end-to-end.** Toman is a display
     transform, never storage; displayed figures truncate, never round.` True for the ledger,
     but the per-bank balance folded from SMS is a `Double` in Toman kept in prefs:
     `Sms.kt` `data class BankAccount(… val balance: Double = 0.0, …)`, converted with
     `Math.round(folded.balance * 10.0)` in `AppVm.kt` ~line 816.
  8. `README.md` ~lines 257-258: "The sync service can see opaque household/member ids, record
     types, timestamps, and ciphertext". A shared SMS-derived transaction's record id is
     `txn:<member>:<hex of "s:<srcHash>:<seq>">` (`Sync.kt` `familyTxnId`), and `srcHash` is
     `sha256(sender + time + message body)` (`Ledger.kt` `srcHash`). The id is not the message,
     but it is a fingerprint of it, and the README should say so. `README.fa.md` line ~188 has
     the Persian twin: «سرویس همگام‌سازی فقط شناسه‌های نامفهوم خانواده و اعضا، نوع رکوردها،
     زمان‌ها و متن رمزنگاری‌شده را می‌بیند؛».

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| PWA gate (also builds `pwa/dist`) | `cd pwa && pnpm install --frozen-lockfile && pnpm run check` | exit 0 |
| Sync gate (after the PWA build) | `cd sync && pnpm install --frozen-lockfile && pnpm run check` | exit 0 |
| Worker gate | `cd worker && pnpm install --frozen-lockfile && pnpm run check` | exit 0 |
| Kotlin still compiles after the comment edit | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk" && ./gradlew compileFullDebugKotlin` | `BUILD SUCCESSFUL` |

## Scope

**In scope** (the only files you should modify or create):
- `tools/deploy-guard.sh` (create)
- `sync/package.json`, `worker/package.json` — the `deploy` script line only
- `DEVELOPMENT.md`, `AGENTS.md`, `README.md`, `README.fa.md` — only the lines named above
- `app/build.gradle.kts` — the one comment
- `pwa/test/mirror.test.ts` — the one string

**Out of scope** (do NOT touch):
- `sync/src`, `worker/src`, any `wrangler.jsonc` — no behaviour changes.
- `.github/workflows/*` — no deploy job is added here.
- `MainActivity.kt` — the lock behaviour is a recorded decision; only the doc is wrong.
- Migrating the `Double` Toman balance to `Long` Rial — a hand-kept balance no rescan can
  rebuild; AGENTS.md is corrected instead.
- Any real `wrangler deploy`, `wrangler login`, or anything that needs Cloudflare credentials.

## Git workflow

- Commit on the worktree's current branch: one commit for the deploy guard, one for the docs.
- Subjects in English only, lower-case area prefix then a plain description, like the log's
  `docs: drop the stale audience line, count all five notification kinds, fix moved pointers`.
- No `Co-Authored-By` or any other trailer. Never describe users as "she/her" in a commit
  message. Do not push, merge or touch `main`.

## Steps

### Step 1: Write the guard script

Create `tools/deploy-guard.sh`:

```sh
#!/bin/sh
# Refuses a Worker deploy while any of the named top-level directories holds uncommitted or
# untracked files. A deploy uploads whatever is on disk, so without this "what is live?" has no
# answer in git. To deploy a deliberate local state anyway, call `pnpm exec wrangler deploy`.
set -eu
cd "$(git rev-parse --show-toplevel)"
dirty=$(git status --porcelain -- "$@")
if [ -n "$dirty" ]; then
  echo "deploy refused: uncommitted changes in: $*" >&2
  echo "$dirty" >&2
  exit 1
fi
```

**Verify** (use a directory you have not edited):

```bash
sh tools/deploy-guard.sh docs; echo "exit=$?"          # → exit=0
touch docs/guard-probe.tmp
sh tools/deploy-guard.sh docs; echo "exit=$?"          # → names docs/guard-probe.tmp, exit=1
rm docs/guard-probe.tmp
```

### Step 2: Route both deploy scripts through it

- `worker/package.json`:
  `"deploy": "sh ../tools/deploy-guard.sh worker && wrangler deploy --message \"$(git rev-parse --short HEAD)\"",`
- `sync/package.json`:
  `"deploy": "sh ../tools/deploy-guard.sh sync pwa && pnpm --dir ../pwa install --frozen-lockfile && pnpm --dir ../pwa run build && wrangler deploy --message \"$(git rev-parse --short HEAD)\"",`

Leave every other script, including `check`, exactly as it is.

**Verify** (no deploy happens in any of these):

```bash
node -e "for (const p of ['sync','worker']) JSON.parse(require('fs').readFileSync(p+'/package.json','utf8'))" && echo json-ok
cd worker && pnpm install --frozen-lockfile && pnpm exec wrangler deploy --dry-run --message probe; cd ..
cd pwa && pnpm install --frozen-lockfile && pnpm run check; cd ..
cd sync && pnpm install --frozen-lockfile && pnpm exec wrangler deploy --dry-run --message probe && pnpm run check; cd ..
```

→ `json-ok`, both dry runs exit 0 (they print the upload size and stop), both `check`s exit 0.

### Step 3: Commit the guard

Commit `tools/deploy-guard.sh` and the two `package.json` files. Suggested subject:
`deploy: build the PWA and refuse a dirty tree before a Worker deploy`.

**Verify**: `sh tools/deploy-guard.sh sync pwa worker; echo "exit=$?"` → `exit=0` (the tree is
clean again now that the edits are committed; `pwa/dist` and `node_modules` are ignored).

### Step 4: Correct the docs

Make exactly these edits (numbers match "Current state"):

1. `DEVELOPMENT.md` ~65: the block's command becomes
   `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint`.
2. `DEVELOPMENT.md` `### Tests` block becomes:

   ```bash
   ./gradlew testFullDebugUnitTest testLiteDebugUnitTest   # every JVM test, both editions, no device
   cd pwa  && pnpm run check         # incl. the shared-corpus agreement check; builds pwa/dist
   cd sync && pnpm run check         # real Durable Objects under workerd; serves ../pwa/dist, so after pwa
   ```
3. `DEVELOPMENT.md` ~406: replace the bullet with:

   ```
   - With the app lock on, Android 13+ hides only the app-switcher thumbnail
     (`setRecentsScreenshotEnabled(false)`): screenshots and screen recording still work, on
     purpose, so a screenshot can be sent to whoever helps with the phone. Below Android 13
     there is no narrow tool, so `FLAG_SECURE` is set and `adb screencap` returns black — that
     is the flag working, not a bug.
   ```
4. `DEVELOPMENT.md` `### Deploying` block becomes:

   ```bash
   cd sync && pnpm run deploy        # builds ../pwa first; refuses uncommitted changes in sync/ or pwa/
   ```
   and in `## The Worker`'s block (~line 111-116) leave the commands, adding after the block
   one sentence: `` `pnpm run deploy` refuses uncommitted changes in `worker/` and records the
   commit as the version message (`tools/deploy-guard.sh`). ``
5. `app/build.gradle.kts` ~82: `(see README)` → `(see DEVELOPMENT.md § Signing setup)`.
6. `pwa/test/mirror.test.ts` ~473: the value becomes
   `'the crash sheet: a browser error does not end the app, so there is nothing to report'`.
7. `AGENTS.md`: append to the "Money is `Long` Rial end-to-end" bullet:
   `One exception, kept on purpose: a bank account's SMS-folded balance is a `Double` in Toman
   in prefs (`BankAccount.balance`, `Sms.kt`) — hand-anchored figures no rescan can rebuild, so
   it is pinned by tests rather than migrated.`
8. `README.md` ~257: "opaque household/member ids, record types, timestamps, and ciphertext"
   → "opaque household/member ids, record ids (for a transaction read from an SMS, a hash of
   that message — never its text), record types, timestamps, and ciphertext".
   `README.fa.md` ~188: «شناسه‌های نامفهوم خانواده و اعضا، نوع رکوردها،» →
   «شناسه‌های نامفهوم خانواده و اعضا، شناسهٔ هر رکورد (که برای تراکنش پیامکی، هَشِ همان پیامک
   است و نه متن آن)، نوع رکوردها،».

Edit `README.fa.md` by exact string replacement only — do not run any Persian-text cleanup or
reflow tool over it (one has broken this file's inline HTML before).

**Verify**:

```bash
grep -n 'gradlew test *$\|gradlew test  ' DEVELOPMENT.md               # → no output
grep -c 'setRecentsScreenshotEnabled' DEVELOPMENT.md                    # → 1
grep -n 'see README' app/build.gradle.kts                               # → no output
grep -n 'not ported yet' pwa/test/mirror.test.ts                        # → no output
grep -c 'record ids' README.md                                          # → 1
grep -c 'شناسهٔ هر رکورد' README.fa.md                                   # → 1
cd pwa && pnpm run check                                                # → exit 0
```

and the Kotlin compile command from the table → `BUILD SUCCESSFUL`.

### Step 5: Commit the docs

Suggested subject: `docs: match the test, lock, deploy and sync-id lines to the code`.

## Test plan

No new tests. `pwa/test/mirror.test.ts` is edited in one string that the test only uses as a
reason label; `pnpm run check` in `pwa/` proves it still passes. The guard script is verified
by the probe in step 1.

## Done criteria

- [ ] `sh tools/deploy-guard.sh sync pwa worker` exits 0 on the committed tree and 1 with a probe file present
- [ ] `grep -c 'deploy-guard.sh' sync/package.json worker/package.json` prints `1` for each
- [ ] `cd pwa && pnpm run check`, `cd sync && pnpm run check`, `cd worker && pnpm run check` all exit 0
- [ ] Every grep in step 4's verification gives the stated result
- [ ] `git status --short` is empty and `git diff --stat 3e6246e..HEAD` lists only in-scope files
- [ ] No deploy was run (`wrangler deploy` appears in your command history only with `--dry-run`)

## STOP conditions

- `wrangler deploy --dry-run --message probe` rejects `--message` — report the wrangler
  version; do not invent another way to stamp the commit.
- Any verification would require Cloudflare credentials or a real deploy.
- A "Current state" excerpt does not match the live file.
- `pnpm install --frozen-lockfile` fails (lockfile drift) — report, do not regenerate the lock.

## Maintenance notes

- The guard refuses untracked files too. That is intended (an untracked source file would be
  bundled), but it means a scratch file in `sync/`, `pwa/` or `worker/` blocks a deploy.
- `wrangler deployments list` in each package now shows the commit beside each version; that
  is the answer to "what is live?". A response header or meta endpoint was considered and
  left out — nothing reads one yet.
- A CI deploy job (manual dispatch, behind `check`) is the next step if hand deploys become a
  problem; it needs a Cloudflare token in CI, which is an operator decision.
- The README now states that a shared SMS transaction's id is a hash of the message. Replacing
  those ids with a keyed hash is a sync-format migration (new ids, tombstones for the old,
  `SYNC_READ_VERSION` bump, both clients) and was deliberately not planned here.
