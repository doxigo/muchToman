import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { mergeRates, sanitizeRates } from '../src/data';
import { DAY_MS, tehranDay, tehranDayStart } from '../src/jalali';
import type { LedgerEntry, Quip } from '../src/model';
import {
  fillQuip, lastSpendAt, pickQuip, quietBody, quietDays, quietTitle, quipKey, quipToneOf, sanitizeQuips, usdRiseFa, withQuip,
} from '../src/quips';

/** QuipsTest.kt, case for case. The key test pins the one value both apps must compute identically. */

const witty: Quip = { tone: 'witty', category: '', text: 'شوخی' };
const roast: Quip = { tone: 'roast', category: '', text: 'تیکه' };
const dining: Quip = { tone: 'witty', category: 'cat_dining', text: 'گارسون' };
const named: Quip = { tone: 'witty', category: '', text: '{name}، سلام' };

/** A seeded stand-in for kotlin.random.Random: which line is drawn is not what these assert. */
const seeded = (seed: number) => () => { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646; };

const pick = (
  lines: Quip[],
  { tone = 'ROAST', category = null, vars = {}, seen = new Set<string>() }:
  { tone?: 'PLAIN' | 'WITTY' | 'ROAST'; category?: string | null; vars?: Record<string, string>; seen?: Set<string> } = {},
) => pickQuip({ budget_over: lines }, 'budget_over', tone, category, vars, seen, seeded(7));

describe('picking a line', () => {
  it('says nothing at plain, whatever there is to say', () => {
    expect(pick([witty, roast], { tone: 'PLAIN' })).toBeNull();
  });

  it('gives a tone its own lines and the gentler ones, never the harsher', () => {
    for (let i = 0; i < 20; i++) expect(pick([witty, roast], { tone: 'WITTY' })![0]).toBe('شوخی');
    const heard = new Set(Array.from({ length: 40 }, (_, i) =>
      pickQuip({ budget_over: [witty, roast] }, 'budget_over', 'ROAST', null, {}, new Set(), () => i / 40)![0]));
    expect(heard).toEqual(new Set(['شوخی', 'تیکه']));
  });

  it("says a line kept for a category only over that category's budget", () => {
    expect(pick([witty, dining], { tone: 'WITTY', category: 'cat_groceries', seen: new Set([quipKey('گارسون')]) })![0]).toBe('شوخی');
    expect(pick([dining], { category: 'cat_groceries' })).toBeNull();
    expect(pick([dining])).toBeNull();
    expect(pick([dining], { category: 'cat_dining' })![0]).toBe('گارسون');
  });

  it('skips a line whose placeholder has nothing to fill it', () => {
    expect(pick([named])).toBeNull();
    expect(pick([named], { vars: { name: '  ' } })).toBeNull();
    expect(pick([named], { vars: { name: 'مریم' } })![0]).toBe('مریم، سلام');
    expect(fillQuip('{constructor}', {})).toBeNull();
  });

  it('says nothing twice until everything has been said once, then refills', () => {
    const lines: Quip[] = [1, 2, 3, 4, 5].map((n) => ({ tone: 'witty', category: '', text: `خط ${n}` }));
    let seen = new Set<string>();
    const round: string[] = [];
    for (let i = 1; i <= 5; i++) {
      const [line, next] = pickQuip({ quiet: lines }, 'quiet', 'WITTY', null, {}, seen, seeded(i))!;
      round.push(line);
      seen = next;
    }
    expect(new Set(round)).toEqual(new Set(lines.map((q) => q.text)));
    expect(pickQuip({ quiet: lines }, 'quiet', 'WITTY', null, {}, seen, seeded(9))![1].size).toBe(1);
  });

  it('reads an unset or unknown tone as witty, and keeps an explicit plain', () => {
    expect(quipToneOf(undefined)).toBe('WITTY');
    expect(quipToneOf('SAVAGE')).toBe('WITTY');
    expect(quipToneOf('PLAIN')).toBe('PLAIN');
    expect(quipToneOf('ROAST')).toBe('ROAST');
  });
});

describe('what the browser accepts', () => {
  it('drops what it cannot place', () => {
    expect(sanitizeQuips(undefined)).toBeUndefined();
    expect(sanitizeQuips({
      budget_over: [
        witty,
        { tone: 'plain', text: 'ساده' },
        { tone: 'savage', text: 'نه' },
        { tone: 'witty', category: 'Dining; drop', text: 'نه' },
        { tone: 'witty', text: 'خیلی'.repeat(40) },
        { tone: 'witty', text: '  ' },
        { tone: 'witty', text: 'شوخی' },
      ],
      someday: [witty],
    })).toEqual({ budget_over: [witty] });
  });

  it('costs a malformed line that line, never the prices', () => {
    const rates = sanitizeRates({
      updatedAt: 1, toman: { usd: 100_000 },
      quips: { quiet: [{ tone: 'witty', text: 'سلام' }, { tone: 7, text: ['x'] }, 'nope'], budget_over: { not: 'a list' } },
    });
    expect(rates.toman.usd).toBe(100_000);
    expect(rates.quips).toEqual({ quiet: [{ tone: 'witty', category: '', text: 'سلام' }] });
  });

  it('keeps every line the Worker ships', () => {
    const shipped = JSON.parse(readFileSync(join(__dirname, '../../worker/src/quips.json'), 'utf8')) as Record<string, unknown[]>;
    const kept = sanitizeQuips(shipped)!;
    for (const [event, lines] of Object.entries(shipped)) expect(kept[event]).toHaveLength(lines.length);
  });

  it('keeps the lines already heard when a body has none', () => {
    const cached = { updatedAt: 1, toman: {}, coins: [], quips: { quiet: [witty] } };
    expect(mergeRates({ updatedAt: 2, toman: {}, coins: [] }, cached).quips).toEqual(cached.quips);
    expect(mergeRates({ updatedAt: 2, toman: {}, coins: [], quips: {} }, cached).quips).toEqual({});
  });

  it("keys a line by Java's string hash in base 36, as the phone does", () => {
    expect(quipKey('زنده‌ای؟')).toBe('r4mry2');
    expect(quipKey('گارسون')).toBe('-he7hu0');
    expect(quipKey('')).toBe('0');
  });

  it('keeps the facts under the line', () => {
    expect(withQuip('تیکه', '۲ میلیون مونده')).toBe('تیکه\n۲ میلیون مونده');
    expect(withQuip(null, '۲ میلیون مونده')).toBe('۲ میلیون مونده');
  });
});

describe('the quiet note', () => {
  const noon = tehranDayStart(20_000) + 12 * 3_600_000;
  const spend = (at: number, ref = 's:1:0', signedRial = -500_000, transfer = false) =>
    ({ txn: { ref, at, day: tehranDay(at), signedRial }, duplicate: false, transfer } as unknown as LedgerEntry);

  it('takes her last spend from her own money going out', () => {
    const mine = noon - 9 * DAY_MS;
    expect(lastSpendAt([
      spend(mine),
      spend(noon - DAY_MS, 'f:abc'),
      spend(noon - DAY_MS, 's:2:0', 9_000_000),
      spend(noon - DAY_MS, 's:3:0', -1, true),
    ])).toBe(mine);
    expect(lastSpendAt([])).toBeNull();
  });

  it('asks once a spell, in waking hours, and never about a ledger she stopped keeping', () => {
    const last = noon - 5 * DAY_MS;
    expect(quietDays(last, 0, noon)).toBe(5);
    expect(quietDays(noon - 4 * DAY_MS, 0, noon)).toBeNull();
    expect(quietDays(last, last, noon)).toBeNull();
    expect(quietDays(noon - 30 * DAY_MS, 0, noon)).toBeNull();
    expect(quietDays(last, 0, noon - 9 * 3_600_000)).toBeNull(); // 03:00 in Tehran
    expect(quietDays(last, 0, noon + 8 * 3_600_000)).toBe(5); // 20:00
    expect(quietDays(null, 0, noon)).toBeNull();
  });

  it("counts the dollar's rise in whole percent, and only a rise", () => {
    const since = noon - 5 * DAY_MS;
    const day = Math.trunc(since / DAY_MS);
    const history = { [day - 1]: 100_000, [day + 2]: 999_999 };
    expect(usdRiseFa(history, since, 104_900)).toBe('۴');
    expect(usdRiseFa(history, since, 100_900)).toBe('');
    expect(usdRiseFa(history, since, 90_000)).toBe('');
    expect(usdRiseFa({}, noon, 104_900)).toBe('');
    expect(usdRiseFa(history, since, undefined)).toBe('');
  });

  it('titles itself with the fact', () => {
    expect(quietTitle(5)).toBe('۵ روزه خرجی ندیدیم');
    expect(quietBody()).toContain('وضعیت دفتر');
  });
});
