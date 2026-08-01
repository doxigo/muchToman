# چقدر تومن — MuchToman

An Android app that answers one question: *how much is all my money, in Toman?*

Persian UI, RTL, Persian digits, big type, and large amounts spoken the way people actually
say them ("۴٫۷ میلیارد تومان", not "۴٬۶۶۶٬۲۵۱٬۱۳۶"). Built for a Persian-speaking parent, so
legibility beats density everywhere.

The total is the screen, not a card on it: a deep green field runs edge to edge and under the
status bar, with the figure set in [Modam](https://fontiran.com) — one variable font, weight
and width both live, so a number that grows by an order of magnitude narrows instead of
shrinking. Every figure is tabular, so digits do not shuffle sideways when a rate lands.

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
- **Public-wallet tracking, or manual entry.** For supported crypto networks, save a public
  address and the app refreshes BTC, ETH/ERC-20, SOL, TRX/TRC-20, and supported EVM-token
  balances on BSC, Arbitrum, Polygon, Optimism, and Avalanche. Unsupported coins and anyone
  who does not want to share an address can keep entering amounts manually.
- **Your own names.** Any holding can carry a label of your own — "تتر شخصی" beside "تتر
  مشترک", "طلای مادر" beside your own gold. The asset keeps its real name underneath, so the
  row still says which money it is and the rate still applies.
- **A year of history.** The total is remembered once a day, with 1/3/6/12-month change. The
  one-month move sits on the total itself, as a percentage; the rest is one tap away.
- **Offline-tolerant.** The last good rates are cached on the phone. Toman is pinned at 1 and
  never fetched, so money already in Toman still counts when the network is down.
- **Missing rates are never zero.** An asset with no price is left out of the total and named
  in a note, and any rate can be overridden by hand.
- **Optional app lock** (fingerprint or device PIN), off by default.
- Light and dark, following the system by default.

## Banks it reads

A message is read only if it came from one of these senders. Everything else in the inbox is
skipped — identity is the sending number, never words in the body, so an advert or a one-time
code from a bank cannot be mistaken for a transaction.

| Bank | Sends from |
| --- | --- |
| بلو بانک | `0999 998 7641`, `90000258`, `+9890000258`, `98300087641` |
| بانک سامان | `0999 992 0000`, `+989820000`, `9820000` |
| بانک رفاه | `100031`, `100032`, `Refah Bank` |
| بانک پاسارگاد | `B.Pasargad` |
| بانک اقتصاد نوین | `ENBank` |
| بانک خاورمیانه | `20004861`, `+9820004861`, `+989820004860` |
| بانک صادرات | `+98 9870 0719`, `98700719`, `+98 983 000 9419`, `BankSaderat` |

Only the digits are compared, and only the last ten of them, so every form Android hands over
for the same line matches — `+989999987641`, `989999987641`, `09999987641` and `0999 998 7641`
are one sender. A shortcode is shorter than ten digits and so matches itself. Banks that send
from a lettered header rather than a number (`ENBank`, `B.Pasargad`, `Refah Bank`, `BankSaderat`) are matched
on that name instead, case and spacing ignored.

**Deliberately not read:** بانک آینده. Its messages are shaped like balance updates but are
not, so they are dropped before the "unknown sender" suggestion below ever fires.

<img src="docs/screenshots/banks.png" width="280">

A bank that starts sending from a new shortcode is a one-tap confirmation in the app — a
message that names one of your banks from a number the list lacks becomes a suggestion card,
and your tap is what adds it, on that phone only. Adding a bank outright is one line in `Bank`
in [Sms.kt](app/src/main/java/com/doxigo/muchtoman/Sms.kt), plus one in `BankLogo` for its
mark; the `when` is exhaustive, so the compiler asks.

## Install

Grab the APK from [Releases](https://github.com/doxigo/muchToman/releases). One APK covers
every device — no native code, so nothing to split per ABI — and it installs on Android 7.0
(API 24) and newer.

### Staying updated

Nothing here phones home to update itself, so there are two ways to hear about a new build:

- **[Obtainium](https://github.com/ImranR98/Obtainium)** — add `https://github.com/doxigo/muchToman`
  once and it watches Releases in the background, notifies you, and installs. This is the only
  option that reaches you without opening the app.
- **The app itself** — a new release shows up as a line under your total the next time you open
  it, with a link to the download. Tapping "بعداً" hides that one release, not the next.

## Where the prices come from

Free-market rates only — bonbast/tgju for fiat, gold and coins, and Iranian exchanges
(bitpin, tetherland) for crypto so the Tehran premium is real rather than a USD conversion.
A small Cloudflare Worker normalises all of it to Toman-per-unit; the phone only ever
multiplies `amount × rate`, and caches the last good answer.

## Privacy

No account, no login, no analytics. Holdings and saved wallet links live in the app's own
storage on the phone, and bank SMS is parsed on-device. Wallet tracking is opt-in: when it is
enabled, the public address is sent through the configured Worker to a public blockchain RPC
or indexer. The app never asks for a recovery phrase or private key. Without wallet tracking,
the only network call is fetching rates.

## Building it yourself

See [DEVELOPMENT.md](DEVELOPMENT.md) — including the note on Modam, which is a commercial face
and needs its own licence from [fontiran.com](https://fontiran.com).
