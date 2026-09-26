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
import { LOCAL, PREFS, open } from './db';
import { PREF_DEFAULTS, TABLES } from './model';
import type { Prefs, TableName, Tables } from './model';

const tables = Object.fromEntries(TABLES.map((t) => [t, new Map()])) as { [K in TableName]: Map<string, Tables[K]> };
const prefs: Partial<Prefs> = {};
let version = 0;
let loaded = false;
const listeners = new Set<() => void>();

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
function changed(): void {
  version++;
  for (const l of listeners) l();
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

type Op = { store: typeof LOCAL; value: unknown } | { store: typeof PREFS; key: string; value: unknown }
  | { store: typeof LOCAL; delete: [string, string] };
let queue: Op[] = [];
let flushing: Promise<void> | null = null;
let onWriteError: (error: unknown) => void = (e) => console.error('write failed', e);
export function setWriteErrorHandler(handler: (error: unknown) => void): void { onWriteError = handler; }

function schedule(op: Op): void {
  queue.push(op);
  flushing ??= Promise.resolve().then(flush);
}
async function flush(): Promise<void> {
  try {
    while (queue.length) {
      const batch = queue; queue = [];
      const db = await open();
      const tx = db.transaction([LOCAL, PREFS], 'readwrite');
      const done = new Promise<void>((resolve, reject) => {
        tx.oncomplete = () => resolve();
        tx.onabort = tx.onerror = () => reject(tx.error ?? new Error('write aborted'));
      });
      for (const op of batch) {
        if (op.store === PREFS) tx.objectStore(PREFS).put(op.value, op.key);
        else if ('delete' in op) tx.objectStore(LOCAL).delete(op.delete);
        else tx.objectStore(LOCAL).put(op.value);
      }
      await done;
    }
  } catch (error) {
    onWriteError(error);
  } finally {
    flushing = null;
    if (queue.length) flushing = Promise.resolve().then(flush);
  }
}
/** Resolves once everything written so far is on disk — for backup, and for tests. */
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
  const db = await open();
  const tx = db.transaction([LOCAL, PREFS]);
  const [all, keys, values] = await Promise.all([
    request<Array<{ t: TableName; id: string }>>(tx.objectStore(LOCAL).getAll()),
    request<IDBValidKey[]>(tx.objectStore(PREFS).getAllKeys()),
    request<unknown[]>(tx.objectStore(PREFS).getAll()),
  ]);
  for (const stored of all) {
    const { t, ...value } = stored;
    (tables[t] as Map<string, unknown> | undefined)?.set(value.id, value);
  }
  keys.forEach((key, i) => { (prefs as Record<string, unknown>)[String(key)] = values[i]; });
  loaded = true;
  changed();
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
 * ignored; which prefs travel is the backup writer's call. The household session lives outside
 * these stores and is not touched.
 */
export async function replaceAll(next: Snapshot): Promise<void> {
  await settled();
  const db = await open();
  const tx = db.transaction([LOCAL, PREFS], 'readwrite');
  const done = new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onabort = tx.onerror = () => reject(tx.error ?? new Error('restore aborted'));
  });
  tx.objectStore(LOCAL).clear();
  tx.objectStore(PREFS).clear();
  for (const t of TABLES) for (const value of next.tables[t] ?? []) tx.objectStore(LOCAL).put({ ...value, t });
  for (const [key, value] of Object.entries(next.prefs)) tx.objectStore(PREFS).put(value, key);
  await done;
  for (const t of TABLES) {
    tables[t].clear();
    for (const value of next.tables[t] ?? []) (tables[t] as Map<string, unknown>).set(value.id, value);
  }
  for (const key of Object.keys(prefs)) delete (prefs as Record<string, unknown>)[key];
  Object.assign(prefs, next.prefs);
  changed();
}

// ---- hooks -------------------------------------------------------------------------------

/** Re-renders the caller on every data change; returns the version so memos can key on it. */
export function useData(): number {
  const [, set] = useState(version);
  useEffect(() => subscribe(() => set(version)), []);
  return version;
}
