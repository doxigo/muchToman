import { afterEach, describe, expect, it, vi } from 'vitest';
import worker, { usdDays } from '../src/index';

/** GET /usd-history: nobitex's two bitcoin candles, crossed into a dollar per Tehran day. */

// 2026-10-11 20:30 UTC: midnight in Tehran on 2026-10-12, epoch day 20738.
const T = 1_791_750_600;
const candles = (close: number[], times = close.map((_, i) => T + i * 86_400)) =>
  ({ s: 'ok', t: times, o: close, h: close, l: close, c: close, v: close.map(() => 1) });

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('usdDays', () => {
  it('reads each Tehran day as the Toman close over the USDT close, keyed by epoch day', () => {
    const days = usdDays(candles([26_297_000_000, 26_000_000_000]), candles([100_000, 100_000]));
    expect(days).toEqual({ 20738: 262_970, 20739: 260_000 });
  });

  it('leaves out a day one side lacks or the dollar band refuses, rather than guessing it', () => {
    const irt = candles([26_297_000_000, 26_000_000_000, 5]);
    const usdt = candles([100_000, 100_000], [T, T + 2 * 86_400]);
    expect(usdDays(irt, usdt)).toEqual({ 20738: 262_970 });
  });

  it('throws when nobitex has no candles, so nothing is cached as an empty history', () => {
    expect(() => usdDays({ s: 'no_data' }, candles([1]))).toThrow();
  });
});

describe('GET /usd-history', () => {
  it('answers from nobitex once, then from the cache', async () => {
    let calls = 0;
    vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
      calls++;
      const url = input instanceof Request ? input.url : String(input);
      const symbol = new URL(url).searchParams.get('symbol');
      return Response.json(symbol === 'BTCIRT' ? candles([26_297_000_000]) : candles([100_000]));
    });
    const cache = new Map<string, string>();
    vi.stubGlobal('caches', { default: {
      match: async (request: Request) => {
        const hit = cache.get(request.url);
        return hit == null ? undefined : new Response(hit, { headers: { 'content-type': 'application/json' } });
      },
      put: async (request: Request, response: Response) => { cache.set(request.url, await response.text()); },
    } });
    const pending: Promise<unknown>[] = [];
    const ctx = { waitUntil: (p: Promise<unknown>) => { pending.push(p); } } as ExecutionContext;
    const get = () => worker.fetch(new Request('https://rates.muchtoman.com/usd-history'), { ASSETS: {} as Fetcher }, ctx);

    const first = await get();
    expect(first.status).toBe(200);
    expect(((await first.json()) as { days: Record<string, number> }).days).toEqual({ 20738: 262_970 });
    await Promise.all(pending);
    const second = await get();
    expect(((await second.json()) as { days: Record<string, number> }).days).toEqual({ 20738: 262_970 });
    expect(calls).toBe(2);
  });
});
