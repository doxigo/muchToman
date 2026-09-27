/**
 * Quips.kt, one to one: the line a notification can carry above its facts, in the voice she
 * picked, and the note that asks after her when the ledger goes quiet.
 *
 * The lines come from `worker/src/quips.json` on the /rates payload (the sync Worker passes it
 * through untouched), so a new one is a deploy of the rates Worker and never a release of this.
 * The title stays the plain sentence and the plain body stays under the line; at ساده, the
 * default, nothing here says anything at all.
 */
import { faNumber } from './format';
import { DAY_MS, TEHRAN_OFFSET_MS } from './jalali';
import type { LedgerEntry, Quip, QuipTone } from './model';
import { pref, setPref } from './state';

/** Ordered: a tone says every line at or below it. */
export const QUIP_TONES: QuipTone[] = ['PLAIN', 'WITTY', 'ROAST'];
export const QUIP_TONE_FA: Record<QuipTone, string> = { PLAIN: 'ساده', WITTY: 'شوخ', ROAST: 'بی‌تعارف' };

/** The tone sheet's caption: what each step changes, the note that only exists past ساده included. */
export const QUIP_TONE_CAPTION =
  'با «شوخ» کنار هر اعلان یه تیکه هم می‌ندازیم، با «بی‌تعارف» تیکه‌ها ممکنه بسوزونه. ' +
  'با هر دوتاش، اگه چند روز خرجی نبینیم حالت رو می‌پرسیم.';

/** A pref from an older build or a hand-edited backup reads as the default. */
export const quipToneOf = (tone: unknown): QuipTone => (QUIP_TONES.includes(tone as QuipTone) ? tone as QuipTone : 'PLAIN');

/** quips.json spells them in lower case; «plain» is not a line's tone, it is the absence of one. */
const wireTone = (tone: string): number => (tone === 'witty' ? 1 : tone === 'roast' ? 2 : -1);

/** The moments a line can be said at. Their placeholders are listed in the Worker's test. */
export const QUIP_EVENTS = ['budget_near', 'budget_over', 'installment', 'quiet'];

const MAX_QUIPS_PER_EVENT = 200;
const MAX_QUIP_LENGTH = 140;
const CATEGORY_ID = /^cat_[a-z_]{1,40}$/;
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;

/** Undefined stays undefined — «the Worker said nothing» — so mergeRates keeps the set it had. */
export function sanitizeQuips(raw: unknown): Record<string, Quip[]> | undefined {
  if (raw == null || typeof raw !== 'object' || Array.isArray(raw)) return undefined;
  const out: Record<string, Quip[]> = {};
  for (const event of QUIP_EVENTS) {
    const lines = (raw as Record<string, unknown>)[event];
    if (!Array.isArray(lines)) continue;
    const seen = new Set<string>();
    const kept: Quip[] = [];
    for (const line of lines as Array<Record<string, unknown> | null>) {
      if (kept.length >= MAX_QUIPS_PER_EVENT) break;
      const tone = typeof line?.tone === 'string' ? line.tone : '';
      const category = typeof line?.category === 'string' ? line.category : '';
      const text = typeof line?.text === 'string' ? line.text.trim() : '';
      if (wireTone(tone) < 0 || (category && !CATEGORY_ID.test(category)) || !text ||
        text.length > MAX_QUIP_LENGTH || CONTROL.test(text) || seen.has(text)) continue;
      seen.add(text);
      kept.push({ tone, category, text });
    }
    out[event] = kept;
  }
  return out;
}

/** Java's String.hashCode in base 36 — Quips.kt's quipKey, so the two can be checked against each other. */
export function quipKey(text: string): string {
  let hash = 0;
  for (let i = 0; i < text.length; i++) hash = (Math.imul(31, hash) + text.charCodeAt(i)) | 0;
  return hash.toString(36);
}

/** [text] with every `{placeholder}` filled, or null when one has no value to fill it with. */
export function fillQuip(text: string, vars: Record<string, string>): string | null {
  let missing = false;
  const filled = text.replace(/\{([a-z]+)\}/g, (_, name: string) => {
    const value = Object.hasOwn(vars, name) ? vars[name] : '';
    if (!value.trim()) missing = true;
    return value;
  });
  return missing ? null : filled;
}

/**
 * One line for [event], and the set of lines said to store afterwards — or null at ساده or when
 * nothing fits. A shuffle bag: nothing twice until everything that fits has been said once. A line
 * kept for another category is out; lines for every category are in beside the specific ones.
 */
export function pickQuip(
  quips: Record<string, Quip[]>, event: string, tone: QuipTone, category: string | null,
  vars: Record<string, string>, seen: Set<string>, random: () => number = Math.random,
): [string, Set<string>] | null {
  const ceiling = QUIP_TONES.indexOf(tone);
  if (ceiling <= 0) return null;
  const pool: Array<[string, string]> = [];
  for (const quip of Object.hasOwn(quips, event) ? quips[event] : []) {
    const lineTone = wireTone(quip.tone);
    if (lineTone < 0 || lineTone > ceiling || (quip.category && quip.category !== category)) continue;
    const line = fillQuip(quip.text, vars);
    if (line != null) pool.push([quipKey(quip.text), line]);
  }
  if (!pool.length) return null;
  const fresh = pool.filter(([key]) => !seen.has(key));
  const from = fresh.length ? fresh : pool;
  const [key, line] = from[Math.floor(random() * from.length)];
  const kept = new Set(seen);
  if (!fresh.length) for (const [k] of pool) kept.delete(k);
  kept.add(key);
  return [line, kept];
}

/** The body with her line on top, or the body as it was. */
export const withQuip = (quip: string | null, body: string): string => (quip == null ? body : `${quip}\n${body}`);

/**
 * [pickQuip] against what this browser holds, remembering the line as said. `{name}` is always on
 * offer. Called only from [announce], whose writes happen before anything awaits.
 */
export function takeQuip(event: string, category: string | null = null, vars: Record<string, string> = {}): string | null {
  const quips = pref('rates')?.quips;
  if (!quips) return null;
  const picked = pickQuip(quips, event, quipToneOf(pref('quipTone')), category, { ...vars, name: pref('name') }, new Set(pref('quipsSeen')));
  if (!picked) return null;
  // Pruned to the lines that still exist, so a set edited for years never grows past it.
  const live = new Set(Object.values(quips).flat().map((quip) => quipKey(quip.text)));
  setPref('quipsSeen', [...picked[1]].filter((key) => live.has(key)));
  return picked[0];
}

// ─────────────────────────── the quiet note ───────────────────────────

/** Days without a spend before the app asks after her. */
export const QUIET_AFTER_DAYS = 5;
/** A ledger quiet this long is one she stopped keeping. */
const QUIET_GIVE_UP_DAYS = 30;
const HOUR_MS = 3_600_000;

/** When she last spent, from her own rows — a household member's spend is theirs. */
export function lastSpendAt(entries: LedgerEntry[]): number | null {
  let last: number | null = null;
  for (const e of entries) {
    if (e.duplicate || e.transfer || (e.txn.signedRial ?? 0) >= 0 || e.txn.ref.startsWith('f:')) continue;
    if (last == null || e.txn.at > last) last = e.txn.at;
  }
  return last;
}

/** The days since [lastSpend] when now is the moment to ask: once a spell, 10:00–21:00 in Tehran. */
export function quietDays(lastSpend: number | null, mark: number, now: number): number | null {
  if (lastSpend == null || lastSpend === mark) return null;
  const days = Math.trunc((now - lastSpend) / DAY_MS);
  const hour = Math.floor((((now + TEHRAN_OFFSET_MS) % DAY_MS) + DAY_MS) % DAY_MS / HOUR_MS);
  return days >= QUIET_AFTER_DAYS && days < QUIET_GIVE_UP_DAYS && hour >= 10 && hour <= 20 ? days : null;
}

/** The dollar's rise since [since], whole percent in Persian digits — blank unless it rose at least one. */
export function usdRiseFa(rateHistory: Record<string, number>, since: number, usdNow: number | undefined): string {
  const sinceDay = Math.trunc(since / DAY_MS);
  let thenDay = -Infinity;
  for (const day of Object.keys(rateHistory).map(Number)) if (day <= sinceDay && day > thenDay) thenDay = day;
  const then = rateHistory[thenDay];
  if (!(then > 0) || usdNow == null) return '';
  const rise = Math.trunc((usdNow / then - 1) * 100);
  return rise >= 1 ? faNumber(rise) : '';
}

/** The fact, which is also the title: «۵ روزه خرجی ندیدیم». */
export const quietTitle = (days: number): string => `${faNumber(days)} روزه خرجی ندیدیم`;

/** The half that is not a joke: where to look when she did spend and it is not here. */
export const quietBody = (): string => 'اگه خرج کردی و اینجا نیست، «وضعیت دفتر» رو توی تنظیمات ببین.';
