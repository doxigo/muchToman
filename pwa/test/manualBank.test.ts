import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { LedgerEntry, ManualTxn, Txn } from '../src/model';
import type { Session } from '../src/sync';
import { BUILTIN_CATEGORIES, BUILTIN_RULES } from '../src/rules';

/** Derived.kt `bankOfRow` and the three family gates that read it: a typed row follows its account, not "MANUAL". */

let sync: typeof import('../src/sync');
let crypt: typeof import('../src/crypto');
let db: typeof import('../src/db');
let derived: typeof import('../src/derived');
let state: typeof import('../src/state');
let session: Session;

const HID = 'a'.repeat(32);
const ME = '1'.repeat(32);
const MY_DEVICE = '2'.repeat(32);
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

const typed = (id: string, accountId: string | null): ManualTxn =>
  ({ id, at: NOW - 60_000, day: 0, amountRial: -5_000, accountId, categoryId: null, merchant: 'بقالی', note: '', createdAt: 0, updatedAt: 0, deleted: false });
const row = (id: string, accountId: string | null): Txn => derived.manualToRow(typed(id, accountId));
const entry = (t: Txn): LedgerEntry => ({
  txn: t, categoryId: 'cat_uncategorised', categoryFa: 'دسته‌بندی نشده', confidence: 0, needsReview: true, duplicate: false, transfer: false,
  ownerMemberId: ME, ownerName: '', ownerAvatar: '', categoryEditorName: '', note: '', noteAuthorName: '', sharedWithFamily: true,
});
const note = { id: 'note:m:a', ref: 'm:a', kind: 'note' as const, value: 'نه', createdAt: 1, updatedAt: 1, deleted: false, memberId: ME, familyRef: '' };

describe('a typed row is at the bank of its account', () => {
  it('reads the account, and leaves other rows alone', () => {
    expect(derived.bankOfRow(row('a', 'MELLAT'))).toBe('MELLAT');
    expect(derived.bankOfRow(row('a', 'MELLAT:0457'))).toBe('MELLAT');
    expect(derived.bankOfRow(row('a', null))).toBe('MANUAL');
    expect(derived.bankOfRow({ ...row('a', null), ref: 's:h:0', bank: 'SAMAN', accountId: 'SAMAN:1' })).toBe('SAMAN');
  });

  it('keeps a note on a set-aside bank at home, and cash free to go', () => {
    const set = new Set(['MELLAT']);
    expect(sync.decisionMayLeave(note, row('a', 'MELLAT'), false, ME, set)).toBe(false);
    expect(sync.decisionMayLeave(note, row('a', 'MELLAT:0457'), false, ME, set)).toBe(false);
    expect(sync.decisionMayLeave(note, row('a', null), false, ME, set)).toBe(true);
  });

  it('publishes the cash row and not the one on the set-aside bank', async () => {
    const input: import('../src/sync').PublishInput = {
      session, now: NOW, entries: [entry(row('bank', 'MELLAT')), entry(row('cash', null))],
      member: { id: ME, name: 'مریم', sharesSms: false, avatar: '', updatedAt: NOW - 1, deleted: false },
      publications: [], goals: [], decisions: [], categories: [], shareSms: false, shareAssets: false, excludedBanks: ['MELLAT'], assets: null, exclusions: null,
    };
    const txns = (await sync.outgoingRecords(input)).filter((r) => r.wire.kind === 'transaction');
    expect(txns.map((r) => r.wire.id)).toEqual([sync.familyTxnId(ME, 'm:cash')]);
  });

  it('marks only the cash row as shared when the bank is set aside', () => {
    const view = derived.ledgerView({
      sources: [], manual: [typed('bank', 'MELLAT'), typed('cash', null)], familyTxns: [], familyMembers: [], decisions: [], links: [],
      categories: [...BUILTIN_CATEGORIES], rules: [...BUILTIN_RULES], anchors: [], mineId: 'me', sharesSms: false,
      excludedBanks: ['MELLAT'], disabledBanks: [], now: NOW,
    });
    const shared = Object.fromEntries(view.entries.map((e) => [e.txn.ref, e.sharedWithFamily]));
    expect(shared).toEqual({ 'm:bank': false, 'm:cash': true });
  });
});
