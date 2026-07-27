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

## Where the prices come from

Free-market rates only — bonbast/tgju for fiat, gold and coins, and Iranian exchanges
(bitpin, tetherland) for crypto so the Tehran premium is real rather than a USD conversion.
A small Cloudflare Worker normalises all of it to Toman-per-unit; the phone only ever
multiplies `amount × rate`, and caches the last good answer.

## Privacy

No account, no login, no analytics. Holdings live in the app's own storage on the phone, bank
SMS is parsed on-device, and the only network call is fetching rates.

## Building it yourself

See [DEVELOPMENT.md](DEVELOPMENT.md).
