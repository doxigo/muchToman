/**
 * Quick paste: an amount, a direction, and a date out of one message she pasted herself.
 *
 * Deliberately much smaller than the Android parser, and that is the decision rather than a
 * shortcut. iOS cannot read an inbox — its Message automation takes contacts and phone numbers,
 * not the alphanumeric shortcodes every Iranian bank sends from — so nothing here ever runs
 * unattended. Pasting is a manual act she is already looking at, and anything this gets wrong
 * she corrects in the same screen before it is saved.
 *
 * Porting the full 567-line bank parser would double the hardest, most-corrected code in the
 * project and put two implementations under one golden corpus for ever. The cases these two DO
 * share are held to the same file: see `sharesWithCorpus` in the tests.
 */

const IN_WORDS = ['واریز', 'بستانکار', 'افزایش یافت', 'دریافت وجه', 'نشست'];
const OUT_WORDS = [
  'برداشت', 'بدهکار', 'خرید', 'پرداخت', 'انتقال', 'کاهش یافت', 'کارمزد', 'قبض', 'پرید',
];
const AMOUNT_WORDS = ['مبلغ', 'مقدار'];
const BALANCE_WORDS = ['مانده', 'موجودی'];
const NOT_A_BALANCE = ['بدهی', 'تسهیلات', 'وام', 'قسط', 'چک', 'کارت اعتباری'];

export interface Pasted {
  amountRial: number | null;
  direction: 'in' | 'out' | null;
  balanceRial: number | null;
  printedAt: string;
}

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

export function parsePasted(body: string): Pasted {
  const text = normalise(body);
  const fallback = fallbackDivisor(text);

  const balance = figureAfter(text, BALANCE_WORDS, { allowZero: true, veto: NOT_A_BALANCE });
  const deposit = IN_WORDS.some((w) => text.includes(w));
  const withdrawal = OUT_WORDS.some((w) => text.includes(w));
  const inWords = deposit ? IN_WORDS : [];
  const outWords = withdrawal ? OUT_WORDS : [];
  const amount =
    figureAfter(text, AMOUNT_WORDS) ??
    figureAfter(text, inWords, { stopAt: BALANCE_WORDS }) ??
    figureAfter(text, outWords, { stopAt: BALANCE_WORDS }) ??
    // Nothing after the direction word, so the figure it refers to is the one in front of it: Blu
    // heads a deposit «دریافت پل», which names no direction at all, leaving «نشست» at the end of
    // the sentence as the only word that says which way the money went.
    figureBefore(text, inWords) ??
    figureBefore(text, outWords);

  // Both or neither means the message did not say which way the money went, and guessing is how
  // a deposit becomes a withdrawal.
  const direction = amount == null ? null : deposit && !withdrawal ? 'in' : withdrawal && !deposit ? 'out' : null;

  return {
    amountRial: amount ? rialOf(amount, fallback) : null,
    direction,
    balanceRial: balance ? rialOf(balance, fallback) : null,
    printedAt: printedStampIn(body),
  };
}
