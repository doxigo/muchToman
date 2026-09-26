/**
 * Goals.kt, one to one, plus the goal card's two text functions from BudgetUi.kt. Progress is
 * computed, never stored: a stored figure can drift from the transactions under it, and then the
 * app is congratulating her on a number it made up.
 *
 * One table, three shapes: `save` (this file), `cap` (budget.ts) and `installment`
 * (installments.ts). A goal is met by reaching its figure, a budget by not reaching its own.
 */
import { faCompact, faDate, faNumber, tomanOf } from './format';
import { jalaliMonthsAheadEnd, weekStart } from './jalali';
import type { Decision, Goal, LedgerEntry } from './model';
import { spendable } from './reports';
import { CAT_BILLS_ID, CAT_CASH, CAT_FEES, CAT_INCOME, CAT_TRANSFER, DecisionKind } from './rules';

export const GoalKind = { SAVE: 'save', CAP: 'cap', INSTALLMENT: 'installment' } as const;

/** The `period` column's vocabulary — on disk, so values rather than an enum. */
export const GoalPeriod = {
  /** A budget kept Saturday to Friday. */
  WEEK: 'week',
  /** A Jalali month. */
  MONTH: 'jmonth',
  /** A فصل — three Jalali months. */
  QUARTER: 'jquarter',
  /** A savings goal: from its start to its deadline. */
  ONCE: 'once',
} as const;

/** `Goal.total`: a cap on everything rather than on one category — «سقف کل خرج». */
export function isTotal(goal: Goal): boolean {
  return goal.kind === GoalKind.CAP && goal.categoryId == null;
}

/**
 * The rows one figure may count: the household's shared rows, or only hers. The single place
 * `Goal.shared` turns into arithmetic. On a device that never paired [mineId] is blank, and so is
 * every local row's owner, so «only mine» is the whole ledger there.
 */
export function scopedTo(entries: LedgerEntry[], mineId: string, shared: boolean): LedgerEntry[] {
  return shared ? entries.filter((e) => e.sharedWithFamily) : entries.filter((e) => e.ownerMemberId === mineId);
}

const horizon = <N extends string>(name: N, months: number | null, fa: string) => ({
  name,
  months,
  fa,
  /** The Tehran day this horizon ends on — the last day of its own month — or null for OPEN. */
  endsOn: (today: number): number | null => (months == null ? null : jalaliMonthsAheadEnd(today, months)),
});

/** How long a savings goal may run for, offered as four pills instead of a date picker. */
export const GoalHorizon = {
  QUARTER: horizon('QUARTER', 3, '۳ ماه'),
  HALF: horizon('HALF', 6, '۶ ماه'),
  YEAR: horizon('YEAR', 12, '۱ سال'),
  /** No deadline at all, which is a real answer: «هر وقت شد». */
  OPEN: horizon('OPEN', null, 'بی‌مهلت'),
} as const;
export type GoalHorizon = (typeof GoalHorizon)[keyof typeof GoalHorizon];
export const GOAL_HORIZONS: readonly GoalHorizon[] = Object.values(GoalHorizon);

/** Where a savings goal stands, worked out from the transactions and nothing else. */
export interface GoalProgress {
  goal: Goal;
  currentRial: number;
  targetRial: number;
  /** Zero once the goal is met. */
  remainingRial: number;
  /** 0..1, clamped. */
  share: number;
  /** She spent more than came in since it started: a flag, never a negative figure. */
  underWater: boolean;
  done: boolean;
  /** Days until the deadline, today counting as one. Null with no deadline. */
  daysLeft: number | null;
  /** What to keep each month to land on time, rounded up; null under a month or with none. */
  perMonthRial: number | null;
  /** The deadline passed with the target unmet. Never true for a met goal. */
  expired: boolean;
  /** Who set it, when that was somebody else. */
  ownerName: string;
  shared: boolean;
}

/** Days in an average Jalali month, as an integer: the rate is rounded up anyway. */
const JALALI_MONTH_DAYS = 30;

/**
 * A savings goal counts what was kept: income minus spending since it started, transfers and
 * duplicates out. ponytail: one net for every goal — allocating between pots needs a screen.
 */
export function goalProgress(
  goal: Goal,
  entries: LedgerEntry[],
  today: number,
  { mineId = '', ownerName = '' }: { mineId?: string; ownerName?: string } = {},
): GoalProgress {
  const net = scopedTo(spendable(entries), mineId, goal.shared)
    .filter((e) => e.txn.day >= goal.startsOn)
    .reduce((sum, e) => sum + (e.txn.signedRial ?? 0), 0);
  const current = Math.max(net, 0);
  const remaining = Math.max(goal.targetRial - current, 0);
  // Today counts: a zero on the deadline itself would read as "the time is up" on a day it is not.
  const daysLeft = goal.endsOn != null ? goal.endsOn - today + 1 : null;
  const done = current >= goal.targetRial;
  let perMonthRial: number | null = null;
  if (daysLeft != null && !done && daysLeft >= JALALI_MONTH_DAYS && remaining > 0) {
    // Ceiling division: floor lands short in the one month where being short is the outcome.
    const months = Math.trunc(daysLeft / JALALI_MONTH_DAYS);
    perMonthRial = Math.trunc((remaining + months - 1) / months);
  }
  return {
    goal,
    currentRial: current,
    targetRial: goal.targetRial,
    remainingRial: remaining,
    share: goal.targetRial > 0 ? Math.min(Math.max(current / goal.targetRial, 0), 1) : 0,
    underWater: net < 0,
    done,
    daysLeft,
    perMonthRial,
    expired: daysLeft != null && daysLeft <= 0 && !done,
    ownerName,
    shared: goal.shared,
  };
}

// ─────────────────────── «آیا ارزشش را داشت؟» ───────────────────────

export const WorthIt = { YES: 'yes', NO: 'no', NEEDED: 'needed' } as const;

/** The card's three answers, in the order it offers them. */
export const WORTH_IT_ANSWERS: ReadonlyArray<[label: string, value: string]> = [
  ['آره', WorthIt.YES],
  ['لازم بود', WorthIt.NEEDED],
  ['نه', WorthIt.NO],
];

/** Rent, bills, fees and cash are never asked about — asking would read as the app being smug. */
const NEVER_ASKED: ReadonlySet<string> = new Set([CAT_TRANSFER, CAT_FEES, CAT_INCOME, CAT_BILLS_ID, CAT_CASH]);

export const WORTH_IT_PER_WEEK = 2;

/**
 * At most two large discretionary purchases a week, and only her own (`txn.ownerMemberId` is blank
 * exactly on rows read on this device) — and only once filed: settle what it is first.
 */
export function worthItCandidates(
  entries: LedgerEntry[],
  answered: ReadonlySet<string>,
  today: number,
  threshold: number,
): LedgerEntry[] {
  const weekFrom = weekStart(today);
  return spendable(entries)
    .filter((e) => e.txn.day >= weekFrom &&
      e.txn.direction === 'out' &&
      (e.txn.amountRial ?? 0) >= threshold &&
      !NEVER_ASKED.has(e.categoryId) &&
      !e.txn.ownerMemberId.trim() &&
      !answered.has(e.txn.ref) &&
      !e.needsReview)
    .sort((a, b) => (b.txn.amountRial ?? 0) - (a.txn.amountRial ?? 0))
    .slice(0, WORTH_IT_PER_WEEK);
}

/** The 90th percentile of what she spends, floored so a quiet month does not ask about bus fares. */
export function largeSpendThreshold(entries: LedgerEntry[], floorRial = 20_000_000): number {
  const amounts = spendable(entries)
    .filter((e) => e.txn.direction === 'out')
    .map((e) => e.txn.amountRial)
    .filter((a): a is number => a != null)
    .sort((a, b) => a - b);
  if (amounts.length < 10) return floorRial;
  return Math.max(amounts[Math.trunc((amounts.length * 9) / 10)], floorRial);
}

/** Her answers so far, ref → «yes»/«no»/«needed» (live `worth_it` decisions). */
export function worthItAnswers(decisions: Decision[]): Map<string, string> {
  const out = new Map<string, string>();
  for (const d of decisions) if (d.kind === DecisionKind.WORTH_IT && !d.deleted && d.value != null) out.set(d.ref, d.value);
  return out;
}

/** What she has said about her own spending. [regretted] is the only actionable half. */
export interface WorthItSummary { worth: number; notWorth: number; needed: number; total: number; regretted: number }

export function worthItSummary(entries: LedgerEntry[], answers: ReadonlyMap<string, string>): WorthItSummary {
  let worth = 0;
  let notWorth = 0;
  let needed = 0;
  for (const entry of spendable(entries)) {
    const amount = entry.txn.amountRial;
    if (amount == null) continue;
    const answer = answers.get(entry.txn.ref);
    if (answer === WorthIt.YES) worth += amount;
    else if (answer === WorthIt.NO) notWorth += amount;
    else if (answer === WorthIt.NEEDED) needed += amount;
  }
  return { worth, notWorth, needed, total: worth + notWorth + needed, regretted: notWorth };
}

// ─────────────────────── the goal card's words (BudgetUi.kt) ───────────────────────

/** «از ۱ مرداد ۱۴۰۵», and the deadline where there is one. */
export function goalWindowFa(progress: GoalProgress): string {
  const until = progress.goal.endsOn != null ? ` تا ${faDate(progress.goal.endsOn)}` : '';
  return `از ${faDate(progress.goal.startsOn)}${until}`;
}

/**
 * The one line a goal card is allowed, and whether it is said out loud. Ordered by what is true
 * rather than what is encouraging; «you failed» appears nowhere.
 */
export function goalNoteFa(progress: GoalProgress): [text: string, loud: boolean] | null {
  if (progress.done) return ['به هدفت رسیدی.', true];
  if (progress.expired) return [`مهلتش تموم شد و ${faCompact(tomanOf(progress.remainingRial))} تومان مونده بود.`, false];
  if (progress.underWater) return ['از وقتی این هدف رو گذاشتی، هنوز چیزی پس‌انداز نشده.', false];
  if (progress.perMonthRial != null) {
    return [`برای رسیدن به هدف، ماهی ${faCompact(tomanOf(progress.perMonthRial))} تومان لازمه.`, false];
  }
  if (progress.daysLeft != null && progress.daysLeft > 0) {
    return [`${faNumber(progress.daysLeft)} روز مونده و ` +
      `${faCompact(tomanOf(progress.remainingRial))} تومان باقیه.`, false];
  }
  return null;
}
