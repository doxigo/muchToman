/**
 * BudgetTest.kt, literally: which days a window holds, which side of a threshold a figure lands
 * on, and whether a crossing already announced announces itself again.
 */
import { describe, expect, it } from 'vitest';
import {
  BUDGET_TOTAL_FA, BudgetLevel, BudgetPeriod, budgetAlertBody, budgetAlertTitle, budgetConflicts, budgetDaysLeftFa,
  budgetInsight, budgetLevel, budgetNews, budgetNoteFa, budgetPeriodOf, budgetProgress, budgetScopeFa, budgetScopeNoteFa,
  budgetSpent, budgetTotalNoteFa, budgetWindow, budgetsOf, pressingBudget,
} from '../src/budget';
import type { BudgetMark } from '../src/budget';
import { GoalKind, GoalPeriod } from '../src/goals';
import { jalaliDay, weekStart } from '../src/jalali';
import type { Goal } from '../src/model';
import { buildStory } from '../src/reports';
import { PASS_THROUGH_CATEGORIES } from '../src/rules';
import { entry as row, goalRow } from './plans-fixture';
import type { EntryOptions } from './plans-fixture';

const year = 1405;
const month = 5;
const first = jalaliDay(year, month, 1);
const dining = 'cat_dining';
const CAT_LOAN = 'cat_loan';
const CAT_SPOUSE = 'cat_spouse';

const entry = (day: number, signed: number, o: EntryOptions = {}) =>
  row(day, signed, { categoryId: dining, category: 'رستوران و کافه', merchant: 'جایی', ...o });

const budget = (
  cap: number,
  { period = BudgetPeriod.MONTH as BudgetPeriod, category = dining as string | null, startsOn = first, id = 'b1', shared = false, owner = '' } = {},
): Goal => goalRow({
  id, nameFa: 'رستوران و کافه', targetRial: cap, kind: GoalKind.CAP,
  categoryId: category, period: period.id, startsOn, shared, ownerMemberId: owner,
});

const passThrough = () => new Set(PASS_THROUGH_CATEGORIES.keys());

describe('budgets', () => {
  it('family spending excludes private rows on either phone', () => {
    const shared = entry(first, -95_000_000, { owner: 'her' });
    const priv = entry(first, -18_000_000, { owner: 'him', sharedWithFamily: false });
    const goal = budget(200_000_000, { shared: true });
    const window = budgetWindow(BudgetPeriod.MONTH, first);
    expect(budgetSpent([shared, priv], goal, window, 'him')).toBe(95_000_000);
    expect(budgetSpent([shared], goal, window, 'her')).toBe(95_000_000);
    expect(budgetSpent([shared, priv], { ...goal, shared: false }, window, 'him')).toBe(18_000_000);
  });

  it('only matching scope category and period collide and totals can coexist', () => {
    const goal = budget(100, { shared: true });
    const duplicate = { ...goal, id: 'duplicate', targetRial: 200, ownerMemberId: 'other' };
    const others = [
      goal, duplicate, { ...goal, id: 'personal', shared: false },
      { ...goal, id: 'weekly', period: GoalPeriod.WEEK },
      { ...goal, id: 'total', categoryId: null },
      { ...duplicate, id: 'deleted', deleted: true },
    ];
    expect(budgetConflicts(others, goal)).toEqual([duplicate]);
    expect(budgetConflicts(others, { ...goal, id: 'total', categoryId: null })).toEqual([]);
  });

  // ─────────────────────────── the window ───────────────────────────

  it('a monthly window is the Jalali month, closed-open', () => {
    const w = budgetWindow(BudgetPeriod.MONTH, first + 10);
    expect(w.startDay).toBe(first);
    expect(w.days).toBe(31);
    expect(w.contains(first)).toBe(true);
    expect(w.contains(first + 30)).toBe(true);
    expect(w.contains(first + 31)).toBe(false);
    expect(w.endDay).toBe(first + 31);
  });

  it('a weekly window runs Saturday to Friday', () => {
    const w = budgetWindow(BudgetPeriod.WEEK, first + 3);
    expect(w.days).toBe(7);
    expect(w.startDay).toBe(weekStart(first + 3));
    expect(w.startDay % 7).toBe(2);
  });

  it('a quarter is a season, and زمستان rolls the year', () => {
    const summer = budgetWindow(BudgetPeriod.QUARTER, first);
    expect(summer.startDay).toBe(jalaliDay(1405, 4, 1));
    expect(summer.endDay).toBe(jalaliDay(1405, 7, 1));
    expect(summer.days).toBe(93);
    expect(summer.fa).toBe('تابستان');
    const winter = budgetWindow(BudgetPeriod.QUARTER, jalaliDay(1405, 12, 20));
    expect(winter.startDay).toBe(jalaliDay(1405, 10, 1));
    expect(winter.endDay).toBe(jalaliDay(1406, 1, 1));
    expect(winter.fa).toBe('زمستان');
    expect(winter.days).toBe(29 + 30 + 30);
  });

  it('a leap اسفند is counted from the leap table, not assumed', () => {
    expect(budgetWindow(BudgetPeriod.MONTH, jalaliDay(1403, 12, 5)).days).toBe(30);
    expect(budgetWindow(BudgetPeriod.MONTH, jalaliDay(1405, 12, 5)).days).toBe(29);
  });

  it('days left counts today, and days gone starts at one', () => {
    const w = budgetWindow(BudgetPeriod.MONTH, first);
    expect(w.daysLeft(first)).toBe(31);
    expect(w.daysGone(first)).toBe(1);
    expect(w.daysLeft(first + 30)).toBe(1);
    expect(w.daysGone(first + 30)).toBe(31);
    expect(w.daysLeft(first + 31)).toBe(0);
  });

  it('an unknown period reads as a month rather than throwing', () => {
    expect(budgetPeriodOf('jdecade')).toBe(BudgetPeriod.MONTH);
    expect(budgetPeriodOf(GoalPeriod.QUARTER)).toBe(BudgetPeriod.QUARTER);
  });

  // ─────────────────────────── what it counts ───────────────────────────

  it('a budget counts its own category, inside its own window', () => {
    const rows = [
      entry(first + 1, -10_000_000),
      entry(first + 2, -5_000_000, { categoryId: 'cat_groceries' }),
      entry(first - 1, -90_000_000),
      entry(first + 31, -90_000_000),
    ];
    const p = budgetProgress(budget(50_000_000), rows, first + 5);
    expect(p.spentRial).toBe(10_000_000);
    expect(p.leftRial).toBe(40_000_000);
    expect(p.overRial).toBe(0);
  });

  it('transfers and hidden duplicates are not spending here either', () => {
    const rows = [
      entry(first + 1, -10_000_000),
      entry(first + 1, -80_000_000, { transfer: true }),
      entry(first + 1, -80_000_000, { duplicate: true }),
    ];
    expect(budgetProgress(budget(50_000_000), rows, first + 5).spentRial).toBe(10_000_000);
  });

  it('money arriving under a spending category does not refund the budget', () => {
    const rows = [entry(first + 1, -10_000_000), entry(first + 2, 4_000_000)];
    expect(budgetProgress(budget(50_000_000), rows, first + 5).spentRial).toBe(10_000_000);
  });

  it('the window is the whole month even when the budget was set part way through', () => {
    const rows = [entry(first + 1, -30_000_000)];
    const p = budgetProgress(budget(50_000_000, { startsOn: first + 20 }), rows, first + 25);
    expect(p.spentRial).toBe(30_000_000);
    expect(p.partWindow).toBe(true);
    expect(budgetProgress(budget(50_000_000, { startsOn: first }), rows, first + 25).partWindow).toBe(false);
  });

  // ─────────────────────────── the thresholds ───────────────────────────

  it('the thresholds land exactly where they say they do', () => {
    const cap = 50_000_000;
    expect(budgetLevel(0, cap)).toBe(BudgetLevel.OK);
    expect(budgetLevel(39_999_999, cap)).toBe(BudgetLevel.OK);
    expect(budgetLevel(40_000_000, cap)).toBe(BudgetLevel.NEAR);
    expect(budgetLevel(47_499_999, cap)).toBe(BudgetLevel.NEAR);
    expect(budgetLevel(47_500_000, cap)).toBe(BudgetLevel.CLOSE);
    expect(budgetLevel(cap, cap)).toBe(BudgetLevel.CLOSE);
    expect(budgetLevel(cap + 1, cap)).toBe(BudgetLevel.OVER);
  });

  it('a cap of nothing is over the moment anything is spent', () => {
    expect(budgetLevel(0, 0)).toBe(BudgetLevel.OK);
    expect(budgetLevel(1, 0)).toBe(BudgetLevel.OVER);
    const p = budgetProgress(budget(0), [entry(first + 1, -1_000_000)], first + 5);
    expect(p.percent).toBe(100);
    expect(p.share).toBeCloseTo(1, 3);
  });

  it('being over is said in words and a percent, never by a bar past its own track', () => {
    const p = budgetProgress(budget(50_000_000), [entry(first + 1, -65_000_000)], first + 5);
    expect(p.over).toBe(true);
    expect(p.overRial).toBe(15_000_000);
    expect(p.leftRial).toBe(0);
    expect(p.percent).toBe(130);
    expect(p.share).toBeCloseTo(1, 3);
    expect(p.perDayRial).toBeNull();
  });

  // ─────────────────────────── pace ───────────────────────────

  it('the same share is outpacing early in the month and not late in it', () => {
    const rows = [entry(first, -35_000_000)];
    expect(budgetProgress(budget(50_000_000), rows, first + 4).outpacing).toBe(true);
    expect(budgetProgress(budget(50_000_000), rows, first + 25).outpacing).toBe(false);
  });

  it('what is left per remaining day is what is left over the days that are left', () => {
    const p = budgetProgress(budget(50_000_000), [entry(first, -20_000_000)], first + 20);
    expect(p.daysLeft).toBe(11);
    expect(p.perDayRial).toBe(Math.trunc(30_000_000 / 11));
  });

  it('a spent-out budget has no daily allowance to offer', () => {
    const p = budgetProgress(budget(50_000_000), [entry(first, -50_000_000)], first + 5);
    expect(p.leftRial).toBe(0);
    expect(p.overRial).toBe(0);
    expect(p.perDayRial).toBeNull();
    expect(p.level).toBe(BudgetLevel.CLOSE);
  });

  // ─────────────────────────── the ordering ───────────────────────────

  it('budgets come back with the one closest to trouble first', () => {
    const rows = [entry(first, -45_000_000, { categoryId: dining }), entry(first, -10_000_000, { categoryId: 'cat_groceries' })];
    const goals = [budget(50_000_000, { category: 'cat_groceries', id: 'b_groceries' }), budget(50_000_000, { category: dining, id: 'b_dining' })];
    expect(budgetsOf(goals, rows, first + 5).map((b) => b.goal.id)).toEqual(['b_dining', 'b_groceries']);
  });

  it('a savings goal is not a budget, whichever way the table is read', () => {
    const save = goalRow({ id: 'g1', nameFa: 'سفر', targetRial: 50_000_000, kind: GoalKind.SAVE, period: GoalPeriod.ONCE, startsOn: first });
    expect(budgetsOf([save], [], first)).toEqual([]);
  });

  it('the live category name wins over the one snapshotted on the row', () => {
    expect(budgetsOf([budget(50_000_000)], [], first, { names: new Map([[dining, 'رستوران']]) })[0].categoryFa).toBe('رستوران');
    expect(budgetsOf([budget(50_000_000)], [], first)[0].categoryFa).toBe('رستوران و کافه');
  });

  // ─────────────────────────── saying it once ───────────────────────────

  const progress = (spentRial: number, cap = 50_000_000, day = first + 5) =>
    budgetProgress(budget(cap), [entry(first, -spentRial)], day);

  it('a crossing is announced once, and the mark is what stops the second time', () => {
    const firstPass = budgetNews([progress(41_000_000)], []);
    expect(firstPass.alerts.map((a) => a.goal.id)).toEqual(['b1']);
    expect(firstPass.marks).toEqual<BudgetMark[]>([{ goalId: 'b1', windowStart: first, level: BudgetLevel.NEAR }]);
    const again = budgetNews([progress(42_000_000)], firstPass.marks);
    expect(again.alerts).toEqual([]);
    expect(again.marks).toEqual(firstPass.marks);
  });

  it('each higher line is its own announcement', () => {
    let marks = budgetNews([progress(41_000_000)], []).marks;
    const close = budgetNews([progress(48_000_000)], marks);
    expect(close.alerts.length).toBe(1);
    expect(close.alerts[0].level).toBe(BudgetLevel.CLOSE);
    marks = close.marks;
    const over = budgetNews([progress(60_000_000)], marks);
    expect(over.alerts.length).toBe(1);
    expect(over.alerts[0].level).toBe(BudgetLevel.OVER);
    expect(budgetNews([progress(90_000_000)], over.marks).alerts).toEqual([]);
  });

  it('a level that falls back does not re-announce when it climbs again', () => {
    const marks = budgetNews([progress(41_000_000)], []).marks;
    const dropped = budgetNews([progress(20_000_000)], marks);
    expect(dropped.alerts).toEqual([]);
    expect(dropped.marks[0].level).toBe(BudgetLevel.NEAR);
    expect(budgetNews([progress(41_000_000)], dropped.marks).alerts).toEqual([]);
  });

  it('a new window is a new conversation, and the old mark is dropped', () => {
    const marks = budgetNews([progress(41_000_000)], []).marks;
    const next = jalaliDay(year, month + 1, 1);
    const later = budgetProgress(budget(50_000_000), [entry(next, -41_000_000)], next + 5);
    const news = budgetNews([later], marks);
    expect(news.alerts.length).toBe(1);
    expect(news.marks).toEqual([{ goalId: 'b1', windowStart: next, level: BudgetLevel.NEAR }]);
  });

  it('a deleted budget leaves no mark behind', () => {
    expect(budgetNews([], budgetNews([progress(41_000_000)], []).marks).marks).toEqual([]);
  });

  it('nothing under the first threshold is worth interrupting her for', () => {
    const news = budgetNews([progress(10_000_000)], []);
    expect(news.alerts).toEqual([]);
    expect(news.marks).toEqual([{ goalId: 'b1', windowStart: first, level: BudgetLevel.OK }]);
  });

  // ─────────────────────────── the words ───────────────────────────

  it('the alert quotes the real share, not the threshold it crossed', () => {
    const title = budgetAlertTitle(progress(41_500_000));
    expect(title).toContain('۸۳٪');
    expect(title).toContain('رستوران و کافه');
    expect(title).toContain('مرداد');
  });

  it('being over is stated as a fact about her money, never as a verdict', () => {
    const over = progress(65_000_000);
    expect(budgetAlertTitle(over)).toBe('رستوران و کافه: از بودجهٔ مرداد گذشتی');
    expect(budgetAlertBody(over).startsWith('۱٫۵ میلیون بیشتر از ۵ میلیون تومان')).toBe(true);
    for (const text of [budgetAlertTitle(over), budgetAlertBody(over), budgetNoteFa(over)]) {
      expect(text).not.toContain('اشتباه');
      expect(text).not.toContain('نتونستی');
    }
  });

  it('the body carries both what is left and how long is left', () => {
    const body = budgetAlertBody(progress(41_000_000, 50_000_000, first + 19));
    expect(body).toContain('۹۰۰ هزار از ۵ میلیون تومان مونده');
    expect(body).toContain('۱۲ روز تا آخر ماه');
  });

  it('the last day of a window says so instead of counting one', () => {
    expect(budgetDaysLeftFa(progress(10_000_000, 50_000_000, first + 30))).toBe('امروز آخرین روزه');
    expect(budgetDaysLeftFa(progress(10_000_000, 50_000_000, first + 29))).toBe('۲ روز تا آخر ماه');
  });

  it('a budget always has at least today left, so no window is ever read as finished', () => {
    for (let day = first; day <= first + 40; day++) {
      const p = budgetProgress(budget(50_000_000), [entry(first, -10_000_000)], day);
      expect(p.daysLeft).toBeGreaterThanOrEqual(1);
      expect(p.window.contains(day)).toBe(true);
    }
    expect(budgetProgress(budget(50_000_000), [], first + 31).window.fa).toBe('شهریور');
  });

  it('a budget comfortably on pace says nothing beyond its own bar', () => {
    expect(budgetNoteFa(progress(10_000_000, 50_000_000, first + 25))).toBe('');
  });

  it('the pace line only appears while there is a window left to change', () => {
    expect(budgetNoteFa(progress(35_000_000, 50_000_000, first + 4))).toContain('روزی');
    expect(budgetNoteFa(progress(35_000_000, 50_000_000, first + 30))).toBe('امروز آخرین روزه.');
  });

  // ─────────────────────────── home ───────────────────────────

  it('only a budget nearly out or past its cap reaches the home screen', () => {
    expect(pressingBudget([progress(41_000_000)])).toBeNull();
    expect(pressingBudget([progress(48_000_000)])!.goal.id).toBe('b1');
    expect(pressingBudget([progress(60_000_000)])!.goal.id).toBe('b1');
    expect(pressingBudget([])).toBeNull();
  });

  it('the worst one wins by share, not by amount', () => {
    const rows = [entry(first, -99_000_000, { categoryId: 'cat_home' }), entry(first, -6_000_000, { categoryId: dining })];
    const goals = [budget(100_000_000, { category: 'cat_home', id: 'b_home' }), budget(5_000_000, { category: dining, id: 'b_dining' })];
    expect(pressingBudget(budgetsOf(goals, rows, first + 5))!.goal.id).toBe('b_dining');
  });

  it('a budget on the home screen carries the rows behind its own figure', () => {
    const mine = entry(first + 1, -60_000_000);
    const theirs = entry(first + 1, -60_000_000, { categoryId: 'cat_groceries' });
    const rows = [mine, theirs];
    const insight = budgetInsight(budgetsOf([budget(50_000_000)], rows, first + 5)[0], rows);
    expect(insight.tone).toBe('ATTENTION');
    expect(insight.refs).toEqual([mine.txn.ref]);
  });

  it('a cap she has run past outranks the review queue for the one slot home has', () => {
    const rows = [entry(first + 1, -60_000_000), entry(first + 1, -1_000_000, { categoryId: 'cat_groceries', review: true })];
    const budgets = budgetsOf([budget(50_000_000)], rows, first + 5);
    const story = buildStory(rows, 0, first + 5, { budgets });
    expect(story.attentionBudget).toBe(budgets[0]);
    expect(story.attention!.text).toContain('رستوران و کافه');
  });

  it('with no budget kept, the home screen is exactly what it was', () => {
    const story = buildStory([entry(first + 1, -1_000_000, { review: true })], 0, first + 5);
    expect(story.attentionBudget).toBeNull();
    expect(story.attention!.text).toContain('منتظر');
  });

  // ─────────────────────────── the roof over the month ───────────────────────────

  const her = 'aaaa1111bbbb2222';
  const him = 'cccc3333dddd4444';
  const total = (cap: number, { shared = false, owner = '', id = 'b_total' } = {}) =>
    budget(cap, { category: null, id, shared, owner });

  it('a total counts every category, not one of them', () => {
    const rows = [
      entry(first + 1, -3_000_000, { categoryId: dining }),
      entry(first + 2, -4_000_000, { categoryId: 'cat_groceries' }),
      entry(first + 3, -1_000_000, { categoryId: 'cat_home' }),
    ];
    const roof = budgetsOf([total(10_000_000)], rows, first + 5)[0];
    expect(roof.total).toBe(true);
    expect(roof.spentRial).toBe(8_000_000);
    expect(roof.categoryFa).toBe(BUDGET_TOTAL_FA);
  });

  it('a total leaves out money that only passes through', () => {
    const rows = [
      entry(first + 1, -2_000_000, { categoryId: dining }),
      entry(first + 2, -50_000_000, { categoryId: CAT_LOAN }),
      entry(first + 3, -9_000_000, { categoryId: CAT_SPOUSE }),
    ];
    expect(budgetsOf([total(10_000_000)], rows, first + 5)[0].spentRial).toBe(2_000_000);
  });

  it('a total leaves out the categories she set aside in the report', () => {
    const rows = [entry(first + 1, -2_000_000, { categoryId: dining }), entry(first + 2, -7_000_000, { categoryId: 'cat_home' })];
    const excluded = new Set([...passThrough(), 'cat_home']);
    expect(budgetsOf([total(10_000_000)], rows, first + 5, { excluded })[0].spentRial).toBe(2_000_000);
  });

  it('un-excluding قرض in the report puts it back under the roof', () => {
    const rows = [entry(first + 1, -2_000_000, { categoryId: dining }), entry(first + 2, -5_000_000, { categoryId: CAT_LOAN })];
    expect(budgetsOf([total(10_000_000)], rows, first + 5, { excluded: new Set() })[0].spentRial).toBe(7_000_000);
  });

  it('a cap on a category she set aside still counts it', () => {
    const rows = [entry(first + 1, -4_000_000, { categoryId: dining })];
    expect(budgetsOf([budget(10_000_000)], rows, first + 5, { excluded: new Set([dining]) })[0].spentRial).toBe(4_000_000);
  });

  it("a total's evidence cites only the rows it counted", () => {
    const counted = entry(first + 1, -9_000_000, { categoryId: dining });
    const setAside = entry(first + 2, -8_000_000, { categoryId: 'cat_home' });
    const excluded = new Set([...passThrough(), 'cat_home']);
    const roof = budgetsOf([total(10_000_000)], [counted, setAside], first + 5, { excluded })[0];
    expect(budgetInsight(roof, [counted, setAside], '', excluded).refs).toEqual([counted.txn.ref]);
  });

  it("the total's note names what it leaves out", () => {
    expect(budgetTotalNoteFa([])).toBe('این سقف روی کل خرجته — همهٔ دسته‌ها.');
    expect(budgetTotalNoteFa(['قرض', 'همسر'])).toBe('این سقف روی کل خرجته، جز دسته‌هایی که توی دخل و خرج کنار گذاشتی: قرض، همسر.');
  });

  it('a cap she put on قرض by name still counts it', () => {
    const rows = [entry(first + 1, -5_000_000, { categoryId: CAT_LOAN })];
    expect(budgetsOf([budget(10_000_000, { category: CAT_LOAN, id: 'b_loan' })], rows, first + 5)[0].spentRial).toBe(5_000_000);
  });

  it('the roof sits above the rooms however far through each of them is', () => {
    const rows = [entry(first + 1, -6_500_000, { categoryId: dining }), entry(first + 2, -1_000_000, { categoryId: 'cat_home' })];
    const ordered = budgetsOf([
      budget(5_000_000, { category: dining, id: 'b_dining' }),
      budget(10_000_000, { category: 'cat_home', id: 'b_home' }),
      total(100_000_000),
    ], rows, first + 5);
    expect(ordered.map((b) => b.goal.id)).toEqual(['b_total', 'b_dining', 'b_home']);
  });

  it('a total says what it is rather than naming itself as a category', () => {
    const roof = budgetsOf([total(10_000_000)], [entry(first + 1, -12_000_000, { categoryId: dining })], first + 5)[0];
    expect(budgetAlertTitle(roof)).toBe('از سقف کل خرج مرداد گذشتی');
    expect(budgetAlertTitle(roof).startsWith(BUDGET_TOTAL_FA)).toBe(false);
  });

  // ─────────────────────────── whose figure it is ───────────────────────────

  it('a private cap counts only her own rows', () => {
    const rows = [entry(first + 1, -3_000_000, { owner: her }), entry(first + 2, -4_000_000, { owner: him })];
    const mine = budgetsOf([budget(10_000_000)], rows, first + 5, { mineId: her })[0];
    expect(mine.spentRial).toBe(3_000_000);
    expect(mine.shared).toBe(false);
  });

  it('a shared cap counts the household', () => {
    const rows = [entry(first + 1, -3_000_000, { owner: her }), entry(first + 2, -4_000_000, { owner: him })];
    const ours = budgetsOf([budget(10_000_000, { shared: true, owner: her })], rows, first + 5, { mineId: her })[0];
    expect(ours.spentRial).toBe(7_000_000);
    expect(ours.shared).toBe(true);
  });

  it('on a phone that never paired, private is the whole ledger and nothing changed', () => {
    const rows = [entry(first + 1, -3_000_000), entry(first + 2, -4_000_000)];
    expect(budgetsOf([budget(10_000_000)], rows, first + 5, { mineId: '' })[0].spentRial).toBe(7_000_000);
  });

  it('a shared cap somebody else set says whose it is', () => {
    const rows = [entry(first + 1, -3_000_000, { owner: him })];
    const theirs = budgetsOf([budget(10_000_000, { shared: true, owner: him })], rows, first + 5, {
      mineId: her, members: new Map([[him, 'مریم'], [her, 'سارا']]),
    })[0];
    expect(theirs.ownerName).toBe('مریم');
    expect(budgetScopeFa(theirs)).toBe('خانوادگی • مریم');
    expect(budgetInsight(theirs, rows, her).why).toContain('مریم');
  });

  it('her own shared cap is not attributed to her on her own phone', () => {
    const rows = [entry(first + 1, -3_000_000, { owner: her })];
    const ours = budgetsOf([budget(10_000_000, { shared: true, owner: her })], rows, first + 5, {
      mineId: her, members: new Map([[her, 'سارا']]),
    })[0];
    expect(ours.ownerName).toBe('');
    expect(budgetScopeFa(ours)).toBe('خانوادگی');
  });

  it('a private cap says nothing about whose it is', () => {
    const rows = [entry(first + 1, -3_000_000, { owner: her })];
    expect(budgetScopeFa(budgetsOf([budget(10_000_000)], rows, first + 5, { mineId: her })[0])).toBe('');
  });

  it("a private cap's evidence is the rows it actually counted", () => {
    const mine = entry(first + 1, -60_000_000, { owner: her });
    const his = entry(first + 1, -60_000_000, { owner: him });
    const rows = [mine, his];
    const b = budgetsOf([budget(50_000_000)], rows, first + 5, { mineId: her })[0];
    expect(budgetInsight(b, rows, her).refs).toEqual([mine.txn.ref]);
  });

  it('unsharing warns that the others lose it, and only then', () => {
    expect(budgetScopeNoteFa(false, true)).toContain('بقیه');
    expect(budgetScopeNoteFa(false, false)).not.toContain('بقیه');
    expect(budgetScopeNoteFa(true, false)).toContain('همه');
  });
});
