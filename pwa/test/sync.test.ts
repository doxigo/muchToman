import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { beforeEach, expect, it, vi } from 'vitest';
import type { Session } from '../src/sync';
let db: typeof import('../src/db'); let sync: typeof import('../src/sync'); let crypt: typeof import('../src/crypto'); let session: Session;
beforeEach(async () => {
  vi.resetModules(); vi.unstubAllGlobals(); globalThis.indexedDB = new IDBFactory();
  db = await import('../src/db'); sync = await import('../src/sync'); crypt = await import('../src/crypto');
  session = { base: 'https://example.test', token: 'house.old-secret', scope: 'family:house', member: 'member', device: 'device', issuedAt: Date.now(), name: 'name', key: (await crypt.generateKey()).key };
  await db.activateSession(session);
});
const entry = { at: 100, amountRial: 12340, direction: 'out' as const, bank: 'MANUAL', categoryId: '', categoryName: '', categoryKind: 'expense' as const, categoryUpdatedAt: 0, merchant: 'test' };

it('drains empty but advancing pages and persists complete data', async () => {
  const encrypted = await crypt.seal(session.key, { ...entry, kind: 'transaction' });
  const fetch = vi.fn().mockResolvedValueOnce(Response.json({ seq: 500, records: [], hasMore: true }))
    .mockResolvedValueOnce(Response.json({ seq: 501, records: [{ ...encrypted, id: 'one', scope: session.scope, updatedAt: 100, device: 'device', kind: 'transaction', deleted: false }], hasMore: false }));
  vi.stubGlobal('fetch', fetch);
  expect(await sync.pull(session)).toBe(1); expect(fetch.mock.calls[1][0]).toContain('since=500');
  expect(await db.getMeta('seq', db.partition(session))).toBe(501);
});

it('retains cursor when an encrypted record cannot be authenticated', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ seq: 2, records: [{ id: 'broken', scope: session.scope, nonce: '', body: '' }], hasMore: false })));
  await expect(sync.pull(session)).rejects.toThrow();
  expect(await db.getMeta('seq', db.partition(session))).toBeUndefined();
});

it('sends multiple count and byte bounded batches and preserves concurrent edits', async () => {
  const p = db.partition(session);
  const rows = Array.from({ length: 501 }, (_, i) => ({ id: `r${i}`, scope: session.scope, updatedAt: 10, device: session.device, ownerMemberId: session.member, kind: 'transaction', deleted: false, value: entry, nonce: 'n', body: 'x'.repeat(3000) }));
  for (const row of rows) await db.enqueue(row, p);
  let calls = 0;
  vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
    calls++; const body = String(init.body); const parsed = JSON.parse(body);
    expect(new TextEncoder().encode(body).length).toBeLessThanOrEqual(sync.MAX_PUSH_BYTES);
    expect(parsed.records.length).toBeLessThanOrEqual(500);
    if (calls === 1) await db.enqueue({ ...rows[0], updatedAt: 20, nonce: 'new' }, p);
    return Response.json({ clamped: [] });
  }));
  expect(await sync.push(session)).toBe(501); expect(calls).toBeGreaterThan(1);
  expect(await db.pendingCount(p)).toBe(1); expect((await db.outbox(p))[0].updatedAt).toBe(20);
});

it('refuses to edit or remove another member transaction before queueing', async () => {
  const p = db.partition(session);
  await db.putRecords([{ id: 'foreign', scope: session.scope, updatedAt: 10, device: 'x', ownerMemberId: 'other', kind: 'transaction', deleted: false, value: entry }], p);
  await expect(sync.remove(session, 'foreign')).rejects.toThrow();
  await expect(sync.save(session, entry, undefined, 'foreign')).rejects.toThrow();
  expect(await db.pendingCount(p)).toBe(0);
});

it('recovers a rotation applied by the server before its response was lost', async () => {
  const p = db.partition(session); const secret = 'b'.repeat(64);
  await db.setMeta('pending-rotation', { oldToken: session.token, newToken: `house.${secret}`, startedAt: Date.now() }, p);
  let rotationRequests = 0;
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/rotate')) {
      rotationRequests++;
      return rotationRequests === 1 ? new Response('', { status: 401 }) : Response.json({ secret });
    }
    if (url.endsWith('/identity')) return Response.json({});
    return Response.json({ seq: 0, records: [], hasMore: false, rotationClientSecret: true });
  }));
  await sync.syncNow(session);
  expect(rotationRequests).toBe(2); expect(session.token).toBe(`house.${secret}`);
  expect(await db.getMeta('pending-rotation', p)).toBeUndefined();
  expect((await db.getMeta<Session>('session'))?.token).toBe(session.token);
});
