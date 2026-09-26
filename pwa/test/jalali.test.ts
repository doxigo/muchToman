import { describe, expect, it } from 'vitest';
import {
  DAY_MS, jalaliDay, jalaliMonthEnd, jalaliMonthLength, jalaliMonthStart, jalaliMonthsAfter, jalaliOf,
  jalaliQuarterEnd, jalaliQuarterStart, tehranDay, tehranDayStart, weekStart,
} from '../src/jalali';

/** The port against the browser's own Persian calendar, day by day across forty years. */
const intl = new Intl.DateTimeFormat('en-u-ca-persian', { timeZone: 'UTC', year: 'numeric', month: 'numeric', day: 'numeric' });
function viaIntl(day: number): string {
  const parts = intl.formatToParts(day * DAY_MS);
  const get = (t: string) => Number(parts.find((p) => p.type === t)!.value);
  return `${get('year')}/${get('month')}/${get('day')}`;
}

describe('jalali', () => {
  it('agrees with Intl on every day from 1370 to 1420 and round-trips', () => {
    const from = jalaliDay(1370, 1, 1); const to = jalaliDay(1420, 12, 29);
    for (let d = from; d <= to; d++) {
      const j = jalaliOf(d);
      expect(`${j.year}/${j.month}/${j.day}`).toBe(viaIntl(d));
      expect(jalaliDay(j.year, j.month, j.day)).toBe(d);
    }
  });

  it('turns the day over at Tehran midnight', () => {
    const start = tehranDayStart(20_000);
    expect(tehranDay(start)).toBe(20_000); expect(tehranDay(start - 1)).toBe(19_999);
  });

  it('knows months, quarters, weeks and leap Esfands', () => {
    expect(jalaliMonthLength(1403, 12)).toBe(30); expect(jalaliMonthLength(1404, 12)).toBe(29);
    const mehr5 = jalaliDay(1405, 7, 5);
    expect(jalaliMonthStart(mehr5)).toBe(jalaliDay(1405, 7, 1));
    expect(jalaliMonthEnd(mehr5)).toBe(jalaliDay(1405, 8, 1));
    expect(jalaliQuarterStart(mehr5)).toBe(jalaliDay(1405, 7, 1));
    expect(jalaliQuarterEnd(jalaliDay(1405, 12, 3))).toBe(jalaliDay(1406, 1, 1));
    expect(jalaliMonthsAfter(jalaliDay(1405, 6, 31), 1)).toBe(jalaliDay(1405, 7, 30));
    // 26 Sep 2026 is a Saturday.
    const sat = tehranDay(Date.UTC(2026, 8, 26, 12));
    expect(weekStart(sat)).toBe(sat); expect(weekStart(sat + 6)).toBe(sat);
  });
});
