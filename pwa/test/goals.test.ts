/** GoalsTest.kt, literally: savings goals and «آیا ارزشش را داشت؟». */
import { describe, expect, it } from 'vitest';
import {
  GoalHorizon, GoalKind, GoalPeriod, WORTH_IT_PER_WEEK, WorthIt, goalNoteFa, goalProgress, goalWindowFa,
  largeSpendThreshold, worthItAnswers, worthItCandidates, worthItSummary,
} from '../src/goals';
import { jalaliDay, jalaliOf } from '../src/jalali';
import type { Decision } from '../src/model';
import { entry as row, goalRow } from './plans-fixture';
import type { EntryOptions } from './plans-fixture';

const year = 1405;
const month = 5;
const first = jalaliDay(year, month, 1);

const entry = (day: number, signed: number, o: EntryOptions = {}) =>
  row(day, signed, { categoryId: 'cat_shopping', merchant: 'جایی', ...o });

const goal = (target: number, endsOn: number | null = null) => goalRow({
  id: 'g1', nameFa: 'سفر', targetRial: target, kind: GoalKind.SAVE, period: GoalPeriod.ONCE, startsOn: first, endsOn,
});

describe('savings goals', () => {
  it('a savings goal counts what was kept, not what came in', () => {
    const p = goalProgress(goal(60_000_000), [entry(first, 100_000_000), entry(first + 1, -40_000_000)], first + 5);
    expect(p.currentRial).toBe(60_000_000);
    expect(p.done).toBe(true);
    expect(p.share).toBeCloseTo(1, 3);
  });

  it('a transfer between her own accounts moves no goal', () => {
    const rows = [entry(first, 100_000_000), entry(first + 1, -500_000_000, { transfer: true })];
    expect(goalProgress(goal(50_000_000), rows, first + 5).currentRial).toBe(100_000_000);
  });

  it('a goal under water shows nothing set aside, never a negative figure', () => {
    const p = goalProgress(goal(50_000_000), [entry(first, 10_000_000), entry(first + 1, -55_000_000)], first + 5);
    expect(p.underWater).toBe(true);
    expect(p.currentRial).toBe(0);
    expect(p.share).toBeCloseTo(0, 3);
    expect(p.done).toBe(false);
  });

  it('progress never runs past its own bar', () => {
    expect(goalProgress(goal(10_000_000), [entry(first, 900_000_000)], first + 5).share).toBeCloseTo(1, 3);
  });

  it('a goal with no deadline says nothing about days or a monthly rate', () => {
    const p = goalProgress(goal(50_000_000), [entry(first, 10_000_000)], first + 5);
    expect(p.daysLeft).toBeNull();
    expect(p.perMonthRial).toBeNull();
    expect(p.expired).toBe(false);
  });

  it('the deadline day still counts as a day she can save in', () => {
    const ends = first + 30;
    expect(goalProgress(goal(50_000_000, ends), [], ends).daysLeft).toBe(1);
    expect(goalProgress(goal(50_000_000, ends), [], ends + 1).daysLeft).toBe(0);
  });

  it('the monthly rate is what lands on the target, rounded up', () => {
    const p = goalProgress(goal(50_000_000, first + 89), [entry(first, 10_000_000)], first);
    expect(p.remainingRial).toBe(40_000_000);
    expect(Math.trunc(p.daysLeft! / 30)).toBe(3);
    expect(p.perMonthRial).toBe(13_333_334);
    expect(p.perMonthRial! * 3).toBeGreaterThanOrEqual(p.remainingRial);
  });

  it('under a month left, there is no monthly rate to quote', () => {
    const p = goalProgress(goal(50_000_000, first + 10), [entry(first, 10_000_000)], first);
    expect(p.daysLeft).toBe(11);
    expect(p.perMonthRial).toBeNull();
  });

  it('a met goal is never asked to keep saving, and never expires', () => {
    const p = goalProgress(goal(50_000_000, first + 1), [entry(first, 60_000_000)], first + 40);
    expect(p.done).toBe(true);
    expect(p.remainingRial).toBe(0);
    expect(p.perMonthRial).toBeNull();
    expect(p.expired).toBe(false);
  });

  it('a deadline that passed unmet says so, and says what was left', () => {
    const p = goalProgress(goal(50_000_000, first + 10), [entry(first, 10_000_000)], first + 40);
    expect(p.expired).toBe(true);
    expect(p.remainingRial).toBe(40_000_000);
  });

  it('a horizon lands on the last day of its own month', () => {
    const late = jalaliDay(1405, 5, 28);
    const end = GoalHorizon.QUARTER.endsOn(late)!;
    expect(jalaliOf(end)).toEqual({ year: 1405, month: 8, day: 30 });
    expect(jalaliOf(end + 1)).toEqual({ year: 1405, month: 9, day: 1 });
    expect(GoalHorizon.OPEN.endsOn(late)).toBeNull();
  });

  it("the card's words state what is true, in order", () => {
    expect(goalWindowFa(goalProgress(goal(50_000_000), [], first))).toBe('از ۱ مرداد ۱۴۰۵');
    expect(goalWindowFa(goalProgress(goal(50_000_000, first + 30), [], first))).toBe('از ۱ مرداد ۱۴۰۵ تا ۳۱ مرداد ۱۴۰۵');
    expect(goalNoteFa(goalProgress(goal(50_000_000), [entry(first, 60_000_000)], first))).toEqual(['به هدفت رسیدی.', true]);
    expect(goalNoteFa(goalProgress(goal(50_000_000, first + 10), [entry(first, 10_000_000)], first + 40)))
      .toEqual(['مهلتش تموم شد و ۴ میلیون تومان مونده بود.', false]);
    expect(goalNoteFa(goalProgress(goal(50_000_000), [entry(first, -1_000_000)], first)))
      .toEqual(['از وقتی این هدف رو گذاشتی، هنوز چیزی پس‌انداز نشده.', false]);
    expect(goalNoteFa(goalProgress(goal(50_000_000, first + 89), [entry(first, 10_000_000)], first)))
      .toEqual(['برای رسیدن به هدف، ماهی ۱٫۳ میلیون تومان لازمه.', false]);
    expect(goalNoteFa(goalProgress(goal(50_000_000, first + 10), [entry(first, 10_000_000)], first)))
      .toEqual(['۱۱ روز مونده و ۴ میلیون تومان باقیه.', false]);
    expect(goalNoteFa(goalProgress(goal(50_000_000), [entry(first, 10_000_000)], first))).toBeNull();
  });
});

describe('«ارزش داشت؟»', () => {
  it('she is asked about at most two purchases a week, largest first', () => {
    const rows = [1, 2, 3, 4, 5, 6].map((i) => entry(first + 1, -(i * 10_000_000)));
    const asked = worthItCandidates(rows, new Set(), first + 1, 10_000_000);
    expect(asked.length).toBe(WORTH_IT_PER_WEEK);
    expect(asked[0].txn.amountRial).toBe(60_000_000);
  });

  it('bills, fees, cash and transfers are never asked about', () => {
    const rows = ['cat_bills', 'cat_fees', 'cat_cash', 'cat_transfer'].map((categoryId) => entry(first, -90_000_000, { categoryId }));
    expect(worthItCandidates(rows, new Set(), first, 1)).toEqual([]);
  });

  it('nothing unconfirmed is asked about, and nothing is asked twice', () => {
    expect(worthItCandidates([entry(first, -90_000_000, { review: true })], new Set(), first, 1)).toEqual([]);
    const settled = entry(first, -90_000_000);
    expect(worthItCandidates([settled], new Set(), first, 1).length).toBe(1);
    expect(worthItCandidates([settled], new Set([settled.txn.ref]), first, 1)).toEqual([]);
  });

  it('only the person who spent it is asked about it', () => {
    const hers = entry(first, -90_000_000);
    const his = entry(first, -95_000_000, { txnOwner: 'm_partner' });
    expect(worthItCandidates([hers, his], new Set(), first, 1).map((e) => e.txn.ref)).toEqual([hers.txn.ref]);
  });

  it('income is never asked about', () => {
    expect(worthItCandidates([entry(first, 90_000_000)], new Set(), first, 1)).toEqual([]);
  });

  it('her verdicts add up to something she can act on', () => {
    const a = entry(first, -50_000_000);
    const b = entry(first, -30_000_000);
    const c = entry(first, -20_000_000);
    const summary = worthItSummary([a, b, c], new Map([[a.txn.ref, WorthIt.YES], [b.txn.ref, WorthIt.NO], [c.txn.ref, WorthIt.NEEDED]]));
    expect(summary.worth).toBe(50_000_000);
    expect(summary.regretted).toBe(30_000_000);
    expect(summary.needed).toBe(20_000_000);
    expect(summary.total).toBe(100_000_000);
  });

  it('a quiet month does not start asking about bus fares', () => {
    expect(largeSpendThreshold([1, 2, 3, 4, 5].map(() => entry(first, -100_000)))).toBe(20_000_000);
  });

  it('reads only her live answers', () => {
    const d = (ref: string, kind: Decision['kind'], value: string | null, deleted = false): Decision =>
      ({ id: `${kind}:${ref}`, ref, kind, value, createdAt: 0, updatedAt: 0, deleted, memberId: '', familyRef: '' });
    expect(worthItAnswers([d('a', 'worth_it', 'yes'), d('b', 'worth_it', 'no', true), d('c', 'note', 'yes'), d('e', 'worth_it', null)]))
      .toEqual(new Map([['a', 'yes']]));
  });
});
