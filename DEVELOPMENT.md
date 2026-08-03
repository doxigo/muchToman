# Development

```
app/      Android app (Kotlin + Compose)
worker/   Cloudflare Worker that serves prices and public-wallet balances
```

The phone never talks to a price source directly. The Worker normalises everything so the app
only ever multiplies `amount × rate`.

## Building

Needs Android Studio (for the SDK) or a standalone Android SDK + JDK 17.

```bash
./gradlew installDebug
```

Both builds talk to the deployed Worker, so a debug install behaves like the shipped app —
same prices, same wallet lookups. Point it somewhere else with `-Pmuchtoman.ratesUrl`:

```bash
cd worker && npx wrangler dev --port 8787
./gradlew installDebug -Pmuchtoman.ratesUrl=http://10.0.2.2:8787/rates
```

`10.0.2.2` is how the **emulator** reaches this machine; a physical device cannot route it at
all. On a real phone either use this machine's LAN address, or tunnel through adb with `adb
reverse tcp:8787 tcp:8787` and pass `http://localhost:8787/rates`.

Getting this wrong fails quietly: prices keep rendering from the last cached fetch while every
wallet balance times out into "به‌روز نشد".

Tests are plain JVM, no device needed:

```bash
./gradlew test
```

## The Worker

```bash
cd worker && npx wrangler deploy
```

It is served from `rates.muchtoman.com`, **not** the `muchtoman-rates.milaniz.workers.dev`
address it also answers on. `workers.dev` is a common host for circumvention proxies, so Iran
filters the entire domain — DNS for it resolves to the `10.10.34.36` sinkhole — and Iran is
where the users are. Pointing the app at workers.dev fails in a peculiarly unhelpful way:
gold, ارز and سکه still list themselves, because that catalogue is compiled into the APK, so
only their prices go missing; رمزارز loses its whole section, because the coin list is the one
part of the catalogue that arrives over the network. It reads as "this app has no crypto"
rather than "the price server is unreachable". Keep the app pointed at a real domain.

`GET /rates` returns Toman-per-unit for every asset id, plus the picker's coin catalogue and
verified wallet-network options:

```json
{
  "updatedAt": 1785000000000,
  "toman": { "usd": 187000, "gold18": 17856318, "btc": 12062547606, ... },
  "coins": [
    {
      "id": "btc",
      "name": "بیت کوین",
      "icon": "https://…/bitcoin.png",
      "wallets": [ { "network": "bitcoin", "networkFa": "بیت کوین" } ]
    }
  ],
  "sources": { "fiat_gold_coins": "ok via bonbast", "crypto_toman": "ok via bitpin", ... }
}
```

Add `?fresh=1` to bypass the 10-minute edge cache.

`POST /wallet-balance` reads one public wallet without changing anything on-chain:

```json
{ "network": "ethereum", "address": "0x...", "contract": "0x..." }
```

It returns `{ "amount": 1.23, "updatedAt": 1785000000000 }`. `contract` is empty for native
coins and comes from curated CoinGecko platform metadata for tokens. The endpoint supports
Bitcoin, Ethereum/ERC-20, native Solana, TRON/TRC-20, and EVM tokens on BSC, Arbitrum,
Polygon, Optimism, and Avalanche. Bad addresses return a stable `invalid_address` code;
upstream failures return `unavailable`, so the phone can keep the previous amount.

### Sources

Two independent chains, because they fail independently. `sources` in every response names
which link actually answered.

- **Fiat, gold, coins:** `bonbast.com` → `tgju.org`. Bonbast quotes Toman directly; tgju
  quotes Rial and is divided by 10.
- **Silver and سکه پارسیان:** tgju's own pages, `/gold-chart` and `/قیمت-سکه-پارسیان`, scraped
  a table row at a time. These have no second source and do not ride the chain above, because
  that chain stops at the first source that answers and bonbast — which answers nearly always
  — quotes neither. tgju's widget API is not used for them: it carries neither, and it returns
  HTTP 200 with an empty body once it decides you have asked too often.
- **Crypto:** `bitpin` → `tetherland` → `coingecko` → `binance` → `kraken`. The Iranian
  exchanges quote Toman and carry the real Tehran premium; the global ones price in USD and
  are cross-rated through whatever dollar rate the fiat chain produced.
- **Wallet balances:** mempool.space for Bitcoin, JSON-RPC for supported EVM networks and
  Solana, and TronGrid for TRON. These calls use public addresses only and are read-only.

Wallex and Nobitex refuse Cloudflare's edge IPs, which is why the chain exists rather than a
single source. If the fiat chain fails entirely there is no dollar rate, so the Worker
publishes no crypto at all rather than inventing a conversion.

## Adding an asset

**Crypto: nothing to do** — anything in CoinGecko's top 250 is already in the picker.

**A new fiat currency** is two lines: one in `STATIC_CATALOG` in
[Catalog.kt](app/src/main/java/com/doxigo/muchtoman/Catalog.kt) and one in `BONBAST_MAP` /
`TGJU_MAP` in [worker/src/index.ts](worker/src/index.ts).

Check the two sources agree on the unit before adding one. Most currencies are quoted per
unit by both, but bonbast quotes JPY and AMD per 10 and IQD per 100 where tgju quotes all
three per 1 — mapping those straight across makes the holding wrong by that factor, but only
on whichever source answered, so it would be intermittent as well as wrong.

**Anything only tgju has** — silver, سکه پارسیان — comes from `fetchTgjuPage`, which reads
named rows off a price page. Give it the page path and a `slug -> app id` map. A row is
matched by `data-market-nameslug` **or** `data-market-row`, since neither is dependable
alone: the Parsian rows carry the name in the first and a bare number in the second, silver
does the reverse, and silver_999 ships an empty `nameslug` on some fetches.

**A new bank** is one line in `Bank` in [Sms.kt](app/src/main/java/com/doxigo/muchtoman/Sms.kt)
— the bank's name and the senders it writes from — plus one in `BankLogo` for its mark; the
`when` is exhaustive, so the compiler asks. A number is compared by its last ten digits, so
every form Android hands over for the same line matches (`+989999987641`, `989999987641`,
`09999987641`, `0999 998 7641` are one sender), and a shortcode is shorter than ten digits so
it matches itself. A lettered sender ID ("Refah Bank") is matched as its whole name, case and
spacing ignored.

## Releasing

A signed APK is built and attached to a GitHub Release whenever a tag is pushed, by
[release.yml](.github/workflows/release.yml):

```bash
git tag v1.0.1 && git push origin v1.0.1
```

`versionName` comes from the tag, `versionCode` from the CI run number, so each release
installs over the last.

### Signing setup (once)

```bash
keytool -genkeypair -v -keystore muchtoman-release.jks -alias muchtoman \
  -keyalg RSA -keysize 4096 -validity 10000
```

For local release builds, a gitignored `keystore.properties` in the repo root:

```properties
storeFile=muchtoman-release.jks
storePassword=…
keyAlias=muchtoman
keyPassword=…
```

Without it the release build still compiles, just unsigned — it never falls back to the debug
key. **Back up the .jks**: lose it and no future build can install over an existing one.

CI needs the same four values as repo secrets, piped in so they never hit the screen:

```bash
base64 -i muchtoman-release.jks | gh secret set KEYSTORE_B64
```

```bash
gh secret set KEYSTORE_STORE_PASSWORD
```

Plus `KEYSTORE_KEY_ALIAS` and `KEYSTORE_KEY_PASSWORD` the same way.

## Typography

The app is set in **Modam**, shipped as one variable font at
`app/src/main/res/font/modam.ttf` — two axes, `wght` 200–900 and `wdth` 70–100. Body text takes
the `Modam` family; every figure takes `ModamFigures`, which is the same file at `wdth` 90 with
`tnum` on. Width, not letter-spacing, is what buys room for a long Toman total: Persian is a
connected script and negative tracking breaks the joins.

That file is **not** the one the foundry ships. Modam's variable font defaults to ExtraLight
Condensed, and Compose only applies font variation settings on API 26+ — below that (minSdk is
24) the whole app would render at the default instance. `tools/make-font.py` retargets the
default to Regular at normal width, keeping both axis ranges:

```bash
python3 tools/make-font.py "/path/to/Modam Pro/04 - Modam Variable/ModamVF.ttf"
```

> **Licensing.** Modam is a commercial face from fontiran.com — its name table reads "To use
> this font, it is necessary to obtain the license from www.fontiran.com". Embedding it in the
> APK is one permission; committing the `.ttf` to a public repository redistributes the binary
> and is another. Check your licence before pushing `app/src/main/res/font/`. If it does not
> allow redistribution, gitignore that path and regenerate it with the script above — note that
> the release workflow builds from a clean checkout and would need the font restored there too.

Modam has no `·` (U+00B7), `≈`, `…` or `▲`. Separators in the UI use `•` (U+2022), which the
font does have; the trend caret is drawn. Anything else falls back to a system face, which for
punctuation is invisible in practice.

## Implementation notes

- No database — holdings and optional public-wallet links are a short JSON string in
  SharedPreferences. No accounts and no analytics. Wallet tracking adds an opt-in call to
  `POST /wallet-balance`; manual holdings only use the rates endpoint.
- Displayed figures **truncate**, never round, so the number shown is never larger than the
  real one. The spelled-out Persian words are never abbreviated at all.
- Latin tickers are wrapped in Unicode bidi isolates, or "۴۰ SOL · نرخ ۱۴ میلیون" renders
  with the number and ticker swapped.
- `FLAG_SECURE` is set while the app lock is on, so `adb screencap` returns black — that is
  the flag working, not a bug.
- The home-screen widget has three layouts, picked per placed widget from the host's own
  size in dp (`Face` in `Widget.kt`): stacked, then a second column for the freshness, then
  a month of chart. Thresholds are dp, not cells, because a cell is not a fixed size — the
  same "two by one" is 110×40dp on the grid the platform documents and nearer 160×100dp on a
  Pixel. Every step up adds a line and never removes one; `WidgetFaceTest` pins that down.
- Nothing in the widget's layouts is a `TextView` and nothing uses `fitCenter`. The launcher
  inflates them in its own process and cannot load this APK's fonts, so every line is a
  bitmap set in the real Modam instance; and `fitCenter` scales those bitmaps *up* to fill
  the view, which is how a four-cell tile ended up showing less than a two-cell one.
