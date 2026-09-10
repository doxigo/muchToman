# Plan 002: Pin the rates Worker's upstream adapters with fixture tests

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: compare the "Current state" excerpts below
> against the live code (`worker/src/index.ts`, `worker/test/download.test.ts`).
> On a mismatch, treat it as a STOP condition. (The repo had uncommitted
> changes when this was planned, so excerpt comparison — not `git diff` against
> the planned-at SHA — is the authoritative check. None of the in-scope files
> were among the uncommitted ones.)

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW (test-only; no production code changes)
- **Depends on**: none
- **Category**: tests
- **Planned at**: commit `26270fd`, 2026-09-10

## Why this matters

The rates Worker (`worker/src/index.ts`, ~1,477 lines) is the single source of every price the
Android app multiplies holdings by. Its upstream adapters scrape bonbast (an HTML token dance),
tgju (a widget API quoting **Rial**, divided by 10), tgju HTML price pages (silver, سکه پارسیان),
bitpin, tetherland, CoinGecko, and Binance. Today `worker/test/` covers only `checks.ts` (pure
plausibility logic) and the `/download` APK proxy — **zero tests execute the adapters or the
`/rates` assembly**. A markup change, a Rial/Toman mix-up, or a broken fallback chain ships
silently and misvalues every user's savings — the one number the app exists to get right.
`DEVELOPMENT.md` documents that unit mix-ups are real and intermittent (bonbast quotes JPY/AMD
per 10 and IQD per 100 where tgju quotes per 1). These tests pin today's upstream contracts so
any adapter regression fails CI instead of production.

## Current state

- `worker/src/index.ts` — the Worker. Relevant symbols:
  - `fetchBonbast()` (~L285–317): fetches `https://bonbast.com/` homepage, extracts a token via
    `html.match(/param:\s*"([^"]+)"/)?.[1]`, then POSTs `param=<token>` (form-encoded, with the
    first `set-cookie` value echoed back) to `https://bonbast.com/json`, and maps fields through
    `BONBAST_MAP` (exported, ~L236: `usd: 'usd1'`, `gold18: 'gol18'`, `coin_emami: 'emami1'`, …).
    Bonbast values are **already Toman**.
  - `fetchTgjuKeys(map)` (~L319–343): GETs
    `https://api.tgju.org/v1/widget/tmp?keys=<comma-joined values of TGJU_MAP>`, reads
    `response.indicators[]` records by `name`, takes `p` as the price, and **divides by 10**
    (Rial → Toman). `TGJU_MAP` is exported (~L254: `usd: 'price_dollar_rl'`, `gold18: 'geram18'`, …).
    Timestamps come from `updated_at`/`created_at` per indicator via `tgjuStampMs`.
  - `fetchTgjuPage(path, rows)` (~L362–394): GETs `https://www.tgju.org/<path>`, splits the HTML on
    `'<tr '`, matches a row when it contains `nameslug="<slug>"` **or** `data-market-row="<slug>"`,
    then reads the price from `/<td class="nf">([\d,]+)<\/td>/` in that same row and divides by 10.
    Throws `no rows matched on /<path>` when nothing matched.
  - `fetchSilver` (~L405): `fetchTgjuPage('gold-chart', { silver_999: 'silver_999', silver_925: 'silver_925' })`.
  - `fetchParsian()` (~L437–446): `fetchTgjuPage(encodeURIComponent('قیمت-سکه-پارسیان'), …)` with a
    slug per size: `parsianSlug(soot)` (~L422) builds `سکه-پارسیان-<gramsFa>-<remainderFa>` using
    Persian digits, e.g. 100 سوت → `سکه-پارسیان-۰-۱۰۰`, 1500 → `سکه-پارسیان-۱-۵۰۰`. Row ids are
    `parsian_100` … `parsian_1500`; a derived `parsian` per-سوت rate is added via
    `deriveParsianPerSoot` (exported from `checks.ts`; reference order `PARSIAN_REFERENCE = [1000, 1500, 1400, …]`).
  - `fetchFiat` (~L448–459): `firstOf([{name:'bonbast', run: fetchBonbast}, {name:'tgju', run: fetchTgju}], v => usdBandVerdict(v.prices.usd))`
    — the USD plausibility band gates **each source**, so an implausible bonbast advances the chain to tgju.
  - `fetchBitpin()` (~L592–612): GETs `https://api.bitpin.ir/v1/mkt/markets/`, keeps `results[]`
    entries whose `code` ends `_IRT`, price from `market.price`, Persian name from
    `currency1.title_fa` only if it contains Persian characters (`/[؀-ۿ]/`).
  - `fetchTetherland()` (~L634–640): GETs `https://api.tetherland.com/currencies`, reads
    `data.currencies.USDT.price` → `{ prices: { usdt }, namesFa: { usdt: 'تتر' } }`.
  - `fetchBinanceUsd()` (~L619–631): GETs `https://api.binance.com/api/v3/ticker/price`, keeps
    symbols ending `USDT` → `{ btc: <usd>, … }`. Used only when CoinGecko fails.
  - `fetchCoinGecko()` (~L538–584): GETs
    `https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=250&page=1`
    and `https://api.coingecko.com/api/v3/coins/list?include_platform=true` (the second one
    `.catch(() => [])`), builds `usd` prices keyed by lowercase `symbol` and the `coins[]`
    catalogue (icon URLs rewritten through `proxiedCoinIcon`, wallets via `walletsFor`).
  - `fetchLatestRelease()` (~L937–965): GETs `https://api.github.com/repos/doxigo/muchToman/releases/latest`.
  - `buildRates()` (~L1138–1323): `Promise.allSettled` over all of the above; assembles `toman`,
    `coins`, `sources` (per-chain strings like `ok via bonbast`, `failed: …`,
    `ok via tgju, 2/2 grades`), applies `applyPlausibility`, prefers Tehran crypto quotes and
    cross-rates the rest by `usd` (drops a coin when both exist and disagree beyond
    `CRYPTO_CROSS_MAX_RATIO`), and answers 200 with an `etag` header and
    `cache-control: public, max-age=…` (502 `no-store` when `toman` is empty).
  - The default export's `fetch` (~L1360–1476): `/rates` is served only on hostname
    `rates.muchtoman.com` (or `*.workers.dev`); it checks `caches.default` under a fixed key,
    else calls `coalescedRates` which caches via `ctx.waitUntil(cache.put(...))`.
- `worker/src/checks.ts` — exported helpers the tests may import: `PARSIAN_SOOT`
  (`[100, 200, …, 1500]`), `PLAUSIBLE_BTC_USD`, `usdBandVerdict`, `deriveParsianPerSoot`.
- `worker/test/checks.test.ts` — the magnitude conventions to reuse (2026-08 market values):
  `usd: 187_000`, `gold18: 17_800_000`, `gold_mesghal: 77_100_000`, `silver_999: 270_000`,
  `silver_925: 250_000`, `coin_emami: 210_000_000`, `coin_bahar: 205_000_000`,
  `coin_nim: 115_000_000`, `coin_rob: 65_000_000`, `coin_gerami: 33_000_000`, and
  `parsianPrice(soot) = soot * 17_800 * 1.1 + 400_000`. **Use these exact magnitudes** so every
  plausibility invariant passes; values far from them get dropped by `applyPlausibility` and the
  test fails confusingly.
- `worker/test/download.test.ts` — **the structural pattern to copy**: `vi.stubGlobal('fetch', …)`
  routing by URL, `vi.stubGlobal('caches', { default: { match, put } })` backed by a local `Map`,
  `const ctx = { waitUntil: (p) => pending.push(p) } as ExecutionContext`, an
  `afterEach(() => vi.unstubAllGlobals())`, and invoking the Worker as
  `worker.fetch(new Request('https://rates.muchtoman.com/…'), { ASSETS: {} as Fetcher }, ctx)`.
- `worker/vitest.config.mts` — plain node, includes `test/**/*.test.ts`. Its header comment says
  "Nothing here touches the network" — your tests must keep that true.

Conventions: no lint/formatter in this package; match the existing hand style (2-space indent,
single quotes, comment paragraphs explaining *why*). TypeScript is strict; `npm run check` runs
`tsc --noEmit && vitest run && wrangler deploy --dry-run`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Install | `cd worker && npm ci` | exit 0 |
| Typecheck | `cd worker && npm run typecheck` | exit 0 |
| Tests | `cd worker && npm run test` | all pass |
| Full gate | `cd worker && npm run check` | exit 0 |

## Scope

**In scope** (the only files you should create/modify):
- `worker/test/rates.test.ts` (create)
- `worker/test/fixtures.ts` (create — optional; inline fixtures in the test file are also fine)

**Out of scope** (do NOT touch):
- `worker/src/index.ts` and `worker/src/checks.ts` — production code. If a test cannot be written
  without exporting an internal symbol, STOP and report; do not change exports.
- `worker/test/checks.test.ts`, `worker/test/download.test.ts` — existing suites.
- Everything outside `worker/`.

## Git workflow

You are in a managed worktree; **do not commit or push** — the operator reviews and commits.

## Steps

### Step 1: Build the upstream fixture router

Create `worker/test/rates.test.ts`. Build a `stubUpstreams(overrides)` helper that installs
`vi.stubGlobal('fetch', router)` where `router(url, init)` matches on the URL and returns fixture
responses. Every URL **must** be handled; an unmatched URL must `throw new Error('unexpected fetch: ' + url)`
so the suite is provably hermetic. Default fixtures (plain objects/strings at the top of the file):

- `https://bonbast.com/` → `new Response('<html>… $.post({ param: "tok123" }) …</html>')` — any
  HTML containing `param: "tok123"` (the adapter regex is `/param:\s*"([^"]+)"/`), with header
  `set-cookie: session=abc; Path=/`.
- `https://bonbast.com/json` (POST) → JSON with the sell-side fields from `BONBAST_MAP`
  (import it from `../src/index`): `{ usd1: '187,000', eur1: '218,000', gbp1: '252,000', nok1: '18,500', try1: '4,540', aed1: '50,900', cad1: '136,000', gol18: '17,800,000', mithqal: '77,100,000', emami1: '210,000,000', azadi1: '205,000,000', azadi1_2: '115,000,000', azadi1_4: '65,000,000', azadi1g: '33,000,000' }`
  (strings with commas are what `num()` parses; already Toman).
- `https://api.tgju.org/v1/widget/tmp?...` → `{ response: { indicators: [ { name: 'price_dollar_rl', p: '1,870,000', updated_at: '2026-09-10 12:00:00' }, … ] } }`
  — one indicator per `TGJU_MAP` value (import it), each `p` = **10 × the Toman value** above
  (tgju quotes Rial).
- `https://www.tgju.org/gold-chart` → HTML with two `<tr ` rows:
  `<tr data-market-row="silver_999"><td class="nf">2,700,000</td><td class="nf"><span>1</span></td>`
  and one with `nameslug="silver_925"` and `2,500,000` (Rial = 10× Toman; exercising **both**
  attribute spellings is the point).
- `https://www.tgju.org/` + `encodeURIComponent('قیمت-سکه-پارسیان')` → HTML with fifteen rows, one
  per size in `PARSIAN_SOOT` (import from `../src/checks`). Build the slug in the fixture with a
  local copy of the slug rule (Persian digits: `'۰۱۲۳۴۵۶۷۸۹'`):
  `سکه-پارسیان-<fa(Math.floor(soot/1000))>-<fa(String(soot%1000).padStart(3,'0'))>`, price
  `String(Math.round((soot * 17_800 * 1.1 + 400_000) * 10)).replace(/\B(?=(\d{3})+(?!\d))/g, ',')`
  (Rial). Use `nameslug="<slug>"` for all fifteen.
- `https://api.bitpin.ir/v1/mkt/markets/` → `{ results: [ { code: 'BTC_IRT', price: '12,062,000,000', currency1: { title_fa: 'بیت کوین' } }, { code: 'USDT_IRT', price: '187,500', currency1: { title_fa: 'تتر' } }, { code: 'ETH_USDT', price: '1' } ] }`
  (the `_USDT` market must be ignored).
- `https://api.coingecko.com/api/v3/coins/markets?...` → `[ { id: 'bitcoin', symbol: 'btc', name: 'Bitcoin', current_price: 64_500, image: 'https://assets.coingecko.com/coins/images/1/large/bitcoin.png' }, { id: 'ethereum', symbol: 'eth', name: 'Ethereum', current_price: 3_300, image: '…' } ]`.
- `https://api.coingecko.com/api/v3/coins/list?include_platform=true` → `[]` (platforms are an
  enhancement; empty keeps the fixture small).
- `https://api.binance.com/api/v3/ticker/price` → `[ { symbol: 'BTCUSDT', price: '64500' }, { symbol: 'ETHUSDT', price: '3300' } ]`.
- `https://api.tetherland.com/currencies` → `{ data: { currencies: { USDT: { price: 187_400 } } } }`.
- `https://api.github.com/repos/doxigo/muchToman/releases/latest` → `{ tag_name: 'v9.9.9', body: '<!--fa-->سلام<!--fa-->', assets: [] }`.

`overrides` lets a test replace any route (e.g. bonbast homepage → `new Response('x', { status: 503 })`).
Also stub `caches` with a **fresh Map per test** (copy the `download.test.ts` shape) — a shared
map would serve test 1's cached `/rates` body to test 2. Collect `ctx.waitUntil` promises and
`await Promise.all(pending)` before asserting on the cache.

Add a small helper `const rates = async () => { const res = await worker.fetch(new Request('https://rates.muchtoman.com/rates'), env, ctx); return { res, body: await res.json() }; }`.

**Verify**: `cd worker && npm run typecheck` → exit 0.

### Step 2: The happy path via bonbast

Test: with default fixtures, `GET /rates` →
- `res.status === 200`, `res.headers.get('etag')` non-null,
  `res.headers.get('cache-control')` contains `max-age`.
- `body.toman.usd === 187_000`, `body.toman.gold18 === 17_800_000`,
  `body.toman.silver_999 === 270_000` (Rial fixture ÷ 10), `body.toman.parsian_100` within 1 of
  `100 * 17_800 * 1.1 + 400_000`, `body.toman.parsian` within 1 of
  `(1000 * 17_800 * 1.1 + 400_000) / 1000`.
- `body.toman.btc === 12_062_000_000` (Tehran quote preferred over the USD cross).
- `body.toman.eth === 3_300 * 187_000` (USD-crossed through the fiat dollar).
- `body.sources.fiat_gold_coins === 'ok via bonbast'`; `body.sources.silver` starts `'ok via tgju, 2/2'`;
  `body.sources.crypto_toman` starts `'ok via bitpin'`; `body.sources.plausibility === 'none'`
  **or** whatever `formatDrops([])` returns — assert it does **not** contain `'btc'` or `'usd'`.
- `body.coins.length === 2` and the bitcoin entry's `name === 'بیت کوین'` (Persian name from bitpin
  overlaid on the CoinGecko catalogue) and its `icon` starts `https://rates.muchtoman.com/coin-icon?path=`.

**Verify**: `cd worker && npm run test` → this test passes.

### Step 3: Fallback and unit-conversion regressions

Three tests:
1. **bonbast down → tgju carries fiat, divided by 10.** Override both bonbast routes with 503s.
   Assert `body.toman.usd === 187_000` (from the `1,870,000` Rial fixture) and
   `body.sources.fiat_gold_coins` matches `/^ok via tgju \(tried first: bonbast/`.
2. **bonbast implausible → chain advances.** Override the bonbast `/json` fixture with
   `usd1: '4,200'` (the pegged rate). Assert `body.toman.usd === 187_000` and
   `body.sources.fiat_gold_coins` contains `'implausible'` in the tried-first clause.
3. **fiat chain fully down → USD-crossed coins drop, Tehran quotes survive.** Override bonbast
   routes AND the tgju widget route with 503s. Assert `body.toman.usd === undefined`,
   `body.toman.eth === undefined` (no dollar to cross), `body.toman.btc === 12_062_000_000`
   (bitpin needs no dollar), `res.status === 200`, and `body.sources.fiat_gold_coins` starts `'failed:'`.

**Verify**: `cd worker && npm run test` → all pass.

### Step 4: Markup-change and degraded-page regressions

Two tests:
1. **tgju page stops matching → named failure, nothing zeroed.** Override the gold-chart route
   with `<html><table></table></html>`. Assert `body.toman.silver_999 === undefined`,
   `body.sources.silver` starts `'failed:'` and contains `'no rows matched'`, and
   `body.toman.usd === 187_000` still (independent chains).
2. **A partial Parsian page publishes what it matched.** Override the Parsian route with a page
   containing only the 100 and 1000 سوت rows. Assert `body.toman.parsian_100` and
   `body.toman.parsian_1000` exist, `body.toman.parsian_1500 === undefined`, and
   `body.sources.parsian_coins` contains `'2/16'` (fifteen sizes + the derived per-سوت row are
   counted against the ask; check the exact wording `ok via tgju, 2/16 rows` and adjust the
   assertion to the observed string if the count differs — the *shape* `n/16` is the contract).

**Verify**: `cd worker && npm run test` → all pass.

### Step 5: Conditional requests and cache write

One test: fetch `/rates` once, read the `etag`, fetch again with header
`if-none-match: <etag>` → expect `304`, empty body, same `etag` header. Then assert the stubbed
cache map has exactly one entry (the versioned `__cache/rates/` key) after draining `pending`.
(The second request may be served from the stubbed cache rather than a rebuild — that is fine;
assert on status and etag, not on fetch-call counts.)

**Verify**: `cd worker && npm run check` → exit 0, all tests (old and new) pass.

## Test plan

Covered by the steps: ≥7 new tests in `worker/test/rates.test.ts` — happy path, tgju fallback
with Rial→Toman division, implausible-source advance, fiat-chain-down crypto behaviour,
silver markup change, partial Parsian page, and ETag/304. Model the stubbing after
`worker/test/download.test.ts`. No test may reach the network (the router throws on unknown URLs).

## Done criteria

- [ ] `cd worker && npm run check` exits 0
- [ ] `worker/test/rates.test.ts` exists with ≥7 passing tests, `afterEach(() => vi.unstubAllGlobals())`
- [ ] `git status --short` shows only the in-scope files created
- [ ] No change to any file under `worker/src/`
- [ ] `plans/README.md` status row updated (unless a reviewer maintains the index)

## STOP conditions

Stop and report back (do not improvise) if:

- The "Current state" excerpts don't match the live code (URLs, regexes, map names, or the
  `sources` string formats differ).
- A test cannot observe an adapter's behaviour through `GET /rates` without exporting a new
  symbol from `worker/src/index.ts`.
- Fixture values keep being dropped by plausibility no matter how closely you match the
  magnitudes above (that means the bands moved — report the live band values).
- `npm run check`'s `wrangler deploy --dry-run` step fails for a reason unrelated to your change
  (e.g. no network for wrangler) — report it; `typecheck` + `test` passing is then the gate.

## Maintenance notes

- These fixtures pin **today's** upstream response shapes. When an upstream really changes
  (bonbast token markup, tgju row attributes), the adapter fix must come with the fixture update —
  that is the point: the diff shows the contract change.
- If a new fiat currency is added to `BONBAST_MAP`/`TGJU_MAP`, the fixtures keep passing (they
  iterate the maps). A new tgju *page* asset needs its own fixture route.
- Deliberately deferred: fixtures for `/wallet-balance` upstreams (mempool/RPC/TronGrid) — a
  separate, lower-value suite; and `/coin-icon` proxying.