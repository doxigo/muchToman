# Development

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

**A new bank** is one line in `Bank` in [Sms.kt](app/src/main/java/com/doxigo/muchtoman/Sms.kt)
— the bank's name and the numbers it sends from. Only the last ten digits are compared, so
any format works.

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

## Implementation notes

- No database — holdings are a short JSON string in SharedPreferences. No accounts, no
  analytics, no network calls beyond the rates endpoint.
- Displayed figures **truncate**, never round, so the number shown is never larger than the
  real one. The spelled-out Persian words are never abbreviated at all.
- Latin tickers are wrapped in Unicode bidi isolates, or "۴۰ SOL · نرخ ۱۴ میلیون" renders
  with the number and ticker swapped.
- `FLAG_SECURE` is set while the app lock is on, so `adb screencap` returns black — that is
  the flag working, not a bug.
