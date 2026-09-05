import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import type { OutboxRecord } from '../src/db';

let db: typeof import('../src/db');
const session = (household: string) => ({ base: 'https://example.test', token: `${household}.secret`, scope: `family:${household}`, member: 'member', device: 'device' });
const row = (id: string, at = 100, scope = 'family:a'): OutboxRecord => ({
  id, scope, updatedAt: at, device: 'device', kind: 'transaction', ownerMemberId: 'member',
  deleted: false, value: { at, amountRial: 1000 }, nonce: `nonce-${at}`, body: `body-${at}`,
});
beforeEach(async () => { vi.resetModules(); globalThis.indexedDB = new IDBFactory(); db = await import('../src/db'); });

describe('IndexedDB household and revision boundaries', () => {
  it('keeps colliding IDs, cursors and pending writes separate across household switches', async () => {
    const a = session('a'); const b = session('b'); const pa = db.partition(a); const pb = db.partition(b);
    await db.activateSession(a); await db.enqueue(row('same'), pa); await db.setMeta('seq', 987, pa);
    await db.activateSession(b); await db.enqueue(row('same', 200, b.scope), pb);
    expect((await db.getRecord('same', pb))?.updatedAt).toBe(200);
    expect(await db.getMeta('seq', pb)).toBeUndefined();
    await db.activateSession(a);
    expect((await db.outbox(pa))[0].body).toBe('body-100');
    expect(await db.getMeta('seq', pa)).toBe(987);
    expect(await db.savedSessions()).toHaveLength(2);
  });

  it('migrates v1 records and unsent outbox to the old household before a new pairing', async () => {
    const a = session('a'); const b = session('b');
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open('muchtoman', 1);
      request.onupgradeneeded = () => {
        const old = request.result;
        old.createObjectStore('record', { keyPath: 'id' }); old.createObjectStore('outbox', { keyPath: 'id' }); old.createObjectStore('meta');
      };
      request.onsuccess = () => {
        const old = request.result; const tx = old.transaction(['record', 'outbox', 'meta'], 'readwrite');
        tx.objectStore('record').put(row('legacy')); tx.objectStore('outbox').put(row('legacy'));
        tx.objectStore('meta').put(a, 'session'); tx.objectStore('meta').put(55, 'seq');
        tx.oncomplete = () => { old.close(); resolve(); }; tx.onerror = () => reject(tx.error);
      };
    });
    await db.activateSession(b);
    expect(await db.allRecords(db.partition(b))).toEqual([]);
    expect(await db.outbox(db.partition(a))).toHaveLength(1);
    expect(await db.getMeta('seq', db.partition(a))).toBe(55);
  });

  it('does not acknowledge or clamp an edit made while its older version was being sent', async () => {
    const p = db.partition(session('a')); const sent = row('one');
    await db.enqueue(sent, p); await db.enqueue(row('one', 200), p);
    await db.acknowledge([sent], p, [{ id: sent.id, updatedAt: 50 }]);
    expect((await db.outbox(p))[0].updatedAt).toBe(200);
    expect((await db.getRecord('one', p))?.updatedAt).toBe(200);
    await db.acknowledge(await db.outbox(p), p);
    expect(await db.pendingCount(p)).toBe(0);
  });

  it('does not let a pulled echo overwrite a pending edit and commits its cursor with the page', async () => {
    const p = db.partition(session('a'));
    await db.enqueue(row('one', 200), p); await db.putRecords([row('one', 100)], p, 44);
    expect((await db.getRecord('one', p))?.updatedAt).toBe(200);
    expect(await db.getMeta('seq', p)).toBe(44);
  });

  it('paginates a date index without leaking other scopes or skipping tied timestamps', async () => {
    const p = db.partition(session('a')); const other = db.partition(session('b'));
    await db.putRecords(Array.from({ length: 123 }, (_, i) => row(`r-${String(i).padStart(3, '0')}`, 100)), p);
    await db.enqueue(row('foreign', 200), other);
    const first = await db.recordPage(p, 50); const second = await db.recordPage(p, 50, first.next); const third = await db.recordPage(p, 50, second.next);
    expect([first.rows.length, second.rows.length, third.rows.length]).toEqual([50, 50, 23]);
    expect(new Set([...first.rows, ...second.rows, ...third.rows].map((r) => r.id)).size).toBe(123);
    expect(third.next).toBeUndefined();
  });
});

it('does not restore an obsolete credential when switching with an old session snapshot', async () => {
  const a = { ...session('a'), issuedAt: 10 }; await db.activateSession(a);
  await db.persistSession({ ...a, token: 'a.new-secret', issuedAt: 20 });
  await db.activateSession(session('b')); await db.activateSession(a);
  expect((await db.getMeta<{ token: string }>('session'))?.token).toBe('a.new-secret');
});
