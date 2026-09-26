/**
 * A message she pasted herself, read the way the phone reads one — Sms.kt's `parseBankSms`
 * body path, less the sender gate: iOS cannot read an inbox, so the paste sheet asks which bank
 * it was instead of trusting a number, and nothing here ever runs unattended.
 *
 * Everything the phone takes out of a body is taken here too — the money, and the merchant,
 * reference, channel, fee and account mask the rules, the transfer detector and the duplicate
 * detector key on — and every field is held to the same golden corpus (`test/paste.test.ts`), so
 * the two implementations cannot drift apart on anything they both read without a red test.
 */

import type { Channel, Instrument, PrintedUnit } from './sms';

const IN_WORDS = ['واریز', 'بستانکار', 'افزایش یافت', 'دریافت وجه', 'نشست'];
const OUT_WORDS = [
  'برداشت', 'بدهکار', 'خرید', 'پرداخت', 'انتقال', 'کاهش یافت', 'کارمزد', 'قبض', 'پرید',
];
const AMOUNT_WORDS = ['مبلغ', 'مقدار'];
const BALANCE_WORDS = ['مانده', 'موجودی'];
const NOT_A_BALANCE = ['بدهی', 'تسهیلات', 'وام', 'قسط', 'چک', 'کارت اعتباری'];
// An operator's bundle or an app wallet — data, minutes, «کیف پول» — not money at a bank. The
// bare «کیف» is deliberate: normalise strips ZWNJ, so «کیف‌پول» arrives glued.
const OPERATOR_WORDS = ['اینترنت', 'بسته', 'گیگ', 'مکالمه', 'شارژ', 'کیف'];
// One veto for every place that reads a مانده as money, so a bank's own wallet promo cannot
// state the account's balance here while the Android side refuses it.
const BALANCE_VETO = [...NOT_A_BALANCE, ...OPERATOR_WORDS];

export interface Pasted {
  amountRial: number | null;
  direction: 'in' | 'out' | null;
  balanceRial: number | null;
  printedAt: string;
  feeRial: number | null;
  mask: string;
  instrument: Instrument;
  merchant: string;
  refNo: string;
  channel: Channel;
  unitPrinted: PrintedUnit;
  /** An amount and a balance, but no word for which way the money went. */
  inferred: boolean;
}

const NOTHING: Pasted = {
  amountRial: null, direction: null, balanceRial: null, printedAt: '', feeRial: null, mask: '',
  instrument: 'unknown', merchant: '', refNo: '', channel: 'unknown', unitPrinted: 'none', inferred: false,
};

/** Persian and Arabic-Indic digits fold to ASCII; every separator is dropped, the dot included. */
function digitsOf(s: string): string {
  let out = '';
  for (const c of s) {
    const code = c.codePointAt(0)!;
    if (c >= '0' && c <= '9') out += c;
    else if (code >= 0x06f0 && code <= 0x06f9) out += String(code - 0x06f0);
    else if (code >= 0x0660 && code <= 0x0669) out += String(code - 0x0660);
  }
  return out;
}

function normalise(s: string): string {
  return s
    .replace(/ي/g, 'ی')
    .replace(/ك/g, 'ک')
    .replace(/‌/g, '')
    .replace(/ /g, ' ')
    .toLowerCase();
}

const NUMBER = /[0-9۰-۹٠-٩][0-9۰-۹٠-٩,،٬.٫]*[0-9۰-۹٠-٩]|[0-9۰-۹٠-٩]/g;

/**
 * The amount with the bank's own sign glued to it — in front on a line of its own, "+6,000,000"
 * from خاورمیانه, پاسارگاد and رسالت, or behind after a label, «پایانه فروش: 4,100,000-» and
 * «سود:2,472,328+» from صادرات and ملی. The sign is the direction and outranks the words:
 * «انتقال از اینترنت بانک از کارت 9295» is money arriving. Thousands separators are required so a
 * "+98…" phone number is not a deposit, and only whitespace or a colon may stand in front of it.
 */
const GROUPED = '[0-9۰-۹٠-٩]{1,3}(?:[,،٬][0-9۰-۹٠-٩]{3})+';
const SIGNED = new RegExp(`(?<![^\\s:])(?:([+-])(${GROUPED})|(${GROUPED})([+-]))(?![0-9۰-۹٠-٩])`, 'g');

/**
 * The first signed figure that is not a balance: one with «مانده» or «موجودی» in front of it on its
 * own line is the balance being stated, and reading it as the amount would report everything the
 * account holds as the sum that moved.
 */
function signedAmount(text: string): { value: number; divisor: number | null; plus: boolean } | null {
  for (const m of text.matchAll(SIGNED)) {
    const lineStart = text.lastIndexOf('\n', m.index! - 1) + 1;
    if (BALANCE_WORDS.some((w) => text.slice(lineStart, m.index).includes(w))) continue;
    return {
      value: Number(digitsOf(m[2] ?? m[3])),
      divisor: unitAfter(text, m.index! + m[0].length),
      plus: (m[1] ?? m[4]) === '+',
    };
  }
  return null;
}

/**
 * Which way a Blu box move went: true into the account, false out of it, null when it is not one.
 * A box is her own money set aside inside Blu, and «از حساب در باکس … نشست» is money leaving the
 * account whatever «نشست» says — the side the money left decides. A purchase («پرید») never is.
 */
function boxMove(text: string): boolean | null {
  if (!text.includes('باکس') || text.includes('پرید')) return null;
  const fromBox = /از\s+باکس/.test(text);
  const fromAccount = /از\s+حساب/.test(text);
  return fromBox && !fromAccount ? true : fromAccount && !fromBox ? false : null;
}

const PRINTED_AT =
  /(?<![0-9۰-۹٠-٩.\/\-_])[0-9۰-۹٠-٩]{1,4}[/.][0-9۰-۹٠-٩]{1,2}(?:[/.][0-9۰-۹٠-٩]{1,2})?(?:[ _-]{1,3}[0-9۰-۹٠-٩]{1,2}:[0-9۰-۹٠-٩]{2}(?::[0-9۰-۹٠-٩]{2})?)?(?![0-9۰-۹٠-٩])/;

/** A clock time, hours to optional seconds, in either set of digits. */
const CLOCK = '[0-9۰-۹٠-٩]{1,2}:[0-9۰-۹٠-٩]{2}(?::[0-9۰-۹٠-٩]{2})?';

/**
 * The same time, on a line of its own rather than glued to the date — سامان and خاورمیانه write
 * it under the date, بلو above it, and [PRINTED_AT] alone sees none of those.
 *
 * Anchored to the date on both sides, so only whitespace may stand between the two and a stamp is
 * never a colon picked up from two lines away.
 */
const CLOCK_AFTER = new RegExp(`^\\s*(${CLOCK})(?![0-9۰-۹٠-٩:])`);
const CLOCK_BEFORE = new RegExp(`(?<![0-9۰-۹٠-٩:])(${CLOCK})\\s*$`);

/**
 * The stamp the bank printed, read off the raw body so the Android side and this one answer
 * «زمان ثبت» with the same string.
 *
 * A time with no date beside it is not a stamp and is left alone: half the corpus carries no date
 * at all, and a bare hour would claim the bank said when this happened when it did not.
 */
function printedStampIn(rawBody: string): string {
  const date = PRINTED_AT.exec(rawBody);
  if (!date) return '';
  const stamp = date[0].trim();
  if (stamp.includes(':')) return stamp;
  const after = date.index + date[0].length;
  const time =
    CLOCK_AFTER.exec(rawBody.slice(after))?.[1] ?? CLOCK_BEFORE.exec(rawBody.slice(0, date.index))?.[1];
  return time === undefined ? stamp : `${stamp} ${time}`;
}

function isDigit(c: string | undefined): boolean {
  if (!c) return false;
  const code = c.codePointAt(0)!;
  return (
    (c >= '0' && c <= '9') ||
    (code >= 0x06f0 && code <= 0x06f9) ||
    (code >= 0x0660 && code <= 0x0669)
  );
}

/** An account or card number rather than an amount — a dash between digit runs, or a mask star. */
function isIdentifierPart(text: string, start: number, end: number): boolean {
  const before = text[start - 1];
  const after = text[end];
  if (before === '*' || after === '*') return true;
  if (before === '-' && isDigit(text[start - 2])) return true;
  if (after === '-' && isDigit(text[end + 1])) return true;
  return false;
}

/**
 * Whether the word found at [at] sits right after «قابل», which turns an event into an ability:
 * «موجودی قابل برداشت» is what she may take out, not money leaving. Read as a withdrawal, a
 * Saman balance statement became a spend of everything the account held. normalise already
 * strips ZWNJ, so «قابل‌برداشت» arrives glued and the whitespace here is optional.
 */
function qualifiedAt(text: string, at: number): boolean {
  let i = at - 1;
  while (i >= 0 && /\s/.test(text.charAt(i))) i--;
  if (i < 3 || text.slice(i - 3, i + 1) !== 'قابل') return false;
  // «مقابل» ends in the same four letters and is a different word.
  return !/\p{L}/u.test(text.charAt(i - 4));
}

/** Whether any of [words] appears unqualified: a message whose only «برداشت» follows «قابل» states no direction. */
function statesDirection(text: string, words: string[]): boolean {
  for (const word of words) {
    let i = text.indexOf(word);
    while (i >= 0) {
      if (!qualifiedAt(text, i)) return true;
      i = text.indexOf(word, i + 1);
    }
  }
  return false;
}

/** The unit printed beside a figure, not one decided for the whole message. */
function unitAfter(text: string, end: number): number | null {
  const tail = text.slice(end, end + 14);
  if (tail.includes('تومان') || tail.includes('تومن')) return 1;
  if (tail.includes('ریال') || tail.includes('ر.ی')) return 10;
  return null;
}

function fallbackDivisor(text: string): number {
  return (text.includes('تومان') || text.includes('تومن')) && !text.includes('ریال') ? 1 : 10;
}

function figureAfter(
  text: string,
  labels: string[],
  opts: { allowZero?: boolean; veto?: string[]; window?: number; stopAt?: string[] } = {},
): { value: number; divisor: number | null } | null {
  const window = opts.window ?? 48;
  for (const label of labels) {
    let from = 0;
    for (;;) {
      const at = text.indexOf(label, from);
      if (at < 0) break;
      const start = at + label.length;
      from = start;
      if (qualifiedAt(text, at)) continue;
      const ahead = text.slice(start, start + 16);
      if (opts.veto?.some((v) => ahead.includes(v))) continue;
      // Where this search must give up rather than keep walking. A figure on the far side of
      // «موجودی» is that balance being stated, and returning it as the amount reports everything
      // she has as the sum that just moved.
      const limit = (opts.stopAt ?? [])
        .map((w) => text.indexOf(w, start))
        .filter((i) => i >= 0)
        .reduce((a, b) => Math.min(a, b), text.length);

      NUMBER.lastIndex = 0;
      for (const m of text.matchAll(NUMBER)) {
        const index = m.index!;
        if (index < start) continue;
        if (index - start > window) break;
        if (index >= limit) break;
        if (isIdentifierPart(text, index, index + m[0].length)) continue;
        const digits = digitsOf(m[0]);
        if (!digits) continue;
        const value = Number(digits);
        if (value === 0 && !opts.allowZero) continue;
        return { value, divisor: unitAfter(text, index + m[0].length) };
      }
    }
  }
  return null;
}

/**
 * The last money figure *before* any of [labels], never crossing a line break.
 *
 * Most banks name the amount and then say what became of it — "واریز مبلغ ۵۰۰٬۰۰۰" — which is what
 * {@link figureAfter} reads. Blu says it the other way round: "۱٬۰۰۰٬۰۰۰٬۰۰۰ ریال به حساب شما
 * نشست", with the only word that gives the money a direction trailing the figure it belongs to.
 *
 * Bounded to the one line because that is how these messages are written: an amount and the phrase
 * directing it are a sentence, while the موجودی, the time and the date each get a line of their
 * own. Without the bound the nearest thing behind «نشست» on the previous line is the balance,
 * which is the bug this exists to fix, arriving from the other side.
 */
function figureBefore(text: string, labels: string[]): { value: number; divisor: number | null } | null {
  for (const label of labels) {
    let from = 0;
    for (;;) {
      const at = text.indexOf(label, from);
      if (at < 0) break;
      from = at + label.length;
      if (qualifiedAt(text, at)) continue;
      const lineStart = text.lastIndexOf('\n', at - 1) + 1;

      let found: { value: number; divisor: number | null } | null = null;
      NUMBER.lastIndex = 0;
      for (const m of text.slice(lineStart, at).matchAll(NUMBER)) {
        const index = lineStart + m.index!;
        const end = index + m[0].length;
        if (isIdentifierPart(text, index, end)) continue;
        const value = Number(digitsOf(m[0]));
        if (!value) continue;
        found = { value, divisor: unitAfter(text, end) };
      }
      if (found) return found;
    }
  }
  return null;
}

/** Whole Rial, exactly as the Android side stores it, so the two never disagree about a unit. */
function rialOf(figure: { value: number; divisor: number | null }, fallback: number): number {
  return Math.round(figure.value * (10 / (figure.divisor ?? fallback)));
}

/**
 * Under this, a run of digits is not the amount of a transaction: «... تا ۱ میلیون تومان تخفیف»
 * in a bank's advert is a «۱» with a unit close behind it. Banks print amounts in full, never in
 * words, so a figure that needs «میلیون» to be money is prose.
 */
const MIN_MONEY_FIGURE = 1000;

/**
 * A رمز پویا asks her to approve a purchase; it does not report one. «رمز» as a word of its own,
 * never the one inside «کارمزد» or «رمزارز».
 */
const OTP = /(?<!\p{L})رمز(?!\p{L})|رمز ?(?:پویا|دوم)/u;
/** The same code worded with «کد» — a closed list, never «کد» alone, which is «کد پیگیری» too. */
const OTP_CODE = /(?<!\p{L})کد ?(?:تایید|تأیید|تائید|یک ?بار|فعال ?سازی|ورود|پویا)/gu;

const statedBalance = (text: string) =>
  figureAfter(text, BALANCE_WORDS, { allowZero: true, veto: BALANCE_VETO });

/**
 * Whether a body is a one-time code. A «کد» wording is let through when the message states a
 * مانده: a debit that prints its authorisation code as «کد تایید» and was dropped would be gone
 * for good, balance and all, while a code kept beside a balance costs one row she can hide.
 */
export function isOneTimeCode(body: string): boolean {
  const text = normalise(body);
  // search, not test: a global regex's test moves lastIndex, and matchAll below would start there.
  return OTP.test(text) || (text.search(OTP_CODE) >= 0 && statedBalance(text) == null);
}

const GROUPED_FIGURE = /[0-9۰-۹٠-٩]{1,3}(?:[,،٬.٫][0-9۰-۹٠-٩]{3})+/;
const UNIT_WORDS = ['ریال', 'ر.ی', 'تومان', 'تومن'];
const carriesMoney = (text: string): boolean =>
  [...BALANCE_WORDS, ...AMOUNT_WORDS, ...IN_WORDS, ...OUT_WORDS, ...UNIT_WORDS].some((w) => text.includes(w)) ||
  GROUPED_FIGURE.test(text);

const CODE_AFTER = /^[^0-9۰-۹٠-٩\n•]{0,24}([0-9۰-۹٠-٩]{4,10})(?![0-9۰-۹٠-٩,،٬.٫/:\-])/;
const DIGIT_RUN = /[0-9۰-۹٠-٩]+/g;

/**
 * What is kept of a pasted body, or null for none of it — Sms.kt `bodyToStore`. A one-time code
 * is refused, a body that says nothing about money is refused, and a «کد» code set beside a
 * stated مانده is kept for the money with only the code's own digits blanked. Nothing is refused
 * that the ledger reads, so a body is never worth less stored than it was pasted.
 */
export function bodyToStore(body: string): string | null {
  if (isOneTimeCode(body)) return null;
  const text = normalise(body);
  if (!carriesMoney(text)) return null;
  // Found in the normalised text, blanked in the raw body by its place among the digit runs:
  // normalising never adds, drops or reorders a digit, so the n-th run is the same run in both.
  const runs = [...text.matchAll(DIGIT_RUN)];
  const codes = new Set<number>();
  for (const m of text.matchAll(OTP_CODE)) {
    const from = m.index! + m[0].length;
    const code = CODE_AFTER.exec(text.slice(from));
    if (!code) continue;
    const n = runs.findIndex((r) => r.index === from + code[0].length - code[1].length);
    if (n >= 0) codes.add(n);
  }
  if (!codes.size) return body;
  let n = -1;
  return body.replace(DIGIT_RUN, (run) => {
    n++;
    return codes.has(n) && run === runs[n]?.[0] ? '•'.repeat(run.length) : run;
  });
}

// ---- enrichment: nothing below feeds the money above ----------------------------------------

const FEE_WORDS = ['کارمزد', 'هزینه'];
const REF_WORDS = ['پیگیری', 'رهگیری', 'مرجع', 'شماره سند', 'شناسه پرداخت'];
/** خاورمیانه prints its reference as "020/000016703" with no label at all. */
const SLASHED_REF = /[0-9۰-۹٠-٩]{2,}\/[0-9۰-۹٠-٩]{4,}/;
// «مرکز» is deliberately not here: its only use was اقتصاد نوین signing its own call centre.
const MERCHANT_WORDS = ['فروشگاه', 'پذیرنده', 'به نام', 'بنام'];
const WHITESPACE = /[\s\p{Z}]+/gu;

const digitsIn = (s: string): number => [...s].filter(isDigit).length;

/** Some banks mask a tail ("۱۲۳****"); Saman prints the number in full ("829-800-1092308-1"). */
const MASKED = /[0-9۰-۹٠-٩]*\*{2,}[0-9۰-۹٠-٩]*/;
const DASHED = /[0-9۰-۹٠-٩]{2,}(?:-[0-9۰-۹٠-٩]+)+/g;
function accountIn(text: string): string {
  const masked = MASKED.exec(text);
  if (masked) return masked[0];
  // A Jalali date written 1405-05-01 is digits and dashes too; an account number is longer.
  for (const m of text.matchAll(DASHED)) if (digitsIn(m[0]) >= 10) return m[0];
  return '';
}

function instrumentOf(mask: string): Instrument {
  if (!mask) return 'unknown';
  if (mask.includes('*')) return 'card';
  // Sixteen digits is a card however it is punctuated; Iranian account numbers are shorter.
  return digitsIn(mask) >= 16 ? 'card' : 'account';
}

/** Most specific first: «پایانه فروش» beats «خرید», «کارت به کارت» beats the «انتقال» beside it. */
function channelOf(text: string): Channel {
  if (boxMove(text) != null) return 'box';
  if (text.includes('خودپرداز') || text.includes('atm')) return 'atm';
  if (text.includes('پایانه فروش') || text.includes('پایانه')) return 'pos';
  if (text.includes('کارت به کارت')) return 'card';
  if (text.includes('ساتنا')) return 'satna';
  if (text.includes('پایا')) return 'paya';
  if (text.includes('قبض')) return 'bill';
  if (text.includes('خرید')) return 'pos';
  if (text.includes('انتقال')) return 'transfer';
  // Last, because a fee named beside a purchase does not make the purchase a fee.
  if (FEE_WORDS.some((w) => text.includes(w))) return 'fee';
  return 'unknown';
}

/**
 * A fee named inside another transaction's message. It must name its own unit or be too big to
 * be a count: پاسارگاد's «کارمزد پیامک بانکی ۶ ماهه» is six months, not six Rial.
 */
function feeIn(text: string, fallback: number): number | null {
  const fee = figureAfter(text, FEE_WORDS);
  if (!fee || (fee.divisor == null && fee.value < 1000)) return null;
  return rialOf(fee, fallback);
}

function refIn(text: string): string {
  for (const word of REF_WORDS) {
    const at = text.indexOf(word);
    if (at < 0) continue;
    for (const m of text.slice(at + word.length).matchAll(NUMBER)) {
      if (m.index! > 24) break;
      if (digitsIn(m[0]) >= 6) return m[0];
    }
  }
  return SLASHED_REF.exec(text)?.[0] ?? '';
}

/** The shop or terminal, cut at a colon, a line break or a digit run (a phone number, a terminal code). */
function merchantIn(text: string): string {
  for (const word of MERCHANT_WORDS) {
    const at = text.indexOf(word);
    if (at < 0) continue;
    const tail = text.slice(at + word.length).replace(/^[ :\-،]+/, '');
    let name = '';
    for (const c of tail) {
      if (c === '\n' || c === ':' || c === '،' || isDigit(c)) break;
      name += c;
    }
    name = name.trim();
    if (name.length >= 2) return name.replace(WHITESPACE, ' ');
  }
  return '';
}

export function parsePasted(body: string): Pasted {
  if (isOneTimeCode(body)) return NOTHING;
  const text = normalise(body);
  const fallback = fallbackDivisor(text);

  const balance = statedBalance(text);
  const deposit = statesDirection(text, IN_WORDS);
  const withdrawal = statesDirection(text, OUT_WORDS);
  const inWords = deposit ? IN_WORDS : [];
  const outWords = withdrawal ? OUT_WORDS : [];
  const signed = signedAmount(text);
  const found =
    signed ??
    figureAfter(text, AMOUNT_WORDS, { stopAt: BALANCE_WORDS }) ??
    figureAfter(text, inWords, { stopAt: BALANCE_WORDS }) ??
    figureAfter(text, outWords, { stopAt: BALANCE_WORDS }) ??
    // Nothing after the direction word, so the figure it refers to is the one in front of it: Blu
    // heads a deposit «دریافت پل», which names no direction at all, leaving «نشست» at the end of
    // the sentence as the only word that says which way the money went.
    figureBefore(text, inWords) ??
    figureBefore(text, outWords);
  const amount = found && found.value >= MIN_MONEY_FIGURE ? found : null;

  // Both or neither means the message did not say which way the money went, and guessing is how
  // a deposit becomes a withdrawal.
  const box = boxMove(text);
  const direction =
    amount == null ? null
    : signed ? (signed.plus ? 'in' : 'out')
    : box != null ? (box ? 'in' : 'out')
    : deposit && !withdrawal ? 'in'
    : withdrawal && !deposit ? 'out'
    : null;

  const mask = accountIn(text);
  return {
    amountRial: amount ? rialOf(amount, fallback) : null,
    direction,
    balanceRial: balance ? rialOf(balance, fallback) : null,
    printedAt: printedStampIn(body),
    feeRial: feeIn(text, fallback),
    mask,
    instrument: instrumentOf(mask),
    merchant: merchantIn(text),
    refNo: refIn(text),
    channel: channelOf(text),
    unitPrinted: amount?.divisor === 1 ? 'toman' : amount?.divisor === 10 ? 'rial' : 'none',
    inferred: direction == null && balance != null && amount != null,
  };
}
