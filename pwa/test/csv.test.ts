import { describe, expect, it } from 'vitest';
import { csvField, csvText, csvToman, ledgerCsv, ledgerCsvName } from '../src/csv';
import { jalaliDay, tehranDayStart } from '../src/jalali';
import type { LedgerEntry, SplitPart } from '../src/model';

/** CsvTest.kt: the same file, byte for byte. */

const day = jalaliDay(1405, 7, 11);
const afternoon = tehranDayStart(day) + (14 * 60 + 3) * 60_000;
let n = 0;
function entry(o: {
  merchant?: string; signed?: number | null; at?: number; bank?: string; sourceKind?: 'sms' | 'manual'; note?: string;
  ownerName?: string; transfer?: boolean; duplicate?: boolean; split?: SplitPart[];
} = {}): LedgerEntry {
  const ref = `s:${String(++n).padStart(4, '0')}:0`;
  const signed = o.signed === undefined ? -2_500_000 : o.signed;
  const bank = o.bank ?? 'SAMAN';
  return {
    txn: {
      ref, srcHash: ref, seq: 0, at: o.at ?? afternoon, day, bank, accountId: bank,
      direction: signed == null ? null : signed > 0 ? 'in' : 'out', amountRial: signed == null ? null : Math.abs(signed), signedRial: signed,
      balanceRial: null, feeRial: null, mask: '', instrument: 'unknown', merchant: o.merchant ?? '', merchantNorm: '', refNo: '',
      printedAt: '', channel: 'unknown', unitPrinted: 'none', inferred: false, familyRef: '', ownerMemberId: '', sourceKind: o.sourceKind ?? 'sms',
    },
    categoryId: 'cat_x', categoryFa: 'خوراکی', confidence: 95, needsReview: false, duplicate: o.duplicate ?? false, transfer: o.transfer ?? false,
    ownerMemberId: '', ownerName: o.ownerName ?? '', ownerAvatar: '', categoryEditorName: '', note: o.note ?? '', noteAuthorName: '',
    sharedWithFamily: false, split: o.split,
  };
}

describe('the spreadsheet export', () => {
  it('writes the ledger as دفتر lists it, one line per row', () => {
    const csv = ledgerCsv([
      entry({
        merchant: 'کافه دنج', signed: -60_000_000,
        split: [{ categoryId: 'cat_smokes', categoryFa: 'دخانیات', rial: 53_500_000 }, { categoryId: 'cat_cafe', categoryFa: 'کافی‌شاپ', rial: 6_500_000 }],
      }),
      entry({ merchant: 'کافه دنج', duplicate: true }),
      entry({ signed: -10_000_000, transfer: true }),
      entry({ merchant: '=HYPERLINK("x")', signed: 12_345, note: 'قسط, "دوم"', ownerName: 'مریم' }),
      entry({ signed: -12_340, at: tehranDayStart(day), bank: 'MANUAL', sourceKind: 'manual' }),
      entry({ signed: null }),
    ]);
    expect(csv).toBe('\uFEFF' + [
      'تاریخ,ساعت,مبلغ (ریال),مبلغ (تومان),بانک,طرف حساب,دسته,یادداشت,نوع,صاحب تراکنش',
      '1405/07/11,14:03,-53500000,-5350000,بانک سامان,کافه دنج,دخانیات,,خرج,',
      '1405/07/11,14:03,-6500000,-650000,بانک سامان,کافه دنج,کافی‌شاپ,,خرج,',
      '1405/07/11,14:03,-10000000,-1000000,بانک سامان,,انتقال بین حساب‌ها,,انتقال,',
      '1405/07/11,14:03,12345,1234.5,بانک سامان,"\'=HYPERLINK(""x"")",خوراکی,"قسط, ""دوم""",درآمد,مریم',
      // Entered from a bare date: no minute to print.
      '1405/07/11,,-12340,-1234,ورود دستی,,خوراکی,,خرج,',
      '1405/07/11,14:03,,,بانک سامان,,خوراکی,,مانده,',
    ].map((l) => `${l}\r\n`).join(''));
    expect(csv.replace(/\r\n/g, '')).not.toContain('\n');
  });

  it('quotes per RFC 4180', () => {
    expect(csvField('plain')).toBe('plain');
    expect(csvField('a,b')).toBe('"a,b"');
    expect(csvField('say "hi"')).toBe('"say ""hi"""');
    expect(csvField('two\nlines')).toBe('"two\nlines"');
    expect(csvField('cr\rhere')).toBe('"cr\rhere"');
  });

  it('keeps a text cell that would run as a formula as text', () => {
    for (const s of ['=1+1', '+989120000000', '-2', '@SUM(A1)', '\tx']) expect(csvText(s)).toBe(`'${s}`);
    expect(csvText('\rx')).toBe('"\'\rx"');
    expect(csvText('کافه -دنج')).toBe('کافه -دنج');
    expect(csvText('')).toBe('');
  });

  it('divides the rial by ten, exactly', () => {
    expect(csvToman(12_345)).toBe('1234.5');
    expect(csvToman(12_340)).toBe('1234');
    expect(csvToman(-12_345)).toBe('-1234.5');
    expect(csvToman(-5)).toBe('-0.5');
    expect(csvToman(0)).toBe('0');
    expect(csvToman(9_999_999_999_999)).toBe('999999999999.9');
  });

  it('names the file for today in Tehran, Jalali, Latin digits', () => {
    expect(ledgerCsvName(afternoon)).toBe('muchtoman-1405-07-11.csv');
  });
});
