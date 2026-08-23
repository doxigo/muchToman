import { open as unseal, seal } from './crypto';
import {
import type { OutboxRecord, StoredRecord } from './db';

export interface Session {
  base: string;
  token: string;
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
  note: string;
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

export function nextStamp(previous: number | undefined, now: number): number {
  return Math.max(now, (previous ?? 0) + 1);
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
): Promise<void> {
  const id = familyTxnId(session.member, `m:${localId}`);
  const existing = await getRecord(id);
  const updatedAt = nextStamp(existing?.updatedAt, Date.now());
  const value: Entry = {
    ...entry,
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
  });
}

export async function saveProfile(session: Session, sharesSms = false): Promise<void> {
  const id = `member:${session.member}`;
  const existing = await getRecord(id);
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
  });
}

export async function remove(session: Session, id: string): Promise<void> {
  const existing = (await allRecords()).find((r) => r.id === id);
  if (!existing) return;
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
  });
}

async function request(session: Session, path: string, init?: RequestInit): Promise<Response> {
  return fetch(`${session.base}${path}`, {
    ...init,
    headers: { ...(init?.headers ?? {}), authorization: `Bearer ${session.token}` },
  });
}

async function registerIdentity(session: Session): Promise<void> {
  const identityKey = `sync_identity_ok:${session.member}:${session.device}`;
  if (await getMeta<boolean>(identityKey)) return;
  const res = await request(session, '/v1/identity', {
    method: 'POST',
    body: JSON.stringify({ memberId: session.member, deviceId: session.device }),
  });
  if (!res.ok) throw new Error(`identity failed: ${res.status}`);
  await setMeta(identityKey, true);
}

export async function pull(session: Session): Promise<number> {
  const since = (await getMeta<number>('seq')) ?? 0;
  const res = await request(session, `/v1/sync?since=${since}`);
  if (!res.ok) throw new Error(`pull failed: ${res.status}`);
  const { seq, records } = (await res.json()) as {
    seq: number;
    records: Array<{
      id: string; scope: string; updatedAt: number; device: string;
      kind?: string; ownerMemberId?: string; authorMemberId?: string;
      deleted: boolean; nonce: string; body: string;
    }>;
  };

  const rows: StoredRecord[] = [];
  for (const record of records) {
    const value = await unseal<unknown>(session.key, record.nonce, record.body);
    if (value == null) continue;
    if (record.deleted) {
      // The `deleted` flag rides in plaintext, so it proves nothing on its own: a delete is
      // honoured only when the sealed body authenticates AND names this very record. The old
      // contentless tombstone shape fails here and is rejected — the protocol never shipped in
      // a tagged release, so nothing is owed to it.
      const tombstone = value as { id?: unknown; deleted?: unknown };
      if (tombstone.deleted !== true || tombstone.id !== record.id) continue;
    }
    rows.push({
      id: record.id, scope: record.scope, updatedAt: record.updatedAt,
      device: record.device, kind: record.kind, ownerMemberId: record.ownerMemberId,
      authorMemberId: record.authorMemberId, deleted: record.deleted, value,
    });
  }
  await putRecords(rows);
  await setMeta('seq', seq);
  return rows.length;
}

export async function push(session: Session): Promise<number> {
  const pending: OutboxRecord[] = await outbox();
  if (pending.length === 0) return 0;
  const res = await request(session, '/v1/sync', {
    method: 'POST',
    body: JSON.stringify({
      records: pending.map((r) => ({
        id: r.id, scope: r.scope, updatedAt: r.updatedAt,
        device: r.device, kind: r.kind ?? 'legacy', ownerMemberId: r.ownerMemberId ?? '',
        deleted: r.deleted, nonce: r.nonce, body: r.body,
      })),
    }),
  });
  if (!res.ok) throw new Error(`push failed: ${res.status}`);
  // The server clamps far-future stamps (its LWW skew bound) and answers with what it stored;
  // the local copy takes the server's word so both sides count from the same stamp.
  const ack = (await res.json()) as { clamped?: Array<{ id: string; updatedAt: number }> };
  for (const clamp of ack.clamped ?? []) {
    const row = await getRecord(clamp.id);
    if (row) await putRecords([{ ...row, updatedAt: clamp.updatedAt }]);
  }
  await clearOutbox(pending.map((r) => r.id));
  return pending.length;
}

const TOKEN_ROTATE_AFTER_MS = 30 * 24 * 60 * 60 * 1000;

/**
 * A new secret once a month, taken at the end of a sync that already proved the network works.
 * The old secret is dead the moment the server answers, so the session is persisted immediately;
 * the shape written here is exactly the one main.ts stores and loads.
 */
async function rotateTokenIfStale(session: Session): Promise<void> {
  if (Date.now() - session.issuedAt < TOKEN_ROTATE_AFTER_MS) return;
  const res = await request(session, '/v1/rotate', { method: 'POST' });
  if (!res.ok) return;
  const { secret } = (await res.json()) as { secret: string };
  if (!secret) return;
  session.token = `${session.token.split('.')[0]}.${secret}`;
  session.issuedAt = Date.now();
  await setMeta('session', session);
}

export async function syncNow(session: Session): Promise<{ sent: number; received: number }> {
  await registerIdentity(session);
  const sent = await push(session);
  const received = await pull(session);
  await rotateTokenIfStale(session);
  return { sent, received };
}
