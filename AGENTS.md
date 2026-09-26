# AGENTS.md — the operating contract for coding agents

This is a pointer file. The prose lives in `DEVELOPMENT.md` (operations), `DESIGN.md`
(visual language; ground truth is `Theme.kt`), and `PRODUCT.md` (who it's for and what is
non-negotiable). When this file and those disagree, they win — fix the drift here.

## What this is

A Persian-UI Android app that answers «چقدر تومن دارم؟» — how much is all my money, in
Toman — plus two Cloudflare Workers and a PWA that is the same app for iPhone users. The users are in Iran; privacy is
the product: no accounts, no analytics, sync is ciphertext the server cannot read.

## Layout

| Dir | What |
|---|---|
| `app/` | Android app (Kotlin + Compose) |
| `worker/` | Cloudflare Worker that serves prices and public-wallet balances |
| `sync/` | Cloudflare Worker + Durable Object for encrypted family sync |
| `pwa/` | the same app in the browser (iPhone users), served by `sync/` |

## Verify your change

Run the block for every package you touched:

```bash
./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint   # app/ (CI runs these + assembleDebug)
cd worker && npm ci && npm run check                         # worker/
cd pwa && npm ci && npm run check                            # pwa/ (browser tests: npm run test:browser)
cd pwa && npm run build && cd ../sync && npm ci && npm run check   # sync/ serves ../pwa/dist, build it first
```

`pwa/`'s tests read the golden SMS corpus out of `app/src/test/resources/sms/`, so a parser
change needs both the Gradle and the pwa blocks run.

## Hard rules (data loss / user harm)

- **Every feature ships on both: `app/` and `pwa/`, in the same change.** The PWA is the app for
  iPhone users, not a companion: same screens, same behaviour, same Persian copy, same numbers.
  A user-facing change to one is unfinished until the other has it, and its tests run in both
  blocks below. The only standing exceptions are what a browser cannot do — reading SMS
  automatically (the PWA takes a pasted message instead), the home-screen widget, background
  work and notifications while closed, and TSETMC prices (geo-blocked, no CORS). Anything else
  that cannot be ported gets said in the change, never silently dropped.

- **Never bump `SMS_SCHEMA`; never add a destructive migration to `durable.db`.** Hand-written,
  tested migrations only — a destructive fallback deletes balances no rescan can rebuild.
  (`DEVELOPMENT.md` § The ledger; `PersistenceTest` opens schemas 1–10 through the real migrations.)
- **`derived.db` is the opposite by design**: bump `PARSER_VERSION` in `Derived.kt` to reparse;
  its version bump drops every table on purpose (~40 ms rebuild).
- **Money is `Long` Rial end-to-end.** Toman is a display transform, never storage; displayed
  figures truncate, never round.
- **A new sync record `kind` ships server first** (`sync/`), then clients — an old server
  rejects an unknown kind with a 400 for the whole batch, stopping all sync from that phone.
- **Never point anything at `workers.dev`.** Iran DNS-sinkholes the whole domain; the custom
  domains (`rates.muchtoman.com`, `sync.muchtoman.com`) are the product's lifeline.
- **Persian copy is the interface**: casual register, Persian digits, words carry warnings and
  colour only confirms. New user-facing strings follow `DESIGN.md` and live beside the pure
  text functions they belong to.
- **No analytics, no accounts, no gamification** — `PRODUCT.md` non-negotiables.
- **Secrets**: signing keys live in `keystore.properties` / CI secrets; never commit or echo them.

## Conventions

One flat Kotlin package, file-per-concept, comments explain *why*. The TypeScript packages
have no linter or formatter — match the hand style around you. Tests are plain JVM
(Robolectric where Android types are needed); no device. The golden corpus in
`app/src/test/resources/sms/*.json` pins both parsers — real messages kept verbatim, and an
unknown key fails the run, so a typo cannot quietly weaken it.

## Deploy

`npm run deploy` in each Worker's directory. When record kinds change, deploy `sync/` before
any app release that sends them. Android releases go by annotated tag with a Persian message
(that message is the in-app update sheet); `versionName` comes from the tag and `versionCode`
from arithmetic on it (`v1.2.3` → `1020300`) — see `DEVELOPMENT.md` § Releasing.

## Plans

`plans/` holds improve-skill implementation plans. Read `plans/README.md` before picking one
up, follow the plan's steps exactly, and honor its STOP conditions.
