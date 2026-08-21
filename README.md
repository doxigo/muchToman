# چقدر تومن — MuchToman

An Android app that answers one question: *how much is all my money, in Toman?*

Persian UI, RTL, Persian digits, big type, and large amounts spoken the way people actually
say them ("۴٫۷ میلیارد تومان", not "۴٬۶۶۶٬۲۵۱٬۱۳۶"). Built for a Persian-speaking parent, so
legibility beats density everywhere.

Holds plain Toman, fiat (USD, EUR, GBP, NOK, TRY, AED, CAD), **any of the top 250
cryptocurrencies**, 18k gold by the gram or the مثقال, silver by the gram at ۹۹۹ and ۹۲۵,
Iranian coins (Emami, Bahar Azadi, Nim, Rob, Gerami), and سکه پارسیان in all fifteen sizes
from ۱۰۰ to ۱۵۰۰ سوت. Everything is valued at **free-market** rates — the official ~42,000
IRR peg is not used anywhere.

خودرو, خانه و ویلا and زمین are held too, and are the only rows with no price behind them:
nobody quotes *your* car, so you type what it is worth in Toman and that figure is the value.

سکه پارسیان is quoted per size rather than weighed as gold: the اجرت is close to fixed per
coin, so a ۱۰۰ سوت piece goes for about a quarter more than the gold in it.

## Screenshots

Sample data, live rates.

| | | |
| --- | --- | --- |
| ![](docs/screenshots/home.png) | ![](docs/screenshots/assets.png) | ![](docs/screenshots/report.png) |
| ![](docs/screenshots/home-dark.png) | ![](docs/screenshots/assets-dark.png) | ![](docs/screenshots/report-dark.png) |

## What it does

- **One total, three ways.** Scannable ("۱۰٫۸ میلیون تومان"), spelled out in Persian words,
  and exact digits. Displayed figures truncate rather than round, so the number shown is never
  larger than the real one.
- **Bank balances from SMS.** Read and parsed locally from the phone's inbox, with no bank API.
  SMS sharing is off by default.
- **Budgets, per category.** Set a spending cap on «رستوران و کافه» — weekly, monthly, or by فصل,
  the Jalali quarter that is also a season. The card shows what is gone against what is left, how
  many days the window still has, and whether the spending is running ahead of the calendar. At 80%,
  at 95%, and once it goes past, the phone says so — once per window, never twice. The figure is the
  same one the دخل و خرج report shows for that category, computed from the transactions each time
  and never stored.
- **Savings goals, with a deadline.** «۵۰ میلیون تا ۶ ماه» carries the monthly rate that gets you
  there, and every card states the date its progress is counted from. No points, no streaks, no
  confetti, and no comparison with anybody else.
- **Optional family ledger.** Two or more people can join by one-time QR code. Each person chooses
  whether their parsed SMS transactions are shared. Every shared item names its owner, and any
  family member can categorize it.
- **Public-wallet tracking, or manual entry.** Save a public address and the app refreshes BTC,
  ETH/ERC-20, SOL, TRX/TRC-20, and supported EVM-token balances on BSC, Arbitrum, Polygon,
  Optimism, and Avalanche. Anything else can be entered by hand.
- **Your own names.** Any holding can carry a label of your own — "تتر شخصی" beside "تتر
  مشترک". The asset keeps its real name underneath, so the rate still applies.
- **A year of history.** The total is remembered once a day, with 1/3/6/12-month change.
- **Offline-tolerant.** The last good rates are cached on the phone.
- **Missing rates are never zero.** An asset with no price is left out of the total and named
  in a note, and any rate can be overridden by hand.
- **Optional app lock** (fingerprint or device PIN), off by default.
- Light and dark, following the system by default.

## Banks it reads

A message is read only if it came from one of these senders — identity is the sending number
or header, never words in the body.

| Bank | Sends from |
| --- | --- |
| بلو بانک | `0999 998 7641`, `90000258`, `+9890000258`, `98300087641` |
| بانک سامان | `0999 992 0000`, `+989820000`, `9820000`, `6219` |
| بانک رفاه | `100031`, `100032`, `Refah Bank`, `RefahBank` |
| بانک پاسارگاد | `B.Pasargad` |
| بانک اقتصاد نوین | `ENBank` |
| بانک خاورمیانه | `20004861`, `+9820004861`, `+989820004860` |
| بانک صادرات | `+98 9870 0719`, `98700719`, `+98 983 000 9419`, `BankSaderat` |
| بانک رسالت | `ResalatBank` |
| بانک پارسیان | `PARSIANBANK` |
| بانک ملت | `Bank Mellat`, `6104` |
| بانک ملی ایران | `6037`, `09830009417` |
| بانک دی | `Day Bank` |

**Deliberately not read:** بانک آینده. Its messages are shaped like balance updates but are not.

**SMS only, not push notifications.** Some banks (Blu among them) let you take transaction
alerts as notifications from their own app instead of as SMS. There is no message to read in
that case, so the balance quietly stops at the last real SMS rather than reporting anything
wrong. Turn SMS alerts back on in that bank's own settings.

<img src="docs/screenshots/banks.png" width="280">

A bank that starts sending from a new shortcode becomes a suggestion card in the app; your tap
is what adds it, on that phone only.

## Install

Grab the APK from [Releases](https://github.com/doxigo/muchToman/releases). Each covers every
device, Android 7.0 (API 24) and newer.

Two of them are published side by side:

| File | What it is |
| --- | --- |
| `muchtoman-vX.Y.Z.apk` | everything below — the household ledger, goals, the companion phone |
| `muchtoman-lite-vX.Y.Z.apk` | دارایی only: what you own and what it is worth today |

Take the lite one if you want a portfolio and not a budget. It installs under its own name, so
you can have both on one phone and try the full app without giving up the simple one. Both read
the same bank پیامک and get the same detection fixes — they are one codebase, built twice.

If you are already running an earlier version, the plain `muchtoman-` file is your update: it
keeps its package, so the balances you typed in and every category you confirmed carry over.

New releases show up as a line under your total the next time you open the app. To be notified
without opening it, add `https://github.com/doxigo/muchToman` to
[Obtainium](https://github.com/ImranR98/Obtainium).

## Where the prices come from

Free-market rates only — bonbast/tgju for fiat, gold and coins, and Iranian exchanges
(bitpin, tetherland) for crypto so the Tehran premium is real rather than a USD conversion.

## Privacy

No account, no login, no analytics. Holdings, saved wallet links, and raw bank SMS live in the
app's own storage on the phone and are excluded from Android backup and device transfer.

Family sync is optional and end-to-end encrypted. SMS sharing starts off. When a person enables
it, their phone shares only the parsed amount, direction, time, bank, merchant, and category. It
never uploads the raw SMS text. Manual family-ledger items are shared. The sync service can see
opaque household/member ids, record types, timestamps, and ciphertext, but not the transaction
contents or member names. Turning SMS sharing off sends deletion markers for that person's
previously shared SMS items. It cannot erase anything another member already saw or copied.

Notifications are computed and posted entirely on the phone. There are two: a budget of yours has
crossed a line you asked to be told about, and a transaction has landed that nothing has filed yet.
Neither leaves the device or reaches the family sync — not the category, not the cap, not the
merchant, not the fact that either happened. They sit on separate channels, so you can silence the
filing reminders and keep the budget alerts, or the other way round, in Android's own settings.

The notification permission is asked for when the first budget is saved, or from دفتر once there are
transactions waiting to be filed — never at launch. Denying it costs the alerts and nothing else:
the آینده screen shows the same figures either way, and the badge on دفتر counts the same backlog.

Wallet tracking is opt-in: when enabled, the public address is sent through the configured
Worker to a public blockchain RPC or indexer. The app never asks for a recovery phrase or private
key. Rates and coin logos come through the configured Worker. The only direct price-source
request is TSETMC, and only when the stock picker is opened or a stock is already held.

## Building it yourself

See [DEVELOPMENT.md](DEVELOPMENT.md).
