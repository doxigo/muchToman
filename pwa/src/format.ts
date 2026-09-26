/**
 * Format.kt and the date formatters from Timeline.kt, one to one. The policy that matters is
 * the app's, not the browser's: every displayed figure is settled at nine decimals — which only
 * ever erases binary noise — and then *cut*, never rounded, because the number on screen must
 * never be larger than the real one. `toFixed` is the exact-decimal half-up the phone's
 * BigDecimal does, and the cut is made on its string, so no double arithmetic can nudge it.
 */
import { jalaliOf, tehranDay, tehranDayStart, weekStart } from './jalali';

const SETTLE_SCALE = 9;
const PERSIAN = '۰۱۲۳۴۵۶۷۸۹';
const GROUP = '٬';
const DECIMAL = '٫';

export function faDigits(s: string): string {
  return s.replace(/[0-9]/g, (d) => PERSIAN[Number(d)]);
}

/** Settled half-up at nine places, then truncated to `dec`: a plain decimal string, sign kept. */
function settleDown(value: number, dec: number): string {
  const [int, frac = ''] = Math.abs(value).toFixed(SETTLE_SCALE).split('.');
  const cut = dec > 0 ? `${int}.${frac.slice(0, dec)}` : int;
  return value < 0 && /[1-9]/.test(cut) ? `-${cut}` : cut;
}

/** «۱۲٬۳۴۵٫۶۷» from a plain decimal string, with the phone's own negative form. */
function faPlain(decimal: string, keep: 'all' | 'trim' = 'trim'): string {
  const negative = decimal.startsWith('-');
  const [int, frac0 = ''] = decimal.replace('-', '').split('.');
  const frac = keep === 'trim' ? frac0.replace(/0+$/, '') : frac0;
  const grouped = int.replace(/^0+(?=\d)/, '').replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const body = faDigits(grouped).replaceAll(',', GROUP) + (frac ? DECIMAL + faDigits(frac) : '');
  // What fa-IR's NumberFormat prints for a negative: an LRM, then U+2212.
  return negative ? `‎−${body}` : body;
}

/** Grouped Persian digits, truncated: 118500 → «۱۱۸٬۵۰۰». */
export function faNumber(value: number): string {
  if (!Number.isFinite(value)) return faPlain(String(Math.round(value)));
  if (Math.abs(value) >= 1e21) return faPlain(BigInt(Math.trunc(value)).toString());
  return faPlain(settleDown(value, 0));
}

/**
 * Persian digits with decimals kept: 0.0345 → «۰٫۰۳۴۵». Rounds half-even like the phone's
 * NumberFormat — every money path hands it an already-truncated figure, so this only matters for
 * a caller asking for fewer places than it has.
 */
export function faDecimal(value: number, decimals: number, pad = false): string {
  const factor = 10 ** decimals;
  const scaled = value * factor;
  const floor = Math.floor(scaled);
  const diff = scaled - floor;
  const even = Math.abs(diff - 0.5) < 1e-9 ? (floor % 2 === 0 ? floor : floor + 1) : Math.round(scaled);
  const fixed = decimals > 0 ? (even / factor).toFixed(decimals) : String(even);
  return faPlain(fixed === '-0' ? '0' : fixed.replace(/^-(0(\.0+)?)$/, '$1'), pad ? 'all' : 'trim');
}

export const tomanOf = (rial: number): number => rial / 10;

/** «۳۰۰ میلیون» — the magnitude said out loud, truncated. */
export function faCompact(toman: number, dec = 1, pad = false): string {
  const n = Math.abs(toman);
  let unit: string; let div: number;
  if (n >= 1e12) { unit = 'همت'; div = 1e12; }
  else if (n >= 1e9) { unit = 'میلیارد'; div = 1e9; }
  else if (n >= 1e6) { unit = 'میلیون'; div = 1e6; }
  else if (n >= 1e3) { unit = 'هزار'; div = 1e3; }
  else return faNumber(toman);
  const q = toman / div;
  if (!Number.isFinite(q)) return `${faNumber(q)} ${unit}`;
  const cut = settleDown(q, dec);
  const whole = !/\.\d*[1-9]/.test(cut);
  return `${faPlain(cut, pad && !whole ? 'all' : 'trim')} ${unit}`;
}

const ONES = ['', 'یک', 'دو', 'سه', 'چهار', 'پنج', 'شش', 'هفت', 'هشت', 'نه'];
const TEENS = ['ده', 'یازده', 'دوازده', 'سیزده', 'چهارده', 'پانزده', 'شانزده', 'هفده', 'هجده', 'نوزده'];
const TENS = ['', '', 'بیست', 'سی', 'چهل', 'پنجاه', 'شصت', 'هفتاد', 'هشتاد', 'نود'];
const HUNDREDS = ['', 'صد', 'دویست', 'سیصد', 'چهارصد', 'پانصد', 'ششصد', 'هفتصد', 'هشتصد', 'نهصد'];
const SCALES = ['', 'هزار', 'میلیون', 'میلیارد', 'هزار میلیارد'];

function tripleToWords(n: number): string {
  const parts: string[] = [];
  const h = Math.trunc(n / 100); const r = n % 100;
  if (h > 0) parts.push(HUNDREDS[h]);
  if (r >= 10 && r <= 19) parts.push(TEENS[r - 10]);
  else {
    if (Math.trunc(r / 10) > 0) parts.push(TENS[Math.trunc(r / 10)]);
    if (r % 10 > 0) parts.push(ONES[r % 10]);
  }
  return parts.join(' و ');
}

/** 10_800_000 → «ده میلیون و هشتصد هزار». Exact, never rounded. */
export function faWords(value: number): string {
  if (value === 0) return 'صفر';
  if (value < 0) return `منفی ${faWords(-value)}`;
  const groups: number[] = [];
  let v = BigInt(Math.trunc(value));
  while (v > 0n) { groups.push(Number(v % 1000n)); v /= 1000n; }
  if (groups.length > SCALES.length) return '';
  const parts: string[] = [];
  for (let i = groups.length - 1; i >= 0; i--) {
    const g = groups[i];
    if (g === 0) continue;
    const words = g === 1 && i === 1 ? '' : tripleToWords(g);
    parts.push([words, SCALES[i]].filter((s) => s.trim()).join(' '));
  }
  return parts.join(' و ');
}

export function faWordsToman(value: number): string | null {
  if (value <= 0 || value >= 1e15) return null;
  const words = faWords(Math.round(value));
  return words.trim() ? `${words} تومان` : null;
}

/** Persian, Arabic-Indic or Latin digits, ٫ or . for a decimal, separators ignored; null on anything else. */
export function parseAmount(input: string): number | null {
  let s = '';
  for (const c of input) {
    const code = c.charCodeAt(0);
    if (code >= 0x6f0 && code <= 0x6f9) s += String(code - 0x6f0);
    else if (code >= 0x660 && code <= 0x669) s += String(code - 0x660);
    else if (c >= '0' && c <= '9') s += c;
    else if (c === '.' || c === '٫') s += '.';
    else if (c === ',' || c === '،' || c === '٬' || c === ' ' || c === '‏') continue;
    else return null;
  }
  if (!s || s === '.' || (s.match(/\./g)?.length ?? 0) > 1) return null;
  const n = Number(s);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

/** How much of a thing she holds, sized for a row; truncated at the asset's own precision. */
export function faHeld(amount: number, dec: number): string {
  const d = Math.min(amount >= 1_000 ? 2 : amount >= 1 ? 4 : dec, dec);
  if (d >= dec) return faDecimal(amount, dec);
  const [int, frac = ''] = Math.abs(amount).toFixed(dec).split('.');
  const cut = d > 0 ? `${int}.${frac.slice(0, d)}` : int;
  return faPlain(amount < 0 ? `-${cut}` : cut);
}

export function faRate(rate: number): string {
  return rate >= 1_000_000 ? faCompact(rate) : faNumber(rate);
}

/** Dollars, or nothing: absent whenever it cannot be stated honestly. */
export function usdOf(toman: number, rate: number | null | undefined): number | null {
  return rate != null && rate > 0 && Number.isFinite(rate) && toman > 0 ? toman / rate : null;
}

/** First-strong isolate around a run (FSI … PDI). */
export const bidi = (s: string): string => `⁨${s}⁩`;
/** Left-to-right isolate (LRI … PDI), for a sign and its digits. */
export const ltrFigure = (s: string): string => `⁦${s}⁩`;

export function faSignedParts(toman: number, positive: boolean): [string, string | null] {
  const text = faCompact(Math.abs(toman));
  const at = text.indexOf(' ');
  const digits = at < 0 ? text : text.slice(0, at);
  const magnitude = at < 0 ? null : text.slice(at + 1);
  return [ltrFigure((positive ? '+' : '−') + digits), magnitude];
}
export function faSignedCompact(toman: number, positive: boolean): string {
  const [digits, magnitude] = faSignedParts(toman, positive);
  return magnitude == null ? digits : `${digits} ${magnitude}`;
}

/** ASCII digits for a field's raw state, trailing zeros trimmed. */
export function trimNumber(value: number, dec: number): string {
  const s = value.toFixed(dec);
  return s.includes('.') ? s.replace(/0+$/, '').replace(/\.$/, '') : s;
}

/** Persian digits grouped from the right while she types; decimals left alone. */
export function groupDigits(raw: string): string {
  const persian = (c: string): string | null => {
    const code = c.charCodeAt(0);
    if (c >= '0' && c <= '9') return PERSIAN[code - 48];
    if (code >= 0x6f0 && code <= 0x6f9) return c;
    if (code >= 0x660 && code <= 0x669) return PERSIAN[code - 0x660];
    return null;
  };
  const sep = (c: string) => c === '.' || c === '٫';
  const chars = [...raw];
  let decimalAt = chars.findIndex(sep);
  if (decimalAt < 0) decimalAt = chars.length;
  const intDigits = chars.slice(0, decimalAt).filter((c) => persian(c) != null).length;
  let out = ''; let seen = 0;
  chars.forEach((c, i) => {
    const digit = persian(c);
    if (digit != null) {
      out += digit;
      if (i < decimalAt) {
        seen++;
        const remaining = intDigits - seen;
        if (remaining > 0 && remaining % 3 === 0) out += GROUP;
      }
    } else if (sep(c)) out += DECIMAL;
    else out += c;
  });
  return out;
}

/** «۵ دقیقه پیش» — vague on purpose. */
export function faAgo(epochMillis: number, now: number): string {
  if (epochMillis <= 0) return 'هنوز به‌روز نشده';
  const mins = Math.max(0, Math.trunc((now - epochMillis) / 60_000));
  if (mins < 1) return 'همین الان';
  if (mins < 60) return `${faNumber(mins)} دقیقه پیش`;
  if (mins < 60 * 24) return `${faNumber(Math.trunc(mins / 60))} ساعت پیش`;
  return `${faNumber(Math.trunc(mins / (60 * 24)))} روز پیش`;
}

// ---- dates (Timeline.kt) -------------------------------------------------------------------

export const MONTHS = ['فروردین', 'اردیبهشت', 'خرداد', 'تیر', 'مرداد', 'شهریور', 'مهر', 'آبان', 'آذر', 'دی', 'بهمن', 'اسفند'];
export const WEEKDAYS = ['شنبه', 'یک‌شنبه', 'دوشنبه', 'سه‌شنبه', 'چهارشنبه', 'پنج‌شنبه', 'جمعه'];

export const today = (): number => tehranDay(Date.now());
export const faWeekday = (day: number): string => WEEKDAYS[day - weekStart(day)];
export function faDate(day: number): string {
  const it = jalaliOf(day);
  return `${faNumber(it.day)} ${MONTHS[it.month - 1]} ${faDigits(String(it.year))}`;
}
export const faWeekdayDate = (day: number): string => `${faWeekday(day)} ${faDate(day)}`;
export function faDay(day: number, now = today()): string {
  if (day === now) return 'امروز';
  if (day === now - 1) return 'دیروز';
  return faWeekdayDate(day);
}
export function faClock(epochMillis: number): string {
  const since = epochMillis - tehranDayStart(tehranDay(epochMillis));
  const hour = Math.trunc(since / 3_600_000);
  const minute = Math.trunc(since / 60_000) % 60;
  return faDigits(`${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`);
}
export function faMoment(at: number, day: number): string {
  return at === tehranDayStart(day) ? faWeekdayDate(day) : `${faWeekdayDate(day)}، ${bidi(faClock(at))}`;
}
export function faDayMoment(at: number, day: number, now = today()): string {
  return at === tehranDayStart(day) ? faDay(day, now) : `${faDay(day, now)}، ${bidi(faClock(at))}`;
}
