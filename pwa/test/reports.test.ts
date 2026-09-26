/**
 * ReportsTest.kt, literally: the reports are the only place the ledger becomes a claim about her
 * money, so every one of them has to be arithmetic that can be checked by hand. (The LedgerLens
 * and review-queue tests belong to the ledger module.)
 */
import { describe, expect, it } from 'vitest';
import { faNumber } from '../src/format';
import { jalaliDay, jalaliMonthLength, weekStart } from '../src/jalali';
import type { LedgerEntry } from '../src/model';
import {
  REPORT_SPANS, ReportMonth, ReportRange, ReportSpan, availableReportMonths, bufferDays, buildCashFlow, buildStory,
  categoryWindow, chartWindow, memberShares, memberWindow, monthReport, narrate, periodReport, quietWins, weekRange,
} from '../src/reports';
import type { PeriodReport } from '../src/reports';
import { entry as row } from './plans-fixture';
import type { EntryOptions } from './plans-fixture';

const CAT_LOAN = 'cat_loan';
const CAT_LOAN_BACK = 'cat_loan_back';
const CAT_SPOUSE = 'cat_spouse';
const CAT_TRANSFER = 'cat_transfer';
const CAT_INCOME = 'cat_income';

const entry = (day: number, signed: number | null, o: EntryOptions = {}) => row(day, signed, o);
const loan = (day: number, signed: number) => entry(day, signed, { category: 'قرض', categoryId: CAT_LOAN });
const loanBack = (day: number, signed: number) => entry(day, signed, { category: 'پس‌گرفتن قرض', categoryId: CAT_LOAN_BACK });
const spouse = (day: number, signed: number) => entry(day, signed, { category: 'همسر', categoryId: CAT_SPOUSE });

const year = 1405;
const month = 5;
const here = new ReportMonth(year, month);
const first = jalaliDay(year, month, 1);
const m = (y: number, mo: number) => new ReportMonth(y, mo);
const mr = (rows: LedgerEntry[], y: number, mo: number) => monthReport(rows, m(y, mo));
const set = (...ids: string[]) => new Set(ids);

describe('reports', () => {
  it('prices a window from the rates recorded inside it, or not at all', () => {
    const range = new ReportRange(here, here);
    const recorded = { [first - 1]: 500_000, [first]: 100_000, [first + 1]: 120_000, [range.endDay]: 900_000 };
    expect(range.usdRate(recorded)).toBeCloseTo(110_000, 3);
    expect(new ReportRange(here.previous(), here.previous()).usdRate(recorded)).toBeCloseTo(500_000, 3);
    expect(new ReportRange(m(year, 3), m(year, 3)).usdRate(recorded)).toBeNull();
    expect(range.usdRate({})).toBeNull();
    expect(range.usdRate({ [first]: 0, [first + 1]: NaN })).toBeNull();
  });

  it('income and spending are what the month actually moved', () => {
    const r = mr([entry(first, 100_000_000), entry(first + 1, -30_000_000), entry(first + 2, -20_000_000)], year, month);
    expect(r.incomeRial).toBe(100_000_000);
    expect(r.spentRial).toBe(50_000_000);
    expect(r.netRial).toBe(50_000_000);
    expect(r.savingsRate).toBeCloseTo(0.5, 4);
  });

  it('a transfer between her own accounts is neither income nor spending', () => {
    const r = mr([
      entry(first, 100_000_000),
      entry(first + 1, -500_000_000, { transfer: true }),
      entry(first + 1, 500_000_000, { transfer: true }),
    ], year, month);
    expect(r.incomeRial).toBe(100_000_000);
    expect(r.spentRial).toBe(0);
    expect(r.transactions).toBe(1);
  });

  it('the later leg of a duplicate is not counted twice', () => {
    expect(mr([entry(first, -5_000_000), entry(first, -5_000_000, { duplicate: true })], year, month).spentRial).toBe(5_000_000);
  });

  it('the daily average divides by the days lived, not by the window length', () => {
    const length = jalaliMonthLength(year, month);
    const r = mr([entry(first, -10_000_000), entry(first + 1, -10_000_000), entry(first + 2, -10_000_000)], year, month);
    expect(r.range.daysSoFar(first + 2)).toBe(3);
    expect(r.dailySpendRial(first + 2)).toBe(10_000_000);
    expect(r.range.daysSoFar(first + length)).toBe(length);
    expect(r.dailySpendRial(first + length)).toBe(Math.trunc(30_000_000 / length));
    expect(r.range.daysSoFar(first + length + 400)).toBe(length);
    expect(r.range.daysSoFar(first - 10)).toBe(1);
  });

  it('an average is absent until the window is long enough to be one', () => {
    const r = mr([entry(first, -10_000_000), entry(first + 7, -60_000_000)], year, month);
    expect(r.dailySpendRial(first)).toBeNull();
    expect(r.dailySpendRial(first + 1)).not.toBeNull();
    expect(r.weeklySpendRial(first + 6)).toBeNull();
    expect(r.weeklySpendRial(first + 12)).toBeNull();
    expect(r.weeklySpendRial(first + 13)).toBe(Math.trunc((70_000_000 * 7) / 14));
    expect(r.weeklySpendRial(first + 19)).toBe(Math.trunc((70_000_000 * 7) / 20));
    const quiet = mr([entry(first, 90_000_000)], year, month);
    expect(quiet.dailySpendRial(first + 20)).toBeNull();
    expect(quiet.weeklySpendRial(first + 20)).toBeNull();
  });

  it('the average counts exactly the خرج the card above it prints', () => {
    const rows = [
      entry(first, -10_000_000),
      loan(first + 1, -500_000_000),
      entry(first + 2, -10_000_000, { category: 'قبض', categoryId: 'cat_bill' }),
    ];
    const r = periodReport(rows, new ReportRange(here, here), false, set('cat_bill'));
    expect(r.spentRial).toBe(10_000_000);
    expect(r.dailySpendRial(first + 9)).toBe(10_000_000 / 10);
  });

  it("a week's report averages over the week and says nothing weekly about it", () => {
    const week = weekStart(first + 10);
    const whole = periodReport([0, 1, 2, 3, 4, 5, 6].map((i) => entry(week + i, -7_000_000)), weekRange(week));
    expect(whole.spentRial).toBe(49_000_000);
    expect(whole.range.daysSoFar(week + 6)).toBe(7);
    expect(whole.dailySpendRial(week + 6)).toBe(7_000_000);
    expect(whole.weeklySpendRial(week + 6)).toBeNull();
    const sofar = periodReport([0, 1, 2].map((i) => entry(week + i, -7_000_000)), weekRange(week));
    expect(sofar.spentRial).toBe(21_000_000);
    expect(sofar.range.daysSoFar(week + 2)).toBe(3);
    expect(sofar.dailySpendRial(week + 2)).toBe(7_000_000);
  });

  it('a month with no income has no savings rate rather than a bad one', () => {
    expect(mr([entry(first, -5_000_000)], year, month).savingsRate).toBeNull();
  });

  it('nothing from another month leaks in', () => {
    const rows = [entry(first - 1, -9_000_000), entry(first, -1_000_000), entry(first + jalaliMonthLength(year, month), -8_000_000)];
    expect(mr(rows, year, month).spentRial).toBe(1_000_000);
  });

  // ─────────────────────────── the month as a value ───────────────────────────

  it('the month before فروردین is اسفند of the year before', () => {
    expect(m(1405, 1).previous()).toEqual(m(1404, 12));
    expect(m(1404, 12).next()).toEqual(m(1405, 1));
    expect(here.previous()).toEqual(m(1405, 4));
    expect(here.next()).toEqual(m(1405, 6));
    expect(m(1404, 12).compareTo(m(1405, 1))).toBeLessThan(0);
    expect(m(1405, 6).compareTo(here)).toBeGreaterThan(0);
  });

  it('a month ends exactly where the next one starts', () => {
    for (let mo = 1; mo <= 12; mo++) expect(m(1404, mo).endDay).toBe(m(1404, mo).next().startDay);
    expect(m(1404, 12).endDay).toBe(jalaliDay(1405, 1, 1));
  });

  it('a month writes itself out with its year and no separator', () => {
    expect(here.fa).toBe('مرداد ۱۴۰۵');
    expect(m(1404, 12).fa).toBe('اسفند ۱۴۰۴');
  });

  // ─────────────────────────── which months she may ask about ───────────────────────────

  it('with nothing recorded, the only month she can report on is this one', () => {
    expect(availableReportMonths([], first + 10)).toEqual([here]);
  });

  it('an empty month between the first and this one is still a month', () => {
    expect(availableReportMonths([entry(jalaliDay(1405, 3, 5), -1_000_000)], first + 10)).toEqual([m(1405, 3), m(1405, 4), here]);
  });

  it('a transfer or a duplicate does not open a month of its own', () => {
    const rows = [
      entry(jalaliDay(1404, 1, 5), -5_000_000, { transfer: true }),
      entry(jalaliDay(1404, 2, 5), -5_000_000, { duplicate: true }),
      entry(jalaliDay(1405, 4, 3), -1_000_000),
    ];
    expect(availableReportMonths(rows, first + 10)).toEqual([m(1405, 4), here]);
  });

  it('no month after this one is ever offered', () => {
    expect(availableReportMonths([entry(first + 40, -1_000_000)], first + 10)).toEqual([here]);
  });

  it('the chart shows six months, in order, and never invents one', () => {
    const available = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((mo) => m(1405, mo));
    expect(chartWindow(available, m(1405, 10))).toEqual([5, 6, 7, 8, 9, 10].map((mo) => m(1405, mo)));
    expect(chartWindow(available, m(1405, 2))).toEqual([1, 2, 3, 4, 5, 6].map((mo) => m(1405, mo)));
    const short = [m(1405, 4), m(1405, 5)];
    expect(chartWindow(short, m(1405, 5))).toEqual(short);
    expect(chartWindow(short, m(1399, 1))).toEqual([]);
  });

  // ─────────────────────────── both sides of the month ───────────────────────────

  it('income is categorised as well as spending', () => {
    const r = mr([
      entry(first, 90_000_000, { category: 'حقوق' }),
      entry(first, 10_000_000, { category: 'فروش' }),
      entry(first + 1, -30_000_000, { category: 'خوراکی' }),
      entry(first + 2, -40_000_000, { category: 'اجاره' }),
    ], year, month);
    expect(r.incomeByCategory).toEqual([['حقوق', 90_000_000], ['فروش', 10_000_000]]);
    expect(r.spendingByCategory).toEqual([['اجاره', 40_000_000], ['خوراکی', 30_000_000]]);
  });

  it('neither side of the categories counts a transfer or a duplicate', () => {
    const r = mr([
      entry(first, 100_000_000, { category: 'حقوق' }),
      entry(first, 500_000_000, { category: 'حقوق', transfer: true }),
      entry(first, 100_000_000, { category: 'حقوق', duplicate: true }),
      entry(first + 1, -20_000_000, { category: 'خوراکی' }),
      entry(first + 1, -20_000_000, { category: 'خوراکی', duplicate: true }),
      entry(first + 1, -500_000_000, { category: 'خوراکی', transfer: true }),
    ], year, month);
    expect(r.incomeByCategory).toEqual([['حقوق', 100_000_000]]);
    expect(r.spendingByCategory).toEqual([['خوراکی', 20_000_000]]);
    expect(r.incomeRial).toBe(100_000_000);
    expect(r.spentRial).toBe(20_000_000);
  });

  // ─────────────────────────── money that only passes through ───────────────────────────

  it('a قرض is neither spent nor earned', () => {
    const r = monthReport([
      entry(first, 200_000_000, { category: 'حقوق' }),
      entry(first + 1, -80_000_000, { category: 'خوراکی' }),
      loan(first + 2, -500_000_000),
    ], here);
    expect(r.incomeRial).toBe(200_000_000);
    expect(r.spentRial).toBe(80_000_000);
    expect(r.savingsRate).toBeCloseTo(0.6, 4);
    expect(r.passedRial).toBe(500_000_000);
    expect(r.passedFa).toBe('قرض');
    expect(r.spendingByCategory).toEqual([['خوراکی', 80_000_000]]);
  });

  it('getting a قرض back is not a month to be congratulated for', () => {
    const rows = [
      entry(first, 200_000_000, { category: 'حقوق' }),
      loan(first + 2, -500_000_000),
      entry(first + 31, 200_000_000, { category: 'حقوق' }),
      loanBack(first + 33, 500_000_000),
    ];
    const lent = monthReport(rows, here);
    const repaid = monthReport(rows, here.next());
    expect(lent.savingsRate).toBeCloseTo(1, 4);
    expect(repaid.savingsRate).toBeCloseTo(1, 4);
    expect(repaid.incomeRial).toBe(200_000_000);
    expect(quietWins(repaid, lent, null, true)).toEqual([]);
  });

  it('counting it puts every figure back', () => {
    const r = monthReport([
      entry(first, 200_000_000, { category: 'حقوق' }),
      entry(first + 1, -80_000_000, { category: 'خوراکی' }),
      loan(first + 2, -500_000_000),
    ], here, true);
    expect(r.incomeRial).toBe(200_000_000);
    expect(r.spentRial).toBe(580_000_000);
    expect(r.countedPassThrough).toBe(true);
    expect(r.spendingByCategory).toEqual([['قرض', 500_000_000], ['خوراکی', 80_000_000]]);
    expect(r.passedRial).toBe(500_000_000);
  });

  it('the label names only what the window actually holds', () => {
    expect(monthReport([entry(first, -1_000_000)], here).passedFa).toBe('');
    expect(monthReport([loan(first, -1_000_000)], here).passedFa).toBe('قرض');
    expect(monthReport([spouse(first, -1_000_000)], here).passedFa).toBe('همسر');
    expect(monthReport([spouse(first, -1_000_000), loan(first + 1, -2_000_000)], here).passedFa).toBe('قرض و همسر');
    const halves = [loan(first, -5_000_000), loanBack(first + 1, 5_000_000)];
    expect(monthReport(halves, here).passedFa).toBe('قرض');
    expect(monthReport(halves, here).passedRial).toBe(10_000_000);
  });

  it('the savings rate names the قرض it left out, and only while it is out', () => {
    const rows = [entry(first, 200_000_000, { category: 'حقوق' }), loan(first + 2, -500_000_000)];
    const whyOfRate = (report: PeriodReport) => narrate(report, null, rows, null, true).find((i) => i.text.includes('درآمدت'))!.why;
    expect(whyOfRate(monthReport(rows, here))).toContain('قرض');
    expect(whyOfRate(monthReport(rows, here, true))).not.toContain('قرض');
  });

  it('انتقال بین حساب‌ها is already out, by the older and stronger gate', () => {
    const rows = [
      entry(first, 200_000_000, { category: 'حقوق' }),
      entry(first + 1, -500_000_000, { category: 'انتقال بین حساب‌ها', categoryId: CAT_TRANSFER, transfer: true }),
    ];
    const r = monthReport(rows, here);
    expect(r.incomeRial).toBe(200_000_000);
    expect(r.spentRial).toBe(0);
    expect(r.passedRial).toBe(0);
    expect(r.passedFa).toBe('');
    expect(monthReport(rows, here, true).spentRial).toBe(0);
  });

  it('a month of nothing but قرض is still a month she can open', () => {
    const rows = [loan(first, -500_000_000)];
    expect(availableReportMonths(rows, first)).toEqual([here]);
    expect(monthReport(rows, here).transactions).toBe(1);
  });

  it("the chart's bars leave it out on the same terms as the card above them", () => {
    const rows = [entry(first, 200_000_000, { category: 'حقوق' }), loan(first + 2, -500_000_000)];
    const apart = buildCashFlow(rows, 0, first + 5, here);
    const bar = (c: typeof apart) => c.series.find((s) => s.month.equals(here))!;
    expect(bar(apart).spentRial).toBe(apart.period.spentRial);
    expect(bar(apart).spentRial).toBe(0);
    const folded = buildCashFlow(rows, 0, first + 5, here, { countPassThrough: true });
    expect(bar(folded).spentRial).toBe(folded.period.spentRial);
    expect(bar(folded).spentRial).toBe(500_000_000);
  });

  it('lending does not shorten the runway', () => {
    const rows = [...Array.from({ length: 20 }, (_, i) => entry(first + i, -1_000_000)), loan(first + 20, -500_000_000)];
    expect(bufferDays(rows, 100_000_000, first + 25)).toBe(100);
  });

  it('runway uses the median day so one big repair does not shorten it', () => {
    const rows = [...Array.from({ length: 20 }, (_, i) => entry(first + i, -1_000_000)), entry(first + 20, -500_000_000)];
    expect(bufferDays(rows, 100_000_000, first + 25)).toBe(100);
  });

  it('too little history says nothing at all', () => {
    const rows = Array.from({ length: 5 }, (_, i) => entry(first + i, -1_000_000));
    expect(bufferDays(rows, 100_000_000, first + 10)).toBeNull();
  });

  it('the automatic share is the number the app is judged on', () => {
    const rows = [entry(first, -1_000_000), entry(first, -1_000_000), entry(first, -1_000_000, { review: true }), entry(first, -1_000_000)];
    expect(mr(rows, year, month).automaticShare).toBeCloseTo(0.75, 4);
  });

  // ─────────────────────────── what it says out loud ───────────────────────────

  it('every insight carries the transactions it came from', () => {
    const rows = [entry(first, 100_000_000), entry(first + 1, -40_000_000)];
    const insights = narrate(mr(rows, year, month), null, rows, 45, true);
    expect(insights.length).toBeGreaterThan(0);
    for (const insight of insights) expect(insight.why.trim()).not.toBe('');
    expect(insights.find((i) => i.text.includes('درآمدت'))!.refs.length).toBeGreaterThan(0);
  });

  it('a report on a past month cites that month and nothing else', () => {
    const july = m(1405, 4);
    const before = entry(july.startDay - 1, -3_000_000);
    const inside = entry(july.startDay + 2, -3_000_000);
    const after = entry(july.endDay, -3_000_000);
    const earned = entry(july.startDay + 1, 20_000_000);
    const rows = [before, inside, after, earned];
    const insights = narrate(monthReport(rows, july), null, rows, null, false);
    expect(insights.length).toBeGreaterThan(0);
    const refs = new Set(insights.flatMap((i) => i.refs));
    expect(refs.has(inside.txn.ref)).toBe(true);
    expect(refs.has(earned.txn.ref)).toBe(true);
    expect(refs.has(before.txn.ref)).toBe(false);
    expect(refs.has(after.txn.ref)).toBe(false);
  });

  it('only the month she is standing in says «این ماه»', () => {
    const rows = [entry(first, 100_000_000), entry(first + 1, -40_000_000)];
    const now = mr(rows, year, month);
    expect(narrate(now, null, rows, null, true).map((i) => i.text).join(' ')).toContain('این ماه');
    const past = narrate(now, null, rows, null, false).map((i) => i.text).join(' ');
    expect(past).not.toContain('این ماه');
    expect(past).toContain('مرداد ۱۴۰۵');
  });

  it('nothing is celebrated for being fewer toman', () => {
    const before = monthReport([entry(first - 31, 100_000_000), entry(first - 30, -90_000_000)], here.previous());
    const now = mr([entry(first, 40_000_000), entry(first + 1, -38_000_000)], year, month);
    expect(now.spentRial).toBeLessThan(before.spentRial);
    expect(now.savingsRate!).toBeLessThan(before.savingsRate!);
    expect(narrate(now, before, [], null, true).map((i) => i.text).join(' ')).not.toContain('بهتر');
    expect(quietWins(now, before, null, true)).toEqual([]);
  });

  it('spending more than came in is said plainly, not as a negative percentage', () => {
    const rows = [entry(first, 100_000_000), entry(first + 1, -129_000_000)];
    const now = mr(rows, year, month);
    expect(now.savingsRate!).toBeLessThan(0);
    const text = narrate(now, null, rows, null, true).find((i) => i.text.includes('درآمد'))!.text;
    expect(text).not.toContain('−');
    expect(text).not.toContain('-');
    expect(text).toContain('بیشتر از درآمدت');
  });

  it('a win is a real outcome, never an app-opening', () => {
    const before = monthReport([entry(first - 31, -5_000_000)], here.previous());
    const now = mr([entry(first, 100_000_000), entry(first + 1, -10_000_000)], year, month);
    const wins = quietWins(now, before, 45, true);
    expect(wins.some((w) => w.text.includes('اولین'))).toBe(false);
    expect(wins.some((w) => w.text.includes('درآمد این ماه از خرجت بیشتر شد'))).toBe(true);
    expect(wins.some((w) => w.text.includes('یک ماه خرج'))).toBe(true);
    expect(wins.every((w) => w.tone === 'GOOD')).toBe(true);
    const past = quietWins(now, before, null, false);
    expect(past.some((w) => w.text.includes('این ماه'))).toBe(false);
    expect(past.some((w) => w.text.includes('مرداد ۱۴۰۵'))).toBe(true);
  });

  // ─────────────────────────── categories she excludes ───────────────────────────

  it('an excluded category is in none of the figures', () => {
    const entries = [
      entry(first, -1_000_000, { category: 'خواربار', categoryId: 'cat_groceries' }),
      entry(first + 1, -2_000_000, { category: 'کافیس', categoryId: 'cat_cafes' }),
      entry(first + 2, 5_000_000, { category: 'درآمد', categoryId: CAT_INCOME }),
    ];
    const report = periodReport(entries, new ReportRange(here, here), false, set('cat_cafes'));
    expect(report.spentRial).toBe(1_000_000);
    expect(report.incomeRial).toBe(5_000_000);
    expect(report.transactions).toBe(2);
    expect(report.spendingByCategory.some(([name]) => name === 'کافیس')).toBe(false);
  });

  it('an excluded income category comes off that side the same way', () => {
    const entries = [
      entry(first, 5_000_000, { category: 'حقوق', categoryId: 'cat_salary' }),
      entry(first + 1, 2_000_000, { category: 'مامان', categoryId: 'cat_mom' }),
    ];
    const report = periodReport(entries, new ReportRange(here, here), false, set('cat_mom'));
    expect(report.incomeRial).toBe(5_000_000);
    expect(report.incomeByCategory.some(([name]) => name === 'مامان')).toBe(false);
  });

  it('exclusion reaches the runway too', () => {
    const today = first + 20;
    const entries = Array.from({ length: 20 }, (_, i) => [
      entry(first + i, -2_000_000, { categoryId: 'cat_a' }),
      entry(first + i, -1_000_000, { category: 'قهوه', categoryId: 'cat_b' }),
    ]).flat();
    expect(bufferDays(entries, 20_000_000, today)).toBe(6);
    expect(bufferDays(entries, 20_000_000, today, { excluded: set('cat_b') })).toBe(10);
  });

  it('nothing excluded is the report unchanged', () => {
    const entries = [entry(first, -1_000_000), entry(first + 1, 5_000_000, { category: 'درآمد', categoryId: CAT_INCOME })];
    const range = new ReportRange(here, here);
    expect(periodReport(entries, range)).toEqual(periodReport(entries, range, false, new Set()));
    expect(periodReport(entries, range).excludedSpending).toEqual([]);
    expect(periodReport(entries, range).excludedIncome).toEqual([]);
  });

  it('what an exclusion holds out is stated apart, biggest first, and counted nowhere', () => {
    const entries = [
      entry(first, -1_000_000, { category: 'خواربار', categoryId: 'cat_groceries' }),
      entry(first + 1, -2_000_000, { category: 'کافیس', categoryId: 'cat_cafes' }),
      entry(first + 2, -6_000_000, { category: 'سفر', categoryId: 'cat_travel' }),
      entry(first + 3, 5_000_000, { category: 'درآمد', categoryId: CAT_INCOME }),
      entry(first + 4, 500_000, { category: 'کافیس', categoryId: 'cat_cafes' }),
      entry(here.endDay, -9_000_000, { category: 'کافیس', categoryId: 'cat_cafes' }),
    ];
    const report = periodReport(entries, new ReportRange(here, here), false, set('cat_cafes', 'cat_travel'));
    expect(report.spentRial).toBe(1_000_000);
    expect(report.incomeRial).toBe(5_000_000);
    expect(report.transactions).toBe(2);
    expect(report.excludedSpending).toEqual([['سفر', 6_000_000], ['کافیس', 2_000_000]]);
    expect(report.excludedIncome).toEqual([['کافیس', 500_000]]);
  });

  // ─────────────────────────── one category, taken apart ───────────────────────────

  it('a category window holds its own rows, its share, and its months', () => {
    const entries = [
      entry(first, -3_000_000, { category: 'خوراک', categoryId: 'cat_food' }),
      entry(first + 1, -1_000_000, { category: 'خوراک', categoryId: 'cat_food' }),
      entry(first + 2, -6_000_000, { category: 'خرید', categoryId: 'cat_x' }),
      entry(first + 3, 2_000_000, { category: 'خوراک', categoryId: 'cat_food' }),
      entry(first - 5, -5_000_000, { category: 'خوراک', categoryId: 'cat_food' }),
    ];
    const period = periodReport(entries, new ReportRange(here, here));
    const window = categoryWindow(entries, 'خوراک', false, new ReportRange(here, here), period.spentRial);
    expect(window.totalRial).toBe(4_000_000);
    expect(window.sideRial).toBe(10_000_000);
    expect(window.share).toBeCloseTo(0.4, 9);
    expect(window.rows.length).toBe(2);
    expect(window.rows[0].txn.day).toBe(first + 1);
    expect(window.trend.length).toBe(6);
    expect(window.trend[5][0]).toEqual(here);
    expect(window.trend[5][1]).toBe(4_000_000);
    expect(window.trend[4][1]).toBe(5_000_000);
  });

  // ─────────────────────────── the report, assembled ───────────────────────────

  const threeMonths = (): LedgerEntry[] => [
    entry(jalaliDay(1405, 3, 5), 60_000_000, { category: 'حقوق' }),
    entry(jalaliDay(1405, 3, 8), -20_000_000, { category: 'خوراکی' }),
    entry(jalaliDay(1405, 4, 10), 50_000_000, { category: 'حقوق' }),
    entry(jalaliDay(1405, 4, 12), -20_000_000, { category: 'اجاره' }),
    entry(first + 1, 100_000_000, { category: 'حقوق' }),
    entry(first + 2, -40_000_000, { category: 'خوراکی' }),
    entry(first + 3, -500_000_000, { category: 'خوراکی', transfer: true }),
    entry(first + 4, -40_000_000, { category: 'خوراکی', duplicate: true }),
  ];

  it("the chart's months are the same arithmetic as the totals under them", () => {
    const cash = buildCashFlow(threeMonths(), 0, first + 20, here);
    expect(cash.series.map((s) => s.month)).toEqual([m(1405, 3), m(1405, 4), here]);
    const selected = cash.series.find((s) => s.month.equals(cash.selected))!;
    expect(selected.incomeRial).toBe(cash.period.incomeRial);
    expect(selected.spentRial).toBe(cash.period.spentRial);
    expect(selected.incomeRial).toBe(100_000_000);
    expect(selected.spentRial).toBe(40_000_000);
  });

  it('she can reach every month the ledger has, and no month it does not', () => {
    const rows = threeMonths();
    const today = first + 20;
    const now = buildCashFlow(rows, 0, today, here);
    expect(now.current).toBe(true);
    expect(now.canGoBack).toBe(true);
    expect(now.canGoForward).toBe(false);
    const oldest = buildCashFlow(rows, 0, today, m(1405, 3));
    expect(oldest.current).toBe(false);
    expect(oldest.canGoBack).toBe(false);
    expect(oldest.canGoForward).toBe(true);
    expect(buildCashFlow(rows, 0, today, m(1399, 1)).selected).toEqual(here);
  });

  it("a past month is never told what today's cash would cover", () => {
    const rows = [...threeMonths(), ...Array.from({ length: 20 }, (_, i) => entry(first + i, -1_000_000))];
    const today = first + 25;
    const now = buildCashFlow(rows, 100_000_000, today, here);
    expect(now.bufferDays).not.toBeNull();
    expect(now.insights.some((i) => i.text.includes('روز می‌رسه'))).toBe(true);
    const past = buildCashFlow(rows, 100_000_000, today, m(1405, 4));
    expect(past.current).toBe(false);
    expect(past.bufferDays).toBeNull();
    expect(past.insights.length).toBeGreaterThan(0);
    expect(past.insights.some((i) => i.text.includes('روز می‌رسه'))).toBe(false);
    expect(past.insights.some((i) => i.text.includes('این ماه'))).toBe(false);
    expect(past.wins.some((i) => i.text.includes('این ماه'))).toBe(false);
    expect(past.insights.some((i) => i.text.includes('تیر ۱۴۰۵'))).toBe(true);
    expect(past.period.incomeRial).toBe(50_000_000);
    expect(past.period.spentRial).toBe(20_000_000);
  });

  it('the queue of open items is asked about now and not in a past month', () => {
    const rows = [...threeMonths(), entry(first + 6, -3_000_000, { review: true })];
    const today = first + 20;
    expect(buildCashFlow(rows, 0, today, here).insights.some((i) => i.tone === 'ATTENTION')).toBe(true);
    expect(buildCashFlow(rows, 0, today, m(1405, 4)).insights.some((i) => i.tone === 'ATTENTION')).toBe(false);
  });

  // ─────────────────────────── longer windows ───────────────────────────

  const aYear = (): LedgerEntry[] => {
    const rows: LedgerEntry[] = [];
    for (let back = 0; back <= 12; back++) {
      const mo = here.back(back);
      rows.push(entry(mo.startDay + 1, 10_000_000 * (13 - back), { category: 'حقوق' }));
      rows.push(entry(mo.startDay + 2, -4_000_000 * (13 - back), { category: 'خوراکی' }));
    }
    return rows;
  };

  it('a window of months is the months added up, and nothing from outside them', () => {
    const rows = aYear();
    const quarter = buildCashFlow(rows, 0, first + 20, here, { span: ReportSpan.QUARTER });
    expect(quarter.range).toEqual(new ReportRange(here.back(2), here));
    expect(quarter.range.count).toBe(3);
    expect(quarter.period.incomeRial).toBe(360_000_000);
    expect(quarter.period.spentRial).toBe(144_000_000);
    const byMonth = [0, 1, 2].map((b) => monthReport(rows, here.back(b)));
    expect(byMonth.reduce((s, r) => s + r.incomeRial, 0)).toBe(quarter.period.incomeRial);
    expect(byMonth.reduce((s, r) => s + r.spentRial, 0)).toBe(quarter.period.spentRial);
    expect(quarter.period.spendingByCategory).toEqual([['خوراکی', 144_000_000]]);
    expect(quarter.period.incomeByCategory).toEqual([['حقوق', 360_000_000]]);
  });

  it('a window is compared with the whole window before it', () => {
    const quarter = buildCashFlow(aYear(), 0, first + 20, here, { span: ReportSpan.QUARTER });
    expect(quarter.previous.range).toEqual(new ReportRange(here.back(5), here.back(3)));
    expect(quarter.previous.incomeRial).toBe(270_000_000);
    expect(quarter.range.months.some((mo) => quarter.previous.range.containsMonth(mo))).toBe(false);
  });

  it('a length the ledger cannot fill is not offered, and not silently shortened', () => {
    const rows = [0, 1, 2, 3].map((b) => entry(here.back(b).startDay + 1, -1_000_000));
    const cash = buildCashFlow(rows, 0, first + 20, here, { span: ReportSpan.HALF });
    expect(cash.spans).toEqual([ReportSpan.WEEK, ReportSpan.MONTH, ReportSpan.QUARTER]);
    expect(cash.span).toBe(ReportSpan.QUARTER);
    expect(cash.range.count).toBe(3);
    const whole = buildCashFlow(aYear(), 0, first + 20, here, { span: ReportSpan.YEAR });
    expect(whole.spans).toEqual([...REPORT_SPANS]);
    expect(whole.range.count).toBe(12);
  });

  it('a window at the start of the ledger is not compared with one nobody watched', () => {
    const rows = [0, 1, 2, 3, 4, 5].map((b) => entry(here.back(b).startDay + 1, -1_000_000));
    const full = buildCashFlow(rows, 0, first + 20, here, { span: ReportSpan.QUARTER });
    expect(full.previous.transactions).toBeGreaterThan(0);
    const stepped = buildCashFlow(rows, 0, first + 20, here.previous(), { span: ReportSpan.QUARTER });
    expect(stepped.range.count).toBe(3);
    expect(stepped.period.transactions).toBe(3);
    expect(stepped.insights.some((i) => i.text.includes('دورهٔ قبل'))).toBe(false);
  });

  it('a window says its own name and never calls three months «این ماه»', () => {
    const rows = aYear();
    const quarter = buildCashFlow(rows, 0, first + 20, here, { span: ReportSpan.QUARTER });
    expect(quarter.range.recentFa).toBe('۳ ماه گذشته');
    expect(quarter.range.fa).toBe('خرداد تا مرداد ۱۴۰۵');
    const text = [...quarter.insights, ...quarter.wins].map((i) => i.text).join(' ');
    expect(text.trim()).not.toBe('');
    expect(text).not.toContain('این ماه');
    expect(text).toContain('۳ ماه گذشته');
    expect(text).toContain('دورهٔ قبل');
    expect(text).not.toContain('ماه قبل ');
    const past = buildCashFlow(rows, 0, first + 20, m(1405, 2), { span: ReportSpan.QUARTER });
    expect(past.range.fa).toBe('اسفند ۱۴۰۴ تا اردیبهشت ۱۴۰۵');
    expect(past.insights.map((i) => i.text).join(' ')).toContain('اسفند ۱۴۰۴ تا اردیبهشت ۱۴۰۵');
  });

  it('a single month is exactly the report it always was', () => {
    const rows = threeMonths();
    const cash = buildCashFlow(rows, 0, first + 20, here);
    expect(cash.span).toBe(ReportSpan.MONTH);
    expect(cash.range).toEqual(new ReportRange(here, here));
    expect(cash.period).toEqual(monthReport(rows, here));
    expect(cash.previous).toEqual(monthReport(rows, here.previous()));
    const text = cash.insights.map((i) => i.text).join(' ');
    expect(text).toContain('ماه قبل');
    expect(text).not.toContain('دورهٔ قبل');
  });

  it('the chart keeps six months of context under a shorter window, and grows for a longer one', () => {
    const rows = aYear();
    const quarter = buildCashFlow(rows, 0, first + 20, here, { span: ReportSpan.QUARTER });
    expect(quarter.series.length).toBe(6);
    expect(quarter.series.filter((s) => quarter.range.containsMonth(s.month)).length).toBe(3);
    expect(quarter.series[quarter.series.length - 1].month).toEqual(here);
    const whole = buildCashFlow(rows, 0, first + 20, here, { span: ReportSpan.YEAR });
    expect(whole.series.length).toBe(12);
    expect(whole.series.every((s) => whole.range.containsMonth(s.month))).toBe(true);
    expect(whole.series.reduce((s, r) => s + r.incomeRial, 0)).toBe(whole.period.incomeRial);
    expect(whole.series.reduce((s, r) => s + r.spentRial, 0)).toBe(whole.period.spentRial);
  });

  it('stepping the window moves it one month, not one window', () => {
    const whole = buildCashFlow(aYear(), 0, first + 20, here.previous(), { span: ReportSpan.YEAR });
    expect(whole.range).toEqual(new ReportRange(here.back(12), here.previous()));
    expect(whole.current).toBe(false);
    expect(whole.canGoForward).toBe(true);
  });

  it('a range cannot run backwards', () => {
    expect(() => new ReportRange(here, here.previous())).toThrow();
  });

  it('the home story is still the month she is standing in', () => {
    const story = buildStory(threeMonths(), 0, first + 20);
    expect(story.month.month).toEqual(here);
    expect(story.month.incomeRial).toBe(100_000_000);
    expect(story.month.spentRial).toBe(40_000_000);
    expect(story.month.netRial).toBe(60_000_000);
    expect(story.previous.month).toEqual(m(1405, 4));
    expect(story.insights.length).toBeGreaterThan(0);
    expect(story.insights.some((i) => i.text.includes('مرداد ۱۴۰۵'))).toBe(false);
  });

  // ─────────────────────────── «۱ هفته» ───────────────────────────

  it('«۱ هفته» reads one whole week and compares it with the whole week before', () => {
    const today = first + 20;
    const week = weekStart(today);
    const rows = [entry(week - 7, -40_000_000), entry(week - 1, -10_000_000), entry(week, 60_000_000), entry(today, -30_000_000)];
    const cash = buildCashFlow(rows, 0, today, here, { span: ReportSpan.WEEK });
    expect(cash.span).toBe(ReportSpan.WEEK);
    expect(cash.range.startDay).toBe(week);
    expect(cash.range.endDay).toBe(week + 7);
    expect(cash.current).toBe(true);
    expect(cash.period.incomeRial).toBe(60_000_000);
    expect(cash.period.spentRial).toBe(30_000_000);
    expect(cash.previous.spentRial).toBe(50_000_000);
    expect(cash.canGoForward).toBe(false);
    expect(cash.canGoBack).toBe(true);
    const text = [...cash.insights, ...cash.wins].map((i) => i.text).join(' ');
    expect(text).toContain('این هفته');
    expect(text).not.toContain('این ماه');
  });

  it("a week anchor lands on that week, and the walk stops at the ledger's first", () => {
    const today = first + 20;
    const week = weekStart(today);
    const rows = [entry(week - 14, -10_000_000), entry(today, -30_000_000)];
    const past = buildCashFlow(rows, 0, today, here, { span: ReportSpan.WEEK, anchorDay: week - 14 });
    expect(past.range.startDay).toBe(week - 14);
    expect(past.current).toBe(false);
    expect(past.canGoBack).toBe(false);
    expect(past.canGoForward).toBe(true);
    expect(past.insights.some((i) => i.text.includes('هفتهٔ قبل'))).toBe(false);
    const future = buildCashFlow(rows, 0, today, here, { span: ReportSpan.WEEK, anchorDay: today + 40 });
    expect(future.range.startDay).toBe(week);
  });

  it('the weekly chart lines up the recent weeks and none from before the ledger', () => {
    const today = first + 20;
    const week = weekStart(today);
    const cash = buildCashFlow([entry(week - 7, -10_000_000), entry(week, -20_000_000)], 0, today, here, { span: ReportSpan.WEEK });
    expect(cash.series.map((s) => s.range.startDay)).toEqual([week - 7, week]);
    expect(cash.series.every((s) => s.range.week != null)).toBe(true);
    expect(cash.series[cash.series.length - 1]).toEqual(cash.period);
  });

  it('«۱ هفته» is offered however young the ledger is', () => {
    expect(buildCashFlow([entry(first + 20, -1_000_000)], 0, first + 20, here).spans).toEqual([ReportSpan.WEEK, ReportSpan.MONTH]);
  });

  it('a week names its two ends, and says the month and year once where it can', () => {
    const inside = weekRange(jalaliDay(1405, 6, 3));
    expect(inside.fa).toBe('۳ تا ۹ شهریور ۱۴۰۵');
    expect(inside.recentFa).toBe('این هفته');
    expect(inside.beforeFa).toBe('هفتهٔ قبل');
    expect(inside.before().startDay).toBe(inside.startDay - 7);
    expect(weekRange(jalaliDay(1405, 6, 29)).fa).toBe('۲۹ شهریور تا ۴ مهر ۱۴۰۵');
    const esfand = jalaliMonthLength(1404, 12);
    expect(weekRange(jalaliDay(1404, 12, esfand - 2)).fa).toBe(`${faNumber(esfand - 2)} اسفند ۱۴۰۴ تا ۴ فروردین ۱۴۰۵`);
  });

  it('member shares split the window by owner, through the same gates as the report', () => {
    const range = new ReportRange(here, here);
    const rows = [
      entry(first, -30_000_000, { owner: 'a', ownerName: 'ندا' }),
      entry(first + 1, -10_000_000, { owner: 'b', ownerName: 'امیر' }),
      entry(first + 2, 50_000_000, { owner: 'b', ownerName: 'امیر' }),
      entry(first + 3, -99_000_000, { category: 'قرض', categoryId: CAT_LOAN, owner: 'a', ownerName: 'ندا' }),
      entry(here.endDay, -70_000_000, { owner: 'b', ownerName: 'امیر' }),
    ];
    const shares = memberShares(rows, range, true, set(CAT_LOAN));
    expect(shares.map((s) => s.name)).toEqual(['ندا', 'امیر']);
    expect(shares[0].spentRial).toBe(30_000_000);
    expect(shares[0].incomeRial).toBe(0);
    expect(shares[1].spentRial).toBe(10_000_000);
    expect(shares[1].incomeRial).toBe(50_000_000);
    expect(memberShares(rows, range, false).find((s) => s.id === 'a')!.spentRial).toBe(30_000_000);
    expect(memberShares(rows.filter((r) => r.ownerMemberId === 'a'), range)).toEqual([]);
  });

  it("a member's window holds the rows behind their own bar, and only those", () => {
    const range = new ReportRange(here, here);
    const rows = [
      entry(first, -30_000_000, { owner: 'b', ownerName: 'امیر' }),
      entry(first + 1, -10_000_000, { owner: 'b', ownerName: 'امیر' }),
      entry(first + 2, 50_000_000, { owner: 'b', ownerName: 'امیر' }),
      entry(first + 3, -20_000_000, { owner: 'a', ownerName: 'ندا' }),
      entry(first + 4, -99_000_000, { category: 'قرض', categoryId: CAT_LOAN, owner: 'b', ownerName: 'امیر' }),
      entry(here.endDay, -70_000_000, { owner: 'b', ownerName: 'امیر' }),
    ];
    const amir = memberShares(rows, range).find((s) => s.id === 'b')!;
    const spent = memberWindow(rows, amir, false, range, amir.spentRial);
    expect(spent.totalRial).toBe(amir.spentRial);
    expect(spent.rows.map((r) => r.txn.signedRial)).toEqual([-10_000_000, -30_000_000]);
    const earned = memberWindow(rows, amir, true, range, amir.incomeRial);
    expect(earned.totalRial).toBe(amir.incomeRial);
    expect(spent.rows.some((r) => r.categoryId === CAT_LOAN)).toBe(false);
    expect(memberWindow(rows, amir, false, range, 0, true).totalRial)
      .toBe(memberShares(rows, range, true).find((s) => s.id === 'b')!.spentRial);
    expect(memberWindow(rows, amir, false, range, 0, false, set('cat_x')).totalRial).toBe(0);
  });

  it("a member's sheet groups their own rows by category, biggest first", () => {
    const range = new ReportRange(here, here);
    const rows = [
      entry(first, -30_000_000, { category: 'خوراک', categoryId: 'cat_food', owner: 'b', ownerName: 'امیر' }),
      entry(first + 1, -10_000_000, { category: 'سفر', categoryId: 'cat_travel', owner: 'b', ownerName: 'امیر' }),
      entry(first + 2, -5_000_000, { category: 'خوراک', categoryId: 'cat_food', owner: 'b', ownerName: 'امیر' }),
      entry(first + 3, -80_000_000, { category: 'خوراک', categoryId: 'cat_food', owner: 'a', ownerName: 'ندا' }),
      entry(first + 4, 50_000_000, { category: 'حقوق', categoryId: 'cat_salary', owner: 'b', ownerName: 'امیر' }),
    ];
    const amir = memberShares(rows, range).find((s) => s.id === 'b')!;
    const spent = memberWindow(rows, amir, false, range, amir.spentRial);
    expect(spent.breakdown).toEqual([['خوراک', 35_000_000], ['سفر', 10_000_000]]);
    expect(spent.breakdown.reduce((s, [, rial]) => s + rial, 0)).toBe(spent.totalRial);
    expect(memberWindow(rows, amir, true, range, amir.incomeRial).breakdown).toEqual([['حقوق', 50_000_000]]);
    expect(categoryWindow(rows, 'خوراک', false, range, 0).breakdown).toEqual([]);
  });
});
