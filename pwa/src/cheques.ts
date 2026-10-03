/**
 * Cheques.kt, one to one — «چک صیادی», cheques she has written.
 *
 * A cheque is a `goal` row with `kind = cheque`: the amount in `targetRial`, the date on it in
 * `startsOn`, the bank it draws on (a Bank name, the key the bank accounts sheet folds its balance
 * under) in `categoryId`, who or what it is for in `nameFa` (blank if she left it), and in `endsOn`
 * the day she said «پاس شد» — null while it is still out there. No new store: the backup carries the
 * goals table whole, so it carries cheques. Always private: `shared` is false, never synced.
 *
 * When the account cannot cover a cheque as its date nears, the card says so in words, with the
 * figure missing. ponytail: outgoing only, settled by her tap.
 */
import type { BankAccountView } from './derived';
import { faCompact, faDate, faNumber, tomanOf } from './format';
import { GoalKind, GoalPeriod } from './goals';
import { installmentWhenFa, remindAwake } from './installments';
import { jalaliDay, jalaliMonthLength, jalaliOf, tehranDay } from './jalali';
import type { Goal } from './model';
import type { Insight } from './reports';
import { MAX_PLAUSIBLE_RIAL, bankFa } from './sms';

/** What the warning reads off an account — the bank accounts sheet's own rows. */
export type ChequeAccount = Pick<BankAccountView, 'bank' | 'balanceRial' | 'anchored'>;

/** A cheque she has just written, or null when the answers are not one. */
export function newCheque(id: string, nameFa: string, amountRial: number, due: number, bank: string, now: number, mineId = ''): Goal | null {
  if (amountRial <= 0 || amountRial > MAX_PLAUSIBLE_RIAL || bank.trim() === '') return null;
  return {
    id, nameFa: nameFa.trim().slice(0, 40), targetRial: amountRial, kind: GoalKind.CHEQUE, categoryId: bank,
    period: GoalPeriod.ONCE, startsOn: due, endsOn: null, createdAt: now, updatedAt: now, deleted: false, shared: false,
    ownerMemberId: mineId, editedByMemberId: mineId,
  };
}

const bySooner = (a: Goal, b: Goal): number => a.startsOn - b.startsOn || a.createdAt - b.createdAt;

/** The cheques still out there — not passed, not deleted — soonest first, ties in the order written. */
export function openCheques(goals: readonly Goal[]): Goal[] {
  return goals.filter((g) => g.kind === GoalKind.CHEQUE && !g.deleted && g.endsOn == null).sort(bySooner);
}

/** The card's title: who it is for, or the bank when she left that blank. */
export const chequeTitleFa = (cheque: Goal): string => cheque.nameFa.trim() || `چک ${bankFa(cheque.categoryId ?? '')}`;

/** The cheque inside a sentence: «چک «اجاره»», or «چک بانک ملت» with no name to quote. */
const chequeFa = (cheque: Goal): string => (cheque.nameFa.trim() ? `چک «${cheque.nameFa}»` : chequeTitleFa(cheque));

/** «۳ روز مونده», «امروز», «۲ روز گذشته» — the card's corner. */
export function chequeWhenFa(due: number, today: number): string {
  if (due === today) return 'امروز';
  return due > today ? `${faNumber(due - today)} روز مونده` : `${faNumber(today - due)} روز گذشته`;
}

/**
 * The card's warning, or null while there is nothing to warn of: the date is further off than
 * [daysBefore] (never less than the day itself), or the account holds every open cheque on it up to
 * and including this one. Unknown — no account, or only a running sum — is said as unknown. What is
 * missing is cut at a thousand Toman: a shortfall read low is a cheque that still bounces.
 */
export function chequeWarningFa(cheque: Goal, open: readonly Goal[], accounts: readonly ChequeAccount[], today: number, daysBefore: number): string | null {
  if (cheque.startsOn - today > Math.max(daysBefore, 0)) return null;
  const bank = bankFa(cheque.categoryId ?? '');
  const need = cheque.targetRial + open
    .filter((c) => c.id !== cheque.id && c.categoryId === cheque.categoryId && bySooner(c, cheque) < 0)
    .reduce((sum, c) => sum + c.targetRial, 0);
  const account = accounts.find((a) => a.bank === cheque.categoryId && a.anchored);
  if (!account) return `موجودی حساب ${bank} رو نمی‌دونیم؛ مطمئن شو ${faCompact(tomanOf(need), 3)} تومان توش هست.`;
  if (account.balanceRial >= need) return null;
  const earlier = need > cheque.targetRial ? 'با چک‌های زودتر همین حساب، ' : '';
  return `تو حساب ${bank} ${faCompact(tomanOf(account.balanceRial))} تومان هست؛ ` +
    `${earlier}${faCompact(tomanOf(need - account.balanceRial), 3)} کمه.`;
}

// ─────────────────────────── on home ───────────────────────────

/** The cheque worth home's attention card: inside the window, one the account cannot cover first. */
export function pressingCheque(open: readonly Goal[], accounts: readonly ChequeAccount[], today: number, daysBefore: number): Goal | null {
  const near = open.filter((c) => c.startsOn - today <= Math.max(daysBefore, 0));
  return near.find((c) => chequeWarningFa(c, open, accounts, today, daysBefore) != null) ?? near[0] ?? null;
}

/** «سررسید چک «اجاره» فرداست.» and, when it applies, the warning after it. */
export function chequeInsight(cheque: Goal, open: readonly Goal[], accounts: readonly ChequeAccount[], today: number, daysBefore: number): Insight {
  const warning = chequeWarningFa(cheque, open, accounts, today, daysBefore);
  return {
    text: `${chequeReminderTitle(cheque, today)}.${warning ? ` ${warning}` : ''}`,
    why: `چکی که خودت ثبت کردی: ${chequeLineFa(cheque)}، ${faDate(cheque.startsOn)}.`,
    refs: [],
    tone: 'ATTENTION',
  };
}

// ─────────────────────────── the reminder ───────────────────────────

/**
 * Which cheques are worth a reminder now, and what to remember having said — installmentNews's
 * arrangement, once per date and only a date still to come. Ids are uuid7s, so the marks share
 * `installmentMarks` with the plans' without meeting.
 */
export function chequeNews(open: readonly Goal[], daysBefore: number, said: Readonly<Record<string, number>>, now: number):
  { due: Goal[]; marks: Record<string, number> } {
  const today = tehranDay(now);
  const awake = remindAwake(now);
  const due: Goal[] = [];
  const marks: Record<string, number> = {};
  for (const cheque of open) {
    const day = cheque.startsOn;
    if (daysBefore >= 0 && awake && day >= today && day - today <= daysBefore && said[cheque.id] !== day) {
      due.push(cheque);
      marks[cheque.id] = day;
    } else if (said[cheque.id] != null) {
      marks[cheque.id] = said[cheque.id];
    }
  }
  return { due, marks };
}

/** «سررسید چک «اجاره» فرداست» — the note's title, and home's sentence. */
export const chequeReminderTitle = (cheque: Goal, today: number): string =>
  `سررسید ${chequeFa(cheque)} ${installmentWhenFa(cheque.startsOn - today)}`;

/** «۵۰ میلیون تومان از بانک ملت» — the cheque in one line. */
export const chequeLineFa = (cheque: Goal): string =>
  `${faCompact(tomanOf(cheque.targetRial), 3)} تومان از ${bankFa(cheque.categoryId ?? '')}`;

/** The warning when there is one; otherwise how much, from where, and when. */
export const chequeReminderBody = (cheque: Goal, open: readonly Goal[], accounts: readonly ChequeAccount[], today: number, daysBefore: number): string =>
  chequeWarningFa(cheque, open, accounts, today, daysBefore) ?? `${chequeLineFa(cheque)} • ${faDate(cheque.startsOn)}`;

// ─────────────────────────── the date she types ───────────────────────────

/** ASCII digits from either digit set, nothing else — manualTxn.tsx clockDigits, kept pure here. */
const digitsOf = (text: string): string => text.replace(/[۰-۹]/g, (c) => String(c.charCodeAt(0) - 0x6f0))
  .replace(/[٠-٩]/g, (c) => String(c.charCodeAt(0) - 0x660)).replace(/\D/g, '');

/**
 * The date as printed on the cheque, typed as its eight digits («۱۴۰۵۰۹۱۵», drawn «۱۴۰۵/۰۹/۱۵»), as
 * a Tehran day — or null. A day past its month is refused, never clamped: «۳۱ مهر» is a typo.
 */
export function parseChequeDate(text: string): number | null {
  const digits = digitsOf(text);
  if (digits.length !== 8) return null;
  const year = Number(digits.slice(0, 4)); const month = Number(digits.slice(4, 6)); const day = Number(digits.slice(6));
  if (year < 1300 || year > 1499 || month < 1 || month > 12 || day < 1 || day > jalaliMonthLength(year, month)) return null;
  return jalaliDay(year, month, day);
}

/** A stored date back in the field, as she would have typed it: «14050915». */
export function chequeDateDigits(day: number): string {
  const j = jalaliOf(day);
  return `${j.year}${String(j.month).padStart(2, '0')}${String(j.day).padStart(2, '0')}`;
}
