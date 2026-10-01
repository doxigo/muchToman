import { afterEach, describe, expect, it, vi } from 'vitest';
import LANDING from '../public/index.html?raw';
import worker, { releaseAsset } from '../src/index';

const origin = 'https://rates.muchtoman.com';
const asset = (name: string, tag = 'v2.0') => ({
  name,
  browser_download_url: `https://github.com/doxigo/muchToman/releases/download/${tag}/${name}`,
});

afterEach(() => vi.unstubAllGlobals());

describe('APK downloads', () => {
  it('links the landing page buttons through this origin, never github.com', () => {
    // github.com does not load from Iran; /download streams the same file from here.
    expect(LANDING).not.toMatch(/href="[^"]*\.apk"/);
    expect(LANDING).toContain('href="/download"');
    expect(LANDING).toContain('href="/download/lite"');
  });

  it('selects exact editions regardless of asset order and never substitutes full for lite', () => {
    const assets = [asset('unrelated.apk'), asset('muchtoman-lite-v2.0.apk'), asset('muchtoman-v2.0.apk')];
    expect(releaseAsset(assets, 'v2.0', false)).toBe(assets[2].browser_download_url);
    expect(releaseAsset(assets, 'v2.0', true)).toBe(assets[1].browser_download_url);
    expect(releaseAsset([assets[2]], 'v2.0', true)).toBeNull();
    expect(releaseAsset([{ ...assets[2], browser_download_url: 'https://other.example/file.apk' }], 'v2.0', false)).toBeNull();
  });

  it('resumes a dropped download: Range reaches the cache lookup, against this build only', async () => {
    const asked: (string | null)[] = [];
    vi.stubGlobal('caches', { default: {
      match: async (request: Request) => {
        asked.push(request.headers.get('range'));
        return new Response('apk', { headers: { 'content-length': '3' } });
      },
    } });
    vi.stubGlobal('fetch', async () => Response.json({ tag_name: 'v2.0', assets: [asset('muchtoman-v2.0.apk')] }));
    const env = { ASSETS: {} as Fetcher };
    const ctx = { waitUntil: () => {} } as unknown as ExecutionContext;
    const get = (headers: Record<string, string>) =>
      worker.fetch(new Request(`${origin}/download`, { headers }), env, ctx);

    const first = await get({ range: 'bytes=10-' });
    expect(first.headers.get('accept-ranges')).toBe('bytes');
    expect(first.headers.get('etag')).toBe('"muchtoman-2.0"');
    await get({ range: 'bytes=10-', 'if-range': '"muchtoman-2.0"' });
    await get({ range: 'bytes=10-', 'if-range': '"muchtoman-1.9"' }); // another build: whole file
    expect(asked).toEqual(['bytes=10-', 'bytes=10-', null]);
  });

  it('keeps versioned edition caches separate and revalidates public downloads across releases', async () => {
    const cache = new Map<string, Response>();
    const pending: Promise<unknown>[] = [];
    vi.stubGlobal('caches', { default: {
      match: async (request: Request) => cache.get(request.url)?.clone(),
      put: async (request: Request, response: Response) => { cache.set(request.url, response); },
    } });
    let tag = 'v2.0';
    const upstream = vi.fn(async (url: string) => {
      if (url.startsWith('https://api.github.com/')) return Response.json({
        tag_name: tag,
        assets: [asset(`muchtoman-${tag}.apk`, tag), asset(`muchtoman-lite-${tag}.apk`, tag)],
      });
      const body = `${tag}:${url.includes('muchtoman-lite-') ? 'lite' : 'full'}`;
      return new Response(body, { headers: { 'content-length': String(body.length) } });
    });
    vi.stubGlobal('fetch', upstream);
    const env = { ASSETS: {} as Fetcher };
    const ctx = { waitUntil: (promise: Promise<unknown>) => { pending.push(promise); } } as ExecutionContext;
    const download = async (path: string) => {
      const response = await worker.fetch(new Request(`${origin}${path}`), env, ctx);
      expect(response.status).toBe(200);
      expect(response.headers.get('cache-control')).toBe('no-store');
      const body = await response.text();
      await Promise.all(pending.splice(0));
      return body;
    };
    expect(await download('/download')).toBe('v2.0:full');
    expect(await download('/download/lite')).toBe('v2.0:lite');
    expect(await download('/download/lite')).toBe('v2.0:lite');
    tag = 'v2.1';
    expect(await download('/download')).toBe('v2.1:full');
    expect(cache.size).toBe(3);
    expect([...cache.values()].every((response) => response.headers.get('cache-control')?.includes('immutable'))).toBe(true);
    expect(upstream.mock.calls.filter(([url]) => url.startsWith('https://github.com/')).length).toBe(3);
  });
});
