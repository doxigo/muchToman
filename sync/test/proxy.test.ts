import { SELF } from 'cloudflare:test';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * The page may only talk to its own origin, so the rates Worker's wallet lookup and coin icons are
 * reached through this one. What must hold: the request arrives upstream as sent, the answer comes
 * back as given, and an oversized body never costs a subrequest.
 */

afterEach(() => { vi.restoreAllMocks(); });

describe('rates proxies', () => {
  it('forwards a wallet lookup and passes the status and JSON through, never cached', async () => {
    // Built inside the call: a body made in the test's context cannot be read in the Worker's.
    const upstream = vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response('{"code":"rate_limited"}', {
        status: 429,
        headers: { 'content-type': 'application/json; charset=utf-8', 'retry-after': '12', 'cache-control': 'max-age=60' },
      }));
    const body = JSON.stringify({ network: 'tron', address: 'T'.repeat(34), contract: '' });
    const res = await SELF.fetch('https://sync.test/wallet-balance', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body,
    });
    expect(res.status).toBe(429);
    expect(await res.json()).toEqual({ code: 'rate_limited' });
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.get('retry-after')).toBe('12');
    const [url, init] = upstream.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('https://rates.muchtoman.com/wallet-balance');
    expect(init.method).toBe('POST');
    expect(init.body).toBe(body);
    expect(new Headers(init.headers).get('content-type')).toBe('application/json');
  });

  it('passes the day\'s count on to /rates and no other request header', async () => {
    const upstream = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}'));
    await SELF.fetch('https://sync.test/rates', { headers: { 'x-muchtoman-daily': '1.2.5 pwa', cookie: 'a=b' } });
    const [url, init] = upstream.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('https://rates.muchtoman.com/rates');
    expect([...new Headers(init.headers)]).toEqual([['x-muchtoman-daily', '1.2.5 pwa']]);
  });

  it('refuses an oversized wallet body before asking upstream', async () => {
    const upstream = vi.spyOn(globalThis, 'fetch');
    const res = await SELF.fetch('https://sync.test/wallet-balance', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: 'x'.repeat(5000),
    });
    expect(res.status).toBe(413);
    expect(upstream).not.toHaveBeenCalled();
    expect((await SELF.fetch('https://sync.test/wallet-balance')).status).toBe(405);
  });

  it('answers 502 when the rates Worker cannot be reached', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('down'));
    const res = await SELF.fetch('https://sync.test/wallet-balance', { method: 'POST', body: '{}' });
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ code: 'unavailable' });
  });

  it('forwards a coin icon query and passes the bytes and cache headers through', async () => {
    const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);
    const upstream = vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response(png, { headers: { 'content-type': 'image/png', 'cache-control': 'public, max-age=86400' } }));
    const res = await SELF.fetch('https://sync.test/coin-icon?path=%2Fcoins%2Fimages%2F1%2Flarge%2Fbitcoin.png');
    expect(res.status).toBe(200);
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(png);
    expect(res.headers.get('content-type')).toBe('image/png');
    expect(res.headers.get('cache-control')).toBe('public, max-age=86400');
    expect(upstream.mock.calls[0][0]).toBe('https://rates.muchtoman.com/coin-icon?path=%2Fcoins%2Fimages%2F1%2Flarge%2Fbitcoin.png');
    expect((await SELF.fetch('https://sync.test/coin-icon', { method: 'POST' })).status).toBe(405);
  });
});
