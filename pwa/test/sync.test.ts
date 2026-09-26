import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { createHash } from 'node:crypto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { LedgerEntry, ManualTxn, Txn } from '../src/model';
import type { Session, WireRecord } from '../src/sync';

/**
 * The browser as a member of a household, held to what Sync.kt does on the wire: the phone's
 * payloads as Kotlin writes them (defaults left out), the phone's rules on arrival, and the
 * phone's bookkeeping on the way out.
 */

let state: typeof import('../src/state');
let sync: typeof import('../src/sync');
let crypt: typeof import('../src/crypto');
let db: typeof import('../src/db');
let derived: typeof import('../src/derived');
let session: Session;

const HID = 'a'.repeat(32);
const ME = '1'.repeat(32);
const MY_DEVICE = '2'.repeat(32);
const THEM = '3'.repeat(32);
const THEIR_DEVICE = '4'.repeat(32);
const NOW = Date.UTC(2026, 8, 20, 9, 0);

beforeEach(async () => {
  vi.resetModules(); vi.unstubAllGlobals(); globalThis.indexedDB = new IDBFactory();
  state = await import('../src/state'); sync = await import('../src/sync'); crypt = await import('../src/crypto');
  db = await import('../src/db'); derived = await import('../src/derived');
  await state.load();
  const { key, raw } = await crypt.generateKey();
  session = { base: 'https://sync.test', token: `${HID}.${'b'.repeat(64)}`, issuedAt: NOW, device: MY_DEVICE, member: ME, scope: `family:${HID}`, key, raw: crypt.toBase64Url(raw) };
  await db.setMeta('session', session);
  derived.setMineId(ME);
});

async function record(id: string, kind: string, plain: string, extra: Partial<WireRecord> = {}): Promise<WireRecord> {
  const { nonce, body } = await crypt.seal(session.key, plain);
  return { id, scope: session.scope, updatedAt: NOW - 1000, device: THEIR_DEVICE, kind, ownerMemberId: THEM, authorMemberId: THEM, deleted: false, nonce, body, ...extra };
}
async function apply(r: WireRecord): Promise<boolean> {
  return sync.applyRecord(session, r, await crypt.openSealed(session.key, r.nonce, r.body), NOW);
}

describe('reading what a phone sends', () => {
  it('fills in the Kotlin defaults a member record leaves out', async () => {
    // What `Json { ignoreUnknownKeys = true }` writes for SyncMemberPayload(memberId, name, false):
    // no kind, no avatar.
    const plain = JSON.stringify({ memberId: THEM, name: 'علی', sharesSms: false });
    expect(sync.parseMember(plain)).toEqual({ memberId: THEM, name: 'علی', sharesSms: false, avatar: '' });
    expect(await apply(await record(`member:${THEM}`, 'member', plain))).toBe(true);
    expect(state.row('familyMembers', THEM)).toMatchObject({ name: 'علی', avatar: '', sharesSms: false, deleted: false });
  });

  it('reads a manual transaction with no kind, sourceKind, bank or categoryKind as the defaults', async () => {
    const localRef = 'm:0190d1a2-0000-7000-8000-000000000000';
    const id = sync.familyTxnId(THEM, localRef);
    const plain = JSON.stringify({
      ownerMemberId: THEM, at: NOW - 5000, amountRial: 125000, direction: 'out',
      categoryId: 'cat_food', categoryName: 'خوراک', categoryEditorId: THEM, categoryUpdatedAt: NOW - 6000, merchant: 'نانوایی',
    });
    expect(sync.parseEntry(plain)).toMatchObject({ kind: 'transaction', sourceKind: 'manual', bank: 'MANUAL', categoryKind: 'expense', transfer: false });
    expect(await apply(await record(id, 'transaction', plain))).toBe(true);
    expect(state.row('familyTxns', id)).toMatchObject({ ownerMemberId: THEM, sourceKind: 'manual', bank: 'MANUAL', amountRial: -125000, merchant: 'نانوایی' });
    expect(state.row('categories', 'cat_food')).toMatchObject({ nameFa: 'خوراک', kind: 'expense', sort: 500 });
    // Their filing lands as a decision on the family row, the way txn_decision holds it.
    const ref = derived.familyLocalRef(id);
    expect(state.row('decisions', `category:${ref}`)).toMatchObject({ ref, kind: 'category', value: 'cat_food', memberId: THEM, familyRef: id, updatedAt: NOW - 6000 });
    // And the ledger shows it as theirs.
    const entry = derived.ledger().allEntries.find((e) => e.txn.familyRef === id);
    expect(entry).toMatchObject({ ownerMemberId: THEM, categoryId: 'cat_food' });
  });

  it('lands somebody else\'s note on my own row, and a blank one as taken back', async () => {
    const mine = sync.familyTxnId(ME, 'm:abc');
    const note = (text: string, at: number) => record(`note:${crypt.hexOf(new Uint8Array([1]))}`, 'note',
      JSON.stringify({ target: mine, note: text, editedByMemberId: THEM }), { updatedAt: at, ownerMemberId: ME });
    expect(await apply(await note('کادوی تولد', NOW - 100))).toBe(true);
    expect(state.row('decisions', 'note:m:abc')).toMatchObject({ value: 'کادوی تولد', memberId: THEM, familyRef: mine, deleted: false });
    expect(await apply(await note('', NOW - 50))).toBe(true);
    expect(state.row('decisions', 'note:m:abc')).toMatchObject({ value: '', deleted: true });
    // The older copy coming back round does not resurrect it.
    expect(await apply(await note('کادوی تولد', NOW - 100))).toBe(false);
  });

  it('skips a record from this very device and one it cannot parse', async () => {
    const plain = JSON.stringify({ memberId: THEM, name: 'علی', sharesSms: false });
    expect(await apply(await record(`member:${THEM}`, 'member', plain, { device: MY_DEVICE }))).toBe(false);
    expect(await apply(await record(`member:${THEM}`, 'member', '{"memberId":1}'))).toBe(false);
    expect(sync.applyRecord(session, await record(`member:${THEM}`, 'member', plain), null, NOW)).toBe(false);
    expect(state.row('familyMembers', THEM)).toBeUndefined();
  });
});

describe('last write wins', () => {
  it('breaks a same-millisecond draw by the editor id, whichever arrives first', async () => {
    expect(sync.syncedEditWins(10, 'b', 10, 'c')).toBe(true);
    expect(sync.syncedEditWins(10, 'c', 10, 'b')).toBe(false);
    expect(sync.syncedEditWins(11, 'a', 10, 'z')).toBe(false);
    expect(sync.categoryUpdateWins(0, 'x', 0, 'x')).toBe(true);
    expect(sync.categoryUpdateWins(5, 'x', 5, 'x')).toBe(false);

    const target = sync.familyTxnId(THEM, 'm:1');
    const filing = (author: string, categoryId: string) => record(`category:x`, 'category', JSON.stringify({
      target, categoryId, categoryName: categoryId, categoryKind: 'expense', editedByMemberId: author,
    }), { updatedAt: NOW - 10, authorMemberId: author });
    const ref = derived.familyLocalRef(target);
    const low = '5'.repeat(32); const high = '6'.repeat(32);
    await apply(await filing(high, 'cat_high')); await apply(await filing(low, 'cat_low'));
    expect(state.row('decisions', `category:${ref}`)?.value).toBe('cat_high');
  });

  it('clamps a stamp from the far future to two days ahead', async () => {
    const plain = JSON.stringify({ memberId: THEM, name: 'علی', sharesSms: false });
    expect(sync.clampSyncStamp(NOW + 10 * 86_400_000, NOW)).toBe(NOW + sync.MAX_SYNC_STAMP_SKEW_MS);
    const fetch = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.includes('/v1/sync?')) return Response.json({ seq: 1, records: [await record(`member:${THEM}`, 'member', plain, { updatedAt: NOW + 10 * 86_400_000 })], hasMore: false });
      return Response.json(init?.method === 'POST' && url.endsWith('/v1/sync') ? { clamped: [] } : {});
    });
    vi.stubGlobal('fetch', fetch);
    await sync.syncNow(session, NOW);
    expect(state.row('familyMembers', THEM)?.updatedAt).toBe(NOW + sync.MAX_SYNC_STAMP_SKEW_MS);
  });
});

describe('deletes', () => {
  it('believes a tombstone only when the sealed body names the record it arrived on', async () => {
    const id = sync.familyTxnId(THEM, 'm:1');
    await apply(await record(id, 'transaction', JSON.stringify({ at: NOW - 9000, amountRial: 1000, direction: 'in' }), { updatedAt: NOW - 9000 }));
    const aimedElsewhere = await record(id, 'transaction', JSON.stringify({ v: 1, id: 'txn:other', deleted: true }), { deleted: true });
    const contentless = await record(id, 'transaction', JSON.stringify({ deleted: true }), { deleted: true });
    expect(await apply(aimedElsewhere)).toBe(false);
    expect(await apply(contentless)).toBe(false);
    expect(state.row('familyTxns', id)?.deleted).toBe(false);
    expect(await apply(await record(id, 'transaction', JSON.stringify({ v: 1, id, deleted: true }), { deleted: true }))).toBe(true);
    expect(state.row('familyTxns', id)?.deleted).toBe(true);
  });

  it('never erases this device\'s own member on a server\'s word', async () => {
    const id = `member:${ME}`;
    expect(await apply(await record(id, 'member', JSON.stringify({ v: 1, id, deleted: true }), { deleted: true, ownerMemberId: ME }))).toBe(false);
  });
});

const txn = (ref: string, signed: number, over: Partial<Txn> = {}): Txn => ({
  ...derived.manualToRow({ id: ref.slice(2), at: NOW - 60_000, day: 0, amountRial: signed, accountId: null, categoryId: null, merchant: 'بقالی', note: '', createdAt: 0, updatedAt: 0, deleted: false } as ManualTxn),
  ref, ...over,
});
const entry = (t: Txn, over: Partial<LedgerEntry> = {}): LedgerEntry => ({
  txn: t, categoryId: 'cat_uncategorised', categoryFa: 'دسته‌بندی نشده', confidence: 0, needsReview: true, duplicate: false, transfer: false,
  ownerMemberId: t.ownerMemberId || ME, ownerName: '', ownerAvatar: '', categoryEditorName: '', note: '', noteAuthorName: '', sharedWithFamily: true, ...over,
});

describe('publishing', () => {
  const input = (entries: LedgerEntry[], over: Partial<import('../src/sync').PublishInput> = {}): import('../src/sync').PublishInput => ({
    session, now: NOW, entries,
    member: { id: ME, name: 'مریم', sharesSms: false, avatar: '', updatedAt: NOW - 1, deleted: false },
    publications: [], goals: [], decisions: [], categories: [], shareSms: false, shareAssets: false, excludedBanks: [], assets: null, exclusions: null, ...over,
  });
  const plainOf = async (r: { wire: WireRecord }) => JSON.parse((await crypt.openSealed(session.key, r.wire.nonce, r.wire.body))!);

  it('sends the member, her own shareable rows, and nothing already published unchanged', async () => {
    const own = entry(txn('m:one', -50_000));
    const theirs = entry(txn('f:x', -1, { familyRef: 'txn:x:y', ownerMemberId: THEM }));
    const sms = entry(txn('s:h:0', 70_000, { sourceKind: 'sms', bank: 'MELLI' }));
    const dup = entry(txn('m:dup', -1), { duplicate: true });
    const first = await sync.outgoingRecords(input([own, theirs, sms, dup]));
    expect(first.map((r) => r.wire.kind)).toEqual(['member', 'transaction']);
    const [member, published] = first;
    expect(await plainOf(member)).toEqual({ kind: 'member', memberId: ME, name: 'مریم', sharesSms: false, avatar: '' });
    expect(published.wire).toMatchObject({ id: sync.familyTxnId(ME, 'm:one'), ownerMemberId: ME, updatedAt: NOW, deleted: false });
    expect(await plainOf(published)).toMatchObject({ ownerMemberId: ME, sourceKind: 'manual', amountRial: 50_000, direction: 'out', categoryEditorId: ME });
    expect(published.publication?.contentHash).toBe(createHash('sha256').update(JSON.stringify(await plainOf(published))).digest('hex'));

    const again = await sync.outgoingRecords(input([own, theirs, sms], { publications: [published.publication!] }));
    expect(again.map((r) => r.wire.kind)).toEqual(['member']);

    // SMS sharing on: the pasted row goes too; its bank set aside: it does not.
    expect((await sync.outgoingRecords(input([sms], { shareSms: true }))).map((r) => r.wire.kind)).toEqual(['member', 'transaction']);
    expect((await sync.outgoingRecords(input([sms], { shareSms: true, excludedBanks: ['MELLI'] }))).map((r) => r.wire.kind)).toEqual(['member']);
  });

  it('re-sends a changed row under a later stamp, and tombstones one that left the set', async () => {
    const id = sync.familyTxnId(ME, 'm:one');
    const previous = { id, sourceKind: 'manual', contentHash: 'stale', updatedAt: NOW + 5, deleted: false };
    const [, changed] = await sync.outgoingRecords(input([entry(txn('m:one', -1))], { publications: [previous] }));
    expect(changed.wire.updatedAt).toBe(NOW + 6);
    const [, gone] = await sync.outgoingRecords(input([], { publications: [previous] }));
    expect(gone.wire).toMatchObject({ id, kind: 'transaction', deleted: true, updatedAt: NOW + 6 });
    expect(await plainOf(gone)).toEqual({ v: 1, id, deleted: true });
    expect(gone.publication).toMatchObject({ deleted: true, contentHash: '' });
    // Other kinds answer for themselves: a stray goal publication is never swept as a transaction.
    const goal = { id: 'goal:g', sourceKind: 'goal', contentHash: 'x', updatedAt: 1, deleted: false };
    expect((await sync.outgoingRecords(input([], { publications: [goal] }))).map((r) => r.wire.kind)).toEqual(['member']);
  });

  it('publishes her category and note answers under the household\'s name for the row', async () => {
    const target = sync.familyTxnId(THEM, 'm:theirs');
    const ref = derived.familyLocalRef(target);
    const theirs = entry(txn(ref, -1, { familyRef: target, ownerMemberId: THEM }));
    const category = { id: 'cat_food', parentId: null, nameFa: 'خوراک', kind: 'expense' as const, sort: 1, builtin: true, archived: false, updatedAt: 0, glyph: '' };
    const decisions = [
      { id: `category:${ref}`, ref, kind: 'category' as const, value: 'cat_food', createdAt: 1, updatedAt: NOW - 7, deleted: false, memberId: ME, familyRef: target },
      { id: `note:${ref}`, ref, kind: 'note' as const, value: 'برای مهمونی', createdAt: 1, updatedAt: NOW - 3, deleted: false, memberId: ME, familyRef: target },
      // Somebody else's answer stays theirs.
      { id: 'note:m:x', ref: 'm:x', kind: 'note' as const, value: 'نه', createdAt: 1, updatedAt: 1, deleted: false, memberId: THEM, familyRef: '' },
    ];
    const out = await sync.outgoingRecords(input([theirs], { decisions, categories: [category] }));
    const [filing, note] = out.slice(1);
    expect(filing.wire).toMatchObject({ kind: 'category', ownerMemberId: THEM, updatedAt: NOW - 7 });
    expect(await plainOf(filing)).toMatchObject({ target, categoryId: 'cat_food', editedByMemberId: ME });
    expect(note.wire).toMatchObject({ kind: 'note', ownerMemberId: THEM, updatedAt: NOW - 3 });
    expect(await plainOf(note)).toEqual({ kind: 'note', target, note: 'برای مهمونی', editedByMemberId: ME });
    expect(out).toHaveLength(3);
  });

  it('holds a received goal at its fixed point, so it is never echoed back', async () => {
    const payload = JSON.stringify({ goalId: 'g1', nameFa: 'خرج ماه', targetRial: 5_000_000, goalKind: 'cap', period: 'jmonth', startsOn: 20_000, createdAt: 1, ownerMemberId: THEM, editedByMemberId: THEM });
    expect(await apply(await record('goal:g1', 'goal', payload))).toBe(true);
    const goal = state.row('goals', 'g1')!;
    expect(goal).toMatchObject({ shared: true, categoryId: null, endsOn: null, ownerMemberId: THEM });
    const out = await sync.outgoingRecords(input([], { goals: [goal], publications: state.rows('publications') }));
    expect(out.map((r) => r.wire.kind)).toEqual(['member']);
  });

  it('lands the household\'s report exclusions on the setting every figure reads', async () => {
    const payload = JSON.stringify({ categoryIds: ['cat_b', 'cat_a'], editedByMemberId: THEM });
    expect(await apply(await record(sync.REPORT_EXCLUSIONS_RECORD_ID, 'exclusion', payload))).toBe(true);
    expect(state.pref('reportExcluded')).toEqual(['cat_a', 'cat_b']);
    expect(sync.syncPref('reportExclusions')).toMatchObject({ ids: ['cat_a', 'cat_b'], editedByMemberId: THEM });
    const out = await sync.outgoingRecords(input([], { exclusions: sync.syncPref('reportExclusions'), publications: state.rows('publications') }));
    expect(out.map((r) => r.wire.kind)).toEqual(['member']);
  });
});

describe('syncNow', () => {
  interface Server { pushes: WireRecord[][]; pulls: string[] }
  function serve(pages: unknown[], onPush: (records: WireRecord[]) => Response = () => Response.json({ clamped: [] })): Server {
    const server: Server = { pushes: [], pulls: [] };
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/v1/identity')) return Response.json({});
      if (url.includes('/v1/sync?')) { server.pulls.push(url); return Response.json(pages.shift() ?? { seq: 0, records: [], hasMore: false }); }
      if (url.endsWith('/v1/sync')) {
        const records = (JSON.parse(String(init?.body)) as { records: WireRecord[] }).records;
        server.pushes.push(records);
        return onPush(records);
      }
      return new Response('', { status: 404 });
    }));
    return server;
  }
  const manual = (i: number): ManualTxn => ({
    id: `0190d1a2-0000-7000-8000-${String(i).padStart(12, '0')}`, at: NOW - i * 1000, day: 0, amountRial: -(i + 1) * 1000,
    accountId: null, categoryId: null, merchant: `مورد ${i}`, note: '', createdAt: NOW, updatedAt: NOW, deleted: false,
  });

  it('skips a record it cannot open instead of failing the pull, and moves the cursor on', async () => {
    const good = await record(`member:${THEM}`, 'member', JSON.stringify({ memberId: THEM, name: 'علی', sharesSms: true }));
    const foreign = { ...good, id: 'member:x', nonce: crypt.toBase64(new Uint8Array(12)), body: 'AAAA' };
    const server = serve([
      { seq: 1000, records: [foreign], hasMore: true, primaryMemberId: THEM },
      { seq: 1001, records: [good], hasMore: false },
    ]);
    const result = await sync.syncNow(session, NOW);
    expect(result.received).toBe(1);
    expect(server.pulls).toEqual([`https://sync.test/v1/sync?since=0&limit=1000`, `https://sync.test/v1/sync?since=1000&limit=1000`]);
    expect(sync.syncPref('syncSeq')).toBe(1001);
    expect(sync.syncPref('syncPrimaryMember')).toBe(THEM);
    expect(state.row('familyMembers', THEM)?.sharesSms).toBe(true);
  });

  it('pushes per kind in chunks of 200, skips a kind an old server refuses, and keeps its clamped stamps', async () => {
    state.putAll('manual', Array.from({ length: 450 }, (_, i) => manual(i)));
    sync.setSyncPref('syncShareAssets', true);
    const clampedId = sync.familyTxnId(ME, `m:${manual(0).id}`);
    const server = serve([], (records) => {
      if (records[0].kind === 'asset') return Response.json({ code: 'invalid_kind' }, { status: 400 });
      return Response.json({ clamped: records.some((r) => r.id === clampedId) ? [{ id: clampedId, updatedAt: 42 }] : [] });
    });
    const result = await sync.syncNow(session, NOW, [{ name: 'طلا', toman: 1000 }]);
    expect(server.pushes.map((p) => `${p[0].kind}:${p.length}`)).toEqual(['member:1', 'transaction:200', 'transaction:200', 'transaction:50', 'asset:1']);
    expect(result).toMatchObject({ sent: 451, unsupportedKinds: ['asset'] });
    expect(state.row('publications', clampedId)?.updatedAt).toBe(42);
    expect(state.row('publications', `asset:${ME}`)).toBeUndefined();
    // Nothing changed, so the next sync sends the profile alone.
    serve([]);
    expect((await sync.syncNow(session, NOW + 1)).sent).toBe(1);
  });

  it('does nothing for a session that is no longer the stored household', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    expect(await sync.syncNow({ ...session, token: `${'c'.repeat(32)}.x` }, NOW)).toEqual({ sent: 0, received: 0, unsupportedKinds: [] });
    expect(fetch).not.toHaveBeenCalled();
  });

  it('recovers a rotation the server applied before its answer was lost', async () => {
    const secret = 'e'.repeat(64);
    await db.setMeta('pending-rotation', { oldToken: session.token, newToken: `${HID}.${secret}`, startedAt: NOW }, db.partition(session));
    let rotations = 0;
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/rotate')) return ++rotations === 1 ? new Response('', { status: 401 }) : Response.json({ secret });
      if (url.includes('/v1/sync?')) return Response.json({ seq: 0, records: [], hasMore: false, rotationClientSecret: true });
      return Response.json({});
    }));
    await sync.syncNow(session, NOW);
    expect(rotations).toBe(2);
    expect((await sync.loadSession())?.token).toBe(`${HID}.${secret}`);
    expect(await db.getMeta('pending-rotation', db.partition(session))).toBeUndefined();
  });
});

describe('the household', () => {
  it('builds the invite URL the phone builds, byte for byte', () => {
    // Bytes whose standard base64 carries '+', '/' and padding, so every rule is exercised.
    const raw = new Uint8Array(32).fill(0xfb); raw[31] = 0xff;
    const s = { ...session, base: 'https://sync.muchtoman.com', raw: crypt.toBase64Url(raw) };
    const code = '0123456789abcdef0123456789abcdef';
    const url = sync.pairingUrl(s, code);
    expect(crypt.toBase64(raw)).toMatch(/[+/]/);
    expect(url).toBe(`https://sync.muchtoman.com/join#url=https%3A%2F%2Fsync.muchtoman.com&hid=${HID}&pair=${code}` +
      `&scope=family%3A${HID}&k=${Buffer.from(raw).toString('base64url')}`);
    expect(url).not.toContain('=&');
    const read = crypt.readPairing(url.slice(url.indexOf('#')));
    expect(read).toMatchObject({ url: 'https://sync.muchtoman.com', hid: HID, code, scope: `family:${HID}` });
    expect([...read!.key]).toEqual([...raw]);
    expect(() => sync.pairingUrl({ ...s, raw: undefined }, code)).toThrow(sync.NoInviteKeyError);
  });

  it('tells a join from her own household from a different one', () => {
    expect(sync.pairingCase(null, HID)).toBe('JOIN');
    expect(sync.pairingCase(session.token, HID)).toBe('SAME_HOUSEHOLD');
    expect(sync.pairingCase(session.token, 'f'.repeat(32))).toBe('REJOIN');
  });

  it('creates a household with a fresh 16-byte id, its own key, and her as its first member', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      requests.push(url);
      expect(JSON.parse(String(init?.body))).toMatchObject({ scopes: [expect.stringMatching(/^family:[0-9a-f]{32}$/)] });
      return Response.json({ secret: 'c'.repeat(64) });
    }));
    const claimed = await sync.claimHousehold('https://sync.test/', 'سارا');
    const hid = claimed.token.split('.')[0];
    expect(hid).toMatch(/^[0-9a-f]{32}$/);
    expect(requests).toEqual([`https://sync.test/v1/claim?hid=${hid}`]);
    expect(claimed).toMatchObject({ base: 'https://sync.test', scope: `family:${hid}` });
    expect(crypt.fromBase64Url(claimed.raw!).byteLength).toBe(32);
    expect(state.row('familyMembers', claimed.member)).toMatchObject({ name: 'سارا', sharesSms: false });
    expect((await sync.loadSession())?.token).toBe(claimed.token);
  });

  it('renews into a new household, burying the old one\'s rows and keeping her own goals private', async () => {
    state.put('familyMembers', { id: ME, name: 'مریم', sharesSms: true, avatar: '', updatedAt: 1, deleted: false });
    state.put('familyMembers', { id: THEM, name: 'علی', sharesSms: true, avatar: '', updatedAt: 1, deleted: false });
    state.put('familyTxns', { id: 'txn:x:y', ownerMemberId: THEM, sourceKind: 'manual', at: 1, day: 1, amountRial: -1, bank: 'MANUAL', merchant: '', updatedAt: 1, deleted: false, transfer: false });
    state.put('publications', { id: 'txn:1:2', sourceKind: 'manual', contentHash: 'h', updatedAt: 9, deleted: false });
    const goal = { id: 'g', nameFa: 'x', targetRial: 1, kind: 'cap' as const, categoryId: null, period: 'jmonth' as const, startsOn: 1, endsOn: null, createdAt: 1, updatedAt: 1, deleted: false, shared: true, ownerMemberId: ME, editedByMemberId: ME };
    state.putAll('goals', [goal, { ...goal, id: 'h', ownerMemberId: THEM }]);
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ secret: 'c'.repeat(64) })));
    const renewed = await sync.renewHousehold();
    expect(renewed).toMatchObject({ member: ME, device: MY_DEVICE });
    expect(renewed.token.split('.')[0]).not.toBe(HID);
    expect(state.row('familyMembers', ME)).toMatchObject({ deleted: false, sharesSms: false });
    expect(state.row('familyMembers', THEM)?.deleted).toBe(true);
    expect(state.row('familyTxns', 'txn:x:y')?.deleted).toBe(true);
    expect(state.row('publications', 'txn:1:2')).toMatchObject({ deleted: true, updatedAt: 9 });
    expect(state.row('goals', 'g')).toMatchObject({ shared: false, deleted: false });
    expect(state.row('goals', 'h')?.deleted).toBe(true);
  });

  it('leaves through the server first, then buries the household and forgets the session', async () => {
    const bodies: unknown[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/v1/leave')) bodies.push(JSON.parse(String(init?.body)));
      return Response.json({});
    }));
    await sync.leaveFamily(session);
    const tombstone = (bodies[0] as { record: WireRecord }).record;
    expect(tombstone).toMatchObject({ id: `member:${ME}`, kind: 'member', ownerMemberId: ME, deleted: true });
    expect(JSON.parse((await crypt.openSealed(session.key, tombstone.nonce, tombstone.body))!)).toEqual({ v: 1, id: `member:${ME}`, deleted: true });
    expect(await sync.loadSession()).toBeNull();
  });
});

it('hashes UTF-8 the way the phone does', async () => {
  const { sha256Hex } = await import('../src/sms');
  for (const s of ['', 'txn:abc', 'خوراک'.repeat(40)]) expect(sha256Hex(s)).toBe(createHash('sha256').update(s).digest('hex'));
});
