import { describe, expect, it } from 'vitest';
import { faCompact, faHeld, faNumber, faOrdinal, faSignedCompact, faSignedParts, faUsd, faWords, faWordsToman, fieldNumber, groupDigits, parseAmount } from '../src/format';

/** The phone's own FormatTest expectations, verbatim where they are literal. */
describe('format', () => {
  it('truncates, never rounds', () => {
    expect(faNumber(999.9)).toBe('۹۹۹');
    expect(faNumber(118500)).toBe('۱۱۸٬۵۰۰');
    expect(faCompact(11_000_000)).toBe('۱۱ میلیون');
    expect(faCompact(2_950_000_000)).toBe('۲٫۹ میلیارد');
    expect(faCompact(10_899_999)).toBe('۱۰٫۸ میلیون');
    expect(faCompact(1_007_000, 3, true)).toBe('۱٫۰۰۷ میلیون');
    expect(faCompact(9_643_999_999, 3)).toBe('۹٫۶۴۳ میلیارد');
    expect(faCompact(3_000_000_000, 3)).toBe('۳ میلیارد');
    expect(faCompact(559_500_000, 3, true)).toBe('۵۵۹٫۵۰۰ میلیون');
    expect(faCompact(9_700_000_000, 3, true)).toBe('۹٫۷۰۰ میلیارد');
    expect(faCompact(180_000_000, 3, true)).toBe('۱۸۰ میلیون');
    expect(faCompact(999)).toBe('۹۹۹');
    expect(faHeld(10709.13, 2)).toBe('۱۰٬۷۰۹٫۱۳');
    expect(faHeld(12.123456, 6)).toBe('۱۲٫۱۲۳۴');
    expect(faHeld(10709.135681, 6)).toBe('۱۰٬۷۰۹٫۱۳');
    expect(faHeld(0.000425, 6)).toBe('۰٫۰۰۰۴۲۵');
    expect(faHeld(0.1234567, 6)).toBe('۰٫۱۲۳۴۵۶');
    expect(faHeld(1.99999995, 6)).toBe('۱٫۹۹۹۹');
  });

  it('spells amounts out', () => {
    expect(faWords(10_800_000)).toBe('ده میلیون و هشتصد هزار');
    expect(faWords(0)).toBe('صفر'); expect(faWords(21)).toBe('بیست و یک');
    expect(faWords(1_000)).toBe('هزار'); expect(faWords(1_000_000)).toBe('یک میلیون');
    expect(faWords(999)).toBe('نهصد و نود و نه');
    expect(faWordsToman(3_054_100_221)).toBe('سه میلیارد و پنجاه و چهار میلیون و صد هزار و دویست و بیست و یک تومان');
    // Truncated like the digits above it, never rounded up.
    expect(faWordsToman(999_999.6)).toBe(`${faWords(999_999)} تومان`);
    expect(faWordsToman(12_345 / 10)).toBe(`${faWords(1_234)} تومان`);
    expect(faWordsToman(2.9999999999)).toBe(`${faWords(3)} تومان`);
  });

  it('which one of a run is said the way it is out loud', () => {
    expect([1, 2, 3, 4, 13, 21, 23, 30, 100, 120].map(faOrdinal))
      .toEqual(['اول', 'دوم', 'سوم', 'چهارم', 'سیزدهم', 'بیست و یکم', 'بیست و سوم', 'سی‌ام', 'صدم', 'صد و بیستم']);
  });

  it('reads what a Persian keyboard types, and nothing else', () => {
    expect(parseAmount('۱۲٬۳۴۵')).toBe(12345); expect(parseAmount('١٢.٥')).toBe(12.5);
    expect(parseAmount('12abc')).toBeNull(); expect(parseAmount('')).toBeNull();
    expect(groupDigits('85000000')).toBe('۸۵٬۰۰۰٬۰۰۰'); expect(groupDigits('1234.5')).toBe('۱٬۲۳۴٫۵');
  });

  it('seeds an edit field with every digit, so saving it back changes nothing', () => {
    expect(fieldNumber(1.43)).toBe('1.43');
    expect(fieldNumber(0.1234567)).toBe('0.1234567');
    expect(fieldNumber(180_000_000)).toBe('180000000');
    expect(fieldNumber(1e-7)).toBe('0.0000001');
    expect(fieldNumber(0)).toBe('0');
    for (const v of [1.43, 0.4, 0.1234567, 10_709.13, 1e-7, 187_350.5]) expect(parseAmount(fieldNumber(v))).toBe(v);
  });

  it('keeps the sign on the digits', () => {
    expect(faSignedCompact(400_000_000, false)).toBe('⁦−۴۰۰⁩ میلیون');
    // The halves a row sets apart (SignedFigure), so the word can step down; none under a thousand.
    expect(faSignedParts(400_000_000, false)).toEqual(['⁦−۴۰۰⁩', 'میلیون']);
    expect(faSignedParts(832, true)).toEqual(['⁦+۸۳۲⁩', null]);
    expect(faUsd(832.9)).toBe('\u2066$۸۳۲\u2069');
    expect(faUsd(1_200_000)).toBe('\u2066$۱٫۲\u2069 میلیون');
  });
});
