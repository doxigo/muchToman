import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { webcrypto } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  bankAccountsOf, bankTotal, deriveBalance, familyLocalRef, familyToRow, ledgerView, manualToRow, startingFrom,
} from '../src/derived';
import type { DeriveInput } from '../src/derived';
import { autoFilePlan } from '../src/filing';
import { jalaliDay, jalaliMonthStart, tehranDay, tehranDayStart } from '../src/jalali';
import type { BalanceAnchor, Decision, FamilyTxn, LedgerEntry, ManualTxn, Source, Txn } from '../src/model';
import { BUILTIN_CATEGORIES, BUILTIN_RULES, CAT_OTHER, CAT_SHOPPING_ID, CAT_UNCATEGORISED } from '../src/rules';
import { AT_FUTURE_SLACK_MS, parseToRows, printedMoment, sha256Hex, sourceId } from '../src/sms';

/** Derived.kt, as DeriveTest.kt, LedgerStartTest.kt and FilingTest.kt pin it, and AppVm's ledger actions. */

const CORPUS = join(__dirname, '../../app/src/test/resources/sms');
interface Case { id: string; sender: string; at?: number; body: string[]; expect: Record<string, unknown> | null }
const cases = (): Case[] => readdirSync(CORPUS).filter((f) => f.endsWith('.json')).sort()
  .flatMap((f) => (JSON.parse(readFileSync(join(CORPUS, f), 'utf8')) as { cases: Case[] }).cases);

let n = 0;
function txn(o: { at: number; signed?: number | null; balance?: number | null; account?: string; ref?: string; inferred?: boolean }): Txn {
  const ref = o.ref ?? `s:${String(n++).padStart(8, '0')}:0`;
  const signed = o.signed ?? null;
  const account = o.account ?? 'SAMAN';
  return {
    ref, srcHash: ref, seq: 0, at: o.at, day: tehranDay(o.at), bank: account, accountId: account,
    direction: signed == null ? null : signed > 0 ? 'in' : 'out',
    amountRial: signed == null ? null : Math.abs(signed), signedRial: signed, balanceRial: o.balance ?? null,
    feeRial: null, mask: '', instrument: 'unknown', merchant: '', merchantNorm: '', refNo: '', printedAt: '',
    channel: 'unknown', unitPrinted: 'none', inferred: o.inferred ?? false, familyRef: '', ownerMemberId: '', sourceKind: 'sms',
  };
}
const anchor = (at: number, rial: number, account = 'SAMAN'): BalanceAnchor =>
  ({ id: `a${at}`, accountId: account, at, balanceRial: rial, source: 'user', createdAt: at, updatedAt: at, deleted: false });
const source = (bank: string, body: string, at: number, id = sha256Hex(`${bank}\u0000${at}\u0000${body.trim()}`)): Source =>
  ({ id, bank, body, at, ingestedAt: 0 });

describe('identity', () => {
  it('hashes exactly as crypto.subtle does, across block boundaries and scripts', async () => {
    for (const input of ['', 'abc', 'a'.repeat(55), 'a'.repeat(56), 'a'.repeat(64), 'بانک سامان\u0000۱۴۰۵'.repeat(9)]) {
      const digest = await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode(input));
      expect(sha256Hex(input)).toBe(Buffer.from(digest).toString('hex'));
    }
  });

  it("matches the phone's pinned message hashes", () => {
    // LedgerTest.kt: sha256(addrKey + NUL + at + NUL + trimmed body), computed outside that codebase.
    expect(sha256Hex('9999920000\u00001\u0000بانک سامان')).toBe('278256bcb1a2ef5ba3dc2951413be77381f974b637d64a3a153a054ffbc9af1a');
    expect(sha256Hex('refah bank\u000042\u0000مانده 80,000,000 ریال')).toBe('8ce70b641e0f2b5dee28dd826ba65a76e7199d55b92265478976e1391d2b9694');
  });

  it('names one pasted message by its bank, its moment and its trimmed body', async () => {
    const body = 'بانک سامان\nواریز 1,000,000 ریال';
    const base = await sourceId('SAMAN', body, 42);
    expect(await sourceId('SAMAN', `\n  ${body}  \n`, 42)).toBe(base);
    expect(await sourceId('BLU', body, 42)).not.toBe(base);
    expect(await sourceId('SAMAN', body, 43)).not.toBe(base);
    expect(await sourceId('SAMAN', body.replace('\n', ' '), 42)).not.toBe(base);
    // NUL between the fields, so no body can slide the boundary and forge another's key.
    expect(await sourceId('SAMAN', '12', 3)).not.toBe(await sourceId('SAMAN', '2', 123));
  });

  it("gives another member's row a compact ref of its own", () => {
    expect(familyLocalRef('txn:m1:abc')).toBe(`f:${sha256Hex('txn:m1:abc')}`);
    const f: FamilyTxn = { id: 'txn:m1:abc', ownerMemberId: 'm1', sourceKind: 'sms', at: 5, day: 0, amountRial: -700, bank: 'BLU', merchant: ' اسنپ ', updatedAt: 1, deleted: false, transfer: false };
    expect(familyToRow(f)).toMatchObject({
      ref: familyLocalRef(f.id), srcHash: f.id, accountId: 'family:m1:BLU', direction: 'out', amountRial: 700, signedRial: -700,
      merchantNorm: 'اسنپ', familyRef: f.id, ownerMemberId: 'm1', sourceKind: 'sms',
    });
    const m: ManualTxn = { id: 'u1', at: 5, day: 0, amountRial: 900, accountId: null, categoryId: null, merchant: '', note: '', createdAt: 0, updatedAt: 0, deleted: false };
    expect(manualToRow(m)).toMatchObject({ ref: 'm:u1', bank: 'MANUAL', accountId: 'MANUAL', direction: 'in', signedRial: 900, sourceKind: 'manual' });
  });
});

describe('reading pasted messages', () => {
  it('turns every corpus message that parses into exactly one row, the same way twice', () => {
    const sources = cases().map((c) => source('SAMAN', c.body.join('\n'), c.at ?? 1));
    const once = sources.flatMap((s) => parseToRows(s, 1e12));
    expect(once.length).toBeGreaterThanOrEqual(25);
    expect(sources.flatMap((s) => parseToRows(s, 1e12))).toEqual(once);
    expect(new Set(once.map((r) => r.ref)).size).toBe(once.length);
    for (const r of once) {
      expect(r.ref).toBe(`s:${r.srcHash}:0`);
      expect(r.day).toBe(tehranDay(r.at));
      expect(r.signedRial != null).toBe(r.direction != null && r.amountRial != null);
      if (r.signedRial != null) expect(r.signedRial).toBe(r.direction === 'in' ? r.amountRial : -r.amountRial!);
    }
  });

  it('declines a message the phone declines for what it says', () => {
    for (const id of ['rejected-otp-from-a-real-bank-number', 'rejected-wallet-promo-from-a-real-bank-number']) {
      const c = cases().find((x) => x.id === id)!;
      expect(parseToRows(source('SAMAN', c.body.join('\n'), 1)), id).toEqual([]);
    }
    // Neither a direction nor a balance: nothing happened.
    expect(parseToRows(source('SAMAN', 'مبلغ 5,000,000 ریال', 1))).toEqual([]);
  });

  it('drops an absurd figure rather than saturating the ledger', () => {
    expect(parseToRows(source('REFAH', 'بانک رفاه واریز مبلغ 999999999999999999999 مانده 99999999999', 1))).toEqual([]);
    const large = parseToRows(source('REFAH', 'بانک رفاه واریز مبلغ 900,000,000,000 ریال مانده 1,000,000,000,000 ریال', 2));
    expect(large[0].amountRial).toBe(900_000_000_000);
  });

  it('keeps a poison stamp\'s money and loses only its day', () => {
    const now = 1_700_000_000_000;
    const body = 'بانک رفاه واریز مبلغ 900,000 ریال مانده 1,000,000 ریال';
    const row = parseToRows(source('REFAH', body, 1_700_000_000_000_000), now)[0];
    expect(row.amountRial).toBe(900_000);
    expect(row.at).toBe(now + AT_FUTURE_SLACK_MS);
    expect(row.day).toBe(tehranDay(row.at));
    expect(parseToRows(source('REFAH', body, -5), now)[0].at).toBe(0);
    expect(parseToRows(source('REFAH', body, 2), now)[0].at).toBe(2);
  });

  it('files a message from a bank she did not name as OTHER', () => {
    expect(parseToRows(source('', 'واریز مبلغ 5,000,000 ریال', 1))[0]).toMatchObject({ bank: 'OTHER', accountId: 'OTHER' });
  });

  it('reads the moment the bank printed', () => {
    const now = tehranDayStart(jalaliDay(1405, 7, 4)) + 12 * 3600_000;
    const at = (y: number, m: number, d: number, h = 0, min = 0, s = 0): number =>
      tehranDayStart(jalaliDay(y, m, d)) + ((h * 60 + min) * 60 + s) * 1000;
    expect(printedMoment('1405/5/1 11:26', now)).toBe(at(1405, 5, 1, 11, 26));
    expect(printedMoment('1405/5/3 21:59:27', now)).toBe(at(1405, 5, 3, 21, 59, 27));
    expect(printedMoment('۱۴۰۵.۰۵.۰۴ ۱۶:۰۰', now)).toBe(at(1405, 5, 4, 16));
    expect(printedMoment('05/05/11-11:52', now)).toBe(at(1405, 5, 11, 11, 52));
    expect(printedMoment('00/12/17_16:43', now)).toBe(at(1400, 12, 17, 16, 43));
    // A bare date is Tehran midnight, which prints no clock.
    expect(printedMoment('05/04/27', now)).toBe(at(1405, 4, 27));
    // Month and day with no year take this one, or last year's when this one's is still to come.
    expect(printedMoment('05/08_13:37', now)).toBe(at(1405, 5, 8, 13, 37));
    expect(printedMoment('5/8-10:04', now)).toBe(at(1405, 5, 8, 10, 4));
    expect(printedMoment('12/25', now)).toBe(at(1404, 12, 25));
    for (const nonsense of ['', '2026/09/26', '1405/13/1', '1405/12/31 10:00', '1405/5/1 25:00', '1406/1/1']) {
      expect(printedMoment(nonsense, now), nonsense).toBeNull();
    }
  });
});

describe('what an account holds', () => {
  it('anchors on a stated balance and builds on it', () => {
    const b = deriveBalance('SAMAN', [
      txn({ at: 1, signed: 10_000_000, balance: 50_000_000 }), txn({ at: 2, signed: -20_000_000 }), txn({ at: 3, signed: 5_000_000 }),
    ], null)!;
    expect(b).toMatchObject({ rial: 35_000_000, anchored: true });
  });

  it('takes the last stated balance however many came before it', () => {
    expect(deriveBalance('SAMAN', [
      txn({ at: 1, signed: 3_000_000_000, balance: 19_787_503_090 }), txn({ at: 2, signed: -1_083_381_300, balance: 19_787_503_090 }),
      txn({ at: 3, signed: -50_000_000, balance: 19_607_503_090 }), txn({ at: 4, signed: -15_000_000_000, balance: 4_607_253_090 }),
    ], null)!.rial).toBe(4_607_253_090);
  });

  it('needs no guard for an out-of-order message', () => {
    const forwards = [txn({ at: 100, balance: 90_000_000, ref: 's:a:0' }), txn({ at: 500, signed: -1_000_000, ref: 's:b:0' })];
    expect(deriveBalance('SAMAN', [...forwards].reverse(), null)!.rial).toBe(89_000_000);
    expect(deriveBalance('SAMAN', forwards, null)!.rial).toBe(89_000_000);
  });

  it('never trusts a running sum, which may go negative', () => {
    expect(deriveBalance('SAMAN', [txn({ at: 1, signed: -25_000_000 })], null)).toMatchObject({ rial: -25_000_000, anchored: false });
  });

  it('lets the newest evidence win, hers or the bank\'s, and gives the bank the tie', () => {
    const rows = [txn({ at: 100, balance: 10_000_000 })];
    expect(deriveBalance('SAMAN', rows, anchor(200, 90_000_000))!.rial).toBe(90_000_000);
    expect(deriveBalance('SAMAN', rows, anchor(50, 90_000_000))!.rial).toBe(10_000_000);
    expect(deriveBalance('SAMAN', rows, anchor(100, 90_000_000))!.rial).toBe(10_000_000);
    expect(deriveBalance('SAMAN', [txn({ at: 500, signed: 3_000_000_000 })], anchor(10_000, 500_000_000))!.rial).toBe(500_000_000);
  });

  it('reads an emptied account as zero, still anchored, and an unknown one as nothing', () => {
    expect(deriveBalance('SAMAN', [txn({ at: 2, signed: -50_000_000, balance: 0 })], null)).toMatchObject({ rial: 0, anchored: true });
    expect(deriveBalance('SAMAN', [], null)).toBeNull();
  });
});

describe('the accounts sheet', () => {
  const stated = (at: number, rial: number, account = 'SAMAN'): Txn => txn({ at, balance: rial, account });

  it('refuses one message claiming a hundred-fold jump over an anchored balance', () => {
    let accounts = bankAccountsOf([stated(1, 50_000_000), stated(2, 6_000_000_000)], [], []);
    expect(bankTotal(accounts)).toBe(50_000_000);
    accounts = bankAccountsOf([stated(1, 50_000_000), stated(2, 6_000_000_000), stated(3, 51_000_000)], [], []);
    expect(bankTotal(accounts)).toBe(51_000_000);
    // A believable jump, a small account, an unanchored start and an emptied account all still land.
    expect(bankTotal(bankAccountsOf([stated(1, 100_000_000), stated(2, 2_000_000_000)], [], []))).toBe(2_000_000_000);
    expect(bankTotal(bankAccountsOf([stated(1, 500_000), stated(2, 60_000_000)], [], []))).toBe(60_000_000);
    expect(bankTotal(bankAccountsOf([txn({ at: 1, signed: 100_000_000 }), stated(2, 1_500_000_000)], [], []))).toBe(1_500_000_000);
    expect(bankTotal(bankAccountsOf([txn({ at: 1, signed: -500_000_000, balance: 0 }), stated(2, 1_500_000_000)], [], []))).toBe(1_500_000_000);
  });

  it('refuses a figure past the plausibility bound, balance and all', () => {
    const accounts = bankAccountsOf([stated(1, 50_000_000), txn({ at: 2, signed: 2e17, balance: 5_000_000 })], [], []);
    expect(bankTotal(accounts)).toBe(50_000_000);
  });

  it('counts only anchored, switched-on banks, and lists the rest', () => {
    const rows = [stated(1, 50_000_000), txn({ at: 2, signed: -90_000_000, account: 'BLU' }), stated(3, 20_000_000, 'REFAH')];
    const accounts = bankAccountsOf(rows, [], ['REFAH']);
    expect(accounts.map((a) => [a.bank, a.anchored, a.disabled])).toEqual([['BLU', false, false], ['SAMAN', true, false], ['REFAH', true, true]]);
    expect(accounts.find((a) => a.bank === 'BLU')).toMatchObject({ balanceRial: -90_000_000, trusted: false, bankFa: 'بلو بانک' });
    expect(bankTotal(accounts)).toBe(50_000_000);
  });

  it('lets her figure and the bank\'s compete on time, and builds on whichever is newer', () => {
    const rows = [stated(100, 10_000_000), txn({ at: 300, signed: -1_000_000 })];
    expect(bankTotal(bankAccountsOf(rows, [anchor(200, 90_000_000)], []))).toBe(89_000_000);
    expect(bankTotal(bankAccountsOf(rows, [anchor(50, 90_000_000)], []))).toBe(9_000_000);
    expect(bankTotal(bankAccountsOf(rows, [anchor(100, 90_000_000)], []))).toBe(9_000_000);
    // A typed figure alone is an account.
    expect(bankAccountsOf([], [anchor(5, 7_000_000, 'SINA')], [])).toMatchObject([{ bank: 'SINA', balanceRial: 7_000_000, trusted: true }]);
  });

  it('never counts the echo of a message pasted twice, and never names OTHER', () => {
    const once = txn({ at: 1, signed: -1_000_000, ref: 's:a:0' });
    const echo = { ...once, ref: 's:b:0', at: 2 };
    expect(bankAccountsOf([once, echo], [], [], new Set(['s:b:0']))[0].balanceRial).toBe(-1_000_000);
    expect(bankAccountsOf([stated(1, 5, 'OTHER')], [], [])).toEqual([]);
  });

  it('starts a forgotten account again from the next message', () => {
    const forget: BalanceAnchor = { ...anchor(150, 0), id: 'forget:SAMAN', deleted: true };
    expect(bankAccountsOf([stated(100, 10_000_000)], [anchor(120, 5), forget], [])).toEqual([]);
    expect(bankAccountsOf([stated(100, 10_000_000), txn({ at: 200, signed: -1_000 })], [forget], []))
      .toMatchObject([{ balanceRial: -1_000, anchored: false }]);
  });
});

function input(o: Partial<DeriveInput>): DeriveInput {
  return {
    sources: [], manual: [], familyTxns: [], familyMembers: [], decisions: [], links: [], categories: [...BUILTIN_CATEGORIES],
    rules: [...BUILTIN_RULES], anchors: [], mineId: '', sharesSms: false, excludedBanks: [], disabledBanks: [], now: 1e12, ...o,
  };
}
const manual = (id: string, day: number, rial: number): ManualTxn =>
  ({ id, at: tehranDayStart(day) + 3_600_000, day, amountRial: rial, accountId: null, categoryId: null, merchant: '', note: '', createdAt: 0, updatedAt: 0, deleted: false });
const decided = (ref: string, kind: Decision['kind'], value: string | null, memberId = '', deleted = false): Decision =>
  ({ id: `${kind}:${ref}`, ref, kind, value, createdAt: 1, updatedAt: 1, deleted, memberId, familyRef: '' });

describe('the ledger view', () => {
  it('sets months before the start aside without losing them from the picker', () => {
    const now = Date.now();
    const today = tehranDay(now);
    const thisMonth = jalaliMonthStart(today);
    const lastMonth = jalaliMonthStart(thisMonth - 1);
    const all = input({ now, manual: [manual('salary', lastMonth + 2, 50_000_000), manual('rent', lastMonth + 3, -10_000_000), manual('bread', today, -1_000_000)] });
    const whole = ledgerView(all);
    const clean = ledgerView(all, thisMonth);
    expect(whole.entries).toHaveLength(3);
    expect(clean.entries.map((e) => e.txn.day)).toEqual([today]);
    expect(clean.review.every((e) => e.txn.day >= thisMonth)).toBe(true);
    expect(clean.health).toMatchObject({ startsOn: thisMonth, setAside: 2, transactionCount: 1 });
    expect(clean.health.months).toEqual(whole.health.months);
    expect(clean.health.months.reduce((s, [, c]) => s + c, 0)).toBe(3);
    expect(clean.allEntries).toHaveLength(3);
    expect(startingFrom(whole.entries, 0)).toBe(whole.entries);
  });

  it('drops a row she deleted, keeps her answers, and names who wrote what', () => {
    const s = source('SAMAN', 'برداشت مبلغ 2,000,000 ریال', 10);
    const gone = source('SAMAN', 'برداشت مبلغ 3,000,000 ریال', 20);
    const ref = `s:${s.id}:0`;
    const view = ledgerView(input({
      sources: [s, gone],
      mineId: 'me',
      familyMembers: [
        { id: 'me', name: 'سارا', sharesSms: true, avatar: '👩', updatedAt: 0, deleted: false },
        { id: 'him', name: 'علی', sharesSms: true, avatar: '👨', updatedAt: 0, deleted: false },
      ],
      decisions: [
        decided(`s:${gone.id}:0`, 'hide', '1'),
        decided(ref, 'category', 'cat_send', 'him'),
        decided(ref, 'note', 'کادوی تولد مامان', 'him'),
      ],
    }));
    expect(view.entries).toHaveLength(1);
    expect(view.entries[0]).toMatchObject({
      // An archived category still names the rows filed under it.
      categoryId: 'cat_send', categoryFa: 'انتقال وجه', needsReview: false, ownerMemberId: 'me', ownerName: 'سارا',
      ownerAvatar: '👩', categoryEditorName: 'علی', note: 'کادوی تولد مامان', noteAuthorName: 'علی', sharedWithFamily: false,
    });
    expect(view.categories.some((c) => c.id === 'cat_send')).toBe(false);
    expect(view.managedCategories.some((c) => c.id === 'cat_send')).toBe(true);
  });

  it('shares with the household exactly what the phone shares', () => {
    const s = source('SAMAN', 'برداشت مبلغ 2,000,000 ریال', 10);
    const m = manual('u1', 100, -5_000);
    const f: FamilyTxn = { id: 'txn:him:1', ownerMemberId: 'him', sourceKind: 'sms', at: 5, day: 0, amountRial: -700, bank: 'BLU', merchant: '', updatedAt: 1, deleted: false, transfer: true };
    const shared = (o: Partial<DeriveInput>) =>
      Object.fromEntries(ledgerView(input({ sources: [s], manual: [m], familyTxns: [f], ...o })).entries.map((e) => [e.txn.sourceKind + e.txn.ref[0], e.sharedWithFamily]));
    expect(shared({})).toEqual({ smss: false, manualm: false, smsf: true });
    expect(shared({ mineId: 'me' })).toEqual({ smss: false, manualm: true, smsf: true });
    expect(shared({ mineId: 'me', sharesSms: true })).toEqual({ smss: true, manualm: true, smsf: true });
    expect(shared({ mineId: 'me', sharesSms: true, excludedBanks: ['SAMAN'] })).toEqual({ smss: false, manualm: true, smsf: true });
    // A member's transfer flag rides across: it is out of every total here too.
    const view = ledgerView(input({ familyTxns: [f] }));
    expect(view.entries[0]).toMatchObject({ transfer: true, ownerMemberId: 'him' });
  });

  it('asks about the biggest first, and never about a transfer or a duplicate', () => {
    const view = ledgerView(input({
      sources: [
        source('SAMAN', 'برداشت مبلغ 2,000,000 ریال', 10), source('SAMAN', 'برداشت مبلغ 9,000,000 ریال', 20),
        source('SAMAN', 'برداشت مبلغ 50,000,000 ریال', 100_000), source('BLU', 'واریز مبلغ 50,000,000 ریال', 130_000),
      ],
    }));
    expect(view.review.map((e) => e.txn.amountRial)).toEqual([9_000_000, 2_000_000]);
    expect(view.entries.filter((e) => e.transfer)).toHaveLength(2);
    expect(view.links).toHaveLength(1);
  });

  it('files a pasted message by the channel it names', () => {
    const view = ledgerView(input({ sources: [source('MELLAT', 'برداشت از خودپرداز مبلغ 2,000,000 ریال', 10)] }));
    expect(view.entries[0]).toMatchObject({ categoryId: 'cat_cash', needsReview: true });
  });
});

describe('filing the whole backlog', () => {
  const waiting = (signed: number | null, categoryId = CAT_UNCATEGORISED): LedgerEntry => ({
    txn: txn({ at: 1, signed }), categoryId, categoryFa: '', confidence: 0, needsReview: true, duplicate: false, transfer: false,
    ownerMemberId: '', ownerName: '', ownerAvatar: '', categoryEditorName: '', note: '', noteAuthorName: '', sharedWithFamily: true,
  });

  it('keeps a suggestion, and falls back by direction', () => {
    const suggested = waiting(-4_500_000, CAT_SHOPPING_ID);
    const out = waiting(-4_500_000);
    const inflow = waiting(4_000_000);
    const unknown = waiting(null);
    const plan = autoFilePlan([suggested, out, inflow, unknown]);
    expect(plan).toMatchObject({ total: 4, suggested: 1, shopping: 1, other: 2 });
    expect(plan.assignments.map(([, c]) => c)).toEqual([CAT_SHOPPING_ID, CAT_SHOPPING_ID, CAT_OTHER, CAT_OTHER]);
    expect(autoFilePlan([])).toMatchObject({ total: 0, assignments: [] });
  });
});

describe('ledger actions', () => {
  let state: typeof import('../src/state');
  let derived: typeof import('../src/derived');
  let ledger: typeof import('../src/ledger');
  beforeEach(async () => {
    vi.resetModules();
    globalThis.indexedDB = new IDBFactory();
    state = await import('../src/state');
    derived = await import('../src/derived');
    ledger = await import('../src/ledger');
    (await import('../src/rules')).seedBuiltins(1);
  });
  const entry = (ref: string): LedgerEntry => derived.ledger().allEntries.find((e) => e.txn.ref === ref)!;

  it('stores a pasted message once, and refuses a one-time code', async () => {
    const first = await ledger.addPastedSms('بانک سامان\nبرداشت مبلغ 2,000,000 ریال\n1405/5/1 11:26', 'SAMAN');
    const again = await ledger.addPastedSms('بانک سامان\nبرداشت مبلغ 2,000,000 ریال\n1405/5/1 11:26\n', 'SAMAN');
    expect(again!.id).toBe(first!.id);
    expect(first!.at).toBe(tehranDayStart(jalaliDay(1405, 5, 1)) + (11 * 60 + 26) * 60_000);
    expect(state.rows('sources')).toHaveLength(1);
    expect(state.pref('lastPasteBank')).toBe('SAMAN');
    expect(await ledger.addPastedSms('رمز پویا: 482139\nخرید مبلغ 1,250,000 ریال', 'SAMAN')).toBeNull();
    expect(derived.ledger().entries).toHaveLength(1);
  });

  it('files one row, and «همیشه» files every one like it', async () => {
    const a = await ledger.addPastedSms('برداشت مبلغ 2,000,000 ریال', 'SAMAN', 1000);
    const b = await ledger.addPastedSms('برداشت مبلغ 7,000,000 ریال', 'SAMAN', 900_000);
    derived.setMineId('me');
    ledger.categorise(entry(`s:${a!.id}:0`), 'cat_health', true);
    const filed = entry(`s:${b!.id}:0`);
    expect(filed).toMatchObject({ categoryId: 'cat_health', needsReview: false });
    const rule = state.rows('rules').find((r) => !r.builtin)!;
    expect(rule).toMatchObject({ pAddrKey: 'SAMAN', pBank: 'SAMAN', pDirection: 'out', pChannel: null, originRef: `s:${a!.id}:0` });
    const d = state.row('decisions', `category:s:${a!.id}:0`)!;
    expect(d).toMatchObject({ value: 'cat_health', memberId: 'me', familyRef: expect.stringMatching(/^txn:me:/) });
    // Monotonic: a stamp from a clock running ahead is never gone back under.
    const ahead = Date.now() + 1e9;
    state.put('decisions', { ...d, updatedAt: ahead });
    ledger.categorise(entry(`s:${a!.id}:0`), 'cat_dining', false);
    expect(state.row('decisions', `category:s:${a!.id}:0`)!.updatedAt).toBe(ahead + 1);
  });

  it('rejects the transfer link when one leg is refiled as spending, and not when it is filed as a transfer', async () => {
    const out = await ledger.addPastedSms('برداشت مبلغ 50,000,000 ریال', 'SAMAN', 100_000);
    const into = await ledger.addPastedSms('واریز مبلغ 50,000,000 ریال', 'BLU', 130_000);
    const [outRef, inRef] = [`s:${out!.id}:0`, `s:${into!.id}:0`];
    expect(entry(outRef).transfer && entry(inRef).transfer).toBe(true);
    ledger.categorise(entry(outRef), 'cat_transfer', false);
    expect(state.rows('links')).toHaveLength(0);
    ledger.categorise(entry(outRef), 'cat_groceries', false);
    expect(state.rows('links')).toMatchObject([{ kind: 'transfer', verdict: 'rejected' }]);
    expect(entry(outRef)).toMatchObject({ transfer: false, categoryId: 'cat_groceries' });
    expect(entry(inRef)).toMatchObject({ transfer: false, categoryId: 'cat_income' });
  });

  it('files the whole deck with rising stamps', async () => {
    await ledger.addPastedSms('برداشت مبلغ 2,000,000 ریال', 'SAMAN', 1000);
    await ledger.addPastedSms('واریز مبلغ 7,000,000 ریال', 'SAMAN', 900_000);
    ledger.categoriseAll(autoFilePlan(derived.ledger().review).assignments);
    expect(derived.ledger().review).toEqual([]);
    const stamps = state.rows('decisions').map((d) => d.updatedAt);
    expect(new Set(stamps).size).toBe(2);
  });

  it('keeps a note to two hundred characters, and takes a blank one back', async () => {
    const s = await ledger.addPastedSms('برداشت مبلغ 2,000,000 ریال', 'SAMAN', 1000);
    const ref = `s:${s!.id}:0`;
    ledger.setNote(entry(ref), `  ${'ن'.repeat(250)}  `);
    expect(entry(ref).note).toHaveLength(200);
    ledger.setNote(entry(ref), '   ');
    expect(entry(ref).note).toBe('');
    expect(state.row('decisions', `note:${ref}`)!.deleted).toBe(true);
  });

  it('adds a typed row with its answers, and refuses nothing at all', () => {
    ledger.addManualTxn(0, 'cat_health', 'x', '', Date.now());
    expect(state.rows('manual')).toHaveLength(0);
    ledger.addManualTxn(-3_000_000, 'cat_health', '  داروخانه  ', 'سرماخوردگی', Date.now());
    const [row] = derived.ledger().entries;
    expect(row).toMatchObject({ categoryId: 'cat_health', note: 'سرماخوردگی', needsReview: false });
    expect(row.txn).toMatchObject({ merchant: 'داروخانه', signedRial: -3_000_000, sourceKind: 'manual' });
  });

  it('deletes and restores her own rows, and never another member\'s', async () => {
    const s = await ledger.addPastedSms('برداشت مبلغ 2,000,000 ریال', 'SAMAN', 1000);
    ledger.addManualTxn(-5_000, null, '', '', 2000);
    const pasted = entry(`s:${s!.id}:0`);
    const typed = derived.ledger().entries.find((e) => e.txn.sourceKind === 'manual')!;
    ledger.deleteTxn(pasted);
    ledger.deleteTxn(typed);
    expect(derived.ledger().entries).toEqual([]);
    expect(state.rows('sources')).toHaveLength(1);
    ledger.restoreTxn(pasted.txn.ref);
    ledger.restoreTxn(typed.txn.ref);
    expect(derived.ledger().entries).toHaveLength(2);
    state.put('familyTxns', { id: 'txn:him:1', ownerMemberId: 'him', sourceKind: 'sms', at: 5, day: 0, amountRial: -700, bank: 'BLU', merchant: '', updatedAt: 1, deleted: false, transfer: false });
    ledger.deleteTxn(entry(familyLocalRef('txn:him:1')));
    expect(derived.ledger().entries).toHaveLength(3);
  });

  it('moves a typed row to another day, keeping its minute, and never into tomorrow', () => {
    const today = tehranDay(Date.now());
    ledger.addManualTxn(-5_000, null, '', '', tehranDayStart(today) + 14 * 3_600_000 + 3 * 60_000);
    const typed = derived.ledger().entries[0];
    ledger.setManualTxnDay(typed, today - 3);
    expect(derived.ledger().entries[0].txn).toMatchObject({ day: today - 3, at: tehranDayStart(today - 3) + (14 * 60 + 3) * 60_000 });
    ledger.setManualTxnDay(derived.ledger().entries[0], today + 5);
    expect(derived.ledger().entries[0].txn.day).toBe(today);
  });

  it('adds, renames and archives categories without touching a filed row', () => {
    const gym = ledger.addCategory(' باشگاه ', 'expense', 'DOTS');
    expect(derived.ledger().categories.some((c) => c.id === gym.id && c.nameFa === 'باشگاه')).toBe(true);
    ledger.editCategory(state.row('categories', 'cat_groceries')!, 'بقالی', 'BASKET');
    expect(derived.ledger().marks['بقالی']).toBe('BASKET');
    ledger.toggleCategoryArchived(gym);
    expect(derived.ledger().categories.some((c) => c.id === gym.id)).toBe(false);
    expect(state.row('categories', gym.id)!.updatedAt).toBe(gym.updatedAt);
  });

  it('keeps her balances, forgets one on request, and switches banks out of the total', async () => {
    await ledger.addPastedSms('برداشت مبلغ 1,000,000 ریال\nمانده 50,000,000 ریال', 'SAMAN', 1000);
    expect(derived.ledger().bankTotalRial).toBe(50_000_000);
    ledger.setBankBalance('BLU', 20_000_000);
    expect(derived.ledger().bankTotalRial).toBe(70_000_000);
    ledger.toggleBankDisabled('BLU');
    expect(derived.ledger().bankTotalRial).toBe(50_000_000);
    expect(derived.bankAccountsView().find((a) => a.bank === 'BLU')!.disabled).toBe(true);
    ledger.toggleBankDisabled('BLU');
    ledger.forgetAccount('BLU');
    expect(derived.bankAccountsView().map((a) => a.bank)).toEqual(['SAMAN']);
    ledger.setLedgerStartsOn(tehranDay(Date.now()));
    expect(derived.ledger().entries).toEqual([]);
    expect(derived.ledger().bankTotalRial).toBe(50_000_000);
    await state.settled();
  });
});
