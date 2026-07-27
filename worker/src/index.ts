/**
 * muchtoman-rates — one endpoint, GET /rates, returning Toman per unit of everything.
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

async function fetchTgju(): Promise<Record<string, number>> {
  const keys = Object.values(TGJU_MAP).join(',');
  const data = await getJson(`https://api.tgju.org/v1/widget/tmp?keys=${keys}`);

  const byName = new Map<string, unknown>();
  for (const ind of data?.response?.indicators ?? []) byName.set(ind?.name, ind?.p);

  const out: Record<string, number> = {};
  for (const [id, key] of Object.entries(TGJU_MAP)) {
    const rial = num(byName.get(key));
    if (rial != null) out[id] = rial / 10; // Rial -> Toman
  }
  return out;
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
type Coin = { id: string; name: string; en: string; icon: string };

/**
 * The coin catalogue and its USD prices. This is also where the picker's names and logos
 * come from — an emoji standing in for a coin is just a wrong logo, so we ship the real one.
 */
async function fetchCoinGecko(): Promise<{ usd: Record<string, number>; coins: Coin[] }> {
  const list = await getJson(
    `https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=${COIN_LIMIT}&page=1`,
  );
  if (!Array.isArray(list) || list.length === 0) throw new Error('empty coin list');

  const usd: Record<string, number> = {};
  const coins: Coin[] = [];

  for (const c of list) {
    const id = String(c?.symbol ?? '').toLowerCase();
    const price = num(c?.current_price);
    // A coin whose ticker collides with a currency/gold/coin id would shadow it in the
    // rates map. There are none today; skipping is still cheaper than debugging it later.
    if (!id || price == null || id in BONBAST_MAP || id === 'toman') continue;

    const en = String(c?.name ?? id.toUpperCase());
    usd[id] = price;
    coins.push({
      id,
      name: en, // until a Persian name is found for it below
      en,
      icon: String(c?.image ?? '').split('?')[0], // cache-buster query is dead weight x250
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

// ───────────────────────── assembly ─────────────────────────

async function buildRates(): Promise<Response> {
  const [fiat, cryptoToman, gecko] = await Promise.allSettled([
    fetchFiat(),
    firstOf(
      [
        { name: 'bitpin', run: fetchBitpin },
        { name: 'tetherland', run: fetchTetherland },
      ],
      hasPrices,
    ),
    fetchCoinGecko(),
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

  const ok = Object.keys(toman).length > 0;
  const payload = { updatedAt: Date.now(), toman, coins, sources };

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
    if (url.pathname !== '/rates') {
      return new Response('muchtoman rates: GET /rates\n', {
        status: 404,
        headers: { 'content-type': 'text/plain; charset=utf-8' },
      });
    }

    const cache = caches.default;
    const key = new Request(`${url.origin}/rates`, { method: 'GET' });

    // ?fresh=1 skips the read (still refills the cache) — for debugging and after a deploy,
    // since a new version does not invalidate what caches.default is already holding.
    if (!url.searchParams.has('fresh')) {
      const hit = await cache.match(key);
      if (hit) return hit;
    }

    const res = await buildRates();
    if (res.status === 200) ctx.waitUntil(cache.put(key, res.clone()));
    return res;
  },
} satisfies ExportedHandler;
