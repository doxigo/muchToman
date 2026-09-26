import { describe, expect, it } from 'vitest';
import { matchesLedgerSearch, searchFold } from '../src/timeline';
import { parseFaClock } from '../src/manualTxn';
import { faClock } from '../src/format';
import { tehranDayStart } from '../src/jalali';
import type { LedgerEntry } from '../src/model';

/** LedgerSearchTest.kt and ManualTxnTest.kt's clock cases: what the ledger's search and the manual sheet's clock treat as the same. */

let n = 0;
function entry(o: {
  merchant?: string; signed?: number | null; category?: string; note?: string; bank?: string; transfer?: boolean;
  sourceKind?: 'sms' | 'manual'; ownerName?: string; refNo?: string;
} = {}): LedgerEntry {
  const ref = `s:${String(++n).padStart(4, '0')}:0`;
  const signed = o.signed === undefined ? -2_500_000 : o.signed;
  const bank = o.bank ?? 'SAMAN';
  return {
    txn: {
      ref, srcHash: ref, seq: 0, at: 1_700_000_000_000, day: 20_000, bank, accountId: bank,
      direction: signed == null ? null : signed > 0 ? 'in' : 'out', amountRial: signed == null ? null : Math.abs(signed), signedRial: signed,
      balanceRial: null, feeRial: null, mask: '', instrument: 'unknown', merchant: o.merchant ?? '', merchantNorm: '', refNo: o.refNo ?? '',
      printedAt: '', channel: 'unknown', unitPrinted: 'none', inferred: false, familyRef: '', ownerMemberId: '', sourceKind: o.sourceKind ?? 'sms',
    },
    categoryId: 'cat_x', categoryFa: o.category ?? 'خوراکی', confidence: 95, needsReview: false, duplicate: false, transfer: o.transfer ?? false,
    ownerMemberId: '', ownerName: o.ownerName ?? '', ownerAvatar: '', categoryEditorName: '', note: o.note ?? '', noteAuthorName: '', sharedWithFamily: false,
  };
}

describe('the ledger search', () => {
  it('folds three digit sets, ZWNJ and spaces, ي/ك and latin case', () => {
    expect(searchFold('۲۵۰')).toBe('250');
    expect(searchFold('٢٥٠')).toBe('250');
    expect(searchFold('اسنپ‌فود')).toBe(searchFold('اسنپ فود'));
    expect(searchFold('اسنپ‌فود')).toBe(searchFold('اسنپفود'));
    expect(searchFold('علي')).toBe(searchFold('علی'));
    expect(searchFold('SNAPP')).toBe('snapp');
  });

  it('matches what the row shows', () => {
    expect(matchesLedgerSearch(entry({ merchant: 'کافه دنج' }), '   ')).toBe(true);
    expect(matchesLedgerSearch(entry({ merchant: 'اسنپ‌فود' }), 'اسنپ فود')).toBe(true);
    expect(matchesLedgerSearch(entry({ merchant: 'اسنپ‌فود' }), 'دیجی')).toBe(false);
    // A merchantless row is titled by its bank; a transfer by what it prints, not what it hides.
    expect(matchesLedgerSearch(entry({ bank: 'SAMAN' }), 'سامان')).toBe(true);
    expect(matchesLedgerSearch(entry({ transfer: true }), 'انتقال')).toBe(true);
    expect(matchesLedgerSearch(entry({ transfer: true }), 'خوراکی')).toBe(false);
    expect(matchesLedgerSearch(entry({ note: 'قسط ۱۲ لپ‌تاپ' }), '12')).toBe(true);
    expect(matchesLedgerSearch(entry({ note: 'قسط 12 لپ‌تاپ' }), '۱۲')).toBe(true);
    expect(matchesLedgerSearch(entry({ merchant: 'كافه 24' }), 'کافه ۲۴')).toBe(true);
    expect(matchesLedgerSearch(entry({ merchant: 'کافه دنج', ownerName: 'مریم' }), 'مریم')).toBe(true);
    expect(matchesLedgerSearch(entry({ merchant: 'کافه دنج', refNo: '784512' }), '۷۸۴۵۱۲')).toBe(true);
    // The MANUAL placeholder must not answer to the name the bank list falls back to.
    expect(matchesLedgerSearch(entry({ merchant: 'کافه دنج', bank: 'MANUAL', sourceKind: 'manual' }), 'بانک')).toBe(false);
  });

  it('matches the amount by its Rial digits, and nothing on a row with none', () => {
    const row = entry({ merchant: 'کافه دنج' });
    for (const q of ['250', '۲۵۰', '٢٥٠', '250000', '2500000']) expect(matchesLedgerSearch(row, q)).toBe(true);
    expect(matchesLedgerSearch(row, '999')).toBe(false);
    expect(matchesLedgerSearch(entry({ merchant: 'داروخانه' }), 'کافه')).toBe(false);
    expect(matchesLedgerSearch(entry({ merchant: 'کافه دنج', signed: null }), '250')).toBe(false);
  });
});

describe('the manual sheet clock', () => {
  it('round-trips what faClock prints and refuses a guess', () => {
    const minute = (14 * 60 + 3) * 60_000;
    expect(faClock(tehranDayStart(20_000) + minute)).toBe('۱۴:۰۳');
    expect(parseFaClock('۱۴:۰۳')).toBe(minute);
    expect(parseFaClock('14:03')).toBe(minute);
    for (const bad of ['۱۴', '25:00', '14:60', '']) expect(parseFaClock(bad)).toBeNull();
    expect(parseFaClock('0:00')).toBe(0);
    expect(parseFaClock('۲۳:۵۹')).toBe((23 * 60 + 59) * 60_000);
  });
});
