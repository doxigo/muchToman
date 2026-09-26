import { afterEach, describe, expect, it, vi } from 'vitest';
import worker, { renderUsage } from '../src/index';

/**
 * The count, the crash endpoint and the public page. What is pinned is the privacy contract as
 * much as the plumbing: the header is reduced to two short tokens before it is stored, a crash
 * report is capped, and the page only ever shows totals, escaped.
 */

// public/usage.html's slot, as the Worker sees it: everything between the markers is replaced.
const template = '<main><!--usage--><p class="empty">آمار الان در دسترس نیست</p><!--/usage--></main>';
const ctx = { waitUntil: () => {} } as unknown as ExecutionContext;

function dataset() {
  const points: AnalyticsEngineDataPoint[] = [];
  return { points, binding: { writeDataPoint: (point: AnalyticsEngineDataPoint) => points.push(point) } };
}

afterEach(() => vi.unstubAllGlobals());

describe('the daily count', () => {
  it('rides on /rates, cached or not, as two sanitized tokens', async () => {
    vi.stubGlobal('caches', { default: { match: async () => Response.json({ updatedAt: 1 }) } });
    const usage = dataset();
    const env = { ASSETS: {} as Fetcher, USAGE: usage.binding as unknown as AnalyticsEngineDataset };

    const rates = (headers: HeadersInit = {}) =>
      worker.fetch(new Request('https://rates.muchtoman.com/rates', { headers }), env, ctx);
    expect((await rates({ 'x-muchtoman-daily': '1.2.5 com.farsitel.bazaar' })).status).toBe(200);
    await rates({ 'x-muchtoman-daily': '<b>1.2.6</b>   ' + 'x'.repeat(200) });
    await rates();

    expect(usage.points).toEqual([
      { blobs: ['1.2.5', 'com.farsitel.bazaar'] },
      { blobs: ['b1.2.6b', 'x'.repeat(64)] },
    ]);
  });
});

describe('POST /crash', () => {
  const post = (env: object, body: string, ip: string) => worker.fetch(new Request('https://rates.muchtoman.com/crash', {
    method: 'POST', body, headers: { 'content-type': 'text/plain', 'cf-connecting-ip': ip },
  }), env as never, ctx);

  it('stores the report she agreed to send and refuses anything oversized', async () => {
    const crashes = dataset();
    const env = { ASSETS: {} as Fetcher, CRASHES: crashes.binding };

    expect((await post(env, '  1.2.5 · Android 34\njava.lang.IllegalStateException\n', 'a')).status).toBe(204);
    expect((await post(env, 'x'.repeat(9 * 1024), 'a')).status).toBe(413);
    expect((await post(env, '   ', 'a')).status).toBe(400);
    expect(crashes.points).toEqual([{ blobs: ['1.2.5 · Android 34\njava.lang.IllegalStateException'] }]);
  });

  it('throttles one address', async () => {
    const env = { ASSETS: {} as Fetcher, CRASHES: dataset().binding };
    const codes = [];
    for (let i = 0; i < 11; i++) codes.push((await post(env, 'trace', 'b')).status);
    expect(codes.slice(0, 10).every((code) => code === 204)).toBe(true);
    expect(codes[10]).toBe(429);
  });
});

describe('/usage', () => {
  const NOW = Date.parse('2026-09-26T12:00:00Z');

  it('draws up to yesterday, folds stores by name and escapes whatever a caller sent', () => {
    const html = renderUsage([
      { day: '2026-09-25', version: '1.2.5', source: 'com.farsitel.bazaar', devices: 1200 },
      { day: '2026-09-25', version: '1.2.5', source: 'com.google.android.packageinstaller', devices: 30 },
      { day: '2026-09-25', version: '<script>', source: 'none', devices: 4 },
      { day: '2026-09-24', version: '1.2.4', source: 'pwa', devices: 800 },
      { day: '2026-09-26', version: '1.2.5', source: 'pwa', devices: 999_999 }, // today: still counting
    ], NOW);

    expect(html).toContain('<p class="figure">۱٬۲۳۴</p>');
    expect(html).toContain('کافه‌بازار');
    expect(html).toContain('<bdi>گیت‌هاب و نصب مستقیم</bdi></td><td class="n">۳۴</td>');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('۹۹۹٬۹۹۹');
    expect(html.match(/<g class="day">/g)?.length).toBe(90);
  });

  it('reads the SQL API once an hour and keeps the fallback line without credentials', async () => {
    vi.useFakeTimers({ now: NOW, toFake: ['Date'] });
    const cache = new Map<string, Response>();
    vi.stubGlobal('caches', { default: {
      match: async (request: Request) => cache.get(request.url)?.clone(),
      put: async (request: Request, response: Response) => { cache.set(request.url, response); },
    } });
    const sql = vi.fn(async (url: string, init: RequestInit) => {
      expect(url).toBe('https://api.cloudflare.com/client/v4/accounts/acct/analytics_engine/sql');
      expect(new Headers(init.headers).get('authorization')).toBe('Bearer token');
      return Response.json({ meta: [], rows: 1, data: [{ day: '2026-09-25', version: '1.2.5', source: 'pwa', devices: '7' }] });
    });
    vi.stubGlobal('fetch', sql);
    const pending: Promise<unknown>[] = [];
    const ctxWithWait = { waitUntil: (p: Promise<unknown>) => { pending.push(p); } } as unknown as ExecutionContext;
    const assets = { fetch: async () => new Response(template, { headers: { 'content-type': 'text/html' } }) };
    const page = async (env: object) => {
      const res = await worker.fetch(new Request('https://muchtoman.com/usage'), env as never, ctxWithWait);
      await Promise.all(pending.splice(0));
      return res.text();
    };

    expect(await page({ ASSETS: assets })).toContain('آمار الان در دسترس نیست');
    const env = { ASSETS: assets, ANALYTICS_ACCOUNT_ID: 'acct', ANALYTICS_TOKEN: 'token' };
    const first = await page(env);
    expect(first).toContain('<p class="figure">۷</p>');
    expect(first).not.toContain('آمار الان در دسترس نیست');
    await page(env);
    expect(sql).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });
});
