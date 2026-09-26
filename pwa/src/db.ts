/**
 * The browser's one IndexedDB database.
 *
 * v3 is the browser's own ledger (`local_v3`, `prefs_v3`, read and written only through
 * state.ts), the way durable.db is the phone's. `meta` keeps what must never ride a backup — the
 * session, its token and key, the token rotation in flight — keyed per household where it
 * belongs to one.
 *
 * `records_v2`/`outbox_v2` are the companion's old mirror of the server, partitioned by origin,
 * household, scope and member. Nothing writes them any more; family.ts reads them once to carry
 * the active household's own rows and unsent writes into the v3 tables, and they are left in
 * place — never cleared — so no household's unsent data can be lost to the move.
 */
const DB_NAME = 'muchtoman';
const DB_VERSION = 3;
const RECORDS = 'records_v2';
const OUTBOX = 'outbox_v2';
export const LOCAL = 'local_v3';
export const PREFS = 'prefs_v3';

/** A row of the old mirror, as the pre-v3 companion stored it. */
export interface StoredRecord {
  id: string;
  scope: string;
  updatedAt: number;
  device: string;
  kind?: string;
  ownerMemberId?: string;
  authorMemberId?: string;
  deleted: boolean;
  value: unknown;
}
export interface OutboxRecord extends StoredRecord { nonce: string; body: string }
export interface SessionIdentity { base: string; token: string; scope: string; member: string; issuedAt?: number }
export function partition(session: SessionIdentity): string {
  return JSON.stringify([new URL(session.base).origin, session.token.split('.')[0], session.scope, session.member]);
}

let handle: Promise<IDBDatabase> | null = null;
export function open(): Promise<IDBDatabase> {
  if (handle) return handle;
  const attempt = new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      for (const name of [RECORDS, OUTBOX]) {
        if (db.objectStoreNames.contains(name)) continue;
        const store = db.createObjectStore(name, { keyPath: ['partition', 'id'] });
        store.createIndex('partition', 'partition');
        if (name === RECORDS) {
          store.createIndex('kind', ['partition', 'recordKind']);
          store.createIndex('at', ['partition', 'at', 'id']);
          store.createIndex('target', ['partition', 'target']);
        }
      }
      if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta');
      // v3: the browser's own ledger, the way durable.db is the phone's — every table in one
      // store keyed [table, id], and the prefs beside it. Additive only; nothing above moves.
      if (!db.objectStoreNames.contains(LOCAL)) db.createObjectStore(LOCAL, { keyPath: ['t', 'id'] }).createIndex('t', 't');
      if (!db.objectStoreNames.contains(PREFS)) db.createObjectStore(PREFS);
    };
    request.onsuccess = () => {
      const db = request.result;
      db.onversionchange = () => { db.close(); handle = null; };
      db.onclose = () => { handle = null; };
      resolve(db);
    };
    request.onerror = () => reject(request.error);
  });
  handle = attempt;
  void attempt.catch(() => { if (handle === attempt) handle = null; });
  return attempt;
}
function done<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
function committed(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onabort = tx.onerror = () => reject(tx.error ?? new Error('Local storage transaction failed'));
  });
}
function metaKey(key: string, space?: string): string { return space ? `${space}:${key}` : key; }
export async function getMeta<T>(key: string, space?: string): Promise<T | undefined> {
  const db = await open();
  return done(db.transaction('meta').objectStore('meta').get(metaKey(key, space))) as Promise<T | undefined>;
}
export async function setMeta(key: string, value: unknown, space?: string): Promise<void> {
  const db = await open(); const tx = db.transaction('meta', 'readwrite'); const finish = committed(tx);
  tx.objectStore('meta').put(value, metaKey(key, space)); await finish;
}
export async function deleteMeta(key: string, space?: string): Promise<void> {
  const db = await open(); const tx = db.transaction('meta', 'readwrite'); const finish = committed(tx);
  tx.objectStore('meta').delete(metaKey(key, space)); await finish;
}

// ---- the old mirror, read once by the v3 migration -----------------------------------------

function indexed(row: StoredRecord, space: string): StoredRecord & { partition: string; recordKind: string; at: number; target: string } {
  const value = row.value as { at?: unknown; target?: unknown } | null;
  return { ...row, partition: space, target: typeof value?.target === 'string' ? value.target : '', recordKind: row.kind ?? 'legacy',
    at: typeof value?.at === 'number' && Number.isFinite(value.at) ? value.at : -1 };
}

/**
 * The v1 → v2 move, for a browser that has not opened the companion since before households were
 * partitioned: its unpartitioned rows go to the household that was active then. Idempotent — a
 * flag in `meta` says it ran — and the one write the old stores still get.
 */
export async function partitionLegacyStores(active: SessionIdentity): Promise<void> {
  const db = await open();
  const names = ['meta', RECORDS, OUTBOX, ...(['record', 'outbox'].filter((n) => db.objectStoreNames.contains(n)))];
  const tx = db.transaction(names, 'readwrite'); const finish = committed(tx); const meta = tx.objectStore('meta');
  if (!(await done(meta.get('partition-migrated')))) {
    const space = partition(active);
    for (const [oldName, newName] of [['record', RECORDS], ['outbox', OUTBOX]]) {
      if (!db.objectStoreNames.contains(oldName)) continue;
      const rows = await done(tx.objectStore(oldName).getAll()) as StoredRecord[];
      for (const row of rows) tx.objectStore(newName).put(indexed(row, row.scope === active.scope ? space : `legacy-unassigned:${row.scope}`));
      tx.objectStore(oldName).clear();
    }
    const seq = await done(meta.get('seq'));
    if (seq !== undefined) meta.put(seq, metaKey('seq', space));
    meta.delete('seq'); meta.put(true, 'partition-migrated');
  }
  await finish;
}

/** Every row of one household's old mirror, tombstones included. */
export async function legacyRecords(space: string): Promise<StoredRecord[]> {
  const db = await open();
  return done(db.transaction(RECORDS).objectStore(RECORDS).index('partition').getAll(space)) as Promise<StoredRecord[]>;
}
/** One household's writes the old companion never got to send. */
export async function legacyOutbox(space: string): Promise<OutboxRecord[]> {
  const db = await open();
  return done(db.transaction(OUTBOX).objectStore(OUTBOX).index('partition').getAll(space)) as Promise<OutboxRecord[]>;
}

/**
 * One sync at a time across tabs, the browser's `withFamilySync`: Web Locks where there are
 * any, an IndexedDB lease for older browsers.
 */
export async function withSyncLock<T>(space: string, action: () => Promise<T>): Promise<T> {
  if (globalThis.navigator?.locks) return navigator.locks.request(`sync:${space}`, action);
  const owner = crypto.randomUUID(); const key = metaKey('sync-lease', space);
  const acquire = async (): Promise<boolean> => {
    const db = await open(); const tx = db.transaction('meta', 'readwrite'); const finish = committed(tx);
    const store = tx.objectStore('meta'); const lease = await done(store.get(key)) as { owner: string; until: number } | undefined;
    const ok = !lease || lease.until < Date.now() || lease.owner === owner;
    if (ok) store.put({ owner, until: Date.now() + 60_000 }, key);
    await finish; return ok;
  };
  while (!(await acquire())) await new Promise((r) => setTimeout(r, 100));
  const timer = setInterval(() => { void acquire(); }, 10_000);
  try { return await action(); } finally {
    clearInterval(timer); const db = await open(); const tx = db.transaction('meta', 'readwrite'); const finish = committed(tx);
    const store = tx.objectStore('meta'); const lease = await done(store.get(key)) as { owner: string } | undefined;
    if (lease?.owner === owner) store.delete(key); await finish;
  }
}
