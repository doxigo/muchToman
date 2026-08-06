/**
 * The device's own copy, in IndexedDB.
 *
 * Offline-first is not a nicety here. During a national disruption foreign traffic dies first
 * while domestic traffic keeps working, and an app that needs a server in California to show
 * her her own money is broken on exactly the days she most wants to look.
 *
 * Three stores and no wrapper library: `record` is what has been synced, `outbox` is what has
 * not, `meta` is the sync cursor and the keys.
 */

const DB_NAME = 'muchtoman';
const DB_VERSION = 1;

export interface StoredRecord {
  id: string;
  scope: string;
  updatedAt: number;
  device: string;
  kind?: string;
  ownerMemberId?: string;
  authorMemberId?: string;
  deleted: boolean;
  /** Decrypted on the way in. Nothing is ever written to disk still sealed. */
  value: unknown;
}

export interface OutboxRecord extends StoredRecord {
  nonce: string;
  body: string;
}

let handle: Promise<IDBDatabase> | null = null;

function open(): Promise<IDBDatabase> {
  if (handle) return handle;
  const attempt = new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('record')) {
        db.createObjectStore('record', { keyPath: 'id' }).createIndex('scope', 'scope');
      }
      if (!db.objectStoreNames.contains('outbox')) {
        db.createObjectStore('outbox', { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains('meta')) {
        db.createObjectStore('meta');
      }
    };
    request.onsuccess = () => {
      const db = request.result;
      const reset = (): void => {
        if (handle === attempt) handle = null;
      };
      db.onclose = reset;
      db.onversionchange = () => {
        db.close();
        reset();
      };
      resolve(db);
    };
    request.onerror = () => reject(request.error);
  });
  handle = attempt;
  void attempt.catch(() => {
    if (handle === attempt) handle = null;
  });
  return attempt;
}

function done<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function putRecords(rows: StoredRecord[]): Promise<void> {
  if (rows.length === 0) return;
  const db = await open();
  const tx = db.transaction('record', 'readwrite');
  const store = tx.objectStore('record');
  for (const row of rows) store.put(row);
  await new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

export async function allRecords(): Promise<StoredRecord[]> {
  const db = await open();
  const rows = await done(db.transaction('record').objectStore('record').getAll());
  return (rows as StoredRecord[]).filter((r) => !r.deleted);
}

export async function getRecord(id: string): Promise<StoredRecord | undefined> {
  const db = await open();
  const row = (await done(
    db.transaction('record').objectStore('record').get(id),
  )) as StoredRecord | undefined;
  return row?.deleted ? undefined : row;
}

/**
 * Queue a write. It goes to the outbox first and to the server whenever there is a server —
 * which is what makes an edit made on a train indistinguishable from one made at home.
 */
export async function enqueue(row: OutboxRecord): Promise<void> {
  const db = await open();
  const tx = db.transaction(['outbox', 'record'], 'readwrite');
  tx.objectStore('outbox').put(row);
  tx.objectStore('record').put({
    id: row.id, scope: row.scope, updatedAt: row.updatedAt,
    device: row.device, kind: row.kind, ownerMemberId: row.ownerMemberId,
    authorMemberId: row.authorMemberId, deleted: row.deleted, value: row.value,
  });
  await new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

export async function outbox(): Promise<OutboxRecord[]> {
  const db = await open();
  return (await done(db.transaction('outbox').objectStore('outbox').getAll())) as OutboxRecord[];
}

export async function clearOutbox(ids: string[]): Promise<void> {
  if (ids.length === 0) return;
  const db = await open();
  const tx = db.transaction('outbox', 'readwrite');
  for (const id of ids) tx.objectStore('outbox').delete(id);
  await new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

export async function getMeta<T>(key: string): Promise<T | undefined> {
  const db = await open();
  return (await done(db.transaction('meta').objectStore('meta').get(key))) as T | undefined;
}

export async function setMeta(key: string, value: unknown): Promise<void> {
  const db = await open();
  const tx = db.transaction('meta', 'readwrite');
  tx.objectStore('meta').put(value, key);
  await new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}
