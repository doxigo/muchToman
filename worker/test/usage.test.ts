import { afterEach, describe, expect, it, vi } from 'vitest';
import SYNC_WRANGLER from '../../sync/wrangler.jsonc?raw';
import worker, { renderUsage } from '../src/index';
import RATES_WRANGLER from '../wrangler.jsonc?raw';

/**
 * The count, the crash endpoint, the feedback form and the public page. What is pinned is the
 * privacy contract as much as the plumbing: the header is reduced to two short tokens before it
 * is stored, a crash report is capped, feedback goes to one inbox and nothing of it reaches a
 * header, and the page only ever shows totals, escaped.
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

  it('is not also kept in Cloudflare invocation logs, by either Worker', () => {
    // Invocation logs store each request's headers and geo — the daily header among them.
    for (const config of [RATES_WRANGLER, SYNC_WRANGLER]) {
      expect(config).toMatch(/"observability":\s*\{[^}]*"logs":\s*\{\s*"invocation_logs":\s*false\s*\}/);
    }
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

  it('refuses a POST a browser sent, which is never the app', async () => {
    const crashes = dataset();
    const res = await worker.fetch(new Request('https://rates.muchtoman.com/crash', {
      method: 'POST', body: 'trace', headers: { 'content-type': 'text/plain', origin: 'https://elsewhere.example' },
    }), { ASSETS: {} as Fetcher, CRASHES: crashes.binding } as never, ctx);
    expect(res.status).toBe(403);
    expect(crashes.points).toEqual([]);
  });

  it('throttles one address', async () => {
    const env = { ASSETS: {} as Fetcher, CRASHES: dataset().binding };
    const codes = [];
    for (let i = 0; i < 11; i++) codes.push((await post(env, 'trace', 'b')).status);
    expect(codes.slice(0, 10).every((code) => code === 204)).toBe(true);
    expect(codes[10]).toBe(429);
  });
});

describe('POST /feedback', () => {
  const post = (env: object, body: string, ip: string) => worker.fetch(new Request('https://rates.muchtoman.com/feedback', {
    method: 'POST', body, headers: { 'content-type': 'application/json', 'cf-connecting-ip': ip },
  }), env as never, ctx);
  function mailbox(fail = false) {
    const sent: unknown[] = [];
    return { sent, binding: { send: async (m: unknown) => { if (fail) throw new Error('E_SENDER_NOT_VERIFIED'); sent.push(m); return { messageId: 'm' }; } } };
  }
  const from = { email: 'feedback@muchtoman.com', name: 'چقدر تومن' };

  it('mails her words to the one inbox, with a Reply-To only when the contact is plainly an address', async () => {
    const mail = mailbox();
    const env = { ASSETS: {} as Fetcher, FEEDBACK: mail.binding };

    const ok = await post(env, JSON.stringify({ message: '  نرخ سکه\r\nدیر میاد \u0000', contact: 'maryam@example.com', version: '1.2.7-lite' }), 'f1');
    expect(ok.status).toBe(204);
    await post(env, JSON.stringify({ message: 'سلام', contact: 'x@y.z\r\nBcc: a@b.c', version: '<b>' }), 'f1');
    await post(env, JSON.stringify({ message: 'بدون راه تماس' }), 'f1');

    expect(mail.sent).toEqual([
      { from, to: 'hey@muchtoman.com', subject: 'بازخورد چقدر تومن · 1.2.7-lite', replyTo: 'maryam@example.com',
        text: 'نرخ سکه\nدیر میاد\n\n—\nراه تماس: maryam@example.com\nنسخه: 1.2.7-lite\n' },
      { from, to: 'hey@muchtoman.com', subject: 'بازخورد چقدر تومن · b',
        text: 'سلام\n\n—\nراه تماس: x@y.z  Bcc: a@b.c\nنسخه: b\n' },
      { from, to: 'hey@muchtoman.com', subject: 'بازخورد چقدر تومن · unknown',
        text: 'بدون راه تماس\n\n—\nراه تماس: نذاشته\nنسخه: unknown\n' },
    ]);
  });

  it('refuses an empty, malformed or oversized message before anything is sent', async () => {
    const mail = mailbox();
    const env = { ASSETS: {} as Fetcher, FEEDBACK: mail.binding };
    expect((await post(env, JSON.stringify({ message: ' \n ' }), 'f2')).status).toBe(400);
    expect((await post(env, 'not json', 'f2')).status).toBe(400);
    expect((await post(env, 'x'.repeat(9 * 1024), 'f2')).status).toBe(413);
    expect((await worker.fetch(new Request('https://rates.muchtoman.com/feedback'), env as never, ctx)).status).toBe(405);
    expect(mail.sent).toEqual([]);
  });

  it('refuses the simple POST any other site could make its visitors send', async () => {
    const mail = mailbox();
    const res = await worker.fetch(new Request('https://rates.muchtoman.com/feedback', {
      method: 'POST', body: JSON.stringify({ message: 'spam' }), headers: { 'content-type': 'text/plain' },
    }), { ASSETS: {} as Fetcher, FEEDBACK: mail.binding } as never, ctx);
    expect(res.status).toBe(415);
    expect(mail.sent).toEqual([]);
  });

  it('says so when the mail did not go, so the app keeps the draft', async () => {
    const body = JSON.stringify({ message: 'سلام' });
    expect((await post({ ASSETS: {} as Fetcher, FEEDBACK: mailbox(true).binding }, body, 'f3')).status).toBe(502);
    expect((await post({ ASSETS: {} as Fetcher }, body, 'f3')).status).toBe(503);
  });

  it('throttles one address', async () => {
    const env = { ASSETS: {} as Fetcher, FEEDBACK: mailbox().binding };
    const codes = [];
    for (let i = 0; i < 11; i++) codes.push((await post(env, JSON.stringify({ message: 'سلام' }), 'f4')).status);
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
    expect(html).toContain('۴۳۴ تا بیشتر از پریروز');
    expect(html).toContain('کافه‌بازار');
    expect(html).toContain('<bdi>گیت‌هاب و نصب مستقیم</bdi><span class="meter" style="--p:2.8%"></span></td><td class="n">۳۴</td><td class="n">۲٪</td>');
    expect(html).toContain('<td class="n">زیر ۱٪</td>');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('۹۹۹٬۹۹۹');
    // Two counted days: the running total is phone-days, and nothing before the first count is a zero.
    expect(html).toContain('<dd class="figure">۲٬۰۳۴</dd>');
    expect(html).not.toContain('میانگین هر روز');
    expect(html.match(/<li class="day/g)?.length).toBe(2);
    expect(html).toContain('<li class="day last shown" style="--h:61.7%"');
    expect(html).toContain('<span>دیروز</span>');
    expect(html.split('<summary>')[1].match(/<tr><td>/g)?.length).toBe(2);
  });

  it('says nothing is counted until a whole day is', () => {
    expect(renderUsage([{ day: '2026-09-26', version: '1.2.5', source: 'pwa', devices: 5 }], NOW))
      .toContain('هنوز هیچ روزی شمرده نشده');
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
