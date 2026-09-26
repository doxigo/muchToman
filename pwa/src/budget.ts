/**
 * Budget.kt, one to one — «برای این ماه چقدر گذاشتم، و چقدرش رفته». A budget is a `cap` goal with a
 * period; no new table, and no second definition of what a category cost this month.
 *
 * Three rules keep it from becoming a scoreboard:
 *  - A window is closed-open and always the current one. There is no history to browse.
 *  - Spending here is the same number the report shows ([budgetRows]).
 *  - It states facts, never verdicts, and never celebrates spending fewer Toman.
 */
import { MONTHS, faCompact, faNumber, tomanOf } from './format';
import { GoalKind, isTotal, scopedTo } from './goals';
import {
  jalaliMonthEnd, jalaliMonthStart, jalaliOf, jalaliQuarter, jalaliQuarterEnd, jalaliQuarterStart, weekEnd, weekStart,
} from './jalali';
import type { Goal, LedgerEntry } from './model';
import { spendable } from './reports';
import type { Insight } from './reports';
import { PASS_THROUGH_CATEGORIES } from './rules';

// ─────────────────────────── the period ───────────────────────────

/** The Jalali quarters, which are the seasons. */
export const SEASONS = ['بهار', 'تابستان', 'پاییز', 'زمستان'];

/**
 * The three lengths a budget can be kept over. [id] is the `period` column's string; anything
 * longer than a فصل is a report, not a budget. A week runs Saturday to Friday.
 */
// The ids are GoalPeriod's, written out: goals.ts imports this module's importers, so a top-level
// read of GoalPeriod here could run before goals.ts has.
export const BudgetPeriod = {
  WEEK: { name: 'WEEK', id: 'week', fa: 'هفته', everyFa: 'هفتگی' },
  MONTH: { name: 'MONTH', id: 'jmonth', fa: 'ماه', everyFa: 'ماهانه' },
  QUARTER: { name: 'QUARTER', id: 'jquarter', fa: 'فصل', everyFa: 'فصلی' },
} as const;
export type BudgetPeriod = (typeof BudgetPeriod)[keyof typeof BudgetPeriod];
export const BUDGET_PERIODS: readonly BudgetPeriod[] = Object.values(BudgetPeriod);

/**
 * `BudgetPeriod.of`: a month rather than a throw for a period this build does not know — a row a
 * later build wrote must still draw as something she can delete.
 */
export function budgetPeriodOf(id: string): BudgetPeriod {
  return BUDGET_PERIODS.find((p) => p.id === id) ?? BudgetPeriod.MONTH;
}

/** One period of one budget: closed-open Tehran days, [endDay] the first day after. */
export class BudgetWindow {
  constructor(readonly period: BudgetPeriod, readonly startDay: number, readonly endDay: number) {}

  /** 7, 29–31, or 90–92. */
  get days(): number { return this.endDay - this.startDay; }
  contains(day: number): boolean { return day >= this.startDay && day < this.endDay; }

  /** «این هفته», «مرداد», «تابستان» — a week has no name, and a budget only measures the current one. */
  get fa(): string {
    const at = jalaliOf(this.startDay);
    if (this.period === BudgetPeriod.WEEK) return 'این هفته';
    if (this.period === BudgetPeriod.MONTH) return MONTHS[at.month - 1];
    return SEASONS[jalaliQuarter(at.month) - 1];
  }

  /** Days still to come, today included (she can still spend it), floored at zero. */
  daysLeft(today: number): number { return clamp(this.endDay - today, 0, this.days); }
  /** Days gone, today included, so the first day is 1. */
  daysGone(today: number): number { return clamp(today - this.startDay + 1, 1, this.days); }
}

const clamp = (v: number, lo: number, hi: number): number => Math.min(Math.max(v, lo), hi);
/** Rial as the phone's Long, for products that can pass 2^53. */
const big = (rial: number): bigint => BigInt(Math.trunc(rial));

/** The window of [period] that [day] falls in. */
export function budgetWindow(period: BudgetPeriod, day: number): BudgetWindow {
  if (period === BudgetPeriod.WEEK) return new BudgetWindow(period, weekStart(day), weekEnd(day));
  if (period === BudgetPeriod.MONTH) return new BudgetWindow(period, jalaliMonthStart(day), jalaliMonthEnd(day));
  return new BudgetWindow(period, jalaliQuarterStart(day), jalaliQuarterEnd(day));
}

// ─────────────────────────── where it stands ───────────────────────────

/** The shares of the cap at which a budget stops being silent, as whole percents. */
export const BUDGET_NEAR_PERCENT = 80;
export const BUDGET_CLOSE_PERCENT = 95;

/** How loud one budget is. Ordered, so «crossed a line nobody mentioned» is a comparison. */
export const BudgetLevel = { OK: 0, NEAR: 1, CLOSE: 2, OVER: 3 } as const;

/**
 * Exact integer arithmetic (BigInt, as the phone's Long), because at exactly 80% a float
 * comparison is a coin toss on the last bit. Landing exactly on the cap is CLOSE, not OVER.
 */
export function budgetLevel(spentRial: number, capRial: number): number {
  if (capRial <= 0) return spentRial > 0 ? BudgetLevel.OVER : BudgetLevel.OK;
  if (spentRial > capRial) return BudgetLevel.OVER;
  const spent = big(spentRial) * 100n;
  if (spent >= big(capRial) * BigInt(BUDGET_CLOSE_PERCENT)) return BudgetLevel.CLOSE;
  if (spent >= big(capRial) * BigInt(BUDGET_NEAR_PERCENT)) return BudgetLevel.NEAR;
  return BudgetLevel.OK;
}

/** Budget.kt's default for the excluded set: the shipped pass-through categories. */
const passThroughIds = (): ReadonlySet<string> => new Set(PASS_THROUGH_CATEGORIES.keys());

/**
 * The rows one budget counts — the single definition, so the figure, the bar and the evidence on
 * home cannot disagree. Only the negative side (a refund lands on income in the report too).
 * Whose ([scopedTo]), when (the window), what: its category — or, on a total, everything the
 * report does not set aside. A category she caps by name is counted even when set aside.
 */
export function budgetRows(
  entries: LedgerEntry[],
  goal: Goal,
  window: BudgetWindow,
  mineId = '',
  excluded: ReadonlySet<string> = passThroughIds(),
): LedgerEntry[] {
  const category = goal.categoryId ?? null;
  return scopedTo(spendable(entries), mineId, goal.shared).filter((e) =>
    window.contains(e.txn.day) && (category == null ? !excluded.has(e.categoryId) : e.categoryId === category));
}

/** What those rows cost. Whole Rial, never a net: only what went out. */
export function budgetSpent(
  entries: LedgerEntry[],
  goal: Goal,
  window: BudgetWindow,
  mineId = '',
  excluded: ReadonlySet<string> = passThroughIds(),
): number {
  return budgetRows(entries, goal, window, mineId, excluded)
    .reduce((sum, e) => sum - Math.min(e.txn.signedRial ?? 0, 0), 0);
}

/** Where a budget stands, worked out from the transactions and nothing else. Never stored. */
export interface BudgetProgress {
  goal: Goal;
  window: BudgetWindow;
  capRial: number;
  spentRial: number;
  /** Floored at zero; zero exactly when [overRial] is not. */
  leftRial: number;
  overRial: number;
  /** 0..1 for the bar; being over is [overRial]'s job. */
  share: number;
  /** Whole percent gone, unclamped: «۱۳۰٪» is a thing she needs to see. */
  percent: number;
  level: number;
  /** Days still to come, today included — never zero, since the window is today's. */
  daysLeft: number;
  /** What is left over the days left, or null with nothing left to divide. */
  perDayRial: number | null;
  /** More of the cap gone than of the window. */
  outpacing: boolean;
  /** The live category name, the snapshot, or [BUDGET_TOTAL_FA]. Never blank. */
  categoryFa: string;
  /** Set part-way into the window it is reporting on. */
  partWindow: boolean;
  /** Who set it, when that was somebody else; blank on her own and on every private one. */
  ownerName: string;
  period: BudgetPeriod;
  over: boolean;
  /** Whether it has anything to say beyond its own bar. */
  loud: boolean;
  /** The roof over the whole window rather than a cap on one category. */
  total: boolean;
  shared: boolean;
}

/** What a total is called, everywhere it is named. */
export const BUDGET_TOTAL_FA = 'کل خرج';

export function budgetConflicts(goals: Goal[], budget: Goal): Goal[] {
  return goals.filter((it) => !it.deleted && it.id !== budget.id && it.kind === GoalKind.CAP &&
    budget.kind === GoalKind.CAP && (it.categoryId ?? null) === (budget.categoryId ?? null) &&
    it.period === budget.period && it.shared === budget.shared);
}

export interface BudgetOptions {
  categoryFa?: string;
  /** This device's member id, blank when it never paired — see [scopedTo]. */
  mineId?: string;
  ownerName?: string;
  /** What دخل و خرج leaves out — see [budgetRows]. */
  excluded?: ReadonlySet<string>;
}

/** One budget, against the window it is in right now. [today] is passed, never read off a clock. */
export function budgetProgress(
  goal: Goal,
  entries: LedgerEntry[],
  today: number,
  { categoryFa = goal.nameFa, mineId = '', ownerName = '', excluded = passThroughIds() }: BudgetOptions = {},
): BudgetProgress {
  const window = budgetWindow(budgetPeriodOf(goal.period), today);
  const cap = goal.targetRial;
  const spent = budgetSpent(entries, goal, window, mineId, excluded);
  const left = Math.max(cap - spent, 0);
  const over = Math.max(spent - cap, 0);
  const daysLeft = window.daysLeft(today);
  const level = budgetLevel(spent, cap);
  const total = isTotal(goal);
  return {
    goal,
    window,
    capRial: cap,
    spentRial: spent,
    leftRial: left,
    overRial: over,
    share: cap > 0 ? clamp(spent / cap, 0, 1) : 1,
    percent: cap > 0 ? Number((big(spent) * 100n) / big(cap)) : spent > 0 ? 100 : 0,
    level,
    daysLeft,
    perDayRial: daysLeft > 0 && left > 0 ? Math.trunc(left / daysLeft) : null,
    // Exact on both sides, so there is nothing to argue with when the two ratios are equal.
    outpacing: cap > 0 && big(spent) * big(window.days) > big(cap) * big(window.daysGone(today)),
    categoryFa: total ? BUDGET_TOTAL_FA : categoryFa.trim() ? categoryFa : goal.nameFa,
    partWindow: goal.startsOn > window.startDay,
    ownerName,
    period: window.period,
    over: level === BudgetLevel.OVER,
    loud: level >= BudgetLevel.NEAR,
    total,
    shared: goal.shared,
  };
}

/** A shared figure somebody else set says whose it is; her own and private ones say nothing. */
export function ownerNameOf(goal: Goal, mineId: string, members: ReadonlyMap<string, string>): string {
  const owner = goal.ownerMemberId;
  return owner.trim() && owner !== mineId ? members.get(owner) ?? '' : '';
}

export interface BudgetsOptions {
  /** Live category names by id; the row's snapshot is the fallback. */
  names?: ReadonlyMap<string, string>;
  mineId?: string;
  /** Member names by id, for attributing a shared budget somebody else set. */
  members?: ReadonlyMap<string, string>;
  excluded?: ReadonlySet<string>;
}

/**
 * Every budget she keeps, the one closest to trouble first — under the total, which is pinned
 * above: the roof says how much of the month is left at all. Id breaks ties so cards don't swap.
 */
export function budgetsOf(
  goals: Goal[],
  entries: LedgerEntry[],
  today: number,
  { names = new Map(), mineId = '', members = new Map(), excluded = passThroughIds() }: BudgetsOptions = {},
): BudgetProgress[] {
  return goals.filter((g) => g.kind === GoalKind.CAP)
    .map((g) => budgetProgress(g, entries, today, {
      categoryFa: (g.categoryId != null ? names.get(g.categoryId) : undefined) ?? g.nameFa,
      mineId,
      ownerName: ownerNameOf(g, mineId, members),
      excluded,
    }))
    .sort((a, b) => Number(b.total) - Number(a.total) || b.percent - a.percent ||
      (a.goal.id < b.goal.id ? -1 : a.goal.id > b.goal.id ? 1 : 0));
}

// ─────────────────────────── saying it once ───────────────────────────

/**
 * What this device has already said about one budget, and for which window. Per device, not
 * synced; [windowStart] makes it expire on its own when the window rolls.
 */
export interface BudgetMark { goalId: string; windowStart: number; level: number }

/**
 * [alerts]: every budget past a line higher than anything said for its current window. [marks]:
 * what to store — one per live budget at its high-water level, never lowered, which also prunes
 * deleted budgets and ended windows.
 */
export interface BudgetNews { alerts: BudgetProgress[]; marks: BudgetMark[] }

export function budgetNews(budgets: BudgetProgress[], said: BudgetMark[]): BudgetNews {
  const alerts: BudgetProgress[] = [];
  const marks: BudgetMark[] = [];
  for (const budget of budgets) {
    const start = budget.window.startDay;
    const before = said.find((m) => m.goalId === budget.goal.id && m.windowStart === start)?.level ?? BudgetLevel.OK;
    if (budget.level > before && budget.level >= BudgetLevel.NEAR) alerts.push(budget);
    marks.push({ goalId: budget.goal.id, windowStart: start, level: Math.max(before, budget.level) });
  }
  return { alerts, marks };
}

// ─────────────────────────── how it is said ───────────────────────────

/** The notification's first line: the real percent, not the threshold it crossed. */
export function budgetAlertTitle(budget: BudgetProgress): string {
  if (budget.total && budget.over) return `از سقف کل خرج ${budget.window.fa} گذشتی`;
  if (budget.total) return `${faNumber(budget.percent)}٪ سقف کل خرج ${budget.window.fa} رفته`;
  if (budget.over) return `${budget.categoryFa}: از بودجهٔ ${budget.window.fa} گذشتی`;
  return `${budget.categoryFa}: ${faNumber(budget.percent)}٪ بودجهٔ ${budget.window.fa} رفته`;
}

/** The line under it: what is left (or how far past), and how long the window still runs. */
export function budgetAlertBody(budget: BudgetProgress): string {
  let head: string;
  if (budget.over) head = `${faCompact(tomanOf(budget.overRial))} بیشتر از ${faCompact(tomanOf(budget.capRial))} تومان`;
  else if (budget.leftRial <= 0) head = 'چیزی از بودجه نمونده';
  else head = `${faCompact(tomanOf(budget.leftRial))} از ${faCompact(tomanOf(budget.capRial))} تومان مونده`;
  return `${head} • ${budgetDaysLeftFa(budget)}`;
}

/**
 * The one budget worth a line on home: CLOSE and up only, the worst by share. It outranks the
 * review backlog for that slot — a cap run past is money, the backlog is homework.
 */
export function pressingBudget(budgets: BudgetProgress[]): BudgetProgress | null {
  let worst: BudgetProgress | null = null;
  for (const b of budgets) if (b.level >= BudgetLevel.CLOSE && (worst == null || b.percent > worst.percent)) worst = b;
  return worst;
}

/** That budget as a line for home, carrying the rows [budgetRows] counted as its evidence. */
export function budgetInsight(
  budget: BudgetProgress,
  entries: LedgerEntry[],
  mineId = '',
  excluded: ReadonlySet<string> = passThroughIds(),
): Insight {
  let text: string;
  if (budget.total && budget.over) text = `از سقف کل خرج ${budget.window.fa} گذشتی.`;
  else if (budget.total) text = `${faNumber(budget.percent)}٪ سقف کل خرجت رفته.`;
  else if (budget.over) text = `از بودجهٔ «${budget.categoryFa}» گذشتی.`;
  else text = `${faNumber(budget.percent)}٪ بودجهٔ «${budget.categoryFa}» رفته.`;
  const whose = budget.ownerName.trim() ? `سقفی که ${budget.ownerName} گذاشت`
    : budget.total ? 'سقفی که خودت برای کل خرجت گذاشتی'
    : 'سقفی که خودت برای این دسته گذاشتی';
  return {
    text,
    why: `${whose}، در برابر خرج ${budget.shared ? `خانواده در ${budget.window.fa}` : budget.window.fa}.`,
    refs: budgetRows(entries, budget.goal, budget.window, mineId, excluded).map((e) => e.txn.ref),
    tone: 'ATTENTION',
  };
}

/** Whose budget this is, as the card's subtitle says it — blank on a private one. */
export function budgetScopeFa(budget: BudgetProgress): string {
  if (budget.ownerName.trim()) return `خانوادگی • ${budget.ownerName}`;
  if (budget.shared) return 'خانوادگی';
  return '';
}

/** What the sheet says under «مال کیه؟». Only unsharing warns: it removes it from the other phones. */
export function budgetScopeNoteFa(shared: boolean, wasShared: boolean): string {
  if (shared) return 'همه می‌بینن‌ش و می‌تونن عوضش کنن. فقط تراکنش‌های به‌اشتراک‌گذاشته‌شده حساب می‌شن؛ بانک‌های کنارگذاشته‌شده حساب نمی‌شن.';
  if (wasShared) return 'از روی گوشی بقیه برداشته می‌شه و فقط خرج خودت توش حساب می‌شه.';
  return 'فقط روی گوشی خودته و فقط خرج خودت توش حساب می‌شه.';
}

/** What a roof leaves out, in words, from the same set [budgetRows] filters on. */
export function budgetTotalNoteFa(excludedFa: string[]): string {
  return excludedFa.length === 0
    ? 'این سقف روی کل خرجته — همهٔ دسته‌ها.'
    : 'این سقف روی کل خرجته، جز دسته‌هایی که توی دخل و خرج کنار گذاشتی: ' + excludedFa.join('، ') + '.';
}

/** «۱۲ روز تا آخر ماه», and on the last day the thing worth knowing. `<= 1`: one is the floor. */
export function budgetDaysLeftFa(budget: BudgetProgress): string {
  return budget.daysLeft <= 1 ? 'امروز آخرین روزه' : `${faNumber(budget.daysLeft)} روز تا آخر ${budget.period.fa}`;
}

/**
 * The sentence under the bar, ordered by what she can still do about it; a budget comfortably on
 * pace says nothing beyond its bar.
 */
export function budgetNoteFa(budget: BudgetProgress): string {
  if (budget.over) return `${faCompact(tomanOf(budget.overRial))} بیشتر از بودجه‌ات خرج شده.`;
  if (budget.leftRial <= 0) return `بودجهٔ ${budget.window.fa} تموم شد.`;
  if (budget.daysLeft <= 1) return budgetDaysLeftFa(budget) + '.';
  if (budget.outpacing && budget.perDayRial != null) {
    return `تا آخر ${budget.period.fa} روزی ${faCompact(tomanOf(budget.perDayRial))} تومان جا داری.`;
  }
  if (budget.level >= BudgetLevel.NEAR && budget.perDayRial != null) {
    return `${faCompact(tomanOf(budget.leftRial))} تومان مونده، روزی ${faCompact(tomanOf(budget.perDayRial))} تومان.`;
  }
  return '';
}
