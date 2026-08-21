# Development

```
app/      Android app (Kotlin + Compose)
worker/   Cloudflare Worker that serves prices and public-wallet balances
sync/     Cloudflare Worker + Durable Object for encrypted family sync
pwa/      browser companion served by sync/
```

The Worker normalises fiat, metal, coin, and crypto prices so the app only ever multiplies
`amount × rate`. TSETMC is the one exception: it rejects Cloudflare traffic, so the phone
fetches it directly only while the stock picker is used or a stock is held.

## Building

Needs Android Studio (for the SDK) or a standalone Android SDK + JDK 17.

```bash
./gradlew installFullDev
```

`dev` installs beside the released app with its own data. `debug` can replace the released app
when a local release keystore is configured, so use it only when testing an in-place upgrade
and pass a `muchtoman.versionCode` at least as high as the installed release.

### Editions

Two flavours, `full` and `lite`, so every task name carries one: `installFullDev`,
`assembleLiteRelease`, and so on. `assembleRelease` builds both.

`lite` is دارایی and nothing else — the app as it shipped through v1.0.4, for someone who wants
to know what their gold is worth and has no household to run. It installs as
`com.doxigo.muchtoman.lite` under the name «چقدر تومن دارم», so both can sit on one phone. `full`
keeps the released `com.doxigo.muchtoman`, which is why a v1.0.4 install upgrades into it in place
with its balances and categories intact.

The difference is one boolean, read in one place — `tabs` in `TabBar.kt`. Nothing else in the app
branches on the edition, and nothing else should: a bank detection fix has to land in both APKs
without anyone remembering to do it twice, which is the entire reason this is a flavour and not a
second repository. The cost is that the lite APK carries the ledger code it never shows, so it is
fewer screens rather than a smaller download.

Both builds talk to the deployed Worker, so a debug install behaves like the shipped app —
same prices, same wallet lookups. Point it somewhere else with `-Pmuchtoman.ratesUrl`:

```bash
cd worker && npx wrangler dev --port 8787
./gradlew installFullDebug -Pmuchtoman.ratesUrl=http://10.0.2.2:8787/rates
```

`10.0.2.2` is how the **emulator** reaches this machine; a physical device cannot route it at
all. On a real phone, tunnel through adb with `adb reverse tcp:8787 tcp:8787` and pass
`http://localhost:8787/rates`. Cleartext is limited to these two debug/dev hosts.

Getting this wrong fails quietly: prices keep rendering from the last cached fetch while every
wallet balance times out into "به‌روز نشد".

Tests are plain JVM, no device needed:

```bash
./gradlew test
```

Use `-Pmuchtoman.syncUrl` to point family sync at a local Worker. The emulator reaches a local
port through `10.0.2.2`; a physical phone needs `adb reverse`, as with the rates Worker.

Family records are encrypted on the client. The sync Worker stores routing metadata and
ciphertext only. A member's SMS sharing starts off, raw SMS bodies are never synchronized, and
disabling sharing publishes tombstones for that member's previously shared SMS transactions.

```bash
cd pwa && npm ci && npm run check
cd sync && npm ci && npm run check
```

## The Worker

```bash
cd worker
npm ci
npm run check
npm run deploy
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
      "icon": "https://rates.muchtoman.com/coin-icon?path=…",
      "wallets": [ { "network": "bitcoin", "networkFa": "بیت کوین" } ]
    }
  ],
  "sources": { "fiat_gold_coins": "ok via bonbast", "crypto_toman": "ok via bitpin", ... }
}
```

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
- **Crypto:** bitpin, with tetherland as its Toman fallback; CoinGecko, with Binance as its USD
  fallback. The Iranian sources carry the Tehran premium. USD prices are cross-rated through
  whatever dollar rate the fiat chain produced.
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
git tag -a v1.0.1 -m 'سود بانکی رو دیگه اشتباه نمی‌خونه' && git push origin v1.0.1
```

**Annotate the tag, and write its message in Persian.** That message is what the app shows on
the update sheet to someone still running the old build — casual, one short line per change, no
commit subjects. The workflow copies it into the release body between `<!--fa-->` markers, the
Worker reads it back out of the GitHub API, and the phone caps it at six lines of 120
characters. A lightweight tag (`git tag v1.0.1`) has no message: the update card still appears
and the sheet simply lists nothing. The English `## Changes` list under it is the commit
subjects, for whoever is reading the diff rather than using the app.

`versionName` comes from the tag, `versionCode` from the CI run number, so each release
installs over the last. `mapping.txt` is attached beside the APK: R8 renames everything, so a
stack trace off someone's phone is unreadable without the map for that exact build, and the
build is gone the moment the runner is. `retrace mapping.txt trace.txt` turns one back into
names.

Before it publishes anything, the workflow boots an emulator, installs the APK it is about to
release and opens it, and fails if the app is not on screen afterwards. That step exists
because 1.0.2 shipped an APK that could not start at all: R8 shrank away a constructor
WorkManager reflects on, and every install died before the first frame. Nothing caught it,
because nothing had ever *run* the artifact — unit tests are JVM-only and never load the app,
and lint reads source rather than R8's output. **A release-only crash is invisible to both by
construction**, which is why the smoke test tests the APK rather than the code.

That is also the rule for anything added here later: a check that runs against `src/` cannot
tell you the shipped build works.

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

The companion PWA is set in the same face, from the same file, in the container browsers want.
It is generated out of the app's copy rather than from the foundry's, so the two can never drift
apart:

```bash
python3 -c "from fontTools.ttLib import TTFont; f=TTFont('app/src/main/res/font/modam.ttf'); f.flavor='woff2'; f.save('pwa/public/modam.woff2')"
```

Both axes survive the conversion, so `font-stretch: 90%` in `pwa/index.html` is exactly
`ModamFigures` and `font-variant-numeric: tabular-nums` is exactly `tnum`. 106 KB, preloaded in
the head, and in the service worker's cache from the first visit on.

> **Licensing.** Modam is a commercial face from fontiran.com — its name table reads "To use
> this font, it is necessary to obtain the license from www.fontiran.com". Embedding it in the
> APK is one permission; committing the `.ttf` to a public repository redistributes the binary
> and is another; **serving `modam.woff2` from sync.muchtoman.com is a third**, because a webfont
> is handed to every browser that asks for the page. Check your licence covers web use before
> deploying the PWA. If it does not, gitignore both font paths and regenerate them with the
> commands above — note that the release workflow builds from a clean checkout and would need
> them restored there too, and that dropping the woff2 leaves the PWA on the system face while
> the app keeps Modam.

Modam has no `·` (U+00B7), `≈`, `…` or `▲`. Separators in the UI use `•` (U+2022), which the
font does have; the trend caret is drawn. Anything else falls back to a system face, which for
punctuation is invisible in practice.

## Implementation notes

- No database — holdings and optional public-wallet links are a short JSON string in
  SharedPreferences and are explicitly excluded from Android backup and device transfer. No
  accounts and no analytics. Wallet tracking adds an opt-in call to `POST /wallet-balance`;
  manual holdings use the rates endpoint, plus direct TSETMC access when stocks are involved.
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

## The ledger

```
app/      Android app (Kotlin + Compose) — the household's sensor
worker/   Cloudflare Worker: public prices and wallet balances
sync/     Cloudflare Worker: the household ledger, as ciphertext it cannot read
pwa/      the iPhone companion, served by sync/ as its static assets
```

Two databases on the phone, and the split between them is the whole design:

| | `durable.db` | `derived.db` |
|---|---|---|
| holds | the messages themselves, and every decision she makes | everything a parser computed |
| migrations | hand-written and tested; destructive fallback **forbidden** | version bump drops every table, on purpose |
| rebuild cost | irreplaceable | ~40 ms |

`sms_source` is durable rather than derived because the inbox stops being a trustworthy source
of truth at a thirteen-month horizon — SMS apps prune, people clear bank threads, phone
migrations lose messages. Once a message is here, re-deriving is offline, instant, needs no
permission, and still works after `READ_SMS` is revoked.

Money in the ledger is **`Long` Rial**, never Toman and never a `Double`. Rial is what the
messages actually print; Toman is a display transform, and rounding one leg of a transfer down
while the other rounds up is how exact-amount matching stops working silently.

### Budgets and filing — the two things the app says while it is closed

A budget is a `goal` row with `kind = 'cap'`, a `category_id`, and a `period` of `week`, `jmonth` or
`jquarter`. There is no budget table and no migration: `Goals.kt` declared both shapes from the
start and only the savings one was ever reachable from a screen. `Goals.kt` keeps the savings half,
`Budget.kt` reads the same rows the other way, and they are two files because the arithmetic runs in
opposite directions — a goal is met by reaching its figure, a budget by not reaching past its own.

Every figure is computed from the transactions on each read, and the window is always the current
one. Spend is counted by the same rule `periodReport` counts `spendingByCategory` by, negatives
only, so «رستوران و کافه» on the budget card and in دخل و خرج is one number read twice. The one
deliberate divergence is `PASS_THROUGH_CATEGORIES`: a قرض is held out of the report by default and
is counted by a budget, because a cap on a category is an explicit request to measure it.

One thing *is* stored, and it is in `SharedPreferences` rather than in either database, because it
is neither a message nor a decision of hers: `budgetMarks`, the highest threshold this phone has
already announced, per budget per window. Keyed by window start, so a new month is a new key and
nothing has to remember to reset anything; `budgetNews` only ever returns a mark for a live budget in
its current window, which is also how the list is pruned. The level is a high-water mark and never
lowered, so refiling a receipt back under 80% and then crossing it again does not say the same thing
twice. Another phone in the household keeps its own and neither owes the other one.

**Freshness has a ceiling, and it is architectural.** There is no `RECEIVE_SMS` receiver, so a
purchase becomes visible only when something reads the inbox. `LedgerWatchWorker` is the only thing
that does so unattended — every six hours, no network constraint, scheduled by whether any budget
exists **or** bank messages are switched on, and cancelled when the last of both goes. The app's own
`publishLedger` covers the foreground case, and it is the single place any path may publish a
ledger, precisely so that `announceBudgets` cannot be forgotten by one of the eight callers that
change one.

`announceBudgets` is shared by the worker and the app and is idempotent. Notifications use the goal
id as the tag with one fixed id, so one budget is one live note that gets replaced rather than
stacked, and a genuine race between the worker and the app posts one note instead of two.

To watch an alert fire, set a cap below what the category has already cost this month: saving the
budget publishes the ledger, which announces it on the spot.

**The second thing it says is «این چی بود؟».** A bank message never carries a category, so anything
the rules are not sure about lands in `LedgerView.review` and waits — and the answer decays, because
a merchant she can place today is archaeology in two weeks. `Filing.kt` is the pure half: `filingNews`
takes the review list and one `Long`, and returns what is worth saying plus the mark to write down.
`filingMark` sits in `SharedPreferences` beside `budgetMarks` and is the newest `at` this phone has
accounted for — a stamp rather than a count, so filing some and receiving more is still news, so a
receipt refiled into the backlog is not, and so `rewindIngest` importing a year of past-stamped rows
cannot ambush anyone. Zero means "never looked" and is read as *seed, do not speak*, which is what
keeps a fresh install and an upgrade from opening with a note about sixty-seven old receipts.

The asymmetry with budgets is deliberate and lives in two places. The worker calls `announceFiling`;
`publishLedger` calls **`markFilingSeen`**, which takes the note down and moves the mark past
everything on screen — the app never posts this one, because the tab badge, the pill on دفتر and the
deck they open have already said it better, and a note about rows she is looking at is the app
talking over itself. And the channel is `IMPORTANCE_LOW` against the budget channel's
`IMPORTANCE_DEFAULT`: budgets are money and interrupt, filing is homework and waits in the shade.
Two channels so either can be silenced without the other.

Tapping it opens the deck rather than a tab, through `EXTRA_OPEN_DECK` → `AppVm.openDeck` →
`UiState.openDeck`, the same one-shot shape `openTab` uses. The two `PendingIntent`s differ **only**
in their extras, and the platform does not compare extras — so they carry request codes `0` and `1`,
without which `FLAG_UPDATE_CURRENT` would quietly point the budget note at the review deck.

**Watching it fire is harder than the budget one, and worth writing down.** Opening the app is what
takes the note away, so it cannot be triggered from the UI, and `cmd jobscheduler run -f` does not
work either: JobScheduler starts the job, and WorkManager then refuses it with *"executed before
schedule"* because the six hours have not passed. Three things have to be true at once — a stale
mark, an overdue `WorkSpec`, and no foreground app — and this is the sequence that gets there:

```bash
P=com.doxigo.muchtoman.dev
adb shell am force-stop $P                     # also cancels its jobs; the broadcast brings them back

# 1. stale mark — pull shared_prefs/muchtoman.xml, set filingMark to something older, push it back
adb shell run-as $P cat shared_prefs/muchtoman.xml > mt.xml    # edit <long name="filingMark" …>
adb push mt.xml /data/local/tmp/ && adb shell run-as $P cp /data/local/tmp/mt.xml shared_prefs/muchtoman.xml

# 2. overdue WorkSpec — the phone has no sqlite3, so edit it here
adb shell run-as $P cat no_backup/androidx.work.workdb > w.db
adb shell run-as $P rm -f no_backup/androidx.work.workdb-shm no_backup/androidx.work.workdb-wal
sqlite3 w.db "UPDATE WorkSpec SET last_enqueue_time = last_enqueue_time - 25200000
              WHERE worker_class_name LIKE '%LedgerWatchWorker'; PRAGMA wal_checkpoint(TRUNCATE);"
adb push w.db /data/local/tmp/ && adb shell run-as $P cp /data/local/tmp/w.db no_backup/androidx.work.workdb

# 3. start the process without the activity, so nothing marks the backlog seen on the way in.
#    WorkManager's own diagnostics receiver does it; -f 0x20 is FLAG_INCLUDE_STOPPED_PACKAGES,
#    without which a force-stopped package never sees the broadcast.
adb shell am broadcast -f 0x20 -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS -p $P
adb shell dumpsys notification --noredact | grep -A20 "pkg=$P" | grep android.title
```

Clearing app data puts a device back into the "never looked" state, which is the other case worth
seeing: the first pass must stay silent.

### Shipping a parser fix

Bump `PARSER_VERSION` in `Derived.kt`. The next launch re-derives every transaction from the
stored messages and replays her corrections onto them. It does not read the inbox, does not need
the permission, and does not touch a single anchor.

**Do not bump `SMS_SCHEMA`.** That block was deleted; `Store.init` removed `bankAccounts` before
any migration could read it, which would have deleted every balance she typed in by hand — the
one figure no rescan can rebuild.

### Tests

```bash
./gradlew test                    # 327 JVM tests, no device
cd sync && npm run check          # real Durable Objects under workerd
cd pwa  && npm run check          # incl. the shared-corpus agreement check
```

The golden corpus is `app/src/test/resources/sms/*.json` — real bank messages kept verbatim,
each with the reading it must produce and a `why` explaining which phone produced that shape.
Both parsers read it, so the Kotlin and TypeScript sides cannot drift on the cases they share.
An absent key is not asserted; an unknown key fails the run, so a typo cannot quietly weaken it.

### Deploying

```bash
cd pwa  && npm run build          # sync/ serves this as its assets
cd sync && npm run deploy
```

`sync.muchtoman.com`, custom domain only — `workers.dev` is DNS-sinkholed inside Iran, same as
for the rates Worker.
