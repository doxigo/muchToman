import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { bodyToStore, parsePasted } from '../src/paste';
import { nextStamp, uuid7 } from '../src/sync';

/**
 * The paste parser, held to the **same golden corpus** the Android parser is.
 *
 * This is the whole reason the corpus is a JSON file rather than Kotlin: two implementations,
 * one set of expectations, and no way for them to drift apart without a red test. This one never
 * sees a sender, so it has no opinion about which bank sent anything — `bank` is the one field
 * it skips — but every other field has to match exactly.
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

  it('agrees with the android parser on every field it can see', () => {
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
      for (const key of ['printedAt', 'mask', 'instrument', 'merchant', 'refNo', 'channel', 'unitPrinted'] as const) {
        if (key in c.expect) expect(got[key], `${where} (${key})`).toBe(c.expect[key] ?? '');
      }
      if ('feeRial' in c.expect) expect(got.feeRial, `${where} (feeRial)`).toBe(c.expect.feeRial ?? null);
      if ('inferred' in c.expect) expect(got.inferred, `${where} (inferred)`).toBe(c.expect.inferred);
      checked++;
    }
    expect(checked).toBeGreaterThanOrEqual(25);
  });

  it('gives back nothing rather than a guess when the message says nothing', () => {
    expect(parsePasted('رمز یکبار مصرف شما: 48213').amountRial).toBeNull();
    expect(parsePasted('سلام قربونت برم').balanceRial).toBeNull();
  });

  it('reads a one-time code as nothing, even one that names a purchase', () => {
    // It asks her to approve a spend; the real debit, if she approves, arrives as its own message.
    const code = parsePasted('رمز پویا: 482139\nخرید مبلغ 1,250,000 ریال');
    expect(code.amountRial).toBeNull();
    expect(code.direction).toBeNull();
    // «کارمزد» carries «رمز» inside it and is a fee, not a code.
    expect(parsePasted('کارمزد 5,000 ریال\nبرداشت مبلغ 1,000,000 ریال').amountRial).toBe(1_000_000);
  });

  it('never reads an advert\'s «۱ میلیون تومان» as a one-toman spend', () => {
    expect(parsePasted('با خرید از فروشگاه تا ۱ میلیون تومان تخفیف بگیرید').amountRial).toBeNull();
  });

  it('reads a blu box move the way the money left', () => {
    // Blu's wording, not yet a message off a real phone. «نشست» is its word for money arriving,
    // but into a box is out of the account and back out of one is in.
    const into = parsePasted('بلو\nمبلغ 5,000,000 ریال از حساب در باکس «سفر» نشست.\nموجودی: 95,000,000 ریال');
    expect(into.direction).toBe('out');
    expect(into.amountRial).toBe(5_000_000);
    expect(parsePasted('بلو\nمبلغ 2,000,000 ریال از باکس «سفر» به حساب شما نشست.').direction).toBe('in');
  });

  it('never reads a loan balance as money', () => {
    const got = parsePasted('قسط تسهیلات پرداخت مبلغ 3,000,000 ریال\nمانده بدهی 2,400,000,000 ریال');
    expect(got.balanceRial).toBeNull();
    expect(got.amountRial).toBe(3_000_000);
  });

  it('never reads a wallet promo as the bank balance', () => {
    // The corpus pins this as a declined message, which this side skips — declining is about
    // who sent it. The veto itself still has to hold here, or a pasted promo states a balance.
    const got = parsePasted('بانک سامان\nموجودی کیف پول شما: 200,000 ریال\nهمین حالا از همراه‌بانک شارژش کن!');
    expect(got.balanceRial).toBeNull();
    expect(got.amountRial).toBeNull();
  });
});

describe('what a pasted body keeps', () => {
  it('keeps nothing of a body with no money in it, however its code is worded', () => {
    expect(bodyToStore('شناسه ورود موقت شما: 4821')).toBeNull();
    expect(bodyToStore('Your OTP is 482139. Do not share it.')).toBeNull();
    expect(bodyToStore('به همراه بانک خوش آمدید')).toBeNull();
    const debit = 'بانک سامان\nخرید مبلغ 1,250,000 ریال\nمانده 8,000,000 ریال';
    expect(bodyToStore(debit)).toBe(debit);
  });

  it('keeps a code beside a stated balance for the money, with only its digits blanked', () => {
    const raw = 'خرید مبلغ 1,250,000 ریال\nکد تایید 482139\nمانده 8,000,000 ریال';
    const kept = bodyToStore(raw)!;
    expect(kept).toBe('خرید مبلغ 1,250,000 ریال\nکد تایید ••••••\nمانده 8,000,000 ریال');
    expect(parsePasted(kept)).toEqual(parsePasted(raw));
    expect(bodyToStore(kept)).toBe(kept);
    expect(bodyToStore('مانده ۸٬۰۰۰٬۰۰۰ ریال\nکد‌تایید: ۴۸۲۱۳۹')).toBe('مانده ۸٬۰۰۰٬۰۰۰ ریال\nکد‌تایید: ••••••');
  });

  it('keeps every corpus message the parser reads, and reads the same off what it kept', () => {
    let read = 0;
    for (const c of cases()) {
      if (c.expect === null) continue;
      const body = c.body.join('\n');
      const kept = bodyToStore(body);
      expect(kept, c.id).not.toBeNull();
      expect(parsePasted(kept!), c.id).toEqual(parsePasted(body));
      read++;
    }
    expect(read).toBeGreaterThanOrEqual(25);
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
