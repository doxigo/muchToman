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
  opts: { allowZero?: boolean; veto?: string[]; window?: number } = {},
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

      NUMBER.lastIndex = 0;
      for (const m of text.matchAll(NUMBER)) {
        const index = m.index!;
        if (index < start) continue;
        if (index - start > window) break;
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
  const amount =
    figureAfter(text, AMOUNT_WORDS) ??
    (deposit ? figureAfter(text, IN_WORDS) : null) ??
    (withdrawal ? figureAfter(text, OUT_WORDS) : null);

  // Both or neither means the message did not say which way the money went, and guessing is how
  // a deposit becomes a withdrawal.
  const direction = amount == null ? null : deposit && !withdrawal ? 'in' : withdrawal && !deposit ? 'out' : null;

  return {
    amountRial: amount ? rialOf(amount, fallback) : null,
    direction,
    balanceRial: balance ? rialOf(balance, fallback) : null,
    printedAt: body.match(PRINTED_AT)?.[0]?.trim() ?? '',
  };
}
