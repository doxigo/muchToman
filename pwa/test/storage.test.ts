import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import type { StoredRecord } from '../src/db';

let db: typeof import('../src/db');
const session = (household: string) => ({ base: 'https://example.test', token: `${household}.secret`, scope: `family:${household}`, member: 'member' });
const row = (id: string, at = 100, scope = 'family:a'): StoredRecord & { nonce: string; body: string } => ({
  id, scope, updatedAt: at, device: 'device', kind: 'transaction', ownerMemberId: 'member',
  deleted: false, value: { at, amountRial: 1000 }, nonce: `nonce-${at}`, body: `body-${at}`,
});
beforeEach(async () => { vi.resetModules(); globalThis.indexedDB = new IDBFactory(); db = await import('../src/db'); });

/** The database as a version of the companion left it, written with that version's own schema. */
function seed(version: number, build: (db: IDBDatabase) => void, fill: (tx: IDBTransaction) => void, stores: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('muchtoman', version);
    request.onupgradeneeded = () => build(request.result);
    request.onsuccess = () => {
      const old = request.result; const tx = old.transaction(stores, 'readwrite');
      fill(tx);
      tx.oncomplete = () => { old.close(); resolve(); }; tx.onerror = () => reject(tx.error);
    };
  });
}

describe('the v3 upgrade', () => {
  it('adds the browser\'s own tables and leaves every v2 row, unsent write and cursor where it was', async () => {
    const space = db.partition(session('a'));
    await seed(2, (old) => {
      for (const name of ['records_v2', 'outbox_v2']) old.createObjectStore(name, { keyPath: ['partition', 'id'] }).createIndex('partition', 'partition');
      old.createObjectStore('meta');
    }, (tx) => {
      tx.objectStore('records_v2').put({ ...row('kept'), partition: space });
      tx.objectStore('outbox_v2').put({ ...row('kept'), partition: space });
      tx.objectStore('meta').put(44, `${space}:seq`);
    }, ['records_v2', 'outbox_v2', 'meta']);

    const opened = await db.open();
    expect([...opened.objectStoreNames].sort()).toEqual(['local_v3', 'meta', 'outbox_v2', 'prefs_v3', 'records_v2']);
    expect((await db.legacyRecords(space)).map((r) => r.id)).toEqual(['kept']);
    expect((await db.legacyOutbox(space))[0].body).toBe('body-100');
    expect(await db.getMeta('seq', space)).toBe(44);
  });

  it('moves a v1 browser\'s rows and cursor to the household that was active', async () => {
    await seed(1, (old) => {
      old.createObjectStore('record', { keyPath: 'id' }); old.createObjectStore('outbox', { keyPath: 'id' }); old.createObjectStore('meta');
    }, (tx) => {
      tx.objectStore('record').put(row('legacy')); tx.objectStore('outbox').put(row('legacy'));
      tx.objectStore('record').put(row('elsewhere', 100, 'family:b'));
      tx.objectStore('meta').put(55, 'seq');
    }, ['record', 'outbox', 'meta']);
    const a = session('a');
    await db.partitionLegacyStores(a);
    await db.partitionLegacyStores(a);
    expect((await db.legacyRecords(db.partition(a))).map((r) => r.id)).toEqual(['legacy']);
    expect(await db.legacyOutbox(db.partition(a))).toHaveLength(1);
    expect(await db.legacyRecords('legacy-unassigned:family:b')).toHaveLength(1);
    expect(await db.getMeta('seq', db.partition(a))).toBe(55);
  });
});

it('lets one sync hold the lock at a time', async () => {
  const order: string[] = [];
  const slow = db.withSyncLock('family', async () => { order.push('a:start'); await new Promise((r) => setTimeout(r, 20)); order.push('a:end'); });
  const fast = db.withSyncLock('family', async () => { order.push('b'); });
  await Promise.all([slow, fast]);
  expect(order).toEqual(['a:start', 'a:end', 'b']);
});
