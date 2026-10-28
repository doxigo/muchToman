/**
 * Installments.kt, one to one, plus the plan card's two text functions from BudgetUi.kt — «قسط».
 *
 * A plan is a `goal` row with `kind = installment`: one payment in `targetRial`, the first due day
 * in `startsOn`, the last in `endsOn`, a Jalali month apart. A payment is a transaction the ledger
 * already holds plus an `installment` decision saying which plan it paid — never a figure typed
 * twice. Always private: `shared` is false, so the sync never publishes these.
 *
 * A bill (rent, the internet, the phone) is the same kind with `endsOn` null: due on `startsOn`'s
 * day every month until she deletes it, with no count and no total. Installments.kt says why it is
 * not a kind of its own, and what an older build makes of one.
 */
import { faCompact, faDate, faNumber, faOrdinal, parseAmount, showRial, today as tehranToday, tomanOf, tomanOfShown, moneyUnit } from './format';
import { GoalKind, GoalPeriod } from './goals';
import {
  DAY_MS, TEHRAN_OFFSET_MS, jalaliDay, jalaliMonthLength, jalaliMonthsAfter, jalaliMonthsAheadEnd, jalaliOf, tehranDay,
} from './jalali';
import type { Decision, Goal, LedgerEntry } from './model';
import type { Insight } from './reports';
import { DecisionKind } from './rules';
import { MAX_PLAUSIBLE_RIAL } from './sms';

/**
 * A Rial figure as an amount field is pre-filled with (EditsUi.kt): in her unit (showRial); in Toman,
 * a tenth only when there is one. Whole Toman alone dropped the last Rial, so a re-saved installment
 * never matched its payment again.
 */
export const rialToField = (rial: number): string =>
  showRial() ? String(rial) : rial % 10 === 0 ? String(rial / 10) : `${Math.trunc(rial / 10)}.${rial % 10}`;

/**
 * What she typed into a Toman field, as whole Rial — or null when it is not a figure this app will
 * store (BudgetUi.kt). The ceiling is the parser's own: a cap or target past it is a typo. Checked
 * against zero only once it is whole Rial: «0.04» passed as positive and rounded to nothing.
 */
export function tomanFieldToRial(text: string): number | null {
  const typed = parseAmount(text);
  const toman = typed == null ? null : tomanOfShown(typed); // the field is in her unit, Rial when she reads in Rial
  const rial = toman != null && toman <= MAX_PLAUSIBLE_RIAL / 10 ? Math.round(toman * 10) : 0;
  return rial > 0 ? rial : null;
}

/** Ten years of monthly payments. Past that it is a mortgage, and a typo is likelier. */
export const MAX_INSTALLMENTS = 120;

/** How many possible payments the sheet offers at once. */
const INSTALLMENT_CANDIDATES = 20;

/**
 * The count that makes [newInstallment] a bill: «how many are left?» answered «it never ends» — no
 * field can type it, so a «۰» is still refused. ponytail: a value of the same number, as Installments.kt keeps it, so the two stay one to one.
 */
export const NO_END = -1;

/** A plan with no last payment — rent, the internet, the phone. */
export const isBill = (plan: Goal): boolean => plan.kind === GoalKind.INSTALLMENT && plan.endsOn == null;

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
 * [dayOfMonth] today included, and [NO_END] makes it a bill; [firstDue] instead starts it on a
 * payment she just filed, which [count] then includes.
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
  if (count !== NO_END && (!Number.isInteger(count) || count < 1 || count > MAX_INSTALLMENTS)) return null;
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
    endsOn: count === NO_END ? null : jalaliMonthsAfter(first, count - 1),
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

/** How many payments the plan has — the months from its first due to its last, both counted. Null for a bill. */
export function installmentCount(plan: Goal): number | null {
  if (isBill(plan)) return null;
  const first = jalaliOf(plan.startsOn);
  const last = jalaliOf(plan.endsOn ?? plan.startsOn);
  const months = (last.year - first.year) * 12 + (last.month - first.month) + 1;
  return Math.min(Math.max(months, 1), MAX_INSTALLMENTS);
}

/** The day payment [index] (from 0) falls due. */
export function installmentDueOn(plan: Goal, index: number): number {
  return jalaliMonthsAfter(plan.startsOn, index);
}

/** Which payment (from 0) falls in [today]'s Jalali month; negative before the first one's month. */
export function installmentIndexIn(plan: Goal, today: number): number {
  const first = jalaliOf(plan.startsOn);
  const here = jalaliOf(today);
  return (here.year - first.year) * 12 + (here.month - first.month);
}

/** Where one plan stands, worked out from her links and nothing else. */
export interface InstallmentProgress {
  plan: Goal;
  /** Null for a bill, which has no last payment — and so no [totalRial] either. */
  count: number | null;
  totalRial: number | null;
  /** What her links add up to, never past [totalRial]. */
  paidRial: number;
  /** Payments covered in full — the «۳» of «۳ از ۱۲». */
  paidCount: number;
  /** What fell due before today and is unpaid; zero on a due day itself. */
  overdueRial: number;
  /** When the first payment not yet covered falls due. Null once paid off. */
  nextDue: number | null;
  /** Never, for a bill. */
  done: boolean;
  /** 0..1; 0 for a bill, which has no whole to be a share of. */
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
  const total = count == null ? null : plan.targetRial * count;
  const mine = new Map([...links].filter(([, link]) => link.planId === plan.id));
  // Clamped as it goes, so a link too large reads as «paid off» and never wraps. A bill has no total.
  let paid = 0;
  for (const link of mine.values()) paid = Math.min(total ?? Infinity, paid + link.rial);
  const done = total != null && paid >= total;
  // Every due day before today: up to the last for a plan, every month so far for a bill.
  let lateCount = 0;
  while ((count == null || lateCount < count) && installmentDueOn(plan, lateCount) < today) lateCount++;
  // A bill is never counted paid more than ten years ahead of the calendar: past that, a typo.
  const paidCount = plan.targetRial > 0 ? Math.min(Math.trunc(paid / plan.targetRial), lateCount + MAX_INSTALLMENTS) : 0;
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
    share: total != null && total > 0 ? Math.min(Math.max(paid / total, 0), 1) : 0,
    payments,
    olderRial: Math.min(total ?? Infinity, older),
    candidates: done ? [] : entries
      .filter((e) => installmentPayable(e, mineId) && !links.has(e.txn.ref) && e.txn.day > after)
      .sort((a, b) => Number(a.txn.amountRial !== plan.targetRial) - Number(b.txn.amountRial !== plan.targetRial) ||
        b.txn.day - a.txn.day)
      .slice(0, INSTALLMENT_CANDIDATES),
  };
}

/**
 * Soonest payment first: a plan behind has its next due in the past, so it leads without a rule of
 * its own, and a plan paid off has none and goes last. Ties keep the order they were made in.
 */
export function installmentsByDue(installments: InstallmentProgress[]): InstallmentProgress[] {
  const key = (p: InstallmentProgress) => p.nextDue ?? Number.MAX_SAFE_INTEGER;
  return [...installments].sort((a, b) => key(a) - key(b));
}

/** «۲۱ از ۲۴ قسط مونده، تا ۷ شهریور ۱۴۰۷» — what is left and when it ends. Null once paid off, and for a bill. */
export function installmentLeftFa(progress: InstallmentProgress): string | null {
  const { count } = progress;
  if (progress.done || count == null) return null;
  return `${faNumber(count - progress.paidCount)} از ${faNumber(count)} قسط مونده، ` +
    `تا ${faDate(progress.plan.endsOn ?? progress.plan.startsOn)}`;
}

/** The section's installments in one place: what leaves each month, what is left, and this month's. Bills: [billsLeftThisMonth]. */
export interface InstallmentSummary {
  /** One payment of every plan still running. */
  monthlyRial: number;
  /** What the running plans have left between them. */
  leftRial: number;
  /** The last payment of the last plan to finish. */
  lastDue: number;
  /** Plans with a payment that falls due this Jalali month, paid off or not. */
  dueThisMonth: number;
  /** How many of those that month's payment is covered on. */
  paidThisMonth: number;
}

/** Null under two running plans: with one, every figure here is already on its card. Bills are left out. */
export function installmentSummary(installments: InstallmentProgress[], today: number): InstallmentSummary | null {
  const plans = installments.filter((p) => !isBill(p.plan));
  const running = plans.filter((p) => !p.done);
  if (running.length < 2) return null;
  let due = 0;
  let paid = 0;
  for (const p of plans) {
    const index = installmentIndexIn(p.plan, today);
    if (p.count == null || index < 0 || index >= p.count) continue;
    due++;
    if (p.paidCount > index) paid++;
  }
  return {
    monthlyRial: running.reduce((sum, p) => sum + p.plan.targetRial, 0),
    leftRial: running.reduce((sum, p) => sum + (p.totalRial ?? p.paidRial) - p.paidRial, 0),
    lastDue: Math.max(...running.map((p) => p.plan.endsOn ?? p.plan.startsOn)),
    dueThisMonth: due,
    paidThisMonth: paid,
  };
}

/**
 * What her bills still want this month, between them: each one's payment for this month less what
 * her links already cover of it. Null under two bills (one bill's card already says it) and when
 * nothing is left. Months gone by unpaid are not in it; a bill behind says those on its card.
 */
export function billsLeftThisMonth(installments: InstallmentProgress[], today: number): number | null {
  const bills = installments.filter((p) => isBill(p.plan));
  if (bills.length < 2) return null;
  let left = 0;
  for (const p of bills) {
    const index = installmentIndexIn(p.plan, today);
    if (index >= 0) left += Math.min(Math.max(p.plan.targetRial * (index + 1) - p.paidRial, 0), p.plan.targetRial);
  }
  return left > 0 ? left : null;
}

/** «این ماه هنوز ۱۲ میلیون تومان قبض مونده.» — [billsLeftThisMonth], said. */
export function billsLeftFa(rial: number): string {
  return `این ماه هنوز ${faCompact(tomanOf(rial))} ${moneyUnit()} قبض مونده.`;
}

// ─────────────────────────── on home ───────────────────────────

/**
 * The plan worth home's one attention card, and which payment (from 0) it is about: one already
 * behind, the longest behind first; or else the soonest payment within [daysBefore] of today — the
 * reminder's own window, and never less than the day itself.
 */
export function pressingInstallment(
  installments: InstallmentProgress[],
  today: number,
  daysBefore: number,
): [progress: InstallmentProgress, index: number] | null {
  const late = installments.filter((p) => p.overdueRial > 0)
    .sort((a, b) => (a.nextDue ?? Number.MAX_SAFE_INTEGER) - (b.nextDue ?? Number.MAX_SAFE_INTEGER))[0];
  if (late) return [late, late.paidCount];
  const window = Math.max(daysBefore, 0);
  let best: [InstallmentProgress, number] | null = null;
  for (const p of installments) {
    const i = installmentUpcomingIndex(p, today);
    if (i == null || installmentDueOn(p.plan, i) - today > window) continue;
    if (!best || installmentDueOn(p.plan, i) < installmentDueOn(best[0].plan, best[1])) best = [p, i];
  }
  return best;
}

/** «سررسید قسط «گوشی» فرداست.» — the reminder's words, on home, with the linked payments as evidence. */
export function installmentInsight(progress: InstallmentProgress, index: number, today: number): Insight {
  const due = installmentDueOn(progress.plan, index);
  const monthly = `ماهی ${faCompact(tomanOf(progress.plan.targetRial))} ${moneyUnit()}`;
  const { count } = progress;
  return {
    // A bill is named on its own, «سررسید «اجاره» فرداست.», and never as a قسط.
    text: count == null ? `سررسید «${progress.plan.nameFa}» ${installmentWhenFa(due - today)}.`
      : `سررسید قسط «${progress.plan.nameFa}» ${installmentWhenFa(due - today)}.`,
    why: count == null ? `قبضی که خودت ساختی: ${monthly}، ${faDate(due)}.`
      : `قسطی که خودت ساختی: ${monthly}، قسط ${faOrdinal(index + 1)} از ${faNumber(count)}، ${faDate(due)}.`,
    refs: progress.payments.map((e) => e.txn.ref),
    tone: 'ATTENTION',
  };
}

// ─────────────────────────── the reminder ───────────────────────────

/** How many days before a due date the reminder comes — `-1` for never. On by default, a day ahead. */
export const INSTALLMENT_REMINDER_DAYS = [-1, 0, 1, 3];
export const INSTALLMENT_REMINDER_DEFAULT = 1;

/** The Tehran hours a reminder may be shown in: never at whatever hour a sweep lands past midnight. */
const REMIND_FROM = 9;
const REMIND_TO = 21;

/** Whether [now] falls inside those hours in Tehran — shared with the cheque reminder. */
export function remindAwake(now: number): boolean {
  const hour = Math.trunc(((((now + TEHRAN_OFFSET_MS) % DAY_MS) + DAY_MS) % DAY_MS) / 3_600_000);
  return hour >= REMIND_FROM && hour <= REMIND_TO;
}

/**
 * The next payment she has not covered that falls due today or later. Not [nextDue]: a plan she
 * pays but never links would be reminded once, of a date already gone, and then never again.
 */
export function installmentUpcoming(progress: InstallmentProgress, today: number): number | null {
  const i = installmentUpcomingIndex(progress, today);
  return i == null ? null : installmentDueOn(progress.plan, i);
}

/** Which payment (from 0) [installmentUpcoming] is: a bill always has one, a plan until its last. */
export function installmentUpcomingIndex(progress: InstallmentProgress, today: number): number | null {
  const last = progress.count ?? Infinity;
  let i = progress.paidCount;
  // Ends for a bill too: the due days only move forward, a month a step.
  while (i < last && installmentDueOn(progress.plan, i) < today) i++;
  return i < last ? i : null;
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
  const awake = remindAwake(now);
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

/** When a due day is, from today: «گذشته», «امروزه», «فرداست», «۳ روز دیگه‌ست». */
export function installmentWhenFa(days: number): string {
  if (days < 0) return 'گذشته';
  if (days === 0) return 'امروزه';
  if (days === 1) return 'فرداست';
  if (days === 2) return 'پس‌فرداست';
  return `${faNumber(days)} روز دیگه‌ست`;
}

/** «گوشی: سررسید قسط فرداست»; a bill, «اجاره: سررسیدش فرداست». */
export function installmentReminderTitle(plan: Goal, due: number, today: number): string {
  return `${plan.nameFa}: ${isBill(plan) ? 'سررسیدش' : 'سررسید قسط'} ${installmentWhenFa(due - today)}`;
}

/** How much, and on which date — what she needs in the banking app. */
export function installmentReminderBody(plan: Goal, due: number): string {
  return `${faCompact(tomanOf(plan.targetRial))} ${moneyUnit()} • ${faDate(due)}`;
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
 * once a due day has passed: on the day itself she can still pay it. A bill says this month's,
 * paid or not, and the date that matters next — there is no «قسط سوم» of a rent.
 */
export function installmentNoteFa(progress: InstallmentProgress, today: number = tehranToday()): [text: string, loud: boolean] | null {
  if (isBill(progress.plan)) {
    const index = installmentIndexIn(progress.plan, today);
    const next = progress.nextDue ?? progress.plan.startsOn;
    if (progress.overdueRial > 0) {
      return [`${faCompact(tomanOf(progress.overdueRial))} ${moneyUnit()} از ماه‌هایی که سررسیدشون گذشته، ` +
        'هنوز پرداخت نشده.', true];
    }
    if (index < 0) return [`اولین سررسید: ${faDate(progress.plan.startsOn)}`, false];
    if (progress.paidCount > index) return [`این ماه پرداخت شده، بعدی ${faDate(next)}.`, false];
    if (next === today) return ['سررسید این ماه امروزه.', false];
    return [`این ماه هنوز پرداخت نشده، سررسید ${faDate(next)}.`, false];
  }
  if (progress.done) return ['همه‌ی قسط‌ها پرداخت شد.', true];
  if (progress.overdueRial > 0) {
    return [`${faCompact(tomanOf(progress.overdueRial))} ${moneyUnit()} از قسط‌هایی که سررسیدشون گذشته، ` +
      'هنوز پرداخت نشده.', true];
  }
  const next = faOrdinal(progress.paidCount + 1);
  if (progress.nextDue === today) return [`سررسید قسط ${next} امروزه.`, false];
  if (progress.nextDue != null) return [`قسط ${next}: ${faDate(progress.nextDue)}`, false];
  return null;
}

/** «ماهی ۴٫۵ میلیون تومان • ۳ از ۲۴ قسط» — a plan in one line; a bill, its day: «… • پنجم هر ماه». */
export function installmentLineFa(plan: InstallmentProgress): string {
  return `ماهی ${faCompact(tomanOf(plan.plan.targetRial))} ${moneyUnit()} • ` +
    (plan.count == null ? `${faOrdinal(jalaliOf(plan.plan.startsOn).day)} هر ماه`
      : `${faNumber(plan.paidCount)} از ${faNumber(plan.count)} قسط`);
}

/** The lock screen's word for one plan's reminder (Notify.kt installmentPublicTitle). */
export function installmentPublicTitle(plan: Goal): string {
  return isBill(plan) ? 'یادآوری قبض' : 'یادآوری قسط';
}
