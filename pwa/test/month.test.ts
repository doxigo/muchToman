import { describe, expect, it } from 'vitest';
import { inJalaliMonth, jalaliMonthKey } from '../src/sync';

/**
 * «این ماه» on the hero is a promise about the Jalali month, and Gregorian months are the
 * obvious way to get it silently wrong: Farvardin 1404 runs 21 March – 20 April 2025, so two
 * timestamps can share a Gregorian month and sit in different Jalali ones, and vice versa.
 * Fixed timestamps, hand-checked against the calendar, so a regression is a red test rather
 * than a wrong total.
 */

const at = (y: number, m: number, d: number, hUtc = 12, minUtc = 0): number =>
  Date.UTC(y, m - 1, d, hUtc, minUtc);

describe('jalali month membership', () => {
  it('spans the Gregorian month boundary inside one Jalali month', () => {
    // 25 March and 15 April 2025 are both Farvardin 1404.
    expect(inJalaliMonth(at(2025, 3, 25), at(2025, 4, 15))).toBe(true);
  });

  it('splits one Gregorian month across the Jalali boundary', () => {
    // 19 April 2025 is Farvardin 30; 21 April is Ordibehesht 1.
    expect(inJalaliMonth(at(2025, 4, 19), at(2025, 4, 21))).toBe(false);
  });

  it('turns over at Tehran midnight, not UTC midnight', () => {
    // Ordibehesht 1, 1404 begins 2025-04-21 00:00 Tehran = 2025-04-20 20:30 UTC.
    const lastOfFarvardin = at(2025, 4, 20, 20, 29);
    const firstOfOrdibehesht = at(2025, 4, 20, 20, 30);
    expect(inJalaliMonth(lastOfFarvardin, firstOfOrdibehesht)).toBe(false);
    expect(inJalaliMonth(firstOfOrdibehesht, at(2025, 4, 21))).toBe(true);
  });

  it('keeps the year in the key, so ماه۱ of two years never matches', () => {
    expect(jalaliMonthKey(at(2024, 3, 25))).not.toBe(jalaliMonthKey(at(2025, 3, 25)));
    // Nowruz: 20 March 2025 is Esfand 1403, 21 March is Farvardin 1404.
    expect(inJalaliMonth(at(2025, 3, 20), at(2025, 3, 21))).toBe(false);
  });
});
