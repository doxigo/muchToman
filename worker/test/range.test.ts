import { describe, expect, it } from 'vitest';
import worker from '../src/index';

/**
 * Byte ranges of the page's files. The assets binding ignores Range, and Safari will not play the
 * hero video from a server that answers its opening `bytes=0-1` with the whole file.
 */
const file = new Uint8Array(Array.from({ length: 100 }, (_, i) => i));
const env = {
  ASSETS: { fetch: async () => new Response(file, { headers: { 'content-type': 'video/mp4', etag: '"v1"' } }) },
};
const ctx = { waitUntil: () => {} } as unknown as ExecutionContext;
const get = (headers: Record<string, string>) =>
  worker.fetch(new Request('https://muchtoman.com/hero.mp4', { headers }), env as never, ctx);

describe('byte ranges', () => {
  it('slices the forms players send, and says the size', async () => {
    const probe = await get({ range: 'bytes=0-1' });
    expect(probe.status).toBe(206);
    expect(probe.headers.get('content-range')).toBe('bytes 0-1/100');
    expect(probe.headers.get('content-length')).toBe('2');
    expect([...new Uint8Array(await probe.arrayBuffer())]).toEqual([0, 1]);

    const open = await get({ range: 'bytes=90-' });
    expect(open.headers.get('content-range')).toBe('bytes 90-99/100');
    const past = await get({ range: 'bytes=95-500' });
    expect(past.headers.get('content-range')).toBe('bytes 95-99/100');
    const suffix = await get({ range: 'bytes=-3' });
    expect([...new Uint8Array(await suffix.arrayBuffer())]).toEqual([97, 98, 99]);
  });

  it('refuses a range past the end, and sends the whole file otherwise', async () => {
    const beyond = await get({ range: 'bytes=100-' });
    expect(beyond.status).toBe(416);
    expect(beyond.headers.get('content-range')).toBe('bytes */100');

    expect((await get({})).status).toBe(200);
    expect((await get({ range: 'bytes=0-1,5-6' })).status).toBe(200);
    expect((await get({ range: 'bytes=0-1', 'if-range': '"v0"' })).status).toBe(200);
    expect((await get({ range: 'bytes=0-1', 'if-range': '"v1"' })).status).toBe(206);
  });
});
