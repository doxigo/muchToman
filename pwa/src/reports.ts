/**
 * Reports.kt, one to one: what a month did, worked out from the ledger and nothing else. Every
 * figure is deterministic and can name the transactions it came from; the narrative is a template
 * over these numbers, never prose asked to be true.
 *
 * Four rules run through all of it, as on the phone:
 *  - A transfer is neither income nor spending.
 *  - Money that only passes through (قرض, همسر) is held apart and named, never hidden.
 *  - Never celebrate spending fewer Toman — inflation makes a nominal fall meaningless. Every
 *    comparison is a rate, a share, or a number of days.
 *  - A month only speaks for itself: every range is closed-open, so a report on تیر cannot cite
 *    a transaction from مرداد, and today's cash is never a past month's runway.
 */
import { budgetInsight, pressingBudget } from './budget';
import type { BudgetProgress } from './budget';
import { MONTHS, faDigits, faNumber } from './format';
import { jalaliDay, jalaliMonthLength, jalaliOf, weekStart } from './jalali';
import type { LedgerEntry } from './model';
import { PASS_THROUGH_CATEGORIES } from './rules';

const div = (a: number, b: number): number => Math.trunc(a / b);

/** ۱۴۰۵ and never ۱٬۴۰۵: a year is a name, not a quantity. */
const faYearDigits = (year: number): string => faDigits(String(year));

// ─────────────────────────── the month as a value ───────────────────────────

/** One Jalali month, as a value that can be stepped through, compared and written down. */
export class ReportMonth {
  constructor(readonly year: number, readonly month: number) {
    if (!(month >= 1 && month <= 12)) throw new RangeError(`jalali month out of range: ${month}`);
  }

  /** The first Tehran day of the month. */
  get startDay(): number { return jalaliDay(this.year, this.month, 1); }
  /** The first day of the *next* month: every range here is `startDay until endDay`. */
  get endDay(): number { return this.startDay + jalaliMonthLength(this.year, this.month); }

  previous(): ReportMonth {
    return this.month === 1 ? new ReportMonth(this.year - 1, 12) : new ReportMonth(this.year, this.month - 1);
  }
  next(): ReportMonth {
    return this.month === 12 ? new ReportMonth(this.year + 1, 1) : new ReportMonth(this.year, this.month + 1);
  }
  back(months: number): ReportMonth {
    const total = this.ordinal - months;
    return new ReportMonth(div(total, 12), (total % 12) + 1);
  }

  /** «مرداد ۱۴۰۵». */
  get fa(): string { return `${MONTHS[this.month - 1]} ${faYearDigits(this.year)}`; }

  get ordinal(): number { return this.year * 12 + (this.month - 1); }
  compareTo(other: ReportMonth): number { return this.ordinal - other.ordinal; }
  equals(other: ReportMonth | null | undefined): boolean { return other != null && this.ordinal === other.ordinal; }
}

/** The smallest of [values] mapped, or null — a loop, since a spread has an argument ceiling. */
function minOf<T>(values: T[], of: (v: T) => number): number | null {
  let min: number | null = null;
  for (const v of values) { const n = of(v); if (min == null || n < min) min = n; }
  return min;
}

const indexOfMonth = (months: ReportMonth[], month: ReportMonth): number => months.findIndex((m) => m.equals(month));

/**
 * A run of whole Jalali months, [first] through [last] inclusive — or, when [week] is set, one
 * whole Iranian week, شنبه through جمعه. Never «the last ninety days»: half of فروردین against
 * half of اردیبهشت compares two things each missing a rent payment. Build week ranges through
 * [weekRange], which pins first/last to the month the week starts in.
 */
export class ReportRange {
  constructor(readonly first: ReportMonth, readonly last: ReportMonth, readonly week: number | null = null) {
    if (first.compareTo(last) > 0) throw new RangeError(`range runs backwards: ${first.fa} → ${last.fa}`);
  }

  /** How many months it holds. */
  get count(): number { return (this.last.year * 12 + this.last.month) - (this.first.year * 12 + this.first.month) + 1; }
  get startDay(): number { return this.week ?? this.first.startDay; }
  get endDay(): number { return this.week != null ? this.week + 7 : this.last.endDay; }

  get months(): ReportMonth[] {
    const out = [this.first];
    while (out[out.length - 1].compareTo(this.last) < 0) out.push(out[out.length - 1].next());
    return out;
  }

  /** The closed-open test every figure here is built on. */
  contains(day: number): boolean { return day >= this.startDay && day < this.endDay; }
  containsMonth(month: ReportMonth): boolean { return month.compareTo(this.first) >= 0 && month.compareTo(this.last) <= 0; }

  /**
   * The dollar rate this window's figures are read at: the mean of the days `rateHistory` holds
   * inside it, which is what freezes a month once it is over. Null, never a fallback, when the
   * window has no days on file — today's rate would re-price every month she has ever read.
   *
   * The keys are compared with this window's Tehran days as they are, exactly as the phone does
   * (Data.kt keys the history by UTC epoch day).
   *
   * ponytail: one rate for the whole window; convert each entry at its own day if a stretch
   * where the rial moved hard ever makes the difference matter.
   */
  usdRate(rateHistory: Readonly<Record<string, number>>): number | null {
    const values = Object.entries(rateHistory)
      .filter(([day, rate]) => this.contains(Number(day)) && rate > 0 && Number.isFinite(rate))
      .map(([, rate]) => rate);
    return values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
  }

  /**
   * How many days of this window have been lived, read on [today]: the whole length once it is
   * over, only the part so far for the one she is standing in. Never below one.
   */
  daysSoFar(today: number): number {
    return Math.max(Math.min(this.endDay, today + 1) - this.startDay, 1);
  }

  /** «مرداد ۱۴۰۵», «خرداد تا مرداد ۱۴۰۵», «۳ تا ۹ شهریور ۱۴۰۵» — the year said once where shared. */
  get fa(): string {
    const w = this.week;
    if (w != null) {
      const a = jalaliOf(w);
      const b = jalaliOf(w + 6);
      if (a.year !== b.year) {
        return `${faNumber(a.day)} ${MONTHS[a.month - 1]} ${faYearDigits(a.year)} تا ` +
          `${faNumber(b.day)} ${MONTHS[b.month - 1]} ${faYearDigits(b.year)}`;
      }
      if (a.month !== b.month) {
        return `${faNumber(a.day)} ${MONTHS[a.month - 1]} تا ` +
          `${faNumber(b.day)} ${MONTHS[b.month - 1]} ${faYearDigits(a.year)}`;
      }
      return `${faNumber(a.day)} تا ${faNumber(b.day)} ` +
        `${MONTHS[a.month - 1]} ${faYearDigits(a.year)}`;
    }
    if (this.first.equals(this.last)) return this.last.fa;
    if (this.first.year === this.last.year) return `${MONTHS[this.first.month - 1]} تا ${this.last.fa}`;
    return `${this.first.fa} تا ${this.last.fa}`;
  }

  /** What to call a window that ends at the month she is standing in — described by its length. */
  get recentFa(): string {
    if (this.week != null) return 'این هفته';
    if (this.count === 1) return 'این ماه';
    if (this.count === 12) return 'یک سال گذشته';
    return `${faNumber(this.count)} ماه گذشته`;
  }

  nameFa(current: boolean): string { return current ? this.recentFa : this.fa; }

  get beforeFa(): string {
    if (this.week != null) return 'هفتهٔ قبل';
    if (this.count === 1) return 'ماه قبل';
    return 'دورهٔ قبل';
  }

  /** The window of the same length immediately before this one. */
  before(): ReportRange {
    return this.week != null
      ? weekRange(this.week - 7)
      : new ReportRange(this.first.back(this.count), this.first.previous());
  }
}

/** The week beginning at [start] — a شنبه, as [weekStart] hands out — as a report window. */
export function weekRange(start: number): ReportRange {
  const month = reportMonthOf(start);
  return new ReportRange(month, month, start);
}

/**
 * How much of the ledger دخل و خرج reads. WEEK is the one sub-month length and still a whole unit,
 * شنبه تا جمعه. No «همه»: a savings rate averaged over years of different incomes is not one.
 */
export const ReportSpan = {
  WEEK: { name: 'WEEK', months: 0, fa: '۱ هفته' },
  MONTH: { name: 'MONTH', months: 1, fa: '۱ ماه' },
  QUARTER: { name: 'QUARTER', months: 3, fa: '۳ ماه' },
  HALF: { name: 'HALF', months: 6, fa: '۶ ماه' },
  YEAR: { name: 'YEAR', months: 12, fa: '۱ سال' },
} as const;
export type ReportSpan = (typeof ReportSpan)[keyof typeof ReportSpan];
/** `ReportSpan.entries`, in declaration order. */
export const REPORT_SPANS: readonly ReportSpan[] = Object.values(ReportSpan);

/** The month a Tehran day falls in. */
export function reportMonthOf(day: number): ReportMonth {
  const it = jalaliOf(day);
  return new ReportMonth(it.year, it.month);
}

/** Ten years of months: what stops one corrupt day turning the selector into thousands of rows. */
const MAX_REPORT_MONTHS = 120;

/**
 * Every month she can ask about: the first one the ledger recorded anything in, through this
 * one, nothing skipped (an empty month is an answer) and nothing after today.
 */
export function availableReportMonths(entries: LedgerEntry[], today: number): ReportMonth[] {
  const here = reportMonthOf(today);
  const floor = here.back(MAX_REPORT_MONTHS - 1);
  const earliestDay = minOf(spendable(entries), (e) => e.txn.day);
  const earliest = earliestDay != null ? reportMonthOf(earliestDay) : null;
  // A row dated in the future cannot open a month after this one.
  let first = earliest ?? here;
  if (first.compareTo(here) > 0) first = here;
  if (first.compareTo(floor) < 0) first = floor;
  const out: ReportMonth[] = [];
  for (let m = first; m.compareTo(here) <= 0; m = m.next()) out.push(m);
  return out;
}

/**
 * The months the chart shows around [selected] — at most [span], always real, in order. Near
 * the ledger's start the window slides forward rather than padding with months that never were.
 */
export function chartWindow(available: ReportMonth[], selected: ReportMonth, span = 6): ReportMonth[] {
  const at = indexOfMonth(available, selected);
  if (at < 0) return [];
  const end = Math.max(at + 1, Math.min(span, available.length));
  const start = Math.max(end - span, 0);
  return available.slice(start, end);
}

// ─────────────────────────── one window ───────────────────────────

export type CategoryTotal = [name: string, rial: number];

export interface PeriodReportData {
  range: ReportRange;
  incomeRial: number;
  spentRial: number;
  /** Where the money went, biggest first. */
  spendingByCategory: CategoryTotal[];
  /** Where the money came from, biggest first. */
  incomeByCategory: CategoryTotal[];
  transactions: number;
  handledAutomatically: number;
  /** What only passed through, both directions in one magnitude — the real figure whether or not counted. */
  passedRial: number;
  /** «قرض», «همسر», «قرض و همسر» — only what this window holds. */
  passedFa: string;
  countedPassThrough: boolean;
  /** What the categories she set aside moved anyway, spending side — in no figure above. */
  excludedSpending: CategoryTotal[];
  excludedIncome: CategoryTotal[];
}

/** Two days is the least a daily average can be made of; two whole weeks for a weekly one. */
const MIN_AVERAGE_DAYS = 2;
const MIN_AVERAGE_WEEK_DAYS = 14;

export interface PeriodReport extends PeriodReportData {}
/** One window of the ledger — a single Jalali month, a run of them, or a week. */
export class PeriodReport {
  constructor(data: PeriodReportData) { Object.assign(this, data); }

  /** The month it covers, for the report that covers exactly one. */
  get month(): ReportMonth { return this.range.last; }
  get netRial(): number { return this.incomeRial - this.spentRial; }
  /** Null when nothing came in: a rate against no income is a division by zero dressed as a hard month. */
  get savingsRate(): number | null {
    return this.incomeRial > 0 ? (this.incomeRial - this.spentRial) / this.incomeRial : null;
  }
  /** The share of transactions she never had to touch. The number the app is judged on. */
  get automaticShare(): number | null {
    return this.transactions > 0 ? this.handledAutomatically / this.transactions : null;
  }
  /** خرج per day over the days actually lived; refused over a single day. */
  dailySpendRial(today: number): number | null {
    const days = this.range.daysSoFar(today);
    return days >= MIN_AVERAGE_DAYS && this.spentRial > 0 ? div(this.spentRial, days) : null;
  }
  /** The same pace per week, off the days rather than the truncated daily figure; refused under two weeks. */
  weeklySpendRial(today: number): number | null {
    const days = this.range.daysSoFar(today);
    return days >= MIN_AVERAGE_WEEK_DAYS && this.spentRial > 0 ? div(this.spentRial * 7, days) : null;
  }
}

/**
 * Entries that count as money moving: no transfers, no hidden duplicates, and no مانده-only rows
 * (a balance with no amount would drag the automatic share down for every balance message).
 */
export function spendable(entries: LedgerEntry[]): LedgerEntry[] {
  return entries.filter((e) => !(e.duplicate || e.transfer || e.txn.amountRial == null));
}

const add = (map: Map<string, number>, key: string, rial: number): void => { map.set(key, (map.get(key) ?? 0) + rial); };
const biggestFirst = (map: Map<string, number>): CategoryTotal[] => [...map].sort((a, b) => b[1] - a[1]);
const NONE: ReadonlySet<string> = new Set();

/**
 * The window, both sides of it. A run of months adds up exactly as one month does — no figure is
 * a mean of monthly figures. [countPassThrough] folds قرض and همسر back into the sides; [excluded]
 * is the household's set-aside categories, out of every figure but still summed apart.
 */
export function periodReport(
  entries: LedgerEntry[],
  range: ReportRange,
  countPassThrough = false,
  excluded: ReadonlySet<string> = NONE,
): PeriodReport {
  const inWindow = spendable(entries).filter((e) => range.contains(e.txn.day));
  const leftOut = inWindow.filter((e) => excluded.has(e.categoryId));
  const inRange = inWindow.filter((e) => !excluded.has(e.categoryId));

  const excludedSpending = new Map<string, number>();
  const excludedIncome = new Map<string, number>();
  for (const entry of leftOut) {
    const signed = entry.txn.signedRial;
    // A zero moves nothing, and a «name to ۰» line would be a dot claiming to be a figure.
    if (signed == null || signed === 0) continue;
    if (signed > 0) add(excludedIncome, entry.categoryFa, signed);
    else add(excludedSpending, entry.categoryFa, -signed);
  }

  let income = 0;
  let spent = 0;
  let passed = 0;
  const passedNames = new Set<string>();
  const spending = new Map<string, number>();
  const earning = new Map<string, number>();
  for (const entry of inRange) {
    const signed = entry.txn.signedRial;
    if (signed == null) continue;
    const passes = PASS_THROUGH_CATEGORIES.get(entry.categoryId);
    if (passes != null) {
      passed += Math.abs(signed);
      passedNames.add(passes);
      // Measured either way, counted only when she asks: skipping the rest is the whole switch.
      if (!countPassThrough) continue;
    }
    if (signed > 0) {
      income += signed;
      add(earning, entry.categoryFa, signed);
    } else {
      spent += -signed;
      add(spending, entry.categoryFa, -signed);
    }
  }
  return new PeriodReport({
    range,
    incomeRial: income,
    spentRial: spent,
    spendingByCategory: biggestFirst(spending),
    incomeByCategory: biggestFirst(earning),
    transactions: inRange.length,
    handledAutomatically: inRange.filter((e) => !e.needsReview).length,
    passedRial: passed,
    // The table's order, so it is «قرض و همسر» whichever landed first.
    passedFa: [...new Set(PASS_THROUGH_CATEGORIES.values())].filter((n) => passedNames.has(n)).join(' و '),
    countedPassThrough: countPassThrough,
    excludedSpending: biggestFirst(excludedSpending),
    excludedIncome: biggestFirst(excludedIncome),
  });
}

/** One month of it: the chart's bars and the home screen. */
export function monthReport(
  entries: LedgerEntry[],
  month: ReportMonth,
  countPassThrough = false,
  excluded: ReadonlySet<string> = NONE,
): PeriodReport {
  return periodReport(entries, new ReportRange(month, month), countPassThrough, excluded);
}

export function currentMonthReport(entries: LedgerEntry[], today: number): PeriodReport {
  return monthReport(entries, reportMonthOf(today));
}

export interface ReadingOptions {
  countPassThrough?: boolean;
  excluded?: ReadonlySet<string>;
}

/**
 * How many days of ordinary spending the liquid money would cover: the median daily outflow, so
 * one car repair does not cost her three weeks. Null without 14 days of history. Pass-through and
 * excluded money is left out — lending is not a habit that eats a balance.
 */
export function bufferDays(
  entries: LedgerEntry[],
  liquidRial: number,
  today: number,
  { over = 90, countPassThrough = false, excluded = NONE }: ReadingOptions & { over?: number } = {},
): number | null {
  const from = today - over;
  const byDay = new Map<number, number>();
  for (const e of spendable(entries)) {
    const signed = e.txn.signedRial ?? 0;
    if (e.txn.day < from || e.txn.day > today || signed >= 0) continue;
    if (excluded.has(e.categoryId)) continue;
    if (!countPassThrough && PASS_THROUGH_CATEGORIES.has(e.categoryId)) continue;
    byDay.set(e.txn.day, (byDay.get(e.txn.day) ?? 0) - signed);
  }
  const daily = [...byDay.values()];
  if (daily.length < 14) return null;
  const median = daily.sort((a, b) => a - b)[div(daily.length, 2)];
  if (median <= 0) return null;
  return Math.min(Math.trunc(liquidRial / median), 3650);
}

// ─────────────────────────── one category, taken apart ───────────────────────────

/** One category (or member) out of the window's list, opened up. */
export interface CategoryWindow {
  name: string;
  income: boolean;
  range: ReportRange;
  /** What it moved inside [range]. */
  totalRial: number;
  /** That side's whole figure for the window — what the share is a share of. */
  sideRial: number;
  /** The trailing months ending at the window's last, oldest first; empty months are zero. */
  trend: Array<[month: ReportMonth, rial: number]>;
  /** The rows behind [totalRial], newest first. */
  rows: LedgerEntry[];
  /** The member's face for a member window (blank for none); null for a category. */
  face: string | null;
  /** A member window's money by category, biggest first; empty on a category's own window. */
  breakdown: CategoryTotal[];
  share: number | null;
}

const absRial = (e: LedgerEntry): number => Math.abs(e.txn.signedRial ?? 0);
const sumAbs = (rows: LedgerEntry[]): number => rows.reduce((s, e) => s + absRial(e), 0);

/** By name rather than id: the window's list is aggregated by name, so one line is one sheet. */
export function categoryWindow(
  entries: LedgerEntry[],
  name: string,
  income: boolean,
  range: ReportRange,
  sideRial: number,
  {
    trendMonths = 6,
    face = null,
    matches = (e: LedgerEntry) => e.categoryFa === name,
  }: { trendMonths?: number; face?: string | null; matches?: (e: LedgerEntry) => boolean } = {},
): CategoryWindow {
  const side = spendable(entries).filter((e) => {
    const signed = e.txn.signedRial;
    if (signed == null) return false;
    return matches(e) && (income ? signed > 0 : signed < 0);
  });
  const inRange = side.filter((e) => range.contains(e.txn.day));
  const trend: Array<[ReportMonth, number]> = [];
  for (let back = trendMonths - 1; back >= 0; back--) {
    const month = range.last.back(back);
    trend.push([month, sumAbs(side.filter((e) => e.txn.day >= month.startDay && e.txn.day < month.endDay))]);
  }
  const totalRial = sumAbs(inRange);
  return {
    name,
    income,
    range,
    totalRial,
    sideRial,
    trend,
    rows: [...inRange].sort((a, b) => b.txn.at - a.txn.at),
    face,
    breakdown: [],
    share: sideRial > 0 ? totalRial / sideRial : null,
  };
}

// ─────────────────────────── the members, side by side ───────────────────────────

export interface MemberShare {
  id: string;
  name: string;
  /** The face they picked, for the row's disc. */
  avatar: string;
  spentRial: number;
  incomeRial: number;
}

/**
 * The window split by whose row it is, biggest spender first, through the same gates as
 * [periodReport] so the rows add up to the cards. Empty unless more than one member is in it.
 */
export function memberShares(
  entries: LedgerEntry[],
  range: ReportRange,
  countPassThrough = false,
  excluded: ReadonlySet<string> = NONE,
): MemberShare[] {
  const byOwner = new Map<string, LedgerEntry[]>();
  for (const e of spendable(entries)) {
    if (!range.contains(e.txn.day) || excluded.has(e.categoryId)) continue;
    const list = byOwner.get(e.ownerMemberId);
    if (list) list.push(e); else byOwner.set(e.ownerMemberId, [e]);
  }
  if (byOwner.size < 2) return [];
  return [...byOwner].map(([id, rows]): MemberShare => {
    let income = 0;
    let spent = 0;
    for (const row of rows) {
      const signed = row.txn.signedRial;
      if (signed == null) continue;
      if (!countPassThrough && PASS_THROUGH_CATEGORIES.has(row.categoryId)) continue;
      if (signed > 0) income += signed; else spent += -signed;
    }
    return {
      id,
      // A row can outlive its member's name.
      name: rows[0].ownerName.trim() ? rows[0].ownerName : 'عضو قبلی',
      avatar: rows[0].ownerAvatar,
      spentRial: spent,
      incomeRial: income,
    };
  }).sort((a, b) => b.spentRial - a.spentRial);
}

/**
 * One member's rows in [categoryWindow]'s shape, through the gates [memberShares] counted, so the
 * sheet states the figure on the bar she tapped — plus where that money went, by category.
 */
export function memberWindow(
  entries: LedgerEntry[],
  member: MemberShare,
  income: boolean,
  range: ReportRange,
  sideRial: number,
  countPassThrough = false,
  excluded: ReadonlySet<string> = NONE,
): CategoryWindow {
  const window = categoryWindow(entries, member.name, income, range, sideRial, {
    face: member.avatar,
    matches: (row) => row.ownerMemberId === member.id && !excluded.has(row.categoryId) &&
      (countPassThrough || !PASS_THROUGH_CATEGORIES.has(row.categoryId)),
  });
  const byCategory = new Map<string, number>();
  for (const row of window.rows) add(byCategory, row.categoryFa, absRial(row));
  return { ...window, breakdown: biggestFirst(byCategory) };
}

// ─────────────────────────── the narrative ───────────────────────────

export type InsightTone = 'NEUTRAL' | 'GOOD' | 'ATTENTION';

/** One statement the app is prepared to make, and the transactions behind it («چرا این را می‌بینم؟»). */
export interface Insight {
  text: string;
  why: string;
  refs: string[];
  tone: InsightTone;
}

const faPercent = (share: number): string => faNumber(Math.round(share * 100));

/**
 * What changed, in Persian, from figures computed before any of this ran. Templates, not
 * generation. [current] is «این ماه» versus «در تیر ۱۴۰۵», passed so a past month can never claim
 * to describe the present.
 */
export function narrate(
  now: PeriodReport,
  before: PeriodReport | null,
  entries: LedgerEntry[],
  bufferDays: number | null,
  current: boolean,
): Insight[] {
  const out: Insight[] = [];
  // Closed-open at both ends, or a past report would cite every transaction since.
  const inMonth = spendable(entries).filter((e) => now.range.contains(e.txn.day));
  const monthRefs = inMonth.map((e) => e.txn.ref);
  const named = now.range.nameFa(current);
  const head = current && now.range.count === 1 ? named : `در ${named}،`;
  const beforeNamed = now.range.beforeFa;

  const rate = now.savingsRate;
  if (rate != null) {
    const beforeRate = before?.savingsRate ?? null;
    // A negative rate is not «−۲۹٪ remained»: nothing remained, she spent more than came in.
    let text: string;
    if (rate < 0) text = `${head} ${faPercent(-rate)}٪ بیشتر از درآمدت خرج کردی.`;
    else if (beforeRate == null) text = `${head} ${faPercent(rate)}٪ از درآمدت مونده.`;
    else if (rate > beforeRate + 0.01) text = `${head} ${faPercent(rate)}٪ از درآمدت مونده؛ ${beforeNamed} ${faPercent(beforeRate)}٪ بود.`;
    else if (rate < beforeRate - 0.01) text = `${head} ${faPercent(rate)}٪ از درآمدت مونده؛ ${beforeNamed} ${faPercent(beforeRate)}٪ بود.`;
    else if (current) text = `پس‌اندازت مثل ${beforeNamed} شد: ${faPercent(rate)}٪.`;
    else text = `پس‌انداز ${named} مثل ${beforeNamed}ش بود: ${faPercent(rate)}٪.`;
    out.push({
      text,
      // A rate, never a Toman figure — and the why names everything the figure left out.
      why: `این عدد از درآمد و خرج ${named} به‌دست اومده؛ پولی که بین حساب‌های خودت جابه‌جا کردی، حساب نشده.` +
        (now.passedRial > 0 && !now.countedPassThrough ? ` ${now.passedFa} هم حساب نشده.` : ''),
      refs: monthRefs,
      tone: beforeRate != null && rate > beforeRate + 0.01 ? 'GOOD' : 'NEUTRAL',
    });
  }

  // The single category that moved most, as a share of spending rather than in Toman.
  if (before != null && now.spentRial > 0 && before.spentRial > 0) {
    const beforeShare = new Map(before.spendingByCategory.map(([name, rial]) => [name, rial / before.spentRial]));
    let moved: [string, number, number] | null = null;
    for (const [name, rial] of now.spendingByCategory) {
      const share = rial / now.spentRial;
      const delta = share - (beforeShare.get(name) ?? 0);
      if (Math.abs(delta) < 0.05) continue;
      if (moved == null || Math.abs(delta) > Math.abs(moved[2])) moved = [name, share, delta];
    }
    if (moved) {
      const [name, share, delta] = moved;
      const lead = current && now.range.count === 1 ? '' : `در ${named} `;
      out.push({
        text: delta > 0
          ? `${lead}سهم «${name}» از خرجت بیشتر شده: ${faPercent(share)}٪ از کل.`
          : `${lead}سهم «${name}» از خرجت کمتر شده: ${faPercent(share)}٪ از کل.`,
        why: `این دسته رو با ${beforeNamed} مقایسه کردیم.`,
        refs: inMonth.filter((e) => e.categoryFa === name).map((e) => e.txn.ref),
        tone: 'NEUTRAL',
      });
    }
  }

  // Only ever present tense — [buildCashFlow] refuses it for a past month.
  if (bufferDays != null) {
    out.push({
      text: `با خرج معمولی‌ات، پول نقدت برای ${faNumber(bufferDays)} روز می‌رسه.`,
      why: 'موجودی حساب‌ها تقسیم بر میانهٔ خرج روزانهٔ سه ماه گذشته.',
      refs: [],
      tone: bufferDays >= 90 ? 'GOOD' : 'NEUTRAL',
    });
  }

  const share = now.automaticShare;
  if (share != null && now.transactions >= 5) {
    out.push({
      // The app earning its silence, which is the thing actually worth reporting.
      text: `${faPercent(share)}٪ از ${faNumber(now.transactions)} تراکنش ${named} خودشون دسته‌بندی شدن.`,
      why: 'تراکنش‌هایی که بدون پرسیدن ازت دسته‌بندی شدن.',
      refs: monthRefs,
      tone: share >= 0.9 ? 'GOOD' : 'NEUTRAL',
    });
  }

  // The queue is a thing to do now, counted over the whole ledger: only the current window says it.
  if (current) {
    const waiting = entries.filter((e) => e.needsReview && !e.duplicate && !e.transfer);
    if (waiting.length) {
      out.push({
        text: `${faNumber(waiting.length)} تراکنش منتظر انتخاب توئه.`,
        why: 'تراکنش‌هایی که با اطمینان کافی دسته‌بندی نشدن.',
        refs: waiting.map((e) => e.txn.ref),
        tone: 'ATTENTION',
      });
    }
  }
  return out;
}

/** Wins worth marking, and nothing else: real outcomes, each stated once with its figure. */
export function quietWins(
  now: PeriodReport,
  before: PeriodReport | null,
  bufferDays: number | null,
  current: boolean,
): Insight[] {
  const wins: Insight[] = [];
  const named = now.range.nameFa(current);
  const beforeNamed = now.range.beforeFa;
  const rate = now.savingsRate;
  if (rate != null && rate > 0 && (before?.savingsRate ?? -1) <= 0) {
    wins.push({
      // Not «اولین ماهی که…»: the comparison reaches exactly one window back.
      text: current && now.range.count === 1
        ? `درآمد ${named} از خرجت بیشتر شد.`
        : `در ${named} درآمدت از خرجت بیشتر شد.`,
      why: `${beforeNamed}ش چیزی از درآمدت نمونده بود.`,
      refs: [],
      tone: 'GOOD',
    });
  }
  if (bufferDays != null && bufferDays >= 30) {
    wins.push({
      text: 'یک ماه خرج ضروری‌ات رو کنار گذاشتی.',
      why: 'موجودی نقدی تقسیم بر میانهٔ خرج روزانه.',
      refs: [],
      tone: 'GOOD',
    });
  }
  const beforeRate = before?.savingsRate ?? null;
  if (rate != null && beforeRate != null && rate > beforeRate + 0.05) {
    wins.push({
      text: current ? `پس‌اندازت نسبت به ${beforeNamed} بهتر شده.` : `پس‌انداز ${named} از ${beforeNamed}ش بهتر بود.`,
      why: 'مهم اینه چند درصد از درآمدت مونده، نه چند تومان.',
      refs: [],
      tone: 'GOOD',
    });
  }
  return wins;
}

// ─────────────────────────── one window, assembled ───────────────────────────

/**
 * Everything the cash-flow report says, worked out in one pass: the screen renders this and
 * computes nothing, so the bar she taps and the figure she reads cannot disagree.
 */
export interface CashFlowReport {
  /** The month the window ends at (for WEEK, the month the week starts in). */
  selected: ReportMonth;
  span: ReportSpan;
  /** Every month she may move to, oldest first. Never empty. */
  available: ReportMonth[];
  /** The lengths this ledger can answer at this anchor. Never empty. */
  spans: ReportSpan[];
  period: PeriodReport;
  previous: PeriodReport;
  /** The chart's bars. */
  series: PeriodReport[];
  insights: Insight[];
  wins: Insight[];
  /** Who moved what, on a shared ledger; empty on a ledger of one. */
  members: MemberShare[];
  /** Cash runway, only ever for a window she is standing in. */
  bufferDays: number | null;
  /** Whether the window reaches the month (or week) containing today. */
  current: boolean;
  /** The Tehran day this was built on — one clock for the figures and the tap back to today. */
  today: number;
  canGoBack: boolean;
  canGoForward: boolean;
  range: ReportRange;
}

export interface CashFlowOptions extends ReadingOptions {
  span?: ReportSpan;
  /** The day a WEEK span reads the week of; month spans ignore it. Null anchors at today. */
  anchorDay?: number | null;
}

export function buildCashFlow(
  entries: LedgerEntry[],
  liquidRial: number,
  today: number,
  selected: ReportMonth,
  { span = ReportSpan.MONTH, countPassThrough = false, excluded = NONE, anchorDay = null }: CashFlowOptions = {},
): CashFlowReport {
  if (span === ReportSpan.WEEK) return buildWeekly(entries, liquidRial, today, anchorDay, countPassThrough, excluded);
  const available = availableReportMonths(entries, today);
  const here = reportMonthOf(today);
  // A month she can no longer reach reports as now, not as an empty month she cannot leave.
  const month = indexOfMonth(available, selected) >= 0 ? selected : here;
  const at = indexOfMonth(available, month);
  // Only lengths the ledger can fill: «۶ ماه» over four months is a four-month report mislabelled.
  const spans = REPORT_SPANS.filter((s) => s.months <= at + 1);
  const use = spans.includes(span) ? span : spans[spans.length - 1];
  const range = new ReportRange(month.back(use.months - 1), month);
  const current = month.equals(here);
  const report = periodReport(entries, range, countPassThrough, excluded);
  const before = range.before();
  const previous = periodReport(entries, before, countPassThrough, excluded);
  // Refused for a past window: today's cash against تیر's spending states a balance تیر never had.
  const buffer = current ? bufferDays(entries, liquidRial, today, { countPassThrough, excluded }) : null;
  // A whole window the ledger watched, or no comparison.
  const had = previous.transactions > 0 && before.first.compareTo(available[0]) >= 0 ? previous : null;
  return {
    selected: month,
    span: use,
    available,
    spans,
    period: report,
    previous,
    // Six bars under a shorter window, the window's own length once longer.
    series: chartWindow(available, month, Math.max(use.months, 6))
      .map((m) => monthReport(entries, m, countPassThrough, excluded)),
    insights: narrate(report, had, entries, buffer, current),
    wins: quietWins(report, had, buffer, current),
    members: memberShares(entries, range, countPassThrough, excluded),
    bufferDays: buffer,
    current,
    today,
    canGoBack: month.compareTo(available[0]) > 0,
    canGoForward: month.compareTo(available[available.length - 1]) < 0,
    range,
  };
}

/** How many weeks the weekly chart lines up — the same six the month chart draws. */
const WEEK_BARS = 6;

/** [buildCashFlow] for the one span not made of months: a week against the whole week before. */
function buildWeekly(
  entries: LedgerEntry[],
  liquidRial: number,
  today: number,
  anchorDay: number | null,
  countPassThrough: boolean,
  excluded: ReadonlySet<string>,
): CashFlowReport {
  const thisWeek = weekStart(today);
  // The earliest week the ledger recorded anything in; a future row cannot open a later week.
  const floor = Math.min(minOf(spendable(entries), (e) => weekStart(e.txn.day)) ?? thisWeek, thisWeek);
  const week = Math.min(Math.max(weekStart(anchorDay ?? today), floor), thisWeek);
  const range = weekRange(week);
  const current = week === thisWeek;
  const report = periodReport(entries, range, countPassThrough, excluded);
  const previous = periodReport(entries, range.before(), countPassThrough, excluded);
  const buffer = current ? bufferDays(entries, liquidRial, today, { countPassThrough, excluded }) : null;
  const had = previous.transactions > 0 && range.before().startDay >= floor ? previous : null;
  // The month selector's world, kept so the span picker can still dim what does not fit.
  const available = availableReportMonths(entries, today);
  const startMonth = reportMonthOf(week);
  const month = indexOfMonth(available, startMonth) >= 0 ? startMonth : available[available.length - 1];
  const spans = REPORT_SPANS.filter((s) => s.months <= indexOfMonth(available, month) + 1);
  const series: PeriodReport[] = [];
  for (let back = WEEK_BARS - 1; back >= 0; back--) {
    const start = week - 7 * back;
    if (start >= floor) series.push(periodReport(entries, weekRange(start), countPassThrough, excluded));
  }
  return {
    selected: month,
    span: ReportSpan.WEEK,
    available,
    spans,
    period: report,
    previous,
    series,
    insights: narrate(report, had, entries, buffer, current),
    wins: quietWins(report, had, buffer, current),
    members: memberShares(entries, range, countPassThrough, excluded),
    bufferDays: buffer,
    current,
    today,
    canGoBack: week > floor,
    canGoForward: week < thisWeek,
    range,
  };
}

/** Everything the home screen says, worked out in one pass. */
export interface HomeStory {
  month: PeriodReport;
  previous: PeriodReport;
  insights: Insight[];
  wins: Insight[];
  bufferDays: number | null;
  /** The budget behind [attention], when a budget is what is asking for her — the card's destination. */
  attentionBudget: BudgetProgress | null;
  /** The one line worth leading with, and never more than one. */
  headline: Insight | null;
  /** The single thing asking for her, if anything is. */
  attention: Insight | null;
}

export interface StoryOptions extends ReadingOptions {
  /** The caps she keeps, so home can carry the one that has run past itself. */
  budgets?: BudgetProgress[];
  /** This device's member id, so a private cap's evidence is the rows it counted. */
  mineId?: string;
}

/** The current month for home: [buildCashFlow]'s walk minus the six-month series. */
export function buildStory(
  entries: LedgerEntry[],
  liquidRial: number,
  today: number,
  { budgets = [], countPassThrough = false, excluded = NONE, mineId = '' }: StoryOptions = {},
): HomeStory {
  const here = reportMonthOf(today);
  const month = monthReport(entries, here, countPassThrough, excluded);
  const previous = monthReport(entries, here.previous(), countPassThrough, excluded);
  const buffer = bufferDays(entries, liquidRial, today, { countPassThrough, excluded });
  const had = previous.transactions > 0 ? previous : null;
  const pressing = pressingBudget(budgets);
  // The budget first, so [attention] prefers a cap she has run past over a review queue.
  const insights = [
    ...(pressing ? [budgetInsight(pressing, entries, mineId, excluded)] : []),
    ...narrate(month, had, entries, buffer, true),
  ];
  const wins = quietWins(month, had, buffer, true);
  return {
    month,
    previous,
    insights,
    wins,
    bufferDays: buffer,
    attentionBudget: pressing,
    headline: wins[0] ?? insights.find((i) => i.tone !== 'ATTENTION') ?? null,
    attention: insights.find((i) => i.tone === 'ATTENTION') ?? null,
  };
}
