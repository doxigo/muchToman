# چقدر تومن — MuchToman

An Android app that answers one question: *how much is all my money, in Toman?*

Persian UI, RTL, Persian digits, big type, and large amounts spoken the way people actually
say them ("۴٫۷ میلیارد تومان", not "۴٬۶۶۶٬۲۵۱٬۱۳۶"). Built for a Persian-speaking parent, so
legibility beats density everywhere.

Holds plain Toman, fiat (USD, EUR, GBP, NOK, TRY, AED, CAD), **any of the top 250
cryptocurrencies**, 18k gold by the gram or the مثقال, and Iranian coins (Emami, Bahar Azadi,
Nim, Rob, Gerami). Everything is valued at **free-market** rates — the official ~42,000 IRR
peg is not used anywhere.

## Screenshots

Sample data, live rates.

| | | |
| --- | --- | --- |
| ![](docs/screenshots/home.png) | ![](docs/screenshots/assets.png) | ![](docs/screenshots/report.png) |
| ![](docs/screenshots/home-dark.png) | ![](docs/screenshots/assets-dark.png) | ![](docs/screenshots/report-dark.png) |

## What it does

- **One total, three ways.** Scannable ("۱۰٫۸ میلیون تومان"), spelled out in Persian words,
  and exact digits — the words guard against misreading a magnitude by a factor of ten, so
  they are never rounded. Displayed figures truncate rather than round, so the number shown
  is never larger than the real one.
- **Bank balances from SMS.** Read locally from the phone's inbox, no login and no bank API.
  Nothing leaves the device.
- **A year of history.** The total is remembered once a day, with 1/3/6/12-month change.
- **Offline-tolerant.** The last good rates are cached on the phone. Toman is pinned at 1 and
  never fetched, so money already in Toman still counts when the network is down.
- **Missing rates are never zero.** An asset with no price is left out of the total and named
  in a note, and any rate can be overridden by hand.
- **Optional app lock** (fingerprint or device PIN), off by default.
- Light and dark, following the system by default.

## Banks it reads

Only these senders are parsed; the rest of the inbox is ignored.

| Bank | Sending numbers |
| --- | --- |
| بلو بانک | 0999 998 7641 |
| بانک سامان | 0999 992 0000 |
| بانک رفاه | 100031, 100032 |
| بانک خاورمیانه | 90000258, 20004861 |

<img src="docs/screenshots/banks.png" width="280">

A bank that starts sending from a new shortcode is a one-tap confirmation in the app. Adding
a bank outright is one line in `Bank` in [Sms.kt](app/src/main/java/com/doxigo/muchtoman/Sms.kt).

## Install

Grab the APK from [Releases](https://github.com/doxigo/muchToman/releases). One APK covers
every device — no native code, so nothing to split per ABI — and it installs on Android 7.0
(API 24) and newer.

## Layout

```
app/      Android app (Kotlin + Compose)
worker/   Cloudflare Worker that serves all prices as Toman-per-unit
```

The phone never talks to a price source directly. The Worker normalises everything so the app
only ever multiplies `amount × rate`.

## Building

Needs Android Studio (for the SDK) or a standalone Android SDK + JDK 17.

```bash
./gradlew installDebug -Pmuchtoman.ratesUrl=https://your-worker.workers.dev/rates
```

Debug builds default to `http://10.0.2.2:8787/rates` — `wrangler dev` on your machine as seen
from the emulator:

```bash
cd worker && npx wrangler dev --port 8787
```

If the emulator can't reach `10.0.2.2`, tunnel through adb with `adb reverse tcp:8787
tcp:8787` and point the build at `http://localhost:8787/rates`.

Tests are plain JVM, no device needed:

```bash
./gradlew test
```

## The Worker

```bash
cd worker && npx wrangler deploy
```

`GET /rates` returns Toman-per-unit for every asset id, plus the picker's coin catalogue:

```json
{
  "updatedAt": 1785000000000,
  "toman": { "usd": 187000, "gold18": 17856318, "btc": 12062547606, ... },
  "coins": [ { "id": "btc", "name": "بیت کوین", "icon": "https://…/bitcoin.png" }, ... ],
  "sources": { "fiat_gold_coins": "ok via bonbast", "crypto_toman": "ok via bitpin", ... }
}
```

Add `?fresh=1` to bypass the 10-minute edge cache.

### Sources

Two independent chains, because they fail independently. `sources` in every response names
which link actually answered.

- **Fiat, gold, coins:** `bonbast.com` → `tgju.org`. Bonbast quotes Toman directly; tgju
  quotes Rial and is divided by 10.
- **Crypto:** `bitpin` → `tetherland` → `coingecko` → `binance` → `kraken`. The Iranian
  exchanges quote Toman and carry the real Tehran premium; the global ones price in USD and
  are cross-rated through whatever dollar rate the fiat chain produced.

Wallex and Nobitex refuse Cloudflare's edge IPs, which is why the chain exists rather than a
single source. If the fiat chain fails entirely there is no dollar rate, so the Worker
publishes no crypto at all rather than inventing a conversion.

## Adding an asset

**Crypto: nothing to do** — anything in CoinGecko's top 250 is already in the picker.

**A new fiat currency** is two lines: one in `STATIC_CATALOG` in
[Catalog.kt](app/src/main/java/com/doxigo/muchtoman/Catalog.kt) and one in `BONBAST_MAP` /
`TGJU_MAP` in [worker/src/index.ts](worker/src/index.ts).

## Releasing

Create a keystore, then a gitignored `keystore.properties` in the repo root:

```bash
keytool -genkeypair -v -keystore muchtoman-release.jks -alias muchtoman \
  -keyalg RSA -keysize 4096 -validity 10000
```

```properties
storeFile=muchtoman-release.jks
storePassword=…
keyAlias=muchtoman
keyPassword=…
```

Without it the release build still compiles, just unsigned — it never falls back to the debug
key. **Back up the .jks**: lose it and no future build can install over an existing one.

Pushing a tag builds a signed APK and attaches it to a GitHub Release via
[release.yml](.github/workflows/release.yml):

```bash
git tag v1.0.1 && git push origin v1.0.1
```

`versionName` comes from the tag, `versionCode` from the CI run number. The workflow needs
four repo secrets: `KEYSTORE_B64` (`base64 -i muchtoman-release.jks`),
`KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_ALIAS`, `KEYSTORE_KEY_PASSWORD`.

## Notes

- No database — holdings are a short JSON string in SharedPreferences. No accounts, no
  analytics, no network calls beyond the rates endpoint.
- Latin tickers are wrapped in Unicode bidi isolates, or "۴۰ SOL · نرخ ۱۴ میلیون" renders
  with the number and ticker swapped.
- `FLAG_SECURE` is set while the app lock is on, so `adb screencap` returns black — that is
  the flag working, not a bug.
