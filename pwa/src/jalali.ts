/**
 * Jalali.kt, line for line. A day is a whole number of Tehran days since the epoch, and Tehran
 * is a fixed +3:30 — Iran dropped daylight saving in 2022, and a ledger that moved its midnight
 * twice a year would move rows between days. The calendar is the Borkowski breaks table, the
 * same algorithm the app runs, so a date can never land on a different day on the two sides.
 *
 * Every division below is Kotlin's: integer, truncating toward zero. `div` is that, and `%` in
 * JavaScript already keeps the sign of the dividend the way Kotlin's does.
 */

export const DAY_MS = 86_400_000;
export const TEHRAN_OFFSET_MS = 12_600_000;
const JDN_AT_EPOCH = 2_440_588;

const div = (a: number, b: number): number => Math.trunc(a / b);

export function tehranDay(epochMillis: number): number {
  return Math.floor((epochMillis + TEHRAN_OFFSET_MS) / DAY_MS);
}

export function tehranDayStart(day: number): number {
  return day * DAY_MS - TEHRAN_OFFSET_MS;
}

/** Saturday. Epoch day 2 was a Saturday. */
export function weekStart(day: number): number {
  return day - ((((day - 2) % 7) + 7) % 7);
}

export function weekEnd(day: number): number {
  return weekStart(day) + 7;
}

export interface JalaliDate { year: number; month: number; day: number }

export function jalaliOf(day: number): JalaliDate {
  return jdnToJalali(day + JDN_AT_EPOCH);
}

export function jalaliDay(year: number, month: number, day: number): number {
  return jalaliToJdn(year, month, day) - JDN_AT_EPOCH;
}

export function jalaliMonthStart(day: number): number {
  const it = jalaliOf(day);
  return jalaliDay(it.year, it.month, 1);
}

/** Exclusive: the first day of the next month. */
export function jalaliMonthEnd(day: number): number {
  const it = jalaliOf(day);
  return jalaliDay(it.year, it.month, 1) + jalaliMonthLength(it.year, it.month);
}

export function jalaliQuarter(month: number): number {
  return div(month - 1, 3) + 1;
}

export function jalaliQuarterStart(day: number): number {
  const it = jalaliOf(day);
  return jalaliDay(it.year, (jalaliQuarter(it.month) - 1) * 3 + 1, 1);
}

export function jalaliQuarterEnd(day: number): number {
  const here = jalaliOf(day);
  const firstMonth = (jalaliQuarter(here.month) - 1) * 3 + 1;
  return firstMonth === 10 ? jalaliDay(here.year + 1, 1, 1) : jalaliDay(here.year, firstMonth + 3, 1);
}

/** The last day (inclusive) of the month `months` ahead of the one `day` is in. */
export function jalaliMonthsAheadEnd(day: number, months: number): number {
  const here = jalaliOf(day);
  const total = here.year * 12 + (here.month - 1) + months + 1;
  return jalaliDay(div(total, 12), (total % 12) + 1, 1) - 1;
}

/** The first day of the month `months` before the one `day` is in. */
export function jalaliMonthsBack(day: number, months: number): number {
  const here = jalaliOf(day);
  const total = here.year * 12 + (here.month - 1) - months;
  return jalaliDay(div(total, 12), (total % 12) + 1, 1);
}

/** The same day of the month, `months` later, clamped to that month's length. */
export function jalaliMonthsAfter(day: number, months: number): number {
  const here = jalaliOf(day);
  const total = here.year * 12 + (here.month - 1) + months;
  const year = div(total, 12);
  const month = (total % 12) + 1;
  return jalaliDay(year, month, Math.min(here.day, jalaliMonthLength(year, month)));
}

export function jalaliMonthLength(year: number, month: number): number {
  if (month <= 6) return 31;
  if (month <= 11) return 30;
  return jalCal(year).leap === 0 ? 30 : 29;
}

const BREAKS = [
  -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
  1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
];

function jalCal(jy: number): { leap: number; gy: number; march: number } {
  if (jy < -61 || jy > 3177) throw new RangeError(`jalali year out of range: ${jy}`);
  const gy = jy + 621;
  let leapJ = -14;
  let jp = BREAKS[0];
  let jump = 0;
  for (let i = 1; i < BREAKS.length; i++) {
    const jm = BREAKS[i];
    jump = jm - jp;
    if (jy < jm) break;
    leapJ += div(jump, 33) * 8 + div(jump % 33, 4);
    jp = jm;
  }
  let n = jy - jp;
  leapJ += div(n, 33) * 8 + div((n % 33) + 3, 4);
  if (jump % 33 === 4 && jump - n === 4) leapJ += 1;
  const leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150;
  const march = 20 + leapJ - leapG;
  if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33;
  let leap = (((n + 1) % 33) - 1) % 4;
  if (leap === -1) leap = 4;
  return { leap, gy, march };
}

function gregorianToJdn(gy: number, gm: number, gd: number): number {
  const a = gy + div(gm - 8, 6) + 100100;
  let d = div(a * 1461, 4) + div(153 * ((gm + 9) % 12) + 2, 5) + gd - 34840408;
  d -= div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) - 752;
  return d;
}

function jdnToGregorianYear(jdn: number): number {
  let j = 4 * jdn + 139361631;
  j += div(div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908;
  const i = div(j % 1461, 4) * 5 + 308;
  const gm = (div(i, 153) % 12) + 1;
  return div(j, 1461) - 100100 + div(8 - gm, 6);
}

function jdnToJalali(jdn: number): JalaliDate {
  const gy = jdnToGregorianYear(jdn);
  let jy = gy - 621;
  const r = jalCal(jy);
  let k = jdn - gregorianToJdn(gy, 3, r.march);
  if (k >= 0) {
    if (k <= 185) return { year: jy, month: 1 + div(k, 31), day: (k % 31) + 1 };
    k -= 186;
  } else {
    jy -= 1;
    k += 179;
    if (r.leap === 1) k += 1;
  }
  return { year: jy, month: 7 + div(k, 30), day: (k % 30) + 1 };
}

function jalaliToJdn(jy: number, jm: number, jd: number): number {
  const r = jalCal(jy);
  return gregorianToJdn(r.gy, 3, r.march) + (jm - 1) * 31 - div(jm, 7) * (jm - 7) + jd - 1;
}
