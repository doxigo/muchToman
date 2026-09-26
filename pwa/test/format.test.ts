import { describe, expect, it } from 'vitest';
import { faCompact, faHeld, faNumber, faSignedCompact, faWords, faWordsToman, groupDigits, parseAmount } from '../src/format';

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
  });

  it('spells amounts out', () => {
    expect(faWords(10_800_000)).toBe('ده میلیون و هشتصد هزار');
    expect(faWords(0)).toBe('صفر'); expect(faWords(21)).toBe('بیست و یک');
    expect(faWords(1_000)).toBe('هزار'); expect(faWords(1_000_000)).toBe('یک میلیون');
    expect(faWords(999)).toBe('نهصد و نود و نه');
    expect(faWordsToman(3_054_100_221)).toBe('سه میلیارد و پنجاه و چهار میلیون و صد هزار و دویست و بیست و یک تومان');
  });

  it('reads what a Persian keyboard types, and nothing else', () => {
    expect(parseAmount('۱۲٬۳۴۵')).toBe(12345); expect(parseAmount('١٢.٥')).toBe(12.5);
    expect(parseAmount('12abc')).toBeNull(); expect(parseAmount('')).toBeNull();
    expect(groupDigits('85000000')).toBe('۸۵٬۰۰۰٬۰۰۰'); expect(groupDigits('1234.5')).toBe('۱٬۲۳۴٫۵');
  });

  it('keeps the sign on the digits', () => {
    expect(faSignedCompact(400_000_000, false)).toBe('⁦−۴۰۰⁩ میلیون');
  });
});
