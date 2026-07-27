# چقدر تومن — MuchToman

An Android app that answers one question: *how much is all my money, in Toman?*

Built for an older Persian-speaking user. Persian UI, RTL, Persian digits, big type, and
large amounts spoken the way people actually say them ("۴٫۷ میلیارد تومان", not
"۴٬۶۶۶٬۲۵۱٬۱۳۶").

Holds plain Toman (cash or bank), fiat (USD, EUR, GBP, NOK, TRY, AED, CAD), **any of the
top 250 cryptocurrencies**, 18k gold by the gram or the مثقال, and Iranian coins (Emami,
Bahar Azadi, Nim, Rob, Gerami).
Everything else is valued at **free-market** rates — the official ~42,000 IRR peg is not
used anywhere.

Toman itself is pinned at 1 in the app, not fetched: money already in Toman still counts
when the network is down, and no manual override can change it into something else.

## Layout

```
app/      Android app (Kotlin + Compose)
worker/   Cloudflare Worker that serves all prices as Toman-per-unit
```

The phone never talks to a price source directly. The Worker normalises everything so the
app only ever multiplies `amount × rate`.

## Running it

Requires Android Studio (for the SDK and emulator). Then:

```bash
./gradlew installDebug
```

The **debug** build points at `http://10.0.2.2:8787/rates`, i.e. `wrangler dev` running on
your machine, reachable from the emulator. Start it with:

```bash
cd worker && npx wrangler dev --port 8787
```

On some emulator images (seen on the API 37 one here) app traffic to `10.0.2.2` times out
even though the shell can reach it. `adb logcat -s muchtoman` will show
`rates fetch failed: … SocketTimeoutException`. Tunnel through adb instead:

```bash
adb reverse tcp:8787 tcp:8787
./gradlew installDebug -Pmuchtoman.ratesUrl=http://localhost:8787/rates
```

The **release** build points at the deployed Worker. Override either:

```bash
./gradlew installDebug -Pmuchtoman.ratesUrl=https://muchtoman-rates.milaniz.workers.dev/rates
```

Tests (JVM, no device needed):

```bash
./gradlew test
```

## The Worker

```bash
cd worker && npx wrangler deploy
```

`GET /rates` returns:

```json
{
  "updatedAt": 1785000000000,
  "toman": { "usd": 187000, "gold18": 17856318, "btc": 12062547606, "sol": 13890741, ... },
  "coins": [ { "id": "btc", "name": "بیت کوین", "icon": "https://…/bitcoin.png" }, ... ],
  "sources": {
    "fiat_gold_coins": "ok via bonbast",
    "crypto_toman": "ok via bitpin",
    "coin_catalog": "ok, 250 coins",
    "crypto_pricing": "147 at Tehran price, 103 cross-rated from USD"
  }
}
```

`toman` is Toman-per-unit for every asset id. `coins` is the picker's catalogue — id, Persian
name where a local exchange has one, and a real logo.

Add `?fresh=1` to bypass the 10-minute edge cache. A redeploy does **not** invalidate it.

### Sources

Two independent chains, because they fail independently. `sources` in every response names
which link actually answered, and lists what was tried before it.

**Fiat, gold, coins:** `bonbast.com` → `tgju.org`

Bonbast is primary and already quotes Toman. It has no public API, so the Worker pulls a
token out of the homepage HTML and POSTs it to `/json` — if bonbast changes that page this
breaks, and `sources.fiat_gold_coins` will say so. tgju is the fallback; it quotes **Rial**,
so every value is divided by 10. Both were checked field-by-field against each other and
agree to within 0.1%.

**Crypto:** `bitpin` → `tetherland` → `coingecko` → `binance` → `kraken`

Bitpin and Tetherland quote in Toman on the Iranian market, so they carry the real Tehran
premium (USDT runs about +0.2% over the cash dollar). Preferred per-asset — a source that
only knows USDT is still used for USDT. The global exchanges are the floor: they price in
USD, which is then multiplied by whatever dollar rate the fiat chain produced.

Not every Iranian exchange is reachable from Cloudflare. **Wallex and Nobitex refuse the
edge's IPs** (403 / DNS failure) even though both answer fine from a laptop — tested, not
assumed. Bitpin answers both. That is the reason for the chain rather than a single source.

If the whole fiat chain fails there is no dollar rate, so the Worker publishes no crypto at
all rather than inventing a conversion. A total that is quietly wrong is worse than a gap.

## Signing a release build

The keystore and its password are yours; nothing in this repo generates or stores them.
Create one:

```bash
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
  -genkeypair -v -keystore muchtoman-release.jks -alias muchtoman \
  -keyalg RSA -keysize 4096 -validity 10000
```

> There is no system Java on this machine — the JDK ships inside Android Studio, hence the
> full path. `./gradlew` finds it on its own, but `keytool` and `apksigner` do not. To use
> them bare, export it first:
>
> ```bash
> export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
> export PATH="$JAVA_HOME/bin:$PATH"
> ```

Then create `keystore.properties` in the repo root (already gitignored):

```properties
storeFile=muchtoman-release.jks
storePassword=<what you just typed>
keyAlias=muchtoman
keyPassword=<what you just typed>
```

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

**Back up the .jks file.** Lose it and you can never ship an update that installs over the
copy already on her phone — she would have to uninstall and lose her data first.

Without `keystore.properties` the release build still compiles; it just comes out unsigned
rather than silently falling back to the debug key.

## Adding an asset

**Crypto: nothing to do.** The coin list is served by the Worker from CoinGecko's top 250,
with Persian names and real logos, so anything in that list is already searchable in the
picker. Coins outside the top 250 are the only ones that would need a change.

**A new fiat currency** is two lines: one in `STATIC_CATALOG` in
[Catalog.kt](app/src/main/java/com/doxigo/muchtoman/Catalog.kt) and one in `BONBAST_MAP` /
`TGJU_MAP` in [worker/src/index.ts](worker/src/index.ts). Bonbast returns 28 currencies, so
most are already available upstream.

## SDK levels

`compileSdk 37`, `targetSdk 37`, `minSdk 24`. targetSdk was raised to 37 deliberately and
then verified by running the app on an **API 37 (Android 17) emulator** — both builds, both
sheets, no crashes and no blocked-behaviour warnings in logcat. Raising it on the strength
of the lint warning alone would not have told us anything, since the default emulator here
is API 36 and never exercises Android 17 behaviour.

Note `keytool` and `apksigner` need `JAVA_HOME` exported (see the signing section); only
`./gradlew` finds the bundled JDK by itself.

## Settings

Reached by the ⚙ in the top corner.

- **Your name.** Optional. When set, the top of the app greets you ("سلام، مریم") instead of
  naming itself. Held in local state while typing and committed on the way out, so a name is
  not a write per keystroke. Capped at 24 characters so it cannot crowd out the ⚙.
- **Display: خودکار / روشن / تیره.** Three buttons rather than a switch, because a two-state
  toggle cannot express "follow the phone" — and following the phone has to stay the default,
  or anyone relying on system dark mode silently loses it. An unrecognised stored value falls
  back to خودکار rather than throwing.
- **App lock**, below.

## App lock

An optional lock, off by default, toggled from **Settings** (the ⚙ in the top corner). When on,
the app opens to a lock screen and asks for fingerprint or the phone's PIN/pattern, and
re-arms every time the app leaves the foreground.

- **Device credential is always an accepted authenticator.** A fingerprint that will not
  read must never lock her out of her own balance.
- Turning the lock *on* requires authenticating first, so it can't be armed by someone who
  then couldn't get back in.
- `FLAG_SECURE` is set while the lock is enabled, so the total does not leak through the
  app-switcher thumbnail. A side effect is that `adb screencap` returns black — that is the
  flag working, not a bug.
- Below API 30 the request drops to `BIOMETRIC_WEAK`, because `BIOMETRIC_STRONG |
  DEVICE_CREDENTIAL` is unsupported there. Nothing here guards a cryptographic key, so that
  trade buys the feature working on older phones.
- With no PIN and no fingerprint enrolled the switch is disabled and says why, rather than
  offering a lock that cannot work. That state is verified too.
- Verified end to end on the emulator: arm → background → returns locked (the accessibility
  tree contains only the lock screen, no figures) → PIN → content returns.

Settings is its own page, not a row under the holdings — a permanent switch sitting in the
asset list reads as one of the assets.

## Deliberate omissions

- **No database.** Holdings are a short JSON string in SharedPreferences.
- **No KV/R2/D1 on the Worker.** The phone caches the last good rates itself, so there is
  nothing at the edge worth persisting.
- **Pull-to-refresh exists, but so does the button.** The labelled "به‌روزرسانی" button stays,
  because it is the control she can be told about over the phone; the pull gesture is for
  everyone whose thumbs expect it.
- **No historical charts, no multi-user, no login.** Not asked for.

## Interface notes

Persian throughout, RTL forced regardless of device locale, Persian digits, Vazirmatn.

- Every figure appears three ways: scannable ("۱۰٫۸ میلیون تومان"), spelled out in Persian
  words ("ده میلیون و هشتصد هزار تومان"), and as exact digits. The words are the guard
  against misreading a magnitude by a factor of ten, so they are **never rounded**. The
  scannable line auto-shrinks rather than wrapping, since the figure can grow by orders of
  magnitude.
- `faCompact` **truncates** to one decimal, it never rounds. It used to call `faNumber`
  above 10, which rounds to a whole, so 10,800,000 displayed as "۱۱ میلیون". Rounding to one
  decimal is not enough either — that turns 2.95 billion into "۳ میلیارد". For money there is
  only one safe direction, so the displayed figure is never larger than the real one. Covered
  by a regression test on 10,800,000 and a property test that sweeps magnitudes.
- Typing an amount shows it spelled out under the field, for the same reason.
- Amounts group as you type (`groupDigits`), so eight zeroes never have to be counted. The
  raw field state stays ASCII so parsing is exact; grouping is purely visual.
- Latin tickers are wrapped in Unicode isolates (`bidi()`). Without that, "۴۰ SOL · نرخ ۱۴
  میلیون" renders with the number and ticker swapped.
- Add and edit are drag-dismissable bottom sheets, not dialogs — one-handed reach matters
  more than screen real estate here. Both give their content a **bounded** height
  (`fillMaxHeight(0.88f)` with the list on `weight(1f)`); `fillMaxSize` on the inner list
  makes the sheet grow to the whole screen, losing its corners and scrim and re-measuring on
  every drag, which shows up as a flash when it closes. Selecting a row awaits
  `sheetState.hide()` before leaving composition, so it animates out instead of blinking.
- The colour scheme sets **every** Material role, including the container roles. Leaving
  those unset is what makes an app look unfinished: the M3 baseline is violet, so dialogs
  and text fields come out mauve no matter how carefully `primary` is chosen.
- The hero card is identical in light and dark on purpose — it is the brand anchor, and
  pinning it means dark mode cannot invert it into a gold slab. Both themes are rendered
  and checked, not just declared.
- The hero's top corner carries the same total in dollars ("≈ $۱۷٬۱۲۹"), muted and a size
  below the label it shares the line with, so the corner reads as one sentence and the gold
  figure stays the answer. It divides by the **effective** dollar rate, so a hand-typed
  override moves it too, and it is simply absent when there is no dollar rate — the "≈" is
  there so a conversion is never read as a dollar holding. The "$" sits outside the bidi
  isolate: that is what puts it on the reading side of the figure whether `faRate` returns
  digits or a magnitude ("$۱٫۲ میلیون").

Rates the app can't get are shown as "نرخ ندارد" and **left out of the total**, never counted
as zero — with a note naming what's missing. Any rate can be overridden by hand from the
asset's edit dialog, which is the fallback if a source dies.
