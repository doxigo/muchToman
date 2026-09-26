/**
 * The banks, and a pasted message turned into ledger rows — Sms.kt's `Bank` and Derived.kt's
 * `parseToRows`, for a browser that is handed its messages rather than reading them.
 *
 * The phone knows a bank by the number a message arrives from. A pasted message has no sender,
 * so the paste sheet asks which bank it was, and that answer is `Source.bank`. Everything after
 * that is the phone's: the same reading (paste.ts, held to the same golden corpus), the same
 * `s:<hash>:<seq>` ref, the same Tehran day, the same refusal of an implausible figure.
 */
import { parsePasted } from './paste';
import { jalaliDay, jalaliMonthLength, jalaliOf, tehranDay, tehranDayStart } from './jalali';
import type { Source, Txn } from './model';

export interface Bank { name: string; fa: string; numbers: readonly string[] }

/**
 * Sms.kt's enum, in its order. The numbers are the phone's sender gate and are kept here for
 * reference only — nothing in the browser has a sender to hold against them.
 */
export const BANKS: readonly Bank[] = [
  { name: 'BLU', fa: 'بلو بانک', numbers: ['0999 998 7641', '90000258', '+9890000258', '98300087641'] },
  { name: 'SAMAN', fa: 'بانک سامان', numbers: ['0999 992 0000', '+989820000', '9820000', '6219'] },
  { name: 'REFAH', fa: 'بانک رفاه', numbers: ['100031', '100032', 'Refah Bank', 'RefahBank'] },
  { name: 'PASARGAD', fa: 'بانک پاسارگاد', numbers: ['B.Pasargad'] },
  { name: 'EGHTESAD_NOVIN', fa: 'بانک اقتصاد نوین', numbers: ['ENBank', '+98500015', '+98200050'] },
  { name: 'KHAVARMIANEH', fa: 'بانک خاورمیانه', numbers: ['20004861', '+9820004861', '+989820004860'] },
  { name: 'SADERAT', fa: 'بانک صادرات', numbers: ['+98 9870 0719', '98700719', '+98 983 000 9419', 'BankSaderat'] },
  { name: 'RESALAT', fa: 'بانک رسالت', numbers: ['ResalatBank'] },
  { name: 'PARSIAN', fa: 'بانک پارسیان', numbers: ['PARSIANBANK', '+98300054', '+98300055', '+9850001099'] },
  { name: 'MELLAT', fa: 'بانک ملت', numbers: ['Bank Mellat', '6104'] },
  { name: 'MELLI', fa: 'بانک ملی ایران', numbers: ['6037', '09830009417', '+98700717'] },
  { name: 'DEY', fa: 'بانک دی', numbers: ['Day Bank', 'DayBank', '+982000266', '+982000766'] },
  { name: 'TEJARAT', fa: 'بانک تجارت', numbers: ['TejaratBank'] },
  { name: 'SEPAH', fa: 'بانک سپه', numbers: ['SEPAH BANK'] },
  { name: 'KESHAVARZI', fa: 'بانک کشاورزی', numbers: ['KESHAVARZI'] },
  { name: 'POST_BANK', fa: 'پست بانک', numbers: ['POSTBANK', '+9850004940'] },
  { name: 'MASKAN', fa: 'بانک مسکن', numbers: ['Bank Maskan'] },
  { name: 'MEHR_IRAN', fa: 'بانک قرض‌الحسنه مهر ایران', numbers: ['B.QMEHRIRAN', '+989810008528'] },
  // Known by name only: no number was ever read off a real phone for these.
  { name: 'AYANDEH', fa: 'بانک آینده', numbers: [] },
  { name: 'SHAHR', fa: 'بانک شهر', numbers: [] },
  { name: 'SINA', fa: 'بانک سینا', numbers: [] },
  { name: 'GARDESHGARI', fa: 'بانک گردشگری', numbers: [] },
  { name: 'SARMAYEH', fa: 'بانک سرمایه', numbers: [] },
  { name: 'KARAFARIN', fa: 'بانک کارآفرین', numbers: [] },
  { name: 'TOSEE_TAAVON', fa: 'بانک توسعه تعاون', numbers: [] },
  { name: 'IRAN_ZAMIN', fa: 'بانک ایران زمین', numbers: [] },
  { name: 'SANAT_MADAN', fa: 'بانک صنعت و معدن', numbers: [] },
  // Never read off a message: what a row with no bank, or a name that has left this list, falls back to.
  { name: 'OTHER', fa: 'بانک', numbers: [] },
];

const BY_NAME = new Map(BANKS.map((b) => [b.name, b]));
export const isBank = (name: string): boolean => BY_NAME.has(name);
export const bankFa = (name: string): string => (BY_NAME.get(name) ?? BY_NAME.get('OTHER')!).fa;

/** What the paste sheet offers: every bank bar OTHER and آینده, which the phone ignores on purpose. */
export const PICKABLE_BANKS: readonly Bank[] = BANKS.filter((b) => b.name !== 'OTHER' && b.fa !== 'بانک آینده');

/** How the money moved, as far as the message says — Sms.kt `Channel`, lowercased as the rows store it. */
export type Channel = 'unknown' | 'pos' | 'atm' | 'paya' | 'satna' | 'card' | 'transfer' | 'fee' | 'bill' | 'box';
/** What the message named the account by. */
export type Instrument = 'unknown' | 'card' | 'account';
/** The unit the amount actually carried, before it was converted. */
export type PrintedUnit = 'none' | 'rial' | 'toman';

// ---- identity ------------------------------------------------------------------------------

const K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];
const rotr = (x: number, n: number): number => (x >>> n) | (x << (32 - n));

/**
 * SHA-256 as lowercase hex, synchronously.
 *
 * Synchronous because derive is: a family row's ref is `f:<sha256(familyRef)>`, computed on every
 * derive, and crypto.subtle would make the whole ledger a promise for the sake of one hash. Tested
 * against crypto.subtle and against the phone's pinned vectors.
 */
export function sha256Hex(input: string): string {
  const bytes = new TextEncoder().encode(input);
  const padded = new Uint8Array((((bytes.length + 9) + 63) >> 6) << 6);
  padded.set(bytes);
  padded[bytes.length] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, Math.floor(bytes.length / 0x20000000));
  view.setUint32(padded.length - 4, (bytes.length * 8) >>> 0);
  const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  const w = new Uint32Array(64);
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
  }
  return [...h].map((x) => x.toString(16).padStart(8, '0')).join('');
}

/**
 * The identity of one pasted message: Ledger.kt's `srcHash` with the bank she picked standing
 * where the phone puts the sender, NUL between the fields so no body can slide a boundary.
 * Async so it can move to crypto.subtle without touching a caller; today it is [sha256Hex].
 */
export async function sourceId(bank: string, body: string, at: number): Promise<string> {
  return sha256Hex(`${bank}\u0000${at}\u0000${body.trim()}`);
}

export const refOf = (srcHash: string, seq = 0): string => `s:${srcHash}:${seq}`;

// ---- reading -------------------------------------------------------------------------------

/**
 * Beyond this, a figure is a parse gone wrong rather than money: ten trillion Toman. Dropped the
 * way an asset with no rate is — left out, never guessed at.
 */
export const MAX_PLAUSIBLE_RIAL = 100_000_000_000_000;
const plausible = (rial: number | null): boolean => rial == null || Math.abs(rial) <= MAX_PLAUSIBLE_RIAL;

/** Slack for a bank clock running a little fast; further out than this is not a time. */
export const AT_FUTURE_SLACK_MS = 48 * 60 * 60 * 1000;

/** A poison stamp keeps its money and loses only its day. */
export const clampAt = (at: number, now: number): number => Math.min(Math.max(at, 0), now + AT_FUTURE_SLACK_MS);

// Bank headers really do arrive with non-breaking spaces; \p{Z} catches every Unicode separator.
const WHITESPACE = /[\s\p{Z}]+/gu;
/** Whitespace-collapsed and trimmed — a rule's merchant needle, so it must never change shape. */
export const merchantNorm = (merchant: string): string => merchant.trim().replace(WHITESPACE, ' ');

/**
 * One pasted message, read into transaction rows — Derived.kt `parseToRows`.
 *
 * Empty when the message says nothing happened: no direction and no balance is not a transaction,
 * and neither is a one-time code (paste.ts declines those outright). A bank she did not name reads
 * as OTHER, which the ledger can show and the accounts sheet cannot name.
 */
export function parseToRows(source: Source, now = Date.now()): Txn[] {
  const at = clampAt(source.at, now);
  const read = parsePasted(source.body);
  if (read.direction == null && read.balanceRial == null) return [];
  if (!plausible(read.amountRial) || !plausible(read.balanceRial)) return [];
  const bank = isBank(source.bank) ? source.bank : 'OTHER';
  return [{
    ref: refOf(source.id, 0),
    srcHash: source.id,
    seq: 0,
    at,
    day: tehranDay(at),
    bank,
    accountId: bank,
    direction: read.direction,
    amountRial: read.amountRial,
    signedRial: read.amountRial != null && read.direction != null
      ? (read.direction === 'in' ? read.amountRial : -read.amountRial) : null,
    balanceRial: read.balanceRial,
    feeRial: read.feeRial,
    mask: read.mask,
    instrument: read.instrument,
    merchant: read.merchant,
    merchantNorm: merchantNorm(read.merchant),
    refNo: read.refNo,
    printedAt: read.printedAt,
    channel: read.channel,
    unitPrinted: read.unitPrinted,
    inferred: read.inferred,
    familyRef: '',
    ownerMemberId: '',
    sourceKind: 'sms',
  }];
}

/**
 * When the bank says it happened, as an epoch millisecond — or null when the stamp does not read
 * as a Jalali date.
 *
 * The phone has the network's own stamp on every message; a pasted one has only what the bank
 * printed, and pasting last week's message today must not file it under today. A bare date is
 * Tehran midnight, which is what makes faMoment print no clock for it. Two-part stamps («05/08»)
 * carry no year and take this one, or last year's when this one's would be in the future; a
 * two-digit year is 14xx. A Gregorian year reads as null rather than as a guess.
 */
export function printedMoment(printedAt: string, now: number): number | null {
  const ascii = printedAt.replace(/[۰-۹]/g, (c) => String(c.charCodeAt(0) - 0x06f0))
    .replace(/[٠-٩]/g, (c) => String(c.charCodeAt(0) - 0x0660));
  const m = /^(\d{1,4})[/.](\d{1,2})(?:[/.](\d{1,2}))?(?:[ _-]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?$/.exec(ascii.trim());
  if (!m) return null;
  const thisYear = jalaliOf(tehranDay(now)).year;
  const [first, second, third] = [Number(m[1]), Number(m[2]), m[3] == null ? null : Number(m[3])];
  const clock = ((Number(m[4] ?? 0) * 60 + Number(m[5] ?? 0)) * 60 + Number(m[6] ?? 0)) * 1000;
  if (Number(m[4] ?? 0) > 23 || Number(m[5] ?? 0) > 59 || Number(m[6] ?? 0) > 59) return null;
  const moment = (year: number, month: number, day: number): number | null =>
    year < 1300 || year > 1500 || month < 1 || month > 12 || day < 1 || day > jalaliMonthLength(year, month)
      ? null : tehranDayStart(jalaliDay(year, month, day)) + clock;
  if (third == null) {
    const guess = moment(thisYear, first, second);
    return guess != null && guess > now + AT_FUTURE_SLACK_MS ? moment(thisYear - 1, first, second) : guess;
  }
  const year = first >= 100 ? first : 1400 + first > thisYear + 1 ? 1300 + first : 1400 + first;
  const at = moment(year, second, third);
  return at != null && at > now + AT_FUTURE_SLACK_MS ? null : at;
}
