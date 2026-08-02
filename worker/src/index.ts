/**
 * muchtoman-rates — prices plus read-only public-wallet balance lookup.
 *
 *   { "updatedAt": 1785000000000, "toman": { "usd": 187000, "gold18": 17886329, ... } }
 *
 * The phone does nothing but multiply, so every unit conversion and every source quirk is
 * dealt with here.
 *
 *   fiat / gold / coins   bonbast.com (Toman)  ->  tgju.org (Rial, /10)
 *   crypto                bitpin -> tetherland (Toman, real Tehran price)
 *                         -> coingecko -> binance (USD, x the dollar rate)
 *
 * Two independent chains, because they fail independently. Everything is cross-checked
 * against the dollar rate before it is published.
 *
 * Names and prices are two different jobs, and only coingecko does both. A price is never
 * held back for want of a name: when the catalogue is the thing that is down, prices still
 * go out and the phone keeps the names and logos it already has.
 */

const UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36';

const TTL_SECONDS = 600; // 10 minutes; these markets do not move meaningfully faster.
const RATES_CACHE_VERSION = 'latest-release-v1';

/** Where the app's own APKs come from — the source of the update note in /rates. */
const REPO = 'doxigo/muchToman';

/**
 * A scrape that half-works is more dangerous than one that fails, because it produces a
 * confident wrong total. The official IRR peg would land the dollar near 4,200 Toman and a
 * parse slip lands it near zero — both are rejected rather than shown to a user.
 */
const PLAUSIBLE_USD_TOMAN = { min: 10_000, max: 10_000_000 };

/** How many coins the picker offers, by market cap. */
const COIN_LIMIT = 250;

function num(v: unknown): number | null {
  if (v == null) return null;
  const n = typeof v === 'number' ? v : Number(String(v).replace(/,/g, ''));
  return Number.isFinite(n) && n > 0 ? n : null;
}

async function getJson(url: string, init?: RequestInit): Promise<any> {
  const res = await fetch(url, {
    ...init,
    headers: { 'user-agent': UA, accept: 'application/json', ...(init?.headers ?? {}) },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/** Runs sources in order, returns the first that yields anything. Collects the failures. */
async function firstOf<T>(
  sources: { name: string; run: () => Promise<T> }[],
  usable: (v: T) => boolean,
): Promise<{ value: T; via: string; failures: string[] }> {
  const failures: string[] = [];
  for (const s of sources) {
    try {
      const value = await s.run();
      if (!usable(value)) throw new Error('response had no usable values');
      return { value, via: s.name, failures };
    } catch (e: any) {
      failures.push(`${s.name}: ${e?.message ?? e}`);
    }
  }
  throw new Error(failures.join('; ') || 'no sources configured');
}

// ───────────────────────── fiat, gold, coins ─────────────────────────

/** app id -> bonbast field. The "1" fields are the headline (sell) side. Already Toman. */
const BONBAST_MAP: Record<string, string> = {
  usd: 'usd1',
  eur: 'eur1',
  gbp: 'gbp1',
  nok: 'nok1',
  try: 'try1',
  aed: 'aed1',
  cad: 'cad1',
  gold18: 'gol18',
  gold_mesghal: 'mithqal',
  coin_emami: 'emami1',
  coin_bahar: 'azadi1',
  coin_nim: 'azadi1_2',
  coin_rob: 'azadi1_4',
  coin_gerami: 'azadi1g',
};

/** app id -> tgju indicator key. tgju quotes in RIAL, so every value is divided by 10. */
const TGJU_MAP: Record<string, string> = {
  usd: 'price_dollar_rl',
  eur: 'price_eur',
  gbp: 'price_gbp',
  nok: 'price_nok',
  try: 'price_try',
  aed: 'price_aed',
  cad: 'price_cad',
  gold18: 'geram18',
  gold_mesghal: 'mesghal',
  coin_emami: 'sekee',
  coin_bahar: 'sekeb',
  coin_nim: 'nim',
  coin_rob: 'rob',
  coin_gerami: 'gerami',
};

async function fetchBonbast(): Promise<Record<string, number>> {
  // The JSON endpoint only answers with a token minted into the homepage HTML.
  const home = await fetch('https://bonbast.com/', {
    headers: { 'user-agent': UA, 'accept-language': 'en-US,en;q=0.9' },
  });
  if (!home.ok) throw new Error(`homepage HTTP ${home.status}`);

  const html = await home.text();
  const token = html.match(/param:\s*"([^"]+)"/)?.[1];
  if (!token) throw new Error('token not found in page');

  const setCookie = home.headers.get('set-cookie');
  const data = await getJson('https://bonbast.com/json', {
    method: 'POST',
    headers: {
      'content-type': 'application/x-www-form-urlencoded',
      'x-requested-with': 'XMLHttpRequest',
      referer: 'https://bonbast.com/',
      ...(setCookie ? { cookie: setCookie.split(';')[0] } : {}),
    },
    body: new URLSearchParams({ param: token }).toString(),
  });

  const out: Record<string, number> = {};
  for (const [id, field] of Object.entries(BONBAST_MAP)) {
    const v = num(data?.[field]);
    if (v != null) out[id] = v;
  }
  return out;
}

async function fetchTgjuKeys(map: Record<string, string>): Promise<Record<string, number>> {
  const keys = Object.values(map).join(',');
  const data = await getJson(`https://api.tgju.org/v1/widget/tmp?keys=${keys}`);

  const byName = new Map<string, unknown>();
  for (const ind of data?.response?.indicators ?? []) byName.set(ind?.name, ind?.p);

  const out: Record<string, number> = {};
  for (const [id, key] of Object.entries(map)) {
    const rial = num(byName.get(key));
    if (rial != null) out[id] = rial / 10; // Rial -> Toman
  }
  return out;
}

const fetchTgju = () => fetchTgjuKeys(TGJU_MAP);

// ───────────────── silver and سکه پارسیان: tgju's pages ─────────────────

/**
 * Named rows off a tgju price page, as Toman.
 *
 * The widget API above would be the nicer way to ask, and it is not used here for two
 * reasons: it carries neither silver nor سکه پارسیان, and it answers 200 with an empty body
 * once it decides you have asked too often. An empty body is a failure this code can see —
 * it matches no rows and throws — but it is a failure that arrives without warning.
 *
 * A row is read whole: the price is only ever taken from the same `<tr>` that carries the
 * slug. Matching slugs and prices independently down the page is how a markup change pairs
 * one coin with its neighbour's price, and neighbouring Parsian sizes are about 5% apart —
 * close enough that the wrong one would look perfectly reasonable on screen.
 */
async function fetchTgjuPage(
  path: string,
  rows: Record<string, string>,
): Promise<Record<string, number>> {
  const res = await fetch(`https://www.tgju.org/${path}`, { headers: { 'user-agent': UA } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const html = await res.text();

  const out: Record<string, number> = {};
  for (const row of html.split('<tr ')) {
    for (const [slug, id] of Object.entries(rows)) {
      // Either attribute, because neither is dependable on its own: the Parsian rows carry
      // their name in `nameslug` and a bare number in `data-market-row`, and the silver rows
      // do the reverse — and silver_999 in particular ships `nameslug=""` on some fetches and
      // its real name on others. The closing quote is part of the match, so
      // "سکه-پارسیان-۰-۱۰۰" cannot also match "سکه-پارسیان-۱-۱۰۰".
      if (!row.includes(`nameslug="${slug}"`) && !row.includes(`data-market-row="${slug}"`)) {
        continue;
      }
      // The first `nf` cell is the price; the second is the day's change, which is wrapped
      // in a span and so cannot match this.
      const rial = num(row.match(/<td class="nf">([\d,]+)<\/td>/)?.[1]);
      if (rial != null) out[id] = rial / 10; // Rial -> Toman
    }
  }
  if (Object.keys(out).length === 0) throw new Error(`no rows matched on /${path}`);
  return out;
}

/**
 * Silver, per gram, at both purities the Tehran market quotes.
 *
 * There is no second source. bonbast has no silver at any purity, and tgju's own global
 * silver ounce is the same host, so cross-rating it would be redundancy in name only — and
 * would drop the Tehran premium, which ran about 4% over the ounce when this was written.
 * A rate that goes missing is already handled the way every missing rate is here: left out
 * of the total and named in a note. That is the right answer, and better than a quiet guess.
 */
const fetchSilver = () =>
  fetchTgjuPage('gold-chart', { silver_999: 'silver_999', silver_925: 'silver_925' });

/**
 * سکه پارسیان is 18k gold sold by weight in سوت — 1000 سوت to the gram — in the fifteen
 * sizes tgju quotes, 100 through 1500.
 *
 * Every size is priced on its own rather than as its weight in gold, because the اجرت is
 * close to fixed per coin and so weighs heaviest on the smallest: when this was written a
 * 100 سوت piece went for about 24% over its gold content, a 500 سوت one 8%, and a 1500 سوت
 * one 6%. Pricing them as gold18 × weight would understate the smallest by nearly a quarter.
 */
const PARSIAN_SOOT = [100, 200, 300, 400, 500, 600, 700, 800, 900,
  1000, 1100, 1200, 1300, 1400, 1500];

const FA_DIGITS = '۰۱۲۳۴۵۶۷۸۹';
const faDigits = (s: string) => s.replace(/\d/g, (d) => FA_DIGITS[Number(d)]);

/** tgju names each row by its weight in grams: 100 سوت is "سکه-پارسیان-۰-۱۰۰". */
const parsianSlug = (soot: number) =>
  `سکه-پارسیان-${faDigits(String(Math.floor(soot / 1000)))}-` +
  faDigits(String(soot % 1000).padStart(3, '0'));

/**
 * Which quote stands in for "one سوت". The 1 گرم coin first, because that is the size the
 * market itself quotes as the reference; then the largest still on the page, since the اجرت
 * shrinks with size and the big coins sit closest to the gold in them.
 */
const PARSIAN_REFERENCE = [1000, ...[...PARSIAN_SOOT].reverse()];

/**
 * The app offers one سکه پارسیان counted in سوت rather than fifteen coins, so alongside the
 * fifteen sizes — still published, because holdings saved against them are priced from them
 * — this derives the per-سوت rate that single row multiplies by.
 *
 * It is an approximation and knowingly so: the اجرت is close to fixed per coin, so a ۱۰۰ سوت
 * piece really costs about a quarter over its gold and a ۱۵۰۰ سوت one a few per cent. One
 * scalar cannot hold both. The reference size is the honest middle, and the phone can
 * override any rate by hand.
 */
async function fetchParsian(): Promise<Record<string, number>> {
  const prices = await fetchTgjuPage(
    encodeURIComponent('قیمت-سکه-پارسیان'),
    Object.fromEntries(PARSIAN_SOOT.map((s) => [parsianSlug(s), `parsian_${s}`])),
  );

  const reference = PARSIAN_REFERENCE.find((s) => prices[`parsian_${s}`] != null);
  if (reference != null) prices.parsian = prices[`parsian_${reference}`] / reference;
  return prices;
}

async function fetchFiat() {
  const got = await firstOf(
    [
      { name: 'bonbast', run: fetchBonbast },
      { name: 'tgju', run: fetchTgju },
    ],
    (v) => v.usd != null,
  );

  const usd = got.value.usd;
  if (usd < PLAUSIBLE_USD_TOMAN.min || usd > PLAUSIBLE_USD_TOMAN.max) {
    throw new Error(`${got.via} dollar rate implausible: ${usd} Toman`);
  }
  return got;
}

// ───────────────────────── crypto ─────────────────────────

/**
 * [name] is what she reads — Persian wherever a local exchange has one. [en] is the latin
 * name CoinGecko knows it by, kept alongside rather than replaced: it is the only way the
 * picker can answer "Polkadot" as well as "پولکادات" and "DOT".
 */
type WalletOption = { network: string; networkFa: string; contract?: string };
type Coin = { id: string; name: string; en: string; icon: string; wallets: WalletOption[] };

const WALLET_PLATFORMS: Record<string, Omit<WalletOption, 'contract'>> = {
  ethereum: { network: 'ethereum', networkFa: 'اتریوم' },
  tron: { network: 'tron', networkFa: 'ترون' },
  'binance-smart-chain': { network: 'bsc', networkFa: 'BSC' },
  'arbitrum-one': { network: 'arbitrum', networkFa: 'آربیتروم' },
  'polygon-pos': { network: 'polygon', networkFa: 'پالیگان' },
  'optimistic-ethereum': { network: 'optimism', networkFa: 'آپتیمیسم' },
  avalanche: { network: 'avalanche', networkFa: 'آوالانچ' },
};

// CoinGecko publishes some canonical/bridged USDT contracts as separate market assets.
// They belong in the app's single USDT holding, but other bridged tickers stay distinct.
const WALLET_METADATA_ALIASES: Record<string, string[]> = {
  tether: ['binance-bridged-usdt-bnb-smart-chain', 'usdt0'],
};

const WALLET_NETWORK_ORDER = [
  'bitcoin', 'ethereum', 'tron', 'bsc', 'arbitrum', 'polygon', 'optimism', 'avalanche', 'solana',
];

const NATIVE_WALLETS: Record<string, WalletOption> = {
  bitcoin: { network: 'bitcoin', networkFa: 'بیت کوین' },
  ethereum: { network: 'ethereum', networkFa: 'اتریوم' },
  solana: { network: 'solana', networkFa: 'سولانا' },
  tron: { network: 'tron', networkFa: 'ترون' },
};

function walletsFor(
  geckoId: string,
  platformsById: Map<string, Record<string, unknown>>,
): WalletOption[] {
  const wallets: WalletOption[] = [];
  if (NATIVE_WALLETS[geckoId]) wallets.push(NATIVE_WALLETS[geckoId]);

  for (const metadataId of [geckoId, ...(WALLET_METADATA_ALIASES[geckoId] ?? [])]) {
    for (const [platform, value] of Object.entries(platformsById.get(metadataId) ?? {})) {
      const known = WALLET_PLATFORMS[platform];
      const contract = String(value ?? '').trim();
      if (known && contract) wallets.push({ ...known, contract });
    }
  }

  return wallets
    .filter(
      (wallet, index) => wallets.findIndex((other) => other.network === wallet.network) === index,
    )
    .sort((a, b) => WALLET_NETWORK_ORDER.indexOf(a.network) - WALLET_NETWORK_ORDER.indexOf(b.network));
}

/**
 * The coin catalogue and its USD prices. This is also where the picker's names and logos
 * come from — an emoji standing in for a coin is just a wrong logo, so we ship the real one.
 */
async function fetchCoinGecko(): Promise<{ usd: Record<string, number>; coins: Coin[] }> {
  const [list, platformList] = await Promise.all([
    getJson(
      `https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=${COIN_LIMIT}&page=1`,
    ),
    // Contract metadata is an enhancement. If this one large map is rate-limited, prices and
    // native BTC/ETH/SOL/TRX tracking still go out; token tracking returns on the next refresh.
    getJson('https://api.coingecko.com/api/v3/coins/list?include_platform=true').catch(() => []),
  ]);
  if (!Array.isArray(list) || list.length === 0) throw new Error('empty coin list');

  const platformsById = new Map<string, Record<string, unknown>>();
  for (const coin of Array.isArray(platformList) ? platformList : []) {
    platformsById.set(String(coin?.id ?? ''), coin?.platforms ?? {});
  }

  const usd: Record<string, number> = {};
  const coins: Coin[] = [];

  for (const c of list) {
    const id = String(c?.symbol ?? '').toLowerCase();
    const geckoId = String(c?.id ?? '');
    const price = num(c?.current_price);
    // A coin whose ticker collides with a currency/gold/coin id would shadow it in the
    // rates map. There are none today; skipping is still cheaper than debugging it later.
    if (!id || !geckoId || price == null || id in BONBAST_MAP || id === 'toman') continue;
    // The app has historically keyed holdings by ticker. When two current coins share one,
    // the higher-market-cap entry wins instead of the later one silently changing its price,
    // logo, and now its token contract.
    if (id in usd) continue;

    const en = String(c?.name ?? id.toUpperCase());
    usd[id] = price;
    coins.push({
      id,
      name: en, // until a Persian name is found for it below
      en,
      icon: String(c?.image ?? '').split('?')[0], // cache-buster query is dead weight x250
      wallets: walletsFor(geckoId, platformsById),
    });
  }
  return { usd, coins };
}

type TomanCrypto = { prices: Record<string, number>; namesFa: Record<string, string> };

/**
 * Every *_IRT market bitpin lists, as symbol -> Toman. Carries the real Tehran premium, and
 * also the Persian coin names — "بیت‌کوین" is what she'd recognise, not "Bitcoin".
 */
async function fetchBitpin(): Promise<TomanCrypto> {
  const data = await getJson('https://api.bitpin.ir/v1/mkt/markets/');
  const prices: Record<string, number> = {};
  const namesFa: Record<string, string> = {};

  for (const m of data?.results ?? []) {
    const code = String(m?.code ?? '');
    if (!code.endsWith('_IRT')) continue;

    const id = code.slice(0, -4).toLowerCase();
    const v = num(m?.price);
    if (v != null) prices[id] = v;

    const fa = String(m?.currency1?.title_fa ?? '').trim();
    // Some entries just repeat the latin name; only keep it if it is actually Persian.
    if (fa && /[؀-ۿ]/.test(fa)) namesFa[id] = fa;
  }
  if (Object.keys(prices).length === 0) throw new Error('no IRT markets found');
  return { prices, namesFa };
}

/**
 * Prices only — no names, no logos. Stands in for the catalogue's USD side when it is down,
 * which covers the coins bitpin has no IRT market for. USDT is treated as USD, as everywhere
 * else here; a fraction of a percent of peg drift is far below the spread on the dollar rate.
 */
async function fetchBinanceUsd(): Promise<Record<string, number>> {
  const list = await getJson('https://api.binance.com/api/v3/ticker/price');
  const usd: Record<string, number> = {};
  for (const t of Array.isArray(list) ? list : []) {
    const sym = String(t?.symbol ?? '');
    if (!sym.endsWith('USDT')) continue;
    const v = num(t?.price);
    if (v != null) usd[sym.slice(0, -4).toLowerCase()] = v;
  }
  if (Object.keys(usd).length === 0) throw new Error('no USDT pairs');
  return usd;
}

/** USDT-only, but it is the one crypto price that matters most here. */
async function fetchTetherland(): Promise<TomanCrypto> {
  const data = await getJson('https://api.tetherland.com/currencies');
  const v = num(data?.data?.currencies?.USDT?.price);
  if (v == null) throw new Error('no USDT price');
  return { prices: { usdt: v }, namesFa: { usdt: 'تتر' } };
}

const hasPrices = (v: TomanCrypto) => Object.keys(v.prices).length > 0;

// ───────────────────────── wallet balances ─────────────────────────

class WalletInputError extends Error {
  constructor(readonly code: string) {
    super(code);
  }
}

const EVM_ADDRESS = /^0x[0-9a-fA-F]{40}$/;
const BASE58_KEY = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;
const TRON_ADDRESS = /^T[1-9A-HJ-NP-Za-km-z]{33}$/;
const BITCOIN_ADDRESS = /^(?:[13][a-km-zA-HJ-NP-Z1-9]{25,34}|bc1[ac-hj-np-z02-9]{11,71})$/i;
const EVM_RPCS: Record<string, string[]> = {
  ethereum: ['https://eth.drpc.org', 'https://ethereum-rpc.publicnode.com'],
  bsc: ['https://bsc-dataseed.bnbchain.org', 'https://bsc-dataseed-public.bnbchain.org'],
  arbitrum: ['https://arb1.arbitrum.io/rpc', 'https://arbitrum-one-rpc.publicnode.com'],
  polygon: ['https://polygon.drpc.org', 'https://polygon.publicnode.com'],
  optimism: ['https://mainnet.optimism.io', 'https://optimism-rpc.publicnode.com'],
  avalanche: [
    'https://api.avax.network/ext/bc/C/rpc',
    'https://avalanche-c-chain-rpc.publicnode.com',
  ],
};
const SOLANA_RPCS = ['https://api.mainnet-beta.solana.com', 'https://solana-rpc.publicnode.com'];

async function rpc(url: string, method: string, params: unknown[]): Promise<any> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5_000);
  const data = await getJson(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
    signal: controller.signal,
  }).finally(() => clearTimeout(timeout));
  if (data?.error || data?.result == null) {
    throw new Error(String(data?.error?.message ?? 'RPC returned no result'));
  }
  return data.result;
}

async function firstRpc(urls: string[], method: string, params: unknown[]): Promise<any> {
  const failures: string[] = [];
  for (const url of urls) {
    try {
      return await rpc(url, method, params);
    } catch (error: any) {
      failures.push(`${url}: ${error?.message ?? error}`);
    }
  }
  throw new Error(failures.join('; '));
}

function scaledAmount(raw: unknown, decimals: number): number {
  if (!Number.isInteger(decimals) || decimals < 0 || decimals > 36) {
    throw new Error('invalid token decimals');
  }
  const text = String(raw ?? '');
  const units = BigInt(text);
  if (units < 0n) throw new Error('negative on-chain balance');
  const scale = 10n ** BigInt(decimals);
  const amount = Number(units / scale) + Number(units % scale) / 10 ** decimals;
  if (!Number.isFinite(amount) || amount < 0) throw new Error('balance outside numeric range');
  return amount;
}

async function evmBalance(network: string, address: string, contract: string): Promise<number> {
  if (!EVM_ADDRESS.test(address)) throw new WalletInputError('invalid_address');
  if (contract && !EVM_ADDRESS.test(contract)) throw new WalletInputError('invalid_contract');
  const urls = EVM_RPCS[network];
  if (!urls) throw new WalletInputError('unsupported_network');

  if (!contract) {
    const raw = await firstRpc(urls, 'eth_getBalance', [address, 'latest']);
    return scaledAmount(raw, 18);
  }

  const account = address.slice(2).padStart(64, '0');
  const [raw, decimalsHex] = await Promise.all([
    firstRpc(urls, 'eth_call', [
      { to: contract, data: `0x70a08231${account}` },
      'latest',
    ]),
    firstRpc(urls, 'eth_call', [
      { to: contract, data: '0x313ce567' },
      'latest',
    ]),
  ]);
  return scaledAmount(raw, Number(BigInt(decimalsHex)));
}

async function solanaBalance(address: string, mint: string): Promise<number> {
  if (!BASE58_KEY.test(address)) throw new WalletInputError('invalid_address');
  if (mint && !BASE58_KEY.test(mint)) throw new WalletInputError('invalid_contract');
  if (mint) throw new WalletInputError('unsupported_network');

  const result = await firstRpc(SOLANA_RPCS, 'getBalance', [
    address,
    { commitment: 'finalized' },
  ]);
  return Number(result.value) / 1_000_000_000;
}

async function tronBalance(address: string, contract: string): Promise<number> {
  if (!TRON_ADDRESS.test(address)) throw new WalletInputError('invalid_address');
  if (contract && !TRON_ADDRESS.test(contract)) throw new WalletInputError('invalid_contract');

  if (!contract) {
    const account = await getJson('https://api.trongrid.io/wallet/getaccount', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ address, visible: true }),
    });
    return Number(account?.balance ?? 0) / 1_000_000;
  }

  const [balances, token] = await Promise.all([
    getJson(
      `https://api.trongrid.io/v1/accounts/${encodeURIComponent(address)}` +
        `/trc20/balance?contract_address=${encodeURIComponent(contract)}&limit=1`,
    ),
    getJson('https://api.trongrid.io/wallet/triggerconstantcontract', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        owner_address: address,
        contract_address: contract,
        function_selector: 'decimals()',
        visible: true,
      }),
    }),
  ]);
  const raw = balances?.data?.[0]?.[contract] ?? '0';
  const decimalsHex = token?.constant_result?.[0];
  if (!decimalsHex) throw new Error('TRC-20 decimals unavailable');
  return scaledAmount(raw, Number(BigInt(`0x${decimalsHex}`)));
}

async function bitcoinBalance(address: string, contract: string): Promise<number> {
  if (contract) throw new WalletInputError('invalid_contract');
  if (!BITCOIN_ADDRESS.test(address)) throw new WalletInputError('invalid_address');
  const encoded = encodeURIComponent(address);
  const validation = await getJson(`https://mempool.space/api/v1/validate-address/${encoded}`);
  if (!validation?.isvalid) throw new WalletInputError('invalid_address');

  const data = await getJson(`https://mempool.space/api/address/${encoded}`);
  const confirmed = Number(data?.chain_stats?.funded_txo_sum ?? 0) -
    Number(data?.chain_stats?.spent_txo_sum ?? 0);
  const pending = Number(data?.mempool_stats?.funded_txo_sum ?? 0) -
    Number(data?.mempool_stats?.spent_txo_sum ?? 0);
  const satoshis = confirmed + pending;
  if (!Number.isFinite(satoshis) || satoshis < 0) throw new Error('invalid Bitcoin balance');
  return satoshis / 100_000_000;
}

async function lookupWalletBalance(body: unknown): Promise<number> {
  if (body == null || typeof body !== 'object') throw new WalletInputError('invalid_request');
  const input = body as Record<string, unknown>;
  const network = String(input.network ?? '');
  const address = String(input.address ?? '').trim();
  const contract = String(input.contract ?? '').trim();
  if (!address || address.length > 128 || contract.length > 128) {
    throw new WalletInputError('invalid_address');
  }

  switch (network) {
    case 'bitcoin': return bitcoinBalance(address, contract);
    case 'ethereum':
    case 'bsc':
    case 'arbitrum':
    case 'polygon':
    case 'optimism':
    case 'avalanche': return evmBalance(network, address, contract);
    case 'solana': return solanaBalance(address, contract);
    case 'tron': return tronBalance(address, contract);
    default: throw new WalletInputError('unsupported_network');
  }
}

function jsonResponse(payload: unknown, status = 200, cacheControl = 'no-store'): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': cacheControl,
      'x-content-type-options': 'nosniff',
    },
  });
}

// ───────────────────────── assembly ─────────────────────────

/**
 * The newest tagged release, so the phone can mention an update without reaching
 * api.github.com itself — from Iran that is the request most likely to hang, and it would
 * hand GitHub a list of user IPs besides. The subrequest is cached at the edge for an hour on
 * top of the /rates cache, which is the longest a new release can take to start showing up.
 */
async function fetchLatestRelease(): Promise<{ name: string; url: string } | null> {
  const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
    headers: { 'user-agent': UA, accept: 'application/vnd.github+json' },
    cf: { cacheTtl: 3600, cacheEverything: true },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const body: any = await res.json();
  const tag = typeof body?.tag_name === 'string' ? body.tag_name : '';
  if (!tag) throw new Error('no tag_name');
  return {
    name: tag.replace(/^v/, ''),
    url: typeof body.html_url === 'string'
      ? body.html_url
      : `https://github.com/${REPO}/releases/latest`,
  };
}

async function buildRates(): Promise<Response> {
  const [fiat, cryptoToman, gecko, release, silver, parsian] = await Promise.allSettled([
    fetchFiat(),
    firstOf(
      [
        { name: 'bitpin', run: fetchBitpin },
        { name: 'tetherland', run: fetchTetherland },
      ],
      hasPrices,
    ),
    fetchCoinGecko(),
    fetchLatestRelease(),
    fetchSilver(),
    fetchParsian(),
  ]);

  const toman: Record<string, number> = {};
  const sources: Record<string, string> = {};

  const note = (key: string, r: PromiseSettledResult<{ via: string; failures: string[] }>, ok: string) => {
    if (r.status === 'fulfilled') {
      sources[key] = r.value.failures.length
        ? `${ok} via ${r.value.via} (tried first: ${r.value.failures.join('; ')})`
        : `${ok} via ${r.value.via}`;
    } else {
      sources[key] = `failed — ${String(r.reason?.message ?? r.reason)}`;
    }
  };

  if (fiat.status === 'fulfilled') Object.assign(toman, fiat.value.value);
  note('fiat_gold_coins', fiat, 'ok');

  // Both are tgju-only and stand alone: either one going down takes nothing else with it, and
  // the phone shows those assets as "نرخ ندارد" rather than folding a guess into the total.
  // The count is reported against what was asked for, because these degrade a row at a time —
  // a page that answers with two of fifteen coins is not the same event as one that 404s, and
  // "ok" on its own would read identically for both.
  const alone = (
    key: string,
    r: PromiseSettledResult<Record<string, number>>,
    want: number,
    what: string,
  ) => {
    if (r.status === 'fulfilled') {
      Object.assign(toman, r.value);
      sources[key] = `ok via tgju, ${Object.keys(r.value).length}/${want} ${what}`;
    } else {
      sources[key] = `failed — ${String(r.reason?.message ?? r.reason)}`;
    }
  };
  alone('silver', silver, 2, 'grades');
  // +1 for the derived per-سوت row the app's single سکه پارسیان multiplies by.
  alone('parsian_coins', parsian, PARSIAN_SOOT.length + 1, 'rows');

  const tomanNative = cryptoToman.status === 'fulfilled' ? cryptoToman.value.value.prices : {};
  const namesFa = cryptoToman.status === 'fulfilled' ? cryptoToman.value.value.namesFa : {};
  const coins = gecko.status === 'fulfilled' ? gecko.value.coins : [];
  let usdPrices: Record<string, number> = gecko.status === 'fulfilled' ? gecko.value.usd : {};

  // The catalogue is a source like any other and fails on its own — and it used to take every
  // crypto price down with it, because pricing ran over `coins`: an empty catalogue published
  // bitpin's hundreds of perfectly good Toman prices as nothing at all. Binance stands in for
  // the catalogue's USD side, covering the large caps bitpin has no IRT market for. `coins`
  // stays empty rather than going out degraded — the phone keeps the names and logos it
  // already has, which, unlike a price, have not gone stale.
  let usdVia = 'coingecko';
  if (gecko.status !== 'fulfilled') {
    usdPrices = await fetchBinanceUsd().catch(() => ({}));
    usdVia = Object.keys(usdPrices).length ? 'binance' : 'nothing';
  }

  let tehranPriced = 0;
  let converted = 0;

  for (const coin of coins) {
    // Persian name where a local exchange has one; the latin name is the fallback. Only
    // [name] moves — overwriting the latin name here is what used to make a coin findable
    // in Persian and invisible in English the moment bitpin happened to list it.
    if (namesFa[coin.id]) coin.name = namesFa[coin.id];
  }

  // Every id anyone priced, not only the ones the catalogue happens to list. A coin she holds
  // that has since dropped out of the top 250 keeps its rate too, which it did not before.
  for (const id of new Set([...Object.keys(tomanNative), ...Object.keys(usdPrices)])) {
    // A ticker that collides with a currency/gold/coin id would shadow it in the rates map.
    if (id in BONBAST_MAP || id === 'toman') continue;

    // Prefer the Tehran-quoted price; fall back to USD x the dollar rate.
    if (tomanNative[id] != null) {
      toman[id] = tomanNative[id];
      tehranPriced++;
    } else if (usdPrices[id] != null && toman.usd != null) {
      toman[id] = usdPrices[id] * toman.usd;
      converted++;
    }
    // else: no dollar rate and no local price — the coin stays out of the map entirely,
    // and the app shows it as "نرخ ندارد" instead of folding a guess into her total.
  }

  note('crypto_toman', cryptoToman, 'ok');
  sources.coin_catalog =
    gecko.status === 'fulfilled'
      ? `ok, ${coins.length} coins`
      : `failed, sending none so the phone keeps its own — ${String(gecko.reason?.message ?? gecko.reason)}`;
  sources.crypto_pricing =
    `${tehranPriced} at Tehran price, ${converted} cross-rated from USD via ${usdVia}`;

  sources.latest_release =
    release.status === 'fulfilled'
      ? `ok, ${release.value!.name}`
      : `failed, no update note this fetch — ${String(release.reason?.message ?? release.reason)}`;

  const ok = Object.keys(toman).length > 0;
  const payload = {
    updatedAt: Date.now(),
    toman,
    coins,
    sources,
    // Null when GitHub is the source that is down. The phone keeps whatever it last heard
    // rather than retracting a note it has already shown.
    latest: release.status === 'fulfilled' ? release.value : null,
  };

  return new Response(JSON.stringify(payload), {
    status: ok ? 200 : 502,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': ok ? `public, max-age=${TTL_SECONDS}` : 'no-store',
    },
  });
}

export default {
  async fetch(request: Request, _env: unknown, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === '/wallet-balance') {
      if (request.method !== 'POST') {
        return new Response('Method not allowed', { status: 405, headers: { allow: 'POST' } });
      }
      try {
        const text = await request.text();
        if (text.length > 4096) throw new WalletInputError('invalid_request');
        const amount = await lookupWalletBalance(JSON.parse(text));
        return jsonResponse({ amount, updatedAt: Date.now() });
      } catch (error) {
        if (error instanceof WalletInputError || error instanceof SyntaxError) {
          const code = error instanceof WalletInputError ? error.code : 'invalid_request';
          return jsonResponse({ code }, 400);
        }
        console.error(
          'wallet lookup failed',
          error instanceof Error ? error.message : String(error),
        );
        return jsonResponse({ code: 'unavailable' }, 502);
      }
    }

    if (url.pathname !== '/rates') {
      return new Response('muchtoman: GET /rates or POST /wallet-balance\n', {
        status: 404,
        headers: { 'content-type': 'text/plain; charset=utf-8' },
      });
    }
    if (request.method !== 'GET') {
      return new Response('Method not allowed', { status: 405, headers: { allow: 'GET' } });
    }

    const cache = caches.default;
    const key = new Request(
      `${url.origin}/__cache/rates/${encodeURIComponent(RATES_CACHE_VERSION)}`,
      { method: 'GET' },
    );

    // `fresh` skips the read and refills this location. The versioned key makes a catalogue
    // schema change miss stale entries in every Cloudflare location after deployment.
    if (!url.searchParams.has('fresh')) {
      const hit = await cache.match(key);
      if (hit) return hit;
    }

    const res = await buildRates();
    if (res.status === 200) ctx.waitUntil(cache.put(key, res.clone()));
    return res;
  },
} satisfies ExportedHandler;
