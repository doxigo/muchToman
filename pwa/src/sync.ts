import { open as unseal, seal } from './crypto';
import {
  acknowledge, enqueue, getMeta, getRecord, outbox, putRecords, setMeta, partition, persistSession, withSyncLock,
} from './db';
import type { OutboxRecord, StoredRecord } from './db';

export interface Session {
  base: string;
  token: string;
  /** When the token was minted; a monthly sync-time rotation runs off it. */
  issuedAt: number;
  device: string;
  member: string;
  name: string;
  scope: string;
  key: CryptoKey;
}

export interface Entry {
  kind: 'transaction';
  ownerMemberId: string;
  sourceKind: 'sms' | 'manual';
  transfer?: boolean;
  at: number;
  amountRial: number;
  direction: 'in' | 'out';
  bank: string;
  categoryId: string;
  categoryName: string;
  categoryKind: 'expense' | 'income' | 'transfer';
  categoryEditorId: string;
  categoryUpdatedAt: number;
  merchant: string;
}

export interface MemberProfile {
  kind: 'member';
  memberId: string;
  name: string;
  sharesSms: boolean;
}

export interface CategoryDecision {
  kind: 'category';
  target: string;
  categoryId: string;
  categoryName: string;
  categoryKind: 'expense' | 'income' | 'transfer';
  editedByMemberId: string;
}

/**
 * Somebody's words about one transaction, on a record of its own rather than inside the
 * transaction's — a note is written by whoever is reading the row, and only its owner may write
 * the transaction record. Blank is a note taken back — the row simply loses its line.
 *
 * This page reads notes and does not write them: there is no field to type one into here, and
 * a screen that showed somebody else's words with no way to answer them would be the wrong half
 * of the feature to build first.
 */
export interface NoteDecision {
  kind: 'note';
  target: string;
  note: string;
  editedByMemberId: string;
}

export function nextStamp(previous: number | undefined, now: number): number {
  return Math.max(now, (previous ?? 0) + 1);
}

/**
 * «این ماه» has to mean this *Jalali* month. Intl's Persian calendar already knows the leap
 * years, so month membership is string equality on formatted parts rather than Jalali
 * arithmetic; Tehran's clock decides which day a timestamp lands on, exactly as the app does.
 * (It lives in this file because main.ts cannot be imported without a DOM — the tests need it.)
 */
const JALALI_MONTH = new Intl.DateTimeFormat('fa-IR-u-ca-persian', {
  timeZone: 'Asia/Tehran',
  year: 'numeric',
  month: 'numeric',
});

export function jalaliMonthKey(at: number): string {
  const parts = JALALI_MONTH.formatToParts(at);
  const part = (type: string): string => parts.find((p) => p.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}`;
}

export function inJalaliMonth(at: number, reference: number): boolean {
  return jalaliMonthKey(at) === jalaliMonthKey(reference);
}

export function uuid7(now = Date.now()): string {
  const b = crypto.getRandomValues(new Uint8Array(16));
  for (let i = 0; i < 6; i++) b[i] = Math.floor(now / 2 ** (8 * (5 - i))) & 0xff;
  b[6] = (b[6] & 0x0f) | 0x70;
  b[8] = (b[8] & 0x3f) | 0x80;
  const hex = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function memberId(): string {
  return uuid7().replaceAll('-', '');
}

function utf8Hex(value: string): string {
  return [...new TextEncoder().encode(value)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export function familyTxnId(member: string, localRef: string): string {
  return `txn:${member}:${utf8Hex(localRef)}`;
}

export async function save(
  session: Session,
  entry: Omit<Entry, 'kind' | 'ownerMemberId' | 'sourceKind' | 'categoryEditorId'>,
  localId = uuid7(),
  recordId?: string,
): Promise<void> {
  if (!Number.isSafeInteger(entry.amountRial) || entry.amountRial <= 0 || entry.amountRial > 1_000_000_000_000_000) throw new Error('مبلغ معتبر نیست.');
  const id = recordId ?? familyTxnId(session.member, `m:${localId}`);
  const existing = await getRecord(id, partition(session));
  if (recordId && (!existing || existing.ownerMemberId !== session.member || existing.scope !== session.scope)) throw new Error('فقط تراکنش خودت رو می‌تونی ویرایش کنی.');
  const updatedAt = nextStamp(existing?.updatedAt, Date.now());
  const value: Entry = {
    ...entry,
    merchant: entry.merchant.trim().slice(0, 120),
    kind: 'transaction',
    ownerMemberId: session.member,
    sourceKind: 'manual',
    categoryEditorId: session.member,
  };
  const { nonce, body } = await seal(session.key, value);
  await enqueue({
    id, scope: session.scope, updatedAt, device: session.device,
    kind: 'transaction', ownerMemberId: session.member,
    deleted: false, value, nonce, body,
  }, partition(session));
}

export async function saveProfile(session: Session, sharesSms = false): Promise<void> {
  const id = `member:${session.member}`;
  const existing = await getRecord(id, partition(session));
  const previous = existing?.value as MemberProfile | undefined;
  if (previous?.name === session.name && previous.sharesSms === sharesSms) return;
  const updatedAt = nextStamp(existing?.updatedAt, Date.now());
  const value: MemberProfile = {
    kind: 'member', memberId: session.member, name: session.name, sharesSms,
  };
  const { nonce, body } = await seal(session.key, value);
  await enqueue({
    id, scope: session.scope, updatedAt, device: session.device,
    kind: 'member', ownerMemberId: session.member,
    deleted: false, value, nonce, body,
  }, partition(session));
}

export async function remove(session: Session, id: string): Promise<void> {
  const existing = await getRecord(id, partition(session));
  if (!existing || existing.deleted) return;
  if (existing.ownerMemberId !== session.member || existing.scope !== session.scope) throw new Error('فقط تراکنش خودت رو می‌تونی حذف کنی.');
  const updatedAt = nextStamp(existing.updatedAt, Date.now());
  // The record's id, sealed in: receivers only believe a delete whose ciphertext authenticates
  // and names the record it arrived on, so a compromised server cannot mint or re-aim one.
  const value = { v: 1, id, deleted: true };
  const { nonce, body } = await seal(session.key, value);
  await enqueue({
    ...existing,
    updatedAt,
    deleted: true,
    value,
    nonce,
    body,
  }, partition(session));
}

async function request(session: Session, path: string, init?: RequestInit): Promise<Response> {
  return fetch(`${session.base}${path}`, {
    ...init,
    signal: AbortSignal.timeout(20_000),
    headers: { 'content-type': 'application/json', ...(init?.headers ?? {}), authorization: `Bearer ${session.token}` },
  });
}

async function registerIdentity(session: Session): Promise<void> {
  const identityKey = `sync_identity_ok:${session.member}:${session.device}`;
  if (await getMeta<boolean>(identityKey, partition(session))) return;
  const res = await request(session, '/v1/identity', {
    method: 'POST',
    body: JSON.stringify({ memberId: session.member, deviceId: session.device }),
  });
  if (!res.ok) throw new Error(`identity failed: ${res.status}`);
  await setMeta(identityKey, true, partition(session));
}

export async function pull(session: Session): Promise<number> {
  const space = partition(session);
  let since = (await getMeta<number>('seq', space)) ?? 0;
  let received = 0;
  for (;;) {
    const res = await request(session, `/v1/sync?since=${since}&limit=500`);
    if (!res.ok) throw new Error(`pull failed: ${res.status}`);
    const { seq, records, hasMore, rotationClientSecret } = (await res.json()) as {
      seq: number; hasMore?: boolean; rotationClientSecret?: boolean;
      records: Array<{
        id: string; scope: string; updatedAt: number; device: string;
        kind?: string; ownerMemberId?: string; authorMemberId?: string;
        deleted: boolean; nonce: string; body: string;
      }>;
    };
    if (!Number.isSafeInteger(seq) || seq < since || !Array.isArray(records)) throw new Error('Invalid sync page');
    const rows: StoredRecord[] = [];
    for (const record of records) {
      if (record.scope !== session.scope) continue;
      const value = await unseal<unknown>(session.key, record.nonce, record.body);
      if (value == null) throw new Error('تعویض کلید یا داده ناخوانا؛ همگام‌سازی متوقف شد.');
      if (record.deleted) {
        const tombstone = value as { id?: unknown; deleted?: unknown };
        if (tombstone.deleted !== true || tombstone.id !== record.id) throw new Error('Invalid tombstone');
      }
      rows.push({
        id: record.id, scope: record.scope, updatedAt: record.updatedAt,
        device: record.device, kind: record.kind, ownerMemberId: record.ownerMemberId,
        authorMemberId: record.authorMemberId, deleted: record.deleted, value,
      });
    }
    await putRecords(rows, space, seq);
    await setMeta('rotation-supported', rotationClientSecret === true, space);
    received += rows.length;
    if (hasMore !== true && !(hasMore === undefined && records.length === 500)) return received;
    if (seq <= since) throw new Error('Sync cursor did not advance');
    since = seq;
  }
}

export const MAX_PUSH_BYTES = 900 * 1024;
export const MAX_PUSH_RECORDS = 500;
function wireRecord(r: OutboxRecord): object {
  return {
    id: r.id, scope: r.scope, updatedAt: r.updatedAt, device: r.device,
    kind: r.kind ?? 'legacy', ownerMemberId: r.ownerMemberId ?? '',
    deleted: r.deleted, nonce: r.nonce, body: r.body,
  };
}
export async function push(session: Session): Promise<number> {
  const space = partition(session);
  const pending = await outbox(space);
  let sent = 0;
  for (let offset = 0; offset < pending.length;) {
    const batch: OutboxRecord[] = [];
    let bytes = new TextEncoder().encode('{"records":[]}').length;
    while (offset < pending.length && batch.length < MAX_PUSH_RECORDS) {
      const row = pending[offset];
      if (row.scope !== session.scope || row.ownerMemberId !== session.member) throw new Error('Pending record belongs to another identity');
      const size = new TextEncoder().encode(JSON.stringify(wireRecord(row))).length + (batch.length ? 1 : 0);
      if (bytes + size > MAX_PUSH_BYTES) {
        if (!batch.length) throw new Error('این مورد برای فرستادن زیادی بزرگه.');
        break;
      }
      batch.push(row); bytes += size; offset++;
    }
    const res = await request(session, '/v1/sync', {
      method: 'POST', body: JSON.stringify({ records: batch.map(wireRecord) }),
    });
    if (!res.ok) throw new Error(`push failed: ${res.status}`);
    const ack = (await res.json()) as { clamped?: Array<{ id: string; updatedAt: number }> };
    await acknowledge(batch, space, ack.clamped);
    sent += batch.length;
  }
  return sent;
}

const TOKEN_ROTATE_AFTER_MS = 30 * 24 * 60 * 60 * 1000;
interface PendingRotation { oldToken: string; newToken: string; startedAt: number }
async function recoverRotation(session: Session): Promise<void> {
  const space = partition(session);
  const pending = await getMeta<PendingRotation>('pending-rotation', space);
  if (!pending) return;
  const secret = pending.newToken.split('.')[1];
  let response = await request({ ...session, token: pending.oldToken }, '/v1/rotate', {
    method: 'POST', body: JSON.stringify({ secret }),
  });
  if (response.status === 401) response = await request({ ...session, token: pending.newToken }, '/v1/rotate', {
    method: 'POST', body: JSON.stringify({ secret }),
  });
  if (!response.ok) throw new Error(`Token recovery failed: ${response.status}`);
  const result = await response.json() as { secret?: string };
  if (result.secret !== secret) throw new Error('Token rotation mismatch');
  session.token = pending.newToken; session.issuedAt = pending.startedAt;
  await persistSession(session, true);
}
async function rotateTokenIfStale(session: Session): Promise<void> {
  if (Date.now() - session.issuedAt < TOKEN_ROTATE_AFTER_MS || !await getMeta<boolean>('rotation-supported', partition(session))) return;
  const secret = [...crypto.getRandomValues(new Uint8Array(32))].map((x) => x.toString(16).padStart(2, '0')).join('');
  await setMeta('pending-rotation', {
    oldToken: session.token, newToken: `${session.token.split('.')[0]}.${secret}`, startedAt: Date.now(),
  } satisfies PendingRotation, partition(session));
  await recoverRotation(session);
}

export async function syncNow(session: Session): Promise<{ sent: number; received: number }> {
  const space = partition(session);
  return withSyncLock(space, async () => {
    // A different tab may have rotated while this tab waited for the lock.
    const latest = await getMeta<Session>(`saved-session:${space}`);
    if (latest) { session.token = latest.token; session.issuedAt = latest.issuedAt; }
    await recoverRotation(session);
    await registerIdentity(session);
    const sent = await push(session);
    const received = await pull(session);
    await rotateTokenIfStale(session);
    await setMeta('last-sync', Date.now(), space);
    return { sent, received };
  });
}
