import { describe, expect, it } from 'vitest';
import { parseToman, toman } from '../src/money';
describe('manual money input', () => {
  it('accepts Persian and Arabic integers and correctly grouped thousands', () => {
    for (const text of ['۱۲۳۴۵', '١٢٣٤٥', '12,345', '۱۲٬۳۴۵', '12 345']) expect(parseToman(text)).toBe(123450);
  });
  it('rejects fractions, signs, junk, malformed grouping and unsafe amounts', () => {
    for (const text of ['-25', '+25', '1.5', '۱٫۵', '12abc34', '1,23', '0', '', '100000000000001']) expect(parseToman(text)).toBeNull();
  });
  it('truncates display without rounding either sign away from zero', () => {
    expect(toman(19)).toBe('۱'); expect(toman(-19)).toBe('‎−۱');
  });
});
