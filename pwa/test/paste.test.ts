import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parsePasted } from '../src/paste';
import { nextStamp, uuid7 } from '../src/sync';

/**
 * The quick-paste parser, held to the **same golden corpus** the Android parser is.
 *
 * This is the whole reason the corpus is a JSON file rather than Kotlin: two implementations,
 * one set of expectations, and no way for them to drift apart on the cases they share without a
 * red test. This one reads fewer fields — it never sees a sender, so it has no opinion about
 * which bank sent anything — but every field it *does* claim has to match exactly.
 */

const CORPUS = join(__dirname, '../../app/src/test/resources/sms');

interface Case {
  id: string;
  sender: string;
  body: string[];
  why: string;
  expect: Record<string, unknown> | null;
}

function cases(): Case[] {
  return readdirSync(CORPUS)
    .filter((f: string) => f.endsWith('.json'))
    .sort()
    .flatMap((f: string) => (JSON.parse(readFileSync(join(CORPUS, f), 'utf8')) as { cases: Case[] }).cases);
}

describe('quick paste', () => {
  it('reads the corpus at all', () => {
    // The corpus lives in the Android module. If the relative path ever breaks, this fails
    // loudly rather than passing zero cases in silence.
    expect(cases().length).toBeGreaterThanOrEqual(30);
  });

  it('agrees with the android parser on every amount, direction and balance it can see', () => {
    let checked = 0;
    for (const c of cases()) {
      // A declined case is declined for who sent it, and this side never sees a sender.
      if (c.expect === null) continue;
      const got = parsePasted(c.body.join('\n'));
      const where = `${c.id} — ${c.why}`;

      if ('amountRial' in c.expect) {
        expect(got.amountRial, `${where} (amountRial)`).toBe(c.expect.amountRial ?? null);
      }
      if ('direction' in c.expect) {
        expect(got.direction, `${where} (direction)`).toBe(c.expect.direction ?? null);
      }
      if ('balanceRial' in c.expect) {
        expect(got.balanceRial, `${where} (balanceRial)`).toBe(c.expect.balanceRial ?? null);
      }
      if ('printedAt' in c.expect) {
        expect(got.printedAt, `${where} (printedAt)`).toBe(c.expect.printedAt ?? '');
      }
      checked++;
    }
    expect(checked).toBeGreaterThanOrEqual(25);
  });

  it('gives back nothing rather than a guess when the message says nothing', () => {
    expect(parsePasted('رمز یکبار مصرف شما: 48213').amountRial).toBeNull();
    expect(parsePasted('سلام قربونت برم').balanceRial).toBeNull();
  });

  it('never reads a loan balance as money', () => {
    const got = parsePasted('قسط تسهیلات پرداخت مبلغ 3,000,000 ریال\nمانده بدهی 2,400,000,000 ریال');
    expect(got.balanceRial).toBeNull();
    expect(got.amountRial).toBe(3_000_000);
  });
});

describe('sync bookkeeping', () => {
  it('never stamps a record at or before the one it replaces', () => {
    // A phone with a hand-set clock is not hypothetical in Iran, and plain wall-clock
    // last-write-wins silently drops her edit when the clock is behind.
    expect(nextStamp(5000, 1000)).toBe(5001);
    expect(nextStamp(1000, 5000)).toBe(5000);
    expect(nextStamp(undefined, 42)).toBe(42);
  });

  it('generates ids that sort by the moment they were made', () => {
    const early = uuid7(1_000_000_000_000);
    const late = uuid7(2_000_000_000_000);
    expect(early < late).toBe(true);
    expect(uuid7()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });
});
