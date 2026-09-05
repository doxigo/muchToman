/** Local records and pending writes are isolated by origin, household, scope and member. */
const DB_NAME = 'muchtoman';
const DB_VERSION = 2;
const RECORDS = 'records_v2';
const OUTBOX = 'outbox_v2';

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
function open(): Promise<IDBDatabase> {
  if (handle) return handle;
  const attempt = new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      for (const name of [RECORDS, OUTBOX]) {
        const store = db.createObjectStore(name, { keyPath: ['partition', 'id'] });
        store.createIndex('partition', 'partition');
        if (name === RECORDS) {
          store.createIndex('kind', ['partition', 'recordKind']);
          store.createIndex('at', ['partition', 'at', 'id']);
          store.createIndex('target', ['partition', 'target']);
        }
      }
      if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta');
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
function indexed(row: StoredRecord, space: string): StoredRecord & { partition: string; recordKind: string; at: number; target: string } {
  const value = row.value as { at?: unknown; target?: unknown } | null;
  return { ...row, partition: space, target: typeof value?.target === 'string' ? value.target : '', recordKind: row.kind ?? 'legacy',
    at: typeof value?.at === 'number' && Number.isFinite(value.at) ? value.at : -1 };
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

/** Archive the prior identity rather than throwing away its unsent records and keys. */
export async function activateSession<T extends SessionIdentity>(session: T): Promise<void> {
  const db = await open();
  const names = ['meta', RECORDS, OUTBOX, ...(['record', 'outbox'].filter((n) => db.objectStoreNames.contains(n)))];
  const tx = db.transaction(names, 'readwrite'); const finish = committed(tx); const meta = tx.objectStore('meta');
  const previous = await done(meta.get('session')) as T | undefined;
  if (previous?.member) meta.put(previous, `saved-session:${partition(previous)}`);
  const legacy = previous?.member ? partition(previous) : partition(session);
  if (!(await done(meta.get('partition-migrated')))) {
    for (const [oldName, newName] of [['record', RECORDS], ['outbox', OUTBOX]]) {
      if (!db.objectStoreNames.contains(oldName)) continue;
      const rows = await done(tx.objectStore(oldName).getAll()) as StoredRecord[];
      for (const row of rows) tx.objectStore(newName).put(indexed(row, row.scope === (previous?.scope ?? session.scope) ? legacy : `legacy-unassigned:${row.scope}`));
      tx.objectStore(oldName).clear();
    }
    const seq = await done(meta.get('seq'));
    if (seq !== undefined) meta.put(seq, metaKey('seq', legacy));
    meta.delete('seq'); meta.put(true, 'partition-migrated');
  }
  const saved = await done(meta.get(`saved-session:${partition(session)}`)) as T | undefined;
  if (saved && (saved.issuedAt ?? 0) > (session.issuedAt ?? 0)) Object.assign(session, saved);
  meta.put(session, 'session'); meta.put(session, `saved-session:${partition(session)}`); await finish;
}
export async function savedSessions<T extends SessionIdentity>(): Promise<T[]> {
  const db = await open(); const store = db.transaction('meta').objectStore('meta');
  return done(store.getAll(IDBKeyRange.bound('saved-session:', 'saved-session:\uffff'))) as Promise<T[]>;
}
export async function persistSession<T extends SessionIdentity>(session: T, clearPending = false): Promise<void> {
  const db = await open(); const tx = db.transaction('meta', 'readwrite'); const finish = committed(tx);
  const store = tx.objectStore('meta'); const active = await done(store.get('session')) as T | undefined;
  const space = partition(session);
  store.put(session, `saved-session:${space}`);
  if (active && partition(active) === space) store.put(session, 'session');
  if (clearPending) store.delete(metaKey('pending-rotation', space));
  await finish;
}

export async function getRecord(id: string, space: string): Promise<StoredRecord | undefined> {
  const db = await open();
  return done(db.transaction(RECORDS).objectStore(RECORDS).get([space, id]));
}
export async function allRecords(space: string): Promise<StoredRecord[]> {
  const db = await open();
  const rows = await done(db.transaction(RECORDS).objectStore(RECORDS).index('partition').getAll(space)) as StoredRecord[];
  return rows.filter((r) => !r.deleted);
}
export async function recordsOfKind(space: string, kind: string): Promise<StoredRecord[]> {
  const db = await open();
  const rows = await done(db.transaction(RECORDS).objectStore(RECORDS).index('kind').getAll([space, kind])) as StoredRecord[];
  return rows.filter((r) => !r.deleted);
}
export interface PageCursor { at: number; id: string }
export async function recordPage(space: string, limit = 50, before?: PageCursor, from = 0, to = Number.MAX_SAFE_INTEGER): Promise<{ rows: StoredRecord[]; next?: PageCursor }> {
  const db = await open(); const index = db.transaction(RECORDS).objectStore(RECORDS).index('at');
  const upper = before ? [space, before.at, before.id] : [space, to, '\uffff'];
  const request = index.openCursor(IDBKeyRange.bound([space, from, ''], upper, false, !!before), 'prev');
  return new Promise((resolve, reject) => {
    const rows: StoredRecord[] = [];
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return resolve({ rows });
      const row = cursor.value as StoredRecord & { at: number };
      if (!row.deleted && (row.kind === 'transaction' || !row.kind || row.kind === 'legacy')) {
        if (rows.length === limit) {
          const last = rows[rows.length - 1];
          return resolve({ rows, next: { at: (last.value as { at: number }).at, id: last.id } });
        }
        rows.push(row);
      }
      cursor.continue();
    };
  });
}
export async function recordsBetween(space: string, from: number, to: number): Promise<StoredRecord[]> {
  const rows: StoredRecord[] = []; let before: PageCursor | undefined;
  do { const page = await recordPage(space, 500, before, from, to); rows.push(...page.rows); before = page.next; } while (before);
  return rows;
}
function sameVersion(a: OutboxRecord, b: OutboxRecord): boolean {
  return a.updatedAt === b.updatedAt && a.device === b.device && a.nonce === b.nonce && a.body === b.body;
}
function newer(a: StoredRecord, b: StoredRecord): boolean {
  return a.updatedAt > b.updatedAt || (a.updatedAt === b.updatedAt && (a.authorMemberId ?? a.ownerMemberId ?? '') > (b.authorMemberId ?? b.ownerMemberId ?? ''));
}
/** Merge a page and its cursor in one transaction; a newer local write wins. */
export async function putRecords(rows: StoredRecord[], space: string, seq?: number): Promise<void> {
  const db = await open(); const tx = db.transaction([RECORDS, OUTBOX, 'meta'], 'readwrite'); const finish = committed(tx);
  const store = tx.objectStore(RECORDS);
  for (const row of rows) {
    const current = await done(store.get([space, row.id])) as StoredRecord | undefined;
    const pending = await done(tx.objectStore(OUTBOX).get([space, row.id])) as OutboxRecord | undefined;
    if (!current || newer(row, current) || (!pending && row.updatedAt === current.updatedAt && (row.authorMemberId ?? row.ownerMemberId ?? '') === (current.authorMemberId ?? current.ownerMemberId ?? ''))) store.put(indexed(row, space));
  }
  if (seq !== undefined) tx.objectStore('meta').put(seq, metaKey('seq', space));
  await finish;
}
export async function enqueue(row: OutboxRecord, space: string): Promise<void> {
  const db = await open(); const tx = db.transaction([OUTBOX, RECORDS], 'readwrite'); const finish = committed(tx);
  const current = await done(tx.objectStore(RECORDS).get([space, row.id])) as StoredRecord | undefined;
  const next = { ...row, updatedAt: Math.max(row.updatedAt, (current?.updatedAt ?? 0) + 1) };
  tx.objectStore(OUTBOX).put(indexed(next, space)); tx.objectStore(RECORDS).put(indexed(next, space)); await finish;
}
export async function outbox(space: string): Promise<OutboxRecord[]> {
  const db = await open();
  return done(db.transaction(OUTBOX).objectStore(OUTBOX).index('partition').getAll(space));
}
export async function pendingCount(space: string): Promise<number> {
  const db = await open(); return done(db.transaction(OUTBOX).objectStore(OUTBOX).index('partition').count(space));
}
/** Only acknowledge the exact encrypted revision that was sent, never a concurrent edit. */
export async function acknowledge(sent: OutboxRecord[], space: string, clamped: Array<{ id: string; updatedAt: number }> = []): Promise<void> {
  const db = await open(); const tx = db.transaction([OUTBOX, RECORDS], 'readwrite'); const finish = committed(tx);
  for (const row of sent) {
    const current = await done(tx.objectStore(OUTBOX).get([space, row.id])) as OutboxRecord | undefined;
    if (!current || !sameVersion(current, row)) continue;
    tx.objectStore(OUTBOX).delete([space, row.id]);
    const stamp = clamped.find((c) => c.id === row.id)?.updatedAt;
    if (stamp !== undefined) tx.objectStore(RECORDS).put(indexed({ ...row, updatedAt: stamp }, space));
  }
  await finish;
}

/** Web Locks serialize sync across tabs; an IndexedDB lease covers older browsers. */
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

export async function decisionsFor(space: string, targets: string[]): Promise<StoredRecord[]> {
  if (!targets.length) return [];
  const db = await open(); const store = db.transaction(RECORDS).objectStore(RECORDS).index('target');
  const groups = await Promise.all([...new Set(targets)].map((id) => done(store.getAll([space, id]))));
  return (groups.flat() as StoredRecord[]).filter((r) => !r.deleted);
}
