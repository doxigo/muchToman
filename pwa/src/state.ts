/**
 * The browser's AppVm, minus the view model: every table and pref held in memory, written
 * through to IndexedDB, and one version counter the screens re-render on.
 *
 * In memory because the phone's own numbers say it is right: a household ledger is a few
 * thousand rows, the whole derive is milliseconds, and a screen that awaits IndexedDB for every
 * list is a screen that flickers. Writes are synchronous to the model and queued to disk in
 * order; a failed write is the one thing surfaced loudly, because it is the one way to lose data.
 *
 * ponytail: one version for everything, so any write re-renders every mounted screen. Per-table
 * versions are the upgrade if a profile ever shows it.
 */
import { useEffect, useState } from 'preact/hooks';
import { LOCAL, PREFS, open, reopen } from './db';
import { PREF_DEFAULTS, TABLES } from './model';
import type { Prefs, TableName, Tables } from './model';

const tables = Object.fromEntries(TABLES.map((t) => [t, new Map()])) as { [K in TableName]: Map<string, Tables[K]> };
/** No prototype, so a `__proto__` key from a file or the disk is a key like any other. */
const prefs: Partial<Prefs> = Object.create(null);
let version = 0;
let loaded = false;
type Listener = (external: boolean, bySync: boolean) => void;
const listeners = new Set<Listener>();
let syncWriting = 0;

/**
 * [external] is a change another tab wrote, here reloaded: that tab answers for it (sync, notes).
 * [bySync] is one the family sync wrote itself, which must not ask for another sync.
 */
export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
function changed(external = false): void {
  version++;
  for (const l of listeners) l(external, syncWriting > 0);
}

/**
 * The family sync's own writes. Marked one at a time rather than for the whole run, because the
 * run awaits the network in between, and an edit she makes meanwhile is hers and must still ask.
 */
export function syncWrite(action: () => void): void {
  syncWriting++;
  try { action(); } finally { syncWriting--; }
}
export const dataVersion = (): number => version;

// ---- reads -------------------------------------------------------------------------------

export function pref<K extends keyof Prefs>(key: K): Prefs[K] {
  return (prefs[key] ?? PREF_DEFAULTS[key]) as Prefs[K];
}
/** Every row of a table, tombstones included — the caller decides, as a DAO query would. */
export function rows<K extends TableName>(table: K): Tables[K][] {
  return [...tables[table].values()];
}
export function row<K extends TableName>(table: K, id: string): Tables[K] | undefined {
  return tables[table].get(id);
}

// ---- writes ------------------------------------------------------------------------------

type Op = { store: typeof LOCAL; value: { t: TableName; id: string } } | { store: typeof PREFS; key: string; value: unknown }
  | { store: typeof LOCAL; delete: [TableName, string] };
let queue: Op[] = [];
/** The batch on its way to disk: like the queue, not there yet as far as a reload is concerned. */
let sending: Op[] = [];
let flushing: Promise<void> | null = null;
/** Failed flushes in a row; the first says so, the retries back off quietly. */
let failures = 0;
let retry: ReturnType<typeof setTimeout> | undefined;
/** A restore has the disk: writes still land in memory and the queue, and wait for it. */
let held = false;
let onWriteError: (error: unknown) => void = (e) => console.error('write failed', e);
export function setWriteErrorHandler(handler: (error: unknown) => void): void { onWriteError = handler; }

function kick(): void {
  if (queue.length && !held) flushing ??= Promise.resolve().then(flush);
}
function schedule(op: Op): void {
  queue.push(op);
  kick();
}
async function flush(): Promise<void> {
  try {
    while (queue.length && !held) {
      sending = queue; queue = [];
      const db = await open();
      const tx = db.transaction([LOCAL, PREFS], 'readwrite');
      sending = sending.filter((op) => {
        try {
          if (op.store === PREFS) tx.objectStore(PREFS).put(op.value, op.key);
          else if ('delete' in op) tx.objectStore(LOCAL).delete(op.delete);
          else tx.objectStore(LOCAL).put(op.value);
          return true;
        } catch (error) {
          // A key or value IndexedDB cannot hold never lands however often it is retried, and
          // kept it would hold back every write behind it. Anything else is the connection's.
          if (!(error instanceof DOMException && (error.name === 'DataError' || error.name === 'DataCloneError'))) throw error;
          onWriteError(error);
          return false;
        }
      });
      await new Promise<void>((resolve, reject) => {
        tx.oncomplete = () => resolve();
        tx.onabort = tx.onerror = () => reject(tx.error ?? new Error('write aborted'));
      });
      sending = [];
      failures = 0;
      tabs?.postMessage(null);
    }
  } catch (error) {
    // Back at the front, in order: memory already shows these writes, and dropped here they
    // would be gone at the next reload with nothing on screen to say so.
    queue = [...sending, ...queue];
    sending = [];
    reopen();
    if (failures++ === 0) onWriteError(error);
    clearTimeout(retry);
    retry = setTimeout(kick, Math.min(1000 * 2 ** (failures - 1), 30_000));
  } finally {
    flushing = null;
  }
}
/**
 * Resolves once everything written so far is on disk — for backup, and for tests — or once a
 * flush has failed, so nothing waits forever on a disk that is refusing; the retry goes on.
 */
export async function settled(): Promise<void> {
  while (flushing) await flushing;
}

export function setPref<K extends keyof Prefs>(key: K, value: Prefs[K]): void {
  prefs[key] = value;
  schedule({ store: PREFS, key, value });
  changed();
}

export function put<K extends TableName>(table: K, value: Tables[K]): void {
  putAll(table, [value]);
}
export function putAll<K extends TableName>(table: K, values: Tables[K][]): void {
  if (!values.length) return;
  for (const value of values) {
    tables[table].set(value.id, value);
    schedule({ store: LOCAL, value: { ...value, t: table } });
  }
  changed();
}
/** A real delete, for rows that are caches rather than her data (publications, family mirrors). */
export function erase<K extends TableName>(table: K, id: string): void {
  if (!tables[table].delete(id)) return;
  schedule({ store: LOCAL, delete: [table, id] });
  changed();
}

/** Several writes, one re-render. */
export function batch(action: () => void): void {
  const before = listeners.size ? [...listeners] : [];
  listeners.clear();
  try { action(); } finally {
    for (const l of before) listeners.add(l);
    changed();
  }
}

// ---- load --------------------------------------------------------------------------------

export async function load(): Promise<void> {
  if (loaded) return;
  await readDisk();
  loaded = true;
  if (!tabs && typeof BroadcastChannel === 'function') {
    tabs = new BroadcastChannel('muchtoman-state');
    tabs.onmessage = reload;
  }
  changed();
}

/**
 * The app's other tabs, told after every write lands here, so each reloads from disk rather than
 * writing its stale copy — whole lists of holdings, loans, switches — back over this one's.
 *
 * ponytail: the whole ledger re-read for any write elsewhere, a few milliseconds at a household's
 * size; per-row messages are the upgrade if a profile ever shows it.
 */
let tabs: BroadcastChannel | null = null;
let reloading: Promise<void> | null = null;
/** Another tab wrote while this one was reading or restoring: read again after. */
let stale = false;
function reload(): void {
  if (held || reloading) { stale = true; return; }
  // Loaded rather than written, so nothing goes back out on the channel: no echo between tabs.
  reloading = readDisk().then(() => changed(true), (error: unknown) => console.error('reload failed', error)).finally(() => {
    reloading = null;
    if (stale) { stale = false; reload(); }
  });
}

/** The disk as it stands, with this tab's own writes not yet on it laid back over the top. */
async function readDisk(): Promise<void> {
  const db = await open();
  const tx = db.transaction([LOCAL, PREFS]);
  const [all, keys, values] = await Promise.all([
    request<Array<{ t: TableName; id: string }>>(tx.objectStore(LOCAL).getAll()),
    request<IDBValidKey[]>(tx.objectStore(PREFS).getAllKeys()),
    request<unknown[]>(tx.objectStore(PREFS).getAll()),
  ]);
  for (const t of TABLES) tables[t].clear();
  for (const key of Object.keys(prefs)) delete (prefs as Record<string, unknown>)[key];
  for (const stored of all) {
    const { t, ...value } = stored;
    (tables[t] as Map<string, unknown> | undefined)?.set(value.id, value);
  }
  keys.forEach((key, i) => { (prefs as Record<string, unknown>)[String(key)] = values[i]; });
  for (const op of [...sending, ...queue]) {
    if (op.store === PREFS) (prefs as Record<string, unknown>)[op.key] = op.value;
    else if ('delete' in op) tables[op.delete[0]].delete(op.delete[1]);
    else { const { t, ...value } = op.value; (tables[t] as Map<string, unknown>).set(value.id, value); }
  }
}
function request<T>(req: IDBRequest): Promise<T> {
  return new Promise((resolve, reject) => { req.onsuccess = () => resolve(req.result as T); req.onerror = () => reject(req.error); });
}

export interface Snapshot { prefs: Partial<Prefs>; tables: { [K in TableName]?: Tables[K][] } }

/** Everything, as plain data — the backup's payload and the restore's input. */
export function snapshot(): Snapshot {
  return { prefs: { ...prefs }, tables: Object.fromEntries(TABLES.map((t) => [t, rows(t)])) };
}

/**
 * A restore: every table and pref replaced by the backup's, in one IndexedDB transaction, so a
 * failure half way leaves the old ledger rather than half of each. Unknown tables in the file are
 * ignored; which prefs travel is the backup writer's call, through [build], which is handed
 * everything as it stands once the disk is the restore's alone. The household session lives
 * outside these stores and is not touched.
 *
 * Writes made meanwhile (a wallet refresh, a sync landing) wait in the queue, and once the
 * restore is down they are dropped: memory is replaced as well, so landing after it they would
 * only put the old ledger back on disk under the restored one.
 */
export async function replaceAll(build: (current: Snapshot) => Snapshot): Promise<void> {
  held = true;
  try {
    await settled();
    await reloading;
    await restore(build, await open());
  } finally {
    held = false;
    kick();
    if (stale) { stale = false; reload(); }
  }
}
async function restore(build: (current: Snapshot) => Snapshot, db: IDBDatabase): Promise<void> {
  const next = build(snapshot());
  const tx = db.transaction([LOCAL, PREFS], 'readwrite');
  try {
    tx.objectStore(LOCAL).clear();
    tx.objectStore(PREFS).clear();
    for (const t of TABLES) for (const value of next.tables[t] ?? []) tx.objectStore(LOCAL).put({ ...value, t });
    for (const [key, value] of Object.entries(next.prefs)) tx.objectStore(PREFS).put(value, key);
  } catch (error) {
    // A put refused as it is queued does not abort the transaction, and the clears would commit.
    tx.abort();
    throw error;
  }
  await new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onabort = tx.onerror = () => reject(tx.error ?? new Error('restore aborted'));
  });
  queue = [];
  failures = 0;
  for (const t of TABLES) {
    tables[t].clear();
    for (const value of next.tables[t] ?? []) (tables[t] as Map<string, unknown>).set(value.id, value);
  }
  for (const key of Object.keys(prefs)) delete (prefs as Record<string, unknown>)[key];
  Object.assign(prefs, next.prefs);
  tabs?.postMessage(null);
  changed();
}

// ---- hooks -------------------------------------------------------------------------------

/** Re-renders the caller on every data change; returns the version so memos can key on it. */
export function useData(): number {
  const [, set] = useState(version);
  useEffect(() => subscribe(() => set(version)), []);
  return version;
}
