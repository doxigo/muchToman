import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { StoredRecord } from '../src/db';
import type { WireRecord } from '../src/sync';

/**
 * A browser that was already in a household under the old companion stays in it, now as a full
 * member: its own published rows become its own ledger rows under the same wire ids, its unsent
 * writes still go out, and nothing belonging to any other household it kept is touched.
 */

const HID = 'a'.repeat(32);
const ME = '1'.repeat(32);
const DEVICE = '2'.repeat(32);
const THEM = '3'.repeat(32);
const NOW = Date.UTC(2026, 8, 20, 9, 0);
const LIVE = '0190d1a2-0000-7000-8000-000000000001';
const DELETED_SENT = '0190d1a2-0000-7000-8000-000000000002';
const DELETED_UNSENT = '0190d1a2-0000-7000-8000-000000000003';

let sync: typeof import('../src/sync');
let family: typeof import('../src/family');
let state: typeof import('../src/state');
let db: typeof import('../src/db');

beforeEach(async () => {
  vi.resetModules(); vi.unstubAllGlobals(); globalThis.indexedDB = new IDBFactory();
  vi.stubGlobal('location', { origin: 'https://sync.test' });
  vi.stubGlobal('document', { visibilityState: 'hidden', addEventListener: () => {} });
  vi.stubGlobal('addEventListener', () => {});
  sync = await import('../src/sync'); family = await import('../src/family'); state = await import('../src/state'); db = await import('../src/db');
});

const own = (localId: string, over: Partial<StoredRecord> = {}): StoredRecord => ({
  id: sync.familyTxnId(ME, `m:${localId}`), scope: `family:${HID}`, updatedAt: NOW - 5000, device: DEVICE, kind: 'transaction', ownerMemberId: ME,
  deleted: false, value: { kind: 'transaction', ownerMemberId: ME, sourceKind: 'manual', at: NOW - 86_400_000, amountRial: 12340, direction: 'out', bank: 'MANUAL', merchant: 'نانوایی', categoryId: '' },
  ...over,
});

describe('legacyRows', () => {
  it('carries her own rows, their stamps and her unsent deletes, and nothing of anyone else\'s', () => {
    const tombstone = (id: string): Partial<StoredRecord> => ({ deleted: true, value: { v: 1, id: sync.familyTxnId(ME, `m:${id}`), deleted: true } });
    const records = [
      own(LIVE),
      own(DELETED_SENT, tombstone(DELETED_SENT)),
      own(DELETED_UNSENT, tombstone(DELETED_UNSENT)),
      own('unreadable', { value: { at: 'yesterday' } }),
      { ...own('theirs'), id: sync.familyTxnId(THEM, 'm:theirs'), ownerMemberId: THEM },
      { id: `member:${ME}`, scope: `family:${HID}`, updatedAt: NOW - 9000, device: DEVICE, kind: 'member', ownerMemberId: ME, deleted: false, value: { name: 'مریم', sharesSms: false } },
    ];
    const moved = family.legacyRows(ME, records, new Set([sync.familyTxnId(ME, `m:${DELETED_UNSENT}`)]));
    expect(moved.manual).toEqual([expect.objectContaining({ id: LIVE, amountRial: -12340, merchant: 'نانوایی', accountId: null, categoryId: null, deleted: false })]);
    expect(moved.publications.map((p) => [p.id.slice(-2), p.deleted, p.contentHash])).toEqual([
      [sync.familyTxnId(ME, `m:${LIVE}`).slice(-2), false, ''],
      [sync.familyTxnId(ME, `m:${DELETED_SENT}`).slice(-2), true, ''],
      [sync.familyTxnId(ME, `m:${DELETED_UNSENT}`).slice(-2), false, ''],
    ]);
    expect(moved.profile).toEqual({ name: 'مریم', updatedAt: NOW - 9000 });
  });
});

describe('the first load after the upgrade', () => {
  async function seedOldCompanion(): Promise<{ key: CryptoKey; space: string }> {
    const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    const old = { base: 'https://sync.test', token: `${HID}.${'b'.repeat(64)}`, issuedAt: NOW - 1000, device: DEVICE, member: ME, name: 'مریم', scope: `family:${HID}`, key };
    const space = db.partition(old);
    const other = db.partition({ ...old, token: `${'c'.repeat(32)}.x`, scope: 'family:other' });
    const handle = await db.open();
    const tx = handle.transaction(['meta', 'records_v2', 'outbox_v2'], 'readwrite');
    const put = (store: string, r: StoredRecord, at = space) => tx.objectStore(store).put({ ...r, partition: at, recordKind: r.kind, at: -1, target: '' });
    put('records_v2', own(LIVE));
    put('records_v2', own(DELETED_UNSENT, { deleted: true, value: { v: 1, id: sync.familyTxnId(ME, `m:${DELETED_UNSENT}`), deleted: true } }));
    put('outbox_v2', own(DELETED_UNSENT, { deleted: true }));
    put('records_v2', own('elsewhere'), other);
    tx.objectStore('meta').put(old, 'session');
    tx.objectStore('meta').put(old, `saved-session:${other}`);
    await new Promise<void>((resolve) => { tx.oncomplete = () => resolve(); });
    return { key, space };
  }

  it('moves her rows in once, keeps the old stores, and sends what the old outbox owed', async () => {
    const { key, space } = await seedOldCompanion();
    const pushed: WireRecord[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url.includes('/v1/sync?')) return Response.json({ seq: 0, records: [], hasMore: false });
      if (url.endsWith('/v1/sync')) pushed.push(...(JSON.parse(String(init?.body)) as { records: WireRecord[] }).records);
      return Response.json({ clamped: [] });
    }));
    await state.load();
    await family.loadFamily();
    await vi.waitFor(() => expect(family.familyState().lastSync).not.toBeNull());

    expect(state.row('manual', LIVE)).toMatchObject({ amountRial: -12340, merchant: 'نانوایی' });
    expect(state.pref('name')).toBe('مریم');
    expect(family.familyState()).toMatchObject({ paired: true, memberId: ME, memberName: 'مریم' });
    expect(await db.getMeta('v3-migrated')).toBe(true);
    // Never cleared: the old mirror, its outbox and every other household's rows stay put.
    expect(await db.legacyRecords(space)).toHaveLength(2);
    expect(await db.legacyOutbox(space)).toHaveLength(1);
    expect(await db.legacyRecords(db.partition({ base: 'https://sync.test', token: `${'c'.repeat(32)}.x`, scope: 'family:other', member: ME }))).toHaveLength(1);

    const byId = new Map(pushed.map((r) => [r.id, r]));
    const live = byId.get(sync.familyTxnId(ME, `m:${LIVE}`))!;
    expect(live).toMatchObject({ kind: 'transaction', deleted: false });
    expect(live.updatedAt).toBeGreaterThan(NOW - 5000);
    const plain = JSON.parse((await (await import('../src/crypto')).openSealed(key, live.nonce, live.body, sync.recordAad(`family:${HID}`, live.kind, live.id)))!);
    expect(plain).toMatchObject({ amountRial: 12340, direction: 'out', merchant: 'نانوایی', sourceKind: 'manual' });
    expect(byId.get(sync.familyTxnId(ME, `m:${DELETED_UNSENT}`))).toMatchObject({ deleted: true });
    expect(byId.get(`member:${ME}`)).toMatchObject({ kind: 'member', ownerMemberId: ME });
  });
});

describe('a scanned invite on a browser already in a household', () => {
  // Pinned word for word on both sides (FamilySyncTest holds the same strings).
  const OWN = 'این گوشی دیگه عضو این خانواده نیست. با پیوستن دوباره، دفتر مشترک روی این گوشی از نو شروع می‌شه. تراکنش‌های خود این گوشی سر جاشون می‌مونن و با همون اسم قبلی وارد می‌شی.';
  const OTHER = 'این کد مال یک خانواده دیگه‌ست. با پیوستن، خانواده قبلی روی این گوشی کنار می‌ره: موارد مشترک اعضای قبلی دیگه به‌روز نمی‌شن و دفتر مشترک از نو شروع می‌شه. تراکنش‌های خود این گوشی سر جاشون می‌مونن و با همون اسم قبلی وارد می‌شی.';
  const link = (hid: string) => `#hid=${hid}&pair=code&scope=family:${hid}&k=${'A'.repeat(43)}`;
  beforeEach(async () => {
    const crypt = await import('../src/crypto');
    const { key, raw } = await crypt.generateKey();
    await db.setMeta('session', { base: 'https://sync.test', token: `${HID}.${'b'.repeat(64)}`, issuedAt: NOW, device: DEVICE, member: ME, scope: `family:${HID}`, key, raw: crypt.toBase64Url(raw) });
  });

  it('does not call her own household another family when it no longer answers to this browser', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 401 })));
    await family.acceptPairing(link(HID));
    expect(family.familyState().pendingRejoin?.hid).toBe(HID);
    expect(family.familyState().rejoinSameHousehold).toBe(true);
    expect(family.rejoinLead(true)).toBe(OWN);
  });

  it('says another household is another family', async () => {
    vi.stubGlobal('fetch', vi.fn());
    await family.acceptPairing(link('c'.repeat(32)));
    expect(family.familyState().rejoinSameHousehold).toBe(false);
    expect(family.rejoinLead(false)).toBe(OTHER);
  });
});

describe('what the family screen reads', () => {
  it('counts each member\'s rows, leaving duplicates out', () => {
    const e = (owner: string, duplicate = false) => ({ ownerMemberId: owner, duplicate }) as import('../src/model').LedgerEntry;
    expect(family.contributionsOf([e(ME), e(ME), e(THEM), e(THEM, true)])).toEqual({ [ME]: 2, [THEM]: 1 });
  });
});

describe('phones that hold a key and have no profile', () => {
  it('shows each as unnamed until it is removed — never this browser, never a living profile', async () => {
    const crypt = await import('../src/crypto');
    const { key, raw } = await crypt.generateKey();
    await db.setMeta('session', { base: 'https://sync.test', token: `${HID}.${'b'.repeat(64)}`, issuedAt: NOW, device: DEVICE, member: ME, scope: `family:${HID}`, key, raw: crypt.toBase64Url(raw) });
    const SILENT = '4'.repeat(32);
    const BURIED = '5'.repeat(32);
    const joined = NOW - 86_400_000;
    const removed: unknown[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/v1/devices')) {
        return Response.json({ devices: [ME, THEM, BURIED, SILENT, SILENT, 'not-an-id'].map((memberId) => ({ memberId, addedAt: joined })) });
      }
      if (url.endsWith('/v1/remove')) { removed.push(JSON.parse(String(init?.body))); return Response.json({ revoked: SILENT, seq: 1, clamped: [] }); }
      if (url.includes('/v1/sync?')) return Response.json({ seq: 0, records: [], hasMore: false });
      return Response.json({ clamped: [] });
    }));
    await state.load();
    await family.loadFamily();
    state.put('familyMembers', { id: THEM, name: 'مامان', sharesSms: false, avatar: '', updatedAt: NOW, deleted: false });
    // A profile buried while its phone still holds a key is exactly the one to see.
    state.put('familyMembers', { id: BURIED, name: 'رفته', sharesSms: false, avatar: '', updatedAt: NOW, deleted: true });
    expect(family.familyState().unnamed).toEqual([]);

    await family.loadDevices();
    expect(family.familyState().unnamed.map((d) => d.memberId)).toEqual([BURIED, SILENT]);
    expect(await family.removeMember(SILENT)).toBe(true);
    expect(removed).toEqual([expect.objectContaining({ member: SILENT })]);
    expect(family.familyState().unnamed.map((d) => d.memberId)).toEqual([BURIED]);
  });
});

describe('an invite scanned off a photo', () => {
  it('reads back the link the QR was drawn from, and nothing from a photo without one', async () => {
    const { encode } = await import('uqr');
    const { readPairing, toBase64Url } = await import('../src/crypto');
    // pairingUrl's shape.
    const shown = `https://sync.test/join#url=${encodeURIComponent('https://sync.test')}&hid=${HID}&pair=code123` +
      `&scope=${encodeURIComponent(`family:${HID}`)}&k=${toBase64Url(new Uint8Array(32))}`;
    // InviteCode's settings, each module four pixels wide, dark on white.
    const { data } = encode(shown, { ecc: 'M', border: 2 });
    const px = 4, side = data.length * px;
    const rgba = new Uint8ClampedArray(side * side * 4).fill(255);
    for (let y = 0; y < side; y++) for (let x = 0; x < side; x++) {
      if (data[Math.floor(y / px)][Math.floor(x / px)]) rgba.fill(0, (y * side + x) * 4, (y * side + x) * 4 + 3);
    }
    const scanned = await family.decodeQr(rgba, side, side);
    expect(scanned).toBe(shown);
    expect(readPairing(scanned!.slice(scanned!.indexOf('#') + 1))).not.toBeNull();
    expect(await family.decodeQr(new Uint8ClampedArray(64 * 48 * 4).fill(255), 64, 48)).toBeNull();
  });
});
