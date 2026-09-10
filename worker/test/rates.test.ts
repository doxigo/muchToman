import { afterEach, describe, expect, it, vi } from 'vitest';
import worker, { BONBAST_MAP, TGJU_MAP } from '../src/index';
import { PARSIAN_SOOT } from '../src/checks';

/**
 * Fixture tests for the /rates assembly and every upstream adapter behind it. Each fixture
 * pins today's real response shape (bonbast's token dance, tgju's Rial widget, the two tgju
 * HTML pages, bitpin, CoinGecko); the router throws on any URL it does not know, so the
 * suite is provably hermetic — no test can reach the network. Magnitudes are the 2026-08
 * market values checks.test.ts uses, chosen so every plausibility invariant passes.
 */

// App-id -> Toman, the one source of truth both fiat fixtures are derived from. bonbast
// quotes these directly; the tgju widget quotes them ×10 (Rial).
const TOMAN: Record<string, number> = {
  usd: 187_000,
  eur: 218_000,
  gbp: 252_000,
  nok: 18_500,
  try: 4_540,
  aed: 50_900,
  cad: 136_000,
  gold18: 17_800_000,
  gold_mesghal: 77_100_000,
  coin_emami: 210_000_000,
  coin_bahar: 205_000_000,
  coin_nim: 115_000_000,
  coin_rob: 65_000_000,
  coin_gerami: 33_000_000,
};

const withCommas = (n: number) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');

// tgju stamps rows in Tehran local time; a fresh stamp keeps the stale-note suffix out of
// the sources strings no matter when this suite runs.
const tehranStamp = () => {
  const d = new Date(Date.now() + 3.5 * 3_600_000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ` +
    `${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`;
};

const bonbastJson = () =>
  Object.fromEntries(Object.entries(BONBAST_MAP).map(([id, field]) => [field, withCommas(TOMAN[id])]));

const tgjuWidgetJson = () => ({
  response: {
    indicators: Object.entries(TGJU_MAP).map(([id, name]) => ({
      name,
      p: withCommas(TOMAN[id] * 10), // Rial
      updated_at: tehranStamp(),
    })),
  },
});

// Silver page: one row per attribute spelling, because the adapter must match both —
// silver rows really do flip between them fetch to fetch. Prices are Rial (10× Toman);
// the second nf cell is the day's change wrapped in a span, which the price regex must skip.
const silverPage = () =>
  '<html><table>' +
  '<tr data-market-row="silver_999"><td class="nf">2,700,000</td><td class="nf"><span>1</span></td></tr>' +
  '<tr nameslug="silver_925"><td class="nf">2,500,000</td><td class="nf"><span>1</span></td></tr>' +
  '</table></html>';

// A local copy of the slug rule (سکه-پارسیان-<grams>-<remainder> in Persian digits), so a
// change to the production rule shows up as a fixture mismatch rather than being copied in.
const FA = '۰۱۲۳۴۵۶۷۸۹';
const fa = (s: string) => s.replace(/\d/g, (d) => FA[Number(d)]);
const parsianSlug = (soot: number) =>
  `سکه-پارسیان-${fa(String(Math.floor(soot / 1000)))}-${fa(String(soot % 1000).padStart(3, '0'))}`;
const parsianTomanPrice = (soot: number) => soot * 17_800 * 1.1 + 400_000; // gold + fixed اجرت
const parsianPage = (sizes: readonly number[]) =>
  '<html><table>' +
  sizes
    .map((soot) =>
      `<tr nameslug="${parsianSlug(soot)}"><td class="nf">` +
      `${withCommas(Math.round(parsianTomanPrice(soot) * 10))}</td>` + // Rial
      '<td class="nf"><span>1</span></td></tr>')
    .join('') +
  '</table></html>';

const PARSIAN_URL = `https://www.tgju.org/${encodeURIComponent('قیمت-سکه-پارسیان')}`;

type Routes = Record<string, () => Response>;

// Keys are the fetched URL with its query string stripped; every URL the Worker can reach
// during /rates must be here, and an unmatched one throws so no test can touch the network.
const defaultRoutes = (): Routes => ({
  'https://bonbast.com/': () =>
    new Response('<html>$.post({ param: "tok123" })</html>', {
      headers: { 'set-cookie': 'session=abc; Path=/' },
    }),
  'https://bonbast.com/json': () => Response.json(bonbastJson()),
  'https://api.tgju.org/v1/widget/tmp': () => Response.json(tgjuWidgetJson()),
  'https://www.tgju.org/gold-chart': () => new Response(silverPage()),
  [PARSIAN_URL]: () => new Response(parsianPage(PARSIAN_SOOT)),
  'https://api.bitpin.ir/v1/mkt/markets/': () =>
    Response.json({
      results: [
        { code: 'BTC_IRT', price: '12,062,000,000', currency1: { title_fa: 'بیت کوین' } },
        { code: 'USDT_IRT', price: '187,500', currency1: { title_fa: 'تتر' } },
        { code: 'ETH_USDT', price: '1' }, // not an _IRT market; must be ignored
      ],
    }),
  'https://api.coingecko.com/api/v3/coins/markets': () =>
    Response.json([
      {
        id: 'bitcoin',
        symbol: 'btc',
        name: 'Bitcoin',
        current_price: 64_500,
        image: 'https://coin-images.coingecko.com/coins/images/1/large/bitcoin.png',
      },
      {
        id: 'ethereum',
        symbol: 'eth',
        name: 'Ethereum',
        current_price: 3_300,
        image: 'https://coin-images.coingecko.com/coins/images/279/large/ethereum.png',
      },
    ]),
  'https://api.coingecko.com/api/v3/coins/list': () => Response.json([]),
  'https://api.binance.com/api/v3/ticker/price': () =>
    Response.json([
      { symbol: 'BTCUSDT', price: '64500' },
      { symbol: 'ETHUSDT', price: '3300' },
    ]),
  'https://api.tetherland.com/currencies': () =>
    Response.json({ data: { currencies: { USDT: { price: 187_400 } } } }),
  'https://api.github.com/repos/doxigo/muchToman/releases/latest': () =>
    Response.json({ tag_name: 'v9.9.9', body: '<!--fa-->سلام<!--/fa-->', assets: [] }),
});

const unavailable = () => new Response('x', { status: 503 });

type RatesBody = {
  toman: Record<string, number | undefined>;
  coins: { id: string; name: string; icon: string }[];
  sources: Record<string, string>;
};

/**
 * Installs the fetch router (with per-test route overrides), a fresh cache map — a shared
 * one would serve test 1's cached /rates body to test 2 — and a waitUntil collector.
 */
function setup(overrides: Routes = {}) {
  const routes = { ...defaultRoutes(), ...overrides };
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const url = input instanceof Request ? input.url : String(input);
    const route = routes[url.split('?')[0]];
    if (!route) throw new Error('unexpected fetch: ' + url);
    return route();
  });

  // Buffered bodies rather than stored Response clones (download.test.ts keeps clones): the
  // 304 path cancels the cached body it will not send, and under node's spec-faithful
  // streams a tee-branch cancel never resolves while its sibling clone sits unread in the
  // map. A fresh Response per match has no sibling to wait on.
  const cache = new Map<string, { body: string; status: number; headers: Headers }>();
  const pending: Promise<unknown>[] = [];
  vi.stubGlobal('caches', { default: {
    match: async (request: Request) => {
      const hit = cache.get(request.url);
      return hit && new Response(hit.body, { status: hit.status, headers: hit.headers });
    },
    put: async (request: Request, response: Response) => {
      cache.set(request.url, {
        body: await response.text(),
        status: response.status,
        headers: new Headers(response.headers),
      });
    },
  } });

  const env = { ASSETS: {} as Fetcher };
  const ctx = { waitUntil: (promise: Promise<unknown>) => { pending.push(promise); } } as ExecutionContext;

  const request = (headers: HeadersInit = {}) =>
    worker.fetch(new Request('https://rates.muchtoman.com/rates', { headers }), env, ctx);
  const rates = async () => {
    const res = await request();
    return { res, body: (await res.json()) as RatesBody };
  };
  return { cache, pending, request, rates };
}

afterEach(() => vi.unstubAllGlobals());

describe('GET /rates', () => {
  it('assembles the happy path via bonbast, tgju pages, bitpin, and CoinGecko', async () => {
    const { rates } = setup();
    const { res, body } = await rates();

    expect(res.status).toBe(200);
    expect(res.headers.get('etag')).not.toBeNull();
    expect(res.headers.get('cache-control')).toContain('max-age');

    expect(body.toman.usd).toBe(187_000);
    expect(body.toman.gold18).toBe(17_800_000);
    expect(body.toman.silver_999).toBe(270_000); // Rial fixture ÷ 10
    expect(body.toman.parsian_100).toBeCloseTo(100 * 17_800 * 1.1 + 400_000, 0);
    expect(body.toman.parsian).toBeCloseTo((1000 * 17_800 * 1.1 + 400_000) / 1000, 0);

    // Tehran quote preferred over the USD cross; ETH has no IRT market, so it crosses.
    expect(body.toman.btc).toBe(12_062_000_000);
    expect(body.toman.eth).toBe(3_300 * 187_000);

    expect(body.sources.fiat_gold_coins).toBe('ok via bonbast');
    expect(body.sources.silver.startsWith('ok via tgju, 2/2')).toBe(true);
    expect(body.sources.crypto_toman.startsWith('ok via bitpin')).toBe(true);
    expect(body.sources.plausibility).not.toContain('btc');
    expect(body.sources.plausibility).not.toContain('usd');

    expect(body.coins.length).toBe(2);
    const bitcoin = body.coins.find((coin) => coin.id === 'btc');
    expect(bitcoin?.name).toBe('بیت کوین'); // bitpin's Persian name over the catalogue's
    expect(bitcoin?.icon.startsWith('https://rates.muchtoman.com/coin-icon?path=')).toBe(true);
  });

  it('falls back to tgju when bonbast is down, dividing its Rial quotes by 10', async () => {
    const { rates } = setup({
      'https://bonbast.com/': unavailable,
      'https://bonbast.com/json': unavailable,
    });
    const { body } = await rates();

    expect(body.toman.usd).toBe(187_000); // from the 1,870,000 Rial fixture
    expect(body.sources.fiat_gold_coins).toMatch(/^ok via tgju \(tried first: bonbast/);
  });

  it('records an implausible bonbast dollar as failed and advances to tgju', async () => {
    const { rates } = setup({
      'https://bonbast.com/json': () => Response.json({ ...bonbastJson(), usd1: '4,200' }),
    });
    const { body } = await rates();

    expect(body.toman.usd).toBe(187_000);
    expect(body.sources.fiat_gold_coins).toMatch(/^ok via tgju \(tried first: bonbast/);
    expect(body.sources.fiat_gold_coins).toContain('implausible');
  });

  it('drops USD-crossed coins but keeps Tehran quotes when the whole fiat chain is down', async () => {
    const { rates } = setup({
      'https://bonbast.com/': unavailable,
      'https://bonbast.com/json': unavailable,
      'https://api.tgju.org/v1/widget/tmp': unavailable,
    });
    const { res, body } = await rates();

    expect(res.status).toBe(200); // silver, parsian, and bitpin still answered
    expect(body.toman.usd).toBeUndefined();
    expect(body.toman.eth).toBeUndefined(); // no dollar to cross through
    expect(body.toman.btc).toBe(12_062_000_000); // bitpin needs no dollar
    expect(body.sources.fiat_gold_coins.startsWith('failed:')).toBe(true);
  });

  it('names a tgju page whose markup stopped matching without zeroing anything', async () => {
    const { rates } = setup({
      'https://www.tgju.org/gold-chart': () => new Response('<html><table></table></html>'),
    });
    const { body } = await rates();

    expect(body.toman.silver_999).toBeUndefined();
    expect(body.sources.silver.startsWith('failed:')).toBe(true);
    expect(body.sources.silver).toContain('no rows matched');
    expect(body.toman.usd).toBe(187_000); // independent chains
  });

  it('publishes what a partial Parsian page matched and counts it against the ask', async () => {
    const { rates } = setup({
      [PARSIAN_URL]: () => new Response(parsianPage([100, 1000])),
    });
    const { body } = await rates();

    expect(body.toman.parsian_100).toBeCloseTo(parsianTomanPrice(100), 0);
    expect(body.toman.parsian_1000).toBeCloseTo(parsianTomanPrice(1000), 0);
    expect(body.toman.parsian_1500).toBeUndefined();
    // Two matched rows plus the derived per-سوت rate, against fifteen sizes + 1 asked for.
    expect(body.sources.parsian_coins).toBe('ok via tgju, 3/16 rows');
  });

  it('answers 304 to a matching if-none-match and writes one versioned cache entry', async () => {
    const { cache, pending, request, rates } = setup();
    const { res } = await rates();
    const etag = res.headers.get('etag');
    expect(etag).not.toBeNull();

    const revalidated = await request({ 'if-none-match': etag as string });
    expect(revalidated.status).toBe(304);
    expect(await revalidated.text()).toBe('');
    expect(revalidated.headers.get('etag')).toBe(etag);

    await Promise.all(pending.splice(0));
    expect(cache.size).toBe(1);
    expect([...cache.keys()][0]).toContain('__cache/rates/');
  });
});
