/**
 * Installments.kt, one to one, plus the plan card's two text functions from BudgetUi.kt — «قسط».
 *
 * A plan is a `goal` row with `kind = installment`: one payment in `targetRial`, the first due day
 * in `startsOn`, the last in `endsOn`, a Jalali month apart. A payment is a transaction the ledger
 * already holds plus an `installment` decision saying which plan it paid — never a figure typed
 * twice. Always private: `shared` is false, so the sync never publishes these.
 */
import { faCompact, faDate, faNumber, parseAmount, today as tehranToday, tomanOf } from './format';
import { GoalKind, GoalPeriod } from './goals';
import {
  DAY_MS, TEHRAN_OFFSET_MS, jalaliDay, jalaliMonthLength, jalaliMonthsAfter, jalaliMonthsAheadEnd, jalaliOf, tehranDay,
} from './jalali';
import type { Decision, Goal, LedgerEntry } from './model';
import { DecisionKind } from './rules';
import { MAX_PLAUSIBLE_RIAL } from './sms';

/**
 * What she typed into a Toman field, as whole Rial — or null when it is not a figure this app will
 * store (BudgetUi.kt). The ceiling is the parser's own: a cap or target past it is a typo.
 */
export function tomanFieldToRial(text: string): number | null {
  const toman = parseAmount(text);
  return toman != null && toman > 0 && toman <= MAX_PLAUSIBLE_RIAL / 10 ? Math.round(toman * 10) : null;
}

/** Ten years of monthly payments. Past that it is a mortgage, and a typo is likelier. */
export const MAX_INSTALLMENTS = 120;

/** How many possible payments the sheet offers at once. */
const INSTALLMENT_CANDIDATES = 20;

/**
 * What an `installment` decision's value holds: the plan it paid, and how much. The amount is
 * copied onto the link because the ledger forgets pruned sources, and a loan counted off live rows
 * would read as overdue for payments the app watched her make.
 */
export interface InstallmentLink { planId: string; rial: number }

/** `<planId>:<rial>`. A plan id is a uuid7, which has no colon in it. */
export function encodeInstallmentLink(link: InstallmentLink): string {
  return `${link.planId}:${link.rial}`;
}

/** Null for anything this build did not write, so one bad row cannot poison a total. */
export function decodeInstallmentLink(value: string | null | undefined): InstallmentLink | null {
  if (value == null) return null;
  const at = value.lastIndexOf(':');
  const planId = at < 0 ? '' : value.slice(0, at);
  const digits = value.slice(at + 1);
  // Kotlin's toLongOrNull: an optional sign and decimal digits, nothing else.
  const rial = /^[+-]?\d+$/.test(digits) ? Number(digits) : null;
  if (!planId || rial == null || rial <= 0 || rial > MAX_PLAUSIBLE_RIAL) return null;
  return { planId, rial };
}

/** Every live link, by the transaction it is on — only to plans that still exist. */
export function installmentLinks(decisions: Decision[], plans: Goal[]): Map<string, InstallmentLink> {
  const live = new Set(plans.filter((p) => p.kind === GoalKind.INSTALLMENT && !p.deleted).map((p) => p.id));
  const out = new Map<string, InstallmentLink>();
  for (const d of decisions) {
    if (d.kind !== DecisionKind.INSTALLMENT || d.deleted) continue;
    const link = decodeInstallmentLink(d.value);
    if (link && live.has(link.planId)) out.set(d.ref, link);
  }
  return out;
}

/**
 * A new plan, or null when the answers are not one. [count] is what is left to pay, from the next
 * [dayOfMonth] today included; [firstDue] instead starts it on a payment she just filed, which
 * [count] then includes.
 */
export function newInstallment(
  id: string,
  nameFa: string,
  paymentRial: number,
  count: number,
  dayOfMonth: number,
  now: number,
  mineId = '',
  firstDue: number | null = null,
): Goal | null {
  const name = nameFa.trim().slice(0, 40);
  if (!name || !(paymentRial > 0) || paymentRial > MAX_PLAUSIBLE_RIAL) return null;
  if (!Number.isInteger(count) || count < 1 || count > MAX_INSTALLMENTS) return null;
  if (!Number.isInteger(dayOfMonth) || dayOfMonth < 1 || dayOfMonth > 31) return null;
  const first = firstDue ?? firstInstallmentDue(dayOfMonth, tehranDay(now));
  return {
    id,
    nameFa: name,
    targetRial: paymentRial,
    kind: GoalKind.INSTALLMENT,
    categoryId: null,
    period: GoalPeriod.MONTH,
    startsOn: first,
    endsOn: jalaliMonthsAfter(first, count - 1),
    createdAt: now,
    updatedAt: now,
    deleted: false,
    shared: false,
    ownerMemberId: mineId,
    editedByMemberId: mineId,
  };
}

/**
 * The next [dayOfMonth] on or after [today], clamped to the month's length. ponytail: the day is
 * kept only by the first due date, so a «۳۱ام» first due in a 30-day month is the 30th from then
 * on — early, never late. Keeping the typed day needs a column.
 */
export function firstInstallmentDue(dayOfMonth: number, today: number): number {
  const t = jalaliOf(today);
  const thisMonth = jalaliDay(t.year, t.month, Math.min(dayOfMonth, jalaliMonthLength(t.year, t.month)));
  if (thisMonth >= today) return thisMonth;
  const next = jalaliOf(jalaliMonthsAheadEnd(today, 1));
  return jalaliDay(next.year, next.month, Math.min(dayOfMonth, next.day));
}

/**
 * Whether this row could pay one of her plans: money out, of a known amount, hers, and neither a
 * duplicate nor a transfer. One test for both ends of the link.
 */
export function installmentPayable(entry: LedgerEntry, mineId: string): boolean {
  return entry.txn.direction === 'out' && entry.txn.amountRial != null && !entry.duplicate &&
    !entry.transfer && entry.ownerMemberId === mineId;
}

/** The plan this transaction paid, if she linked it to one the ledger still shows. */
export function installmentPaidBy(ref: string, installments: InstallmentProgress[]): InstallmentProgress | null {
  return installments.find((plan) => plan.payments.some((p) => p.txn.ref === ref)) ?? null;
}

/** How many payments the plan has — the months from its first due to its last, both counted. */
export function installmentCount(plan: Goal): number {
  const first = jalaliOf(plan.startsOn);
  const last = jalaliOf(plan.endsOn ?? plan.startsOn);
  const months = (last.year - first.year) * 12 + (last.month - first.month) + 1;
  return Math.min(Math.max(months, 1), MAX_INSTALLMENTS);
}

/** The day payment [index] (from 0) falls due. */
export function installmentDueOn(plan: Goal, index: number): number {
  return jalaliMonthsAfter(plan.startsOn, index);
}

/** Where one plan stands, worked out from her links and nothing else. */
export interface InstallmentProgress {
  plan: Goal;
  count: number;
  totalRial: number;
  /** What her links add up to, never past [totalRial]. */
  paidRial: number;
  /** Payments covered in full — the «۳» of «۳ از ۱۲». */
  paidCount: number;
  /** What fell due before today and is unpaid; zero on a due day itself. */
  overdueRial: number;
  /** When the first payment not yet covered falls due. Null once paid off. */
  nextDue: number | null;
  done: boolean;
  /** 0..1. */
  share: number;
  /** Her linked transactions the ledger still holds, newest first. */
  payments: LedgerEntry[];
  /** What links to forgotten transactions still count for. */
  olderRial: number;
  /** Her outgoing rows that could pay this plan, its exact amount first then newest. */
  candidates: LedgerEntry[];
}

export function installmentProgress(
  plan: Goal,
  links: ReadonlyMap<string, InstallmentLink>,
  entries: LedgerEntry[],
  today: number,
  /** This device's member id — a payment she can link is one of her own rows. */
  mineId = '',
): InstallmentProgress {
  const count = installmentCount(plan);
  // ponytail: a Number, exact to 2^53 — past it only for a payment near the plausibility cap × 120.
  const total = plan.targetRial * count;
  const mine = new Map([...links].filter(([, link]) => link.planId === plan.id));
  // Clamped as it goes, so a link too large reads as «paid off» and never wraps.
  let paid = 0;
  for (const link of mine.values()) paid = Math.min(total, paid + link.rial);
  const done = paid >= total;
  const paidCount = plan.targetRial > 0 ? Math.trunc(paid / plan.targetRial) : 0;
  let lateCount = 0;
  for (let i = 0; i < count; i++) if (installmentDueOn(plan, i) < today) lateCount++;
  const payments = entries.filter((e) => mine.has(e.txn.ref)).sort((a, b) => b.txn.day - a.txn.day);
  const held = new Set(payments.map((e) => e.txn.ref));
  let older = 0;
  for (const [ref, link] of mine) if (!held.has(ref)) older += link.rial;
  // A payment for the first installment is one made after the one before it would have fallen due.
  const after = jalaliMonthsAfter(plan.startsOn, -1);
  return {
    plan,
    count,
    totalRial: total,
    paidRial: paid,
    paidCount,
    overdueRial: Math.max(plan.targetRial * lateCount - paid, 0),
    nextDue: done ? null : installmentDueOn(plan, paidCount),
    done,
    share: total > 0 ? Math.min(Math.max(paid / total, 0), 1) : 0,
    payments,
    olderRial: Math.min(total, older),
    candidates: done ? [] : entries
      .filter((e) => installmentPayable(e, mineId) && !links.has(e.txn.ref) && e.txn.day > after)
      .sort((a, b) => Number(a.txn.amountRial !== plan.targetRial) - Number(b.txn.amountRial !== plan.targetRial) ||
        b.txn.day - a.txn.day)
      .slice(0, INSTALLMENT_CANDIDATES),
  };
}

// ─────────────────────────── the reminder ───────────────────────────

/** How many days before a due date the reminder comes — `-1` for never. On by default, a day ahead. */
export const INSTALLMENT_REMINDER_DAYS = [-1, 0, 1, 3];
export const INSTALLMENT_REMINDER_DEFAULT = 1;

/** The Tehran hours a reminder may be shown in: never at whatever hour a sweep lands past midnight. */
const REMIND_FROM = 9;
const REMIND_TO = 21;

/**
 * The next payment she has not covered that falls due today or later. Not [nextDue]: a plan she
 * pays but never links would be reminded once, of a date already gone, and then never again.
 */
export function installmentUpcoming(progress: InstallmentProgress, today: number): number | null {
  for (let i = progress.paidCount; i < progress.count; i++) {
    const due = installmentDueOn(progress.plan, i);
    if (due >= today) return due;
  }
  return null;
}

/**
 * Which plans are worth a reminder now, and what to remember having said: plan id → the due day
 * last reminded of. The mark is the date, so next month is a new reminder with nothing to reset.
 */
export interface InstallmentNews { due: Array<[progress: InstallmentProgress, due: number]>; marks: Record<string, number> }

export function installmentNews(
  installments: InstallmentProgress[],
  daysBefore: number,
  said: Readonly<Record<string, number>>,
  now: number,
): InstallmentNews {
  const today = tehranDay(now);
  const hour = Math.trunc(((((now + TEHRAN_OFFSET_MS) % DAY_MS) + DAY_MS) % DAY_MS) / 3_600_000);
  const awake = hour >= REMIND_FROM && hour <= REMIND_TO;
  const due: Array<[InstallmentProgress, number]> = [];
  const marks: Record<string, number> = {};
  for (const progress of installments) {
    const id = progress.plan.id;
    const next = installmentUpcoming(progress, today);
    if (next != null && daysBefore >= 0 && awake && next - today <= daysBefore && said[id] !== next) {
      due.push([progress, next]);
      marks[id] = next;
    } else if (said[id] != null) {
      marks[id] = said[id];
    }
  }
  return { due, marks };
}

/** «گوشی: سررسید قسط فرداست». */
export function installmentReminderTitle(plan: Goal, due: number, today: number): string {
  const days = due - today;
  const whenFa = days === 0 ? 'امروزه' : days === 1 ? 'فرداست' : days === 2 ? 'پس‌فرداست' : `${faNumber(days)} روز دیگه‌ست`;
  return `${plan.nameFa}: سررسید قسط ${whenFa}`;
}

/** How much, and on which date — what she needs in the banking app. */
export function installmentReminderBody(plan: Goal, due: number): string {
  return `${faCompact(tomanOf(plan.targetRial))} تومان • ${faDate(due)}`;
}

/** The setting's answer, on the تنظیمات row and in its sheet. */
export function installmentReminderFa(days: number): string {
  if (days < 0) return 'خاموش';
  if (days === 0) return 'همون روز';
  return `${faNumber(days)} روز قبل`;
}

// ─────────────────────────── the plan card's words (BudgetUi.kt) ───────────────────────────

/**
 * The card's one line, and whether it is said out loud. Behind is a figure and a fact, and only
 * once a due day has passed: on the day itself she can still pay it.
 */
export function installmentNoteFa(progress: InstallmentProgress, today: number = tehranToday()): [text: string, loud: boolean] | null {
  if (progress.done) return ['همه‌ی قسط‌ها پرداخت شد.', true];
  if (progress.overdueRial > 0) {
    return [`${faCompact(tomanOf(progress.overdueRial))} تومان از قسط‌هایی که سررسیدشون گذشته، ` +
      'هنوز پرداخت نشده.', true];
  }
  if (progress.nextDue === today) return ['سررسید قسط بعدی امروزه.', false];
  if (progress.nextDue != null) return [`قسط بعدی: ${faDate(progress.nextDue)}`, false];
  return null;
}

/** «ماهی ۴٫۵ میلیون تومان • ۳ از ۲۴ قسط» — a plan in one line, wherever it is offered. */
export function installmentLineFa(plan: InstallmentProgress): string {
  return `ماهی ${faCompact(tomanOf(plan.plan.targetRial))} تومان • ` +
    `${faNumber(plan.paidCount)} از ${faNumber(plan.count)} قسط`;
}
