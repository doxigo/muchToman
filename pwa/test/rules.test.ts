import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { jalaliDay, tehranDay, tehranDayStart } from '../src/jalali';
import type { LedgerEntry, Rule, Txn } from '../src/model';
import {
  BUILTIN_CATEGORIES, BUILTIN_RULES, CAT_CASH, CAT_INCOME, CAT_OTHER, CAT_TRANSFER, CAT_UNCATEGORISED, Confidence,
  Priority, REVIEW_BELOW, categoryChoices, categoryUseOf, classify, customCategory, ruleFrom, ruleMatches, specificity,
} from '../src/rules';
import { merchantNorm } from '../src/sms';

/** Rules.kt, as ClassifyTest.kt and PersistenceTest.kt pin it. */

let n = 0;
function txn(o: { at?: number; signed?: number | null; balance?: number | null; account?: string; merchant?: string; channel?: string; ref?: string } = {}): Txn {
  n++;
  const at = o.at ?? n * 1000;
  const ref = o.ref ?? `s:${String(n).padStart(4, '0')}:0`;
  const signed = o.signed ?? null;
  const account = o.account ?? 'SAMAN';
  return {
    ref, srcHash: ref, seq: 0, at, day: tehranDay(at), bank: account, accountId: account,
    direction: signed == null ? null : signed > 0 ? 'in' : 'out',
    amountRial: signed == null ? null : Math.abs(signed), signedRial: signed,
    balanceRial: o.balance ?? null, feeRial: null, mask: '', instrument: 'unknown',
    merchant: o.merchant ?? '', merchantNorm: merchantNorm(o.merchant ?? ''), refNo: '', printedAt: '',
    channel: o.channel ?? 'unknown', unitPrinted: 'none', inferred: false, familyRef: '', ownerMemberId: '', sourceKind: 'sms',
  };
}
const rule = (id: string, priority: number, categoryId: string, p: Partial<Rule> = {}): Rule => ({
  id, priority, categoryId, pMerchantNorm: null, pMerchantLike: null, pAddrKey: null, pBank: null, pChannel: null,
  pDirection: null, pMinRial: null, pMaxRial: null, pInstrument: null, enabled: true, builtin: false,
  originRef: null, createdAt: 0, updatedAt: 0, deleted: false, ...p,
});

describe('rules', () => {
  it("is a conjunction and null means don't care", () => {
    const r = rule('r1', Priority.USER_OTHER, 'cat_transport', { pChannel: 'pos', pDirection: 'out', pMaxRial: 500_000 });
    expect(ruleMatches(r, txn({ signed: -400_000, channel: 'pos' }))).toBe(true);
    expect(ruleMatches(r, txn({ signed: -400_000, channel: 'pos', merchant: 'هرچیزی' }))).toBe(true);
    expect(ruleMatches(r, txn({ signed: -600_000, channel: 'pos' }))).toBe(false);
    expect(ruleMatches(r, txn({ signed: -400_000, channel: 'atm' }))).toBe(false);
    expect(ruleMatches(r, txn({ signed: 400_000, channel: 'pos' }))).toBe(false);
  });

  it('files a box move as a transfer whichever way it goes', () => {
    const hers = rule('r_blu_in', Priority.USER_OTHER, CAT_INCOME, { pBank: 'BLU', pDirection: 'in' });
    for (const signed of [-5_000_000, 2_000_000]) {
      const move = txn({ signed, account: 'BLU', channel: 'box' });
      const filed = classify(move, [...BUILTIN_RULES, hers]);
      expect(filed.categoryId).toBe(CAT_TRANSFER);
      expect(filed.needsReview).toBe(false);
      expect(classify(move, BUILTIN_RULES, 'cat_shopping').categoryId).toBe('cat_shopping');
    }
  });

  it('never matches an amount predicate against a transaction with no amount', () => {
    expect(ruleMatches(rule('r1', Priority.USER_OTHER, 'cat_shopping', { pMinRial: 1 }), txn({ balance: 5_000_000 }))).toBe(false);
  });

  it('never asks about a balance-only message', () => {
    const bank = rule('r1', Priority.USER_OTHER, 'cat_bills', { pBank: 'SAMAN' });
    expect(classify(txn({ balance: 5_000_000 }), [bank]).categoryId).toBe(CAT_UNCATEGORISED);
    expect(classify(txn({ balance: 5_000_000 }), [bank]).needsReview).toBe(false);
    expect(classify(txn({ balance: 5_000_000 }), []).needsReview).toBe(false);
    expect(classify(txn({ balance: 5_000_000 }), [], 'cat_health').categoryId).toBe('cat_health');
  });

  it('lets a decision she pinned beat every rule', () => {
    const c = classify(txn({ signed: -1000, channel: 'pos' }), [rule('r1', Priority.USER_EXACT_MERCHANT, 'cat_shopping', { pChannel: 'pos' })], 'cat_health');
    expect(c).toMatchObject({ categoryId: 'cat_health', confidence: Confidence.USER_PINNED, needsReview: false });
  });

  it('breaks ties by priority, then specificity, then the id', () => {
    const t = txn({ signed: -1000, channel: 'pos', merchant: 'دیجی کالا' });
    const shipped = rule('z', Priority.SHIPPED, 'cat_shopping', { pChannel: 'pos' });
    const hers = rule('a', Priority.USER_EXACT_MERCHANT, 'cat_health', { pMerchantNorm: 'دیجی کالا' });
    expect(classify(t, [shipped, hers]).categoryId).toBe('cat_health');
    const a = rule('aaa', Priority.USER_OTHER, 'cat_dining', { pChannel: 'pos' });
    const b = rule('bbb', Priority.USER_OTHER, 'cat_transport', { pChannel: 'pos' });
    expect(classify(t, [a, b]).categoryId).toBe('cat_transport');
    expect(classify(t, [b, a]).categoryId).toBe('cat_transport');
    // Specificity before the id: the narrower of two equal-priority rules wins.
    const narrow = rule('aaa', Priority.USER_OTHER, 'cat_dining', { pChannel: 'pos', pDirection: 'out' });
    expect(specificity(narrow)).toBe(2);
    expect(classify(t, [narrow, b]).categoryId).toBe('cat_dining');
  });

  it('files what matches nothing as uncategorised, and always asks', () => {
    expect(classify(txn({ signed: -1000 }), [])).toMatchObject({ categoryId: CAT_UNCATEGORISED, confidence: Confidence.NONE, needsReview: true });
  });

  it('asks about a shipped guess once, and her answer never asks again', () => {
    const atm = txn({ signed: -2_000_000, channel: 'atm' });
    const shipped = classify(atm, BUILTIN_RULES);
    expect(shipped.categoryId).toBe(CAT_CASH);
    expect(shipped.needsReview).toBe(true);
    expect(shipped.confidence).toBeLessThan(REVIEW_BELOW);
    const hers = ruleFrom(atm, CAT_CASH, '20004861', 5);
    const after = classify(atm, [...BUILTIN_RULES, hers], null, new Set(), '20004861');
    expect(after.categoryId).toBe(CAT_CASH);
    expect(after.needsReview).toBe(false);
    expect(after.confidence).toBeGreaterThanOrEqual(REVIEW_BELOW);
  });

  it('never fires a rule minted from one sender on another sender of the same bank', () => {
    const hers = ruleFrom(txn({ signed: -1000, channel: 'atm' }), CAT_CASH, '20004861', 5);
    expect(classify(txn({ signed: -900, channel: 'atm' }), [hers], null, new Set(), '20004861').categoryId).toBe(CAT_CASH);
    expect(classify(txn({ signed: -900, channel: 'atm' }), [hers], null, new Set(), '998877').categoryId).toBe(CAT_UNCATEGORISED);
    // No sender at all — a manual row. "Unknown" is not "any".
    expect(classify(txn({ signed: -900, channel: 'atm' }), [hers]).categoryId).toBe(CAT_UNCATEGORISED);
  });

  it('keys «همیشه» on the merchant when there is one and the sender when there is not', () => {
    const withMerchant = txn({ signed: -1000, merchant: 'فروشگاه رفاه', channel: 'pos' });
    const r1 = ruleFrom(withMerchant, 'cat_groceries', '100031', 1);
    expect(r1).toMatchObject({ pMerchantNorm: 'فروشگاه رفاه', pAddrKey: null, priority: Priority.USER_EXACT_MERCHANT, originRef: withMerchant.ref });
    const r2 = ruleFrom(txn({ signed: -1000, channel: 'atm' }), CAT_CASH, '20004861', 1);
    expect(r2).toMatchObject({ pMerchantNorm: null, pAddrKey: '20004861', pChannel: 'atm', priority: Priority.USER_OTHER });
    // A learned rule outranks every shipped one: her merchant rule beats rule_pos on the same row.
    const learned = classify(withMerchant, [...BUILTIN_RULES, ruleFrom(withMerchant, 'cat_groceries', 'SAMAN', 2)]);
    expect(learned).toMatchObject({ categoryId: 'cat_groceries', confidence: Confidence.RULE_EXACT, needsReview: false });
  });

  it('treats a settled transfer as bookkeeping, not a spending decision', () => {
    const t = txn({ signed: -50_000_000, channel: 'transfer' });
    expect(classify(t, BUILTIN_RULES, null, new Set([t.ref]))).toMatchObject({ categoryId: CAT_TRANSFER, needsReview: false });
  });
});

describe('the picker', () => {
  it('offers the side the money went, plus the way back from a transfer', () => {
    const incoming = categoryChoices(BUILTIN_CATEGORIES, 'in').map((c) => c.nameFa);
    expect(incoming).toEqual(['درآمد', 'حقوق', 'فروش', 'پاداش', 'سود سرمایه‌گذاری', 'پس‌گرفتن قرض', 'همسر', 'سایر', 'انتقال بین حساب‌ها']);
    const outgoing = categoryChoices(BUILTIN_CATEGORIES, 'out').map((c) => c.nameFa);
    for (const name of ['خواربار', 'سفر', 'ورزش', 'شیرینی', 'گوشت و مرغ', 'آبمیوه بستنی', 'بازپرداخت اسنپ و تپسی', 'آرایشگاه', 'آرایشی و بهداشتی', 'همسر', 'سایر']) {
      expect(outgoing).toContain(name);
    }
    expect(outgoing).not.toContain('درآمد');
    expect(outgoing).not.toContain('انتقال وجه');
    expect(BUILTIN_CATEGORIES.some((c) => c.nameFa === 'انتقال وجه' && c.archived)).toBe(true);
    expect(BUILTIN_CATEGORIES.filter((c) => c.nameFa === 'سایر')).toHaveLength(1);
    for (const list of [incoming, outgoing, categoryChoices(BUILTIN_CATEGORIES, null).map((c) => c.nameFa)]) {
      expect(list).toContain('انتقال بین حساب‌ها');
      expect(list).not.toContain('دسته‌بندی نشده');
    }
    expect(categoryChoices(BUILTIN_CATEGORIES, null)).toHaveLength(BUILTIN_CATEGORIES.length - 2);
  });

  it('offers a category she made on both sides, whichever she made it on', () => {
    const gym = customCategory('باشگاه', 'expense', 'DOTS', 1);
    const side = customCategory('کار آزاد', 'income', 'DOTS', 2);
    const all = [...BUILTIN_CATEGORIES, gym, side];
    for (const direction of ['in', 'out', null]) {
      const offered = categoryChoices(all, direction).map((c) => c.id);
      expect(offered).toContain(gym.id);
      expect(offered).toContain(side.id);
    }
    expect(categoryChoices(all, 'in').map((c) => c.nameFa)).not.toContain('خواربار');
    expect(categoryChoices(all, 'out').map((c) => c.nameFa)).not.toContain('حقوق');
  });

  it('puts what she actually files first', () => {
    const today = jalaliDay(1405, 5, 1);
    const filed = (categoryId: string, daysAgo: number, times: number): LedgerEntry[] => Array.from({ length: times }, () => ({
      txn: txn({ at: tehranDayStart(today - daysAgo) + 1, signed: -1_000_000 }), categoryId, categoryFa: '',
      confidence: Confidence.NONE, needsReview: false, duplicate: false, transfer: false, ownerMemberId: '', ownerName: '',
      ownerAvatar: '', categoryEditorName: '', note: '', noteAuthorName: '', sharedWithFamily: true,
    }));
    const use = categoryUseOf([...filed('cat_health', 3, 4), ...filed('cat_clothing', 10, 3)], today);
    const outgoing = categoryChoices(BUILTIN_CATEGORIES, 'out', use).map((c) => c.nameFa);
    expect(outgoing.slice(0, 2)).toEqual(['سلامت', 'مد و پوشاک']);
    const untouched = categoryChoices(BUILTIN_CATEGORIES, 'out').map((c) => c.nameFa);
    expect(outgoing.slice(2)).toEqual(untouched.filter((n) => n !== 'سلامت' && n !== 'مد و پوشاک'));
    expect(categoryChoices(BUILTIN_CATEGORIES, 'out', categoryUseOf(filed('cat_beauty', 0, 1), today)).map((c) => c.nameFa)).toEqual(untouched);
    const stale = categoryUseOf([...filed('cat_health', 180, 4), ...filed('cat_clothing', 7, 3)], today);
    expect(categoryChoices(BUILTIN_CATEGORIES, 'out', stale)[0].nameFa).toBe('مد و پوشاک');
    const escapes = new Map([[CAT_OTHER, 40], [CAT_TRANSFER, 40]]);
    expect(categoryChoices(BUILTIN_CATEGORIES, 'out', escapes).map((c) => c.nameFa).slice(-2)).toEqual(['سایر', 'انتقال بین حساب‌ها']);
    expect(categoryUseOf(filed('cat_health', 1, 5).map((e) => ({ ...e, transfer: true })), today).size).toBe(0);
  });
});

describe('seeding', () => {
  let state: typeof import('../src/state');
  let rules: typeof import('../src/rules');
  beforeEach(async () => {
    vi.resetModules();
    globalThis.indexedDB = new IDBFactory();
    state = await import('../src/state');
    rules = await import('../src/rules');
  });

  it('keeps an archived shipped category archived, and brings it back when she does', () => {
    rules.seedBuiltins(1);
    const sport = state.row('categories', 'cat_sport')!;
    state.put('categories', { ...sport, archived: true });
    rules.seedBuiltins(2);
    expect(state.row('categories', 'cat_sport')!.archived).toBe(true);
    expect(state.row('categories', 'cat_sport')!.nameFa).toBe(sport.nameFa);
    state.put('categories', { ...sport, archived: false });
    rules.seedBuiltins(3);
    expect(state.row('categories', 'cat_sport')!.archived).toBe(false);
    expect(state.row('categories', 'cat_send')!.archived).toBe(true);
    expect(state.rows('rules').map((r) => r.id).sort()).toEqual(BUILTIN_RULES.map((r) => r.id).sort());
  });

  it('keeps a renamed or re-marked default through reseeding, and only that', () => {
    rules.seedBuiltins(1);
    const groceries = state.row('categories', 'cat_groceries')!;
    state.put('categories', { ...groceries, nameFa: 'بقالی', glyph: 'BASKET', updatedAt: 50 });
    const dining = state.row('categories', 'cat_dining')!;
    state.put('categories', { ...dining, nameFa: "an older build's name" });
    rules.seedBuiltins(100);
    const kept = state.row('categories', 'cat_groceries')!;
    expect(kept).toMatchObject({ nameFa: 'بقالی', glyph: 'BASKET', sort: groceries.sort, updatedAt: 50 });
    expect(state.row('categories', 'cat_dining')!).toMatchObject({ nameFa: 'رستوران و کافه', updatedAt: 100 });
  });

  it('seeds once, after the store has loaded', async () => {
    await rules.ensureSeeded();
    await rules.ensureSeeded();
    expect(state.rows('categories')).toHaveLength(BUILTIN_CATEGORIES.length);
    await state.settled();
  });
});
