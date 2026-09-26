/**
 * The «آینده» tab's actions — MainActivity.kt's addBudget … answerWorthIt and setReportExcluded —
 * written through state.ts, plus [plansOf] (the goals/budgets/installments part of ledgerView)
 * and the browser notifier ([announce], Notify.kt's announceBudgets/announceInstallments).
 *
 * The phone republishes the ledger after every write; here every screen re-derives on the state
 * version, so a write is the whole of it. Family sync is the sync module's to trigger.
 */
import {
  BUDGET_TOTAL_FA, BudgetLevel, budgetAlertBody, budgetAlertTitle, budgetConflicts, budgetNews, budgetsOf, ownerNameOf,
} from './budget';
import type { BudgetMark, BudgetPeriod, BudgetProgress } from './budget';
import { mineId } from './derived';
import { GoalKind, GoalPeriod, goalProgress, worthItAnswers } from './goals';
import type { GoalHorizon, GoalProgress } from './goals';
import {
  encodeInstallmentLink, installmentLinks, installmentNews, installmentProgress, installmentReminderBody,
  installmentReminderTitle, newInstallment,
} from './installments';
import type { InstallmentProgress } from './installments';
import { jalaliMonthStart, jalaliOf, tehranDay } from './jalali';
import type { Category, Decision, Goal, LedgerEntry, Prefs } from './model';
import { DecisionKind, PASS_THROUGH_CATEGORIES } from './rules';
import { batch, pref, put, row, rows, setPref } from './state';
import { uuid7 } from './sync';

// ─────────────────────────── what the tab reads ───────────────────────────

export interface Plans {
  /** Savings goals, in the order she added them. */
  goals: GoalProgress[];
  /** Caps, the total first and then the closest to trouble. */
  budgets: BudgetProgress[];
  /** Installment plans, in the order she added them. */
  installments: InstallmentProgress[];
  /** ref → her «ارزش داشت؟» answer. */
  worthIt: Map<string, string>;
}

export interface PlansInput {
  /** Every goal row, tombstones included — they are dropped here. */
  goals: Goal[];
  decisions: Decision[];
  /** The whole ledger: what she saved and paid off is money that exists wherever she starts reading. */
  entries: LedgerEntry[];
  /** From `ledgerStartsOn` on — what budgets read. Defaults to [entries]. */
  kept?: LedgerEntry[];
  today: number;
  /** Live category names by id. */
  names?: ReadonlyMap<string, string>;
  mineId?: string;
  /** Member names by id (live members only). */
  members?: ReadonlyMap<string, string>;
  /** `reportExcluded` as a set. */
  excluded?: ReadonlySet<string>;
}

/** One read of the goal table, split by shape — ledgerView's goals, budgets and installments. */
export function plansOf({
  goals, decisions, entries, kept = entries, today, names = new Map(), mineId = '', members = new Map(),
  excluded = new Set(PASS_THROUGH_CATEGORIES.keys()),
}: PlansInput): Plans {
  const active = goals.filter((g) => !g.deleted).sort((a, b) => a.createdAt - b.createdAt);
  const links = installmentLinks(decisions, active);
  return {
    // SAVE by name, not «everything but a cap»: an installment is a row of this table too.
    goals: active.filter((g) => g.kind === GoalKind.SAVE)
      .map((g) => goalProgress(g, entries, today, { mineId, ownerName: ownerNameOf(g, mineId, members) })),
    budgets: budgetsOf(active, kept, today, { names, mineId, members, excluded }),
    installments: active.filter((g) => g.kind === GoalKind.INSTALLMENT)
      .map((g) => installmentProgress(g, links, entries, today, mineId)),
    worthIt: worthItAnswers(decisions),
  };
}

/** Everything [plansOf] reads off the store besides the goals and the ledger. */
function stored(now: number): Omit<PlansInput, 'goals' | 'entries' | 'kept'> {
  return {
    decisions: rows('decisions'),
    today: tehranDay(now),
    names: new Map(rows('categories').map((c) => [c.id, c.nameFa])),
    mineId: mineId(),
    members: new Map(rows('familyMembers').filter((m) => !m.deleted).map((m) => [m.id, m.name])),
    excluded: new Set(pref('reportExcluded')),
  };
}

/** [plansOf] over the store, for the «آینده» tab. */
export function readPlans(entries: LedgerEntry[], kept: LedgerEntry[] = entries, now = Date.now()): Plans {
  return plansOf({ ...stored(now), goals: rows('goals'), entries, kept });
}

const activeGoals = (): Goal[] => rows('goals').filter((g) => !g.deleted);
const liveGoal = (id: string): Goal | null => {
  const goal = row('goals', id);
  return goal && !goal.deleted ? goal : null;
};
/** Only ever shared on a device with somebody to share with. */
const sharedIf = (shared: boolean): boolean => shared && mineId().trim() !== '';

// ─────────────────────────── budgets ───────────────────────────

/**
 * A cap on one category — or, with [category] null, on everything («سقف کل خرج») — per week,
 * month or فصل. `startsOn` is the day she set it (the window is still the whole period; the card
 * says so), and `nameFa` snapshots the category's name so the row stays readable once archived.
 */
export function addBudget(category: Category | null, period: BudgetPeriod, capRial: number, shared: boolean): void {
  const now = Date.now();
  put('goals', {
    id: uuid7(now),
    nameFa: category ? category.nameFa.slice(0, 40) : BUDGET_TOTAL_FA,
    targetRial: capRial,
    kind: GoalKind.CAP,
    categoryId: category?.id ?? null,
    period: period.id,
    startsOn: tehranDay(now),
    endsOn: null,
    createdAt: now,
    updatedAt: now,
    deleted: false,
    shared: sharedIf(shared),
    ownerMemberId: mineId(),
    editedByMemberId: mineId(),
  });
}

/**
 * Changes the ceiling, rhythm or scope of a cap; the category stays. Saved untouched is a no-op.
 * What was said about the old cap is forgotten: 80% of a figure she chose five minutes ago is news.
 */
export function editBudget(id: string, period: BudgetPeriod, capRial: number, shared: boolean): void {
  const goal = liveGoal(id);
  if (!goal) return;
  const next = sharedIf(shared);
  if (goal.targetRial === capRial && goal.period === period.id && goal.shared === next) return;
  batch(() => {
    put('goals', { ...goal, targetRial: capRial, period: period.id, updatedAt: Date.now(), shared: next, editedByMemberId: mineId() });
    setBudgetMarks(budgetMarks().filter((m) => m.goalId !== id));
  });
  void closeNote(budgetTag(id));
}

/**
 * Keeps [id] and tombstones every budget it collides with (same category, period and scope) —
 * stamped past all of them so the choice wins on every device.
 */
export function keepBudget(id: string, now = Date.now()): void {
  const budget = liveGoal(id);
  if (!budget) return;
  const conflicts = budgetConflicts(activeGoals(), budget);
  if (!conflicts.length) return;
  const stamp = Math.max(now, Math.max(...[...conflicts, budget].map((g) => g.updatedAt)) + 1);
  batch(() => {
    put('goals', { ...budget, updatedAt: stamp, editedByMemberId: mineId() });
    for (const c of conflicts) put('goals', { ...c, deleted: true, updatedAt: stamp, editedByMemberId: mineId() });
  });
  for (const c of conflicts) void closeNote(budgetTag(c.id));
}

// ─────────────────────────── savings goals ───────────────────────────

/**
 * A savings goal. `startsOn` is the first of this Jalali month, deliberately: what she already
 * kept this month counts, and the card states the date it counts from.
 */
export function addGoal(name: string, targetRial: number, horizon: GoalHorizon, shared: boolean): void {
  const now = Date.now();
  const today = tehranDay(now);
  put('goals', {
    id: uuid7(now),
    nameFa: name.slice(0, 40),
    targetRial,
    kind: GoalKind.SAVE,
    categoryId: null,
    period: GoalPeriod.ONCE,
    startsOn: jalaliMonthStart(today),
    endsOn: horizon.endsOn(today),
    createdAt: now,
    updatedAt: now,
    deleted: false,
    shared: sharedIf(shared),
    ownerMemberId: mineId(),
    editedByMemberId: mineId(),
  });
}

/**
 * Renames, resizes or re-deadlines a goal without moving the day it counts from. [horizon] null
 * keeps the deadline; a picked one is measured from today, as on a new goal.
 */
export function editGoal(id: string, name: string, targetRial: number, horizon: GoalHorizon | null, shared: boolean): void {
  const goal = liveGoal(id);
  if (!goal) return;
  const now = Date.now();
  const next = {
    ...goal,
    nameFa: name.slice(0, 40),
    targetRial,
    endsOn: horizon != null ? horizon.endsOn(tehranDay(now)) : goal.endsOn,
    shared: sharedIf(shared),
  };
  if (next.nameFa === goal.nameFa && next.targetRial === goal.targetRial && next.endsOn === goal.endsOn &&
    next.shared === goal.shared) return;
  put('goals', { ...next, updatedAt: now, editedByMemberId: mineId() });
}

/** Deletes any shape — one table, one tombstone, stamped with who did it for the sync. */
export function deleteGoal(id: string): void {
  const goal = row('goals', id);
  if (goal) put('goals', { ...goal, deleted: true, updatedAt: Date.now(), editedByMemberId: mineId() });
  // A warning about a budget she has deleted is the app talking about nothing.
  void closeNote(budgetTag(id));
}

// ─────────────────────────── installments ───────────────────────────

/** A plan she pays monthly; [count] is what is left, from the next [dayOfMonth]. Private. */
export function addInstallment(name: string, paymentRial: number, count: number, dayOfMonth: number): void {
  const now = Date.now();
  const plan = newInstallment(uuid7(now), name, paymentRial, count, dayOfMonth, now, mineId());
  if (plan) put('goals', plan);
}

/** Says this transaction paid plan [planId], or with null that it paid none. */
export function setInstallmentPayment(entry: LedgerEntry, planId: string | null): void {
  writeInstallmentLink(entry, planId);
}

/**
 * A plan made from the payment she just filed, that payment linked as its first installment —
 * one write, so the plan never shows without the payment that prompted it.
 */
export function addInstallmentFrom(entry: LedgerEntry, name: string, paymentRial: number, count: number): void {
  const now = Date.now();
  const plan = newInstallment(uuid7(now), name, paymentRial, count, jalaliOf(entry.txn.day).day, now, mineId(), entry.txn.day);
  if (!plan) return;
  batch(() => {
    put('goals', plan);
    writeInstallmentLink(entry, plan.id);
  });
}

/**
 * The link itself, false when there was nothing to change. One decision per transaction, so
 * linking a row to a second plan moves it rather than counting it twice.
 */
export function writeInstallmentLink(entry: LedgerEntry, planId: string | null): boolean {
  const amount = entry.txn.amountRial;
  const value = planId != null && amount != null ? encodeInstallmentLink({ planId, rial: amount }) : null;
  const id = `installment:${entry.txn.ref}`;
  const previous = row('decisions', id);
  if (value == null && (!previous || previous.deleted)) return false;
  if (previous && !previous.deleted && previous.value === value) return false;
  const now = Math.max(Date.now(), (previous?.updatedAt ?? 0) + 1);
  put('decisions', {
    id,
    ref: entry.txn.ref,
    kind: DecisionKind.INSTALLMENT,
    value,
    createdAt: previous?.createdAt ?? now,
    updatedAt: now,
    deleted: value == null,
    memberId: '',
    familyRef: '',
  });
  return true;
}

// ─────────────────────────── «ارزش داشت؟» ───────────────────────────

/** Her verdict on one purchase (a WorthIt value). No rule follows from it — an opinion, not a pattern. */
export function answerWorthIt(entry: LedgerEntry, answer: string): void {
  const now = Date.now();
  put('decisions', {
    id: `worth_it:${entry.txn.ref}`,
    ref: entry.txn.ref,
    kind: DecisionKind.WORTH_IT,
    value: answer,
    createdAt: now,
    updatedAt: now,
    deleted: false,
    memberId: '',
    familyRef: '',
  });
}

// ─────────────────────────── what the report leaves out ───────────────────────────

/**
 * The household half of `reportExcluded`: the set as the sync carries it, when it last moved and
 * by whom — the LWW state an arriving `exclusion:report` record is judged against (Sync.kt
 * ReportExclusionsState). `reportExcluded` stays the copy every screen reads; whoever writes one
 * writes both. `updatedAt === 0` is a set nobody here touched and never leaves the device.
 *
 * Kept as a pref of its own under [REPORT_EXCLUSIONS_PREF] until Prefs declares it.
 */
export interface ReportExclusionsState { ids: string[]; updatedAt: number; editedByMemberId: string }
export const REPORT_EXCLUSIONS_PREF = 'reportExclusions';

type LoosePrefs = { pref(key: string): unknown; setPref(key: string, value: unknown): void };
const loose = { pref, setPref } as unknown as LoosePrefs;

export function readReportExclusions(): ReportExclusionsState | null {
  return (loose.pref(REPORT_EXCLUSIONS_PREF) as ReportExclusionsState | undefined) ?? null;
}
export function writeReportExclusions(state: ReportExclusionsState): void {
  loose.setPref(REPORT_EXCLUSIONS_PREF, state);
}

/**
 * Category ids in the one shape both sides store and send: control characters out, each held to
 * 80, blanks and duplicates out, sorted (two devices holding one set must build one byte string)
 * and capped at 400 so the payload cannot outgrow the server's body cap.
 */
export function safeExcludedCategoryIds(ids: Iterable<string>): string[] {
  // Truncate before trimming, so the function is idempotent.
  const clean = [...ids].map((id) => id.replace(/[\u0000-\u001f\u007f-\u009f]/g, '').slice(0, 80).trim()).filter((id) => id !== '');
  return [...new Set(clean)].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).slice(0, 400);
}

/**
 * Which categories دخل و خرج leaves out — a way the household reads the report together, so the
 * edit is stamped for the sync (last write wins) as well as written for the screens. «کل خرج»
 * reads this set too, and moves with it.
 */
export function setReportExcluded(ids: Iterable<string>, now = Date.now()): void {
  const list = [...ids];
  const previous = readReportExclusions();
  batch(() => {
    setPref('reportExcluded', list);
    writeReportExclusions({
      ids: safeExcludedCategoryIds(list),
      updatedAt: Math.max(now, (previous?.updatedAt ?? 0) + 1),
      editedByMemberId: mineId(),
    });
  });
}

// ─────────────────────────── notifications ───────────────────────────
//
// No push server: this runs while the app is open, after the ledger moves. The words are
// budget.ts's and installments.ts's, so what the tests assert is what lands on the screen.

const budgetTag = (goalId: string): string => `budget:${goalId}`;
const installmentTag = (planId: string): string => `installment:${planId}`;
/** Both notes open «آینده», where the cards are. The worker's click handler reads `tab`. */
const OPEN_BUDGET = { tab: 'BUDGET' };

// Prefs types `level` as a string; the phone's BudgetMark holds the Int level, as here.
const budgetMarks = (): BudgetMark[] => pref('budgetMarks') as unknown as BudgetMark[];
const setBudgetMarks = (marks: BudgetMark[]): void => setPref('budgetMarks', marks as unknown as Prefs['budgetMarks']);

const canNotify = (): boolean => typeof Notification !== 'undefined' && Notification.permission === 'granted';

async function registration(): Promise<ServiceWorkerRegistration | null> {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return null;
  // `ready` never settles on a page with no worker (a dev server), so ask first.
  if (!(await navigator.serviceWorker.getRegistration())) return null;
  return navigator.serviceWorker.ready;
}

async function showNote(title: string, body: string, tag: string, data: Record<string, unknown>): Promise<void> {
  if (!canNotify()) return;
  try {
    const reg = await registration();
    // A tag replaces what was said last under it, as the phone's tag-plus-id does.
    await reg?.showNotification(title, { body, tag, data, dir: 'rtl', lang: 'fa', icon: '/icon-192.png' });
  } catch (error) {
    // A note that cannot be shown must not take down the ledger that computed it.
    console.warn('notify failed', error);
  }
}

async function closeNote(tag: string, stale: (data: unknown) => boolean = () => true): Promise<void> {
  try {
    const reg = await registration();
    for (const note of (await reg?.getNotifications({ tag })) ?? []) if (stale(note.data)) note.close();
  } catch (error) {
    console.warn('notify cancel failed', error);
  }
}

const same = (a: unknown, b: unknown): boolean => JSON.stringify(a) === JSON.stringify(b);

/**
 * Work out what is worth saying, say it, and write down what was said — idempotent, so it can run
 * after every ledger change. [entries] are the ledger from `ledgerStartsOn` on (what the caps
 * read); [goals] every goal row.
 *
 * Marks are read and written before anything awaits, so two overlapping calls cannot both announce
 * one crossing; and written only when they changed, because a pref write re-renders every screen
 * and a caller subscribed to the store would otherwise loop.
 */
export async function announce(entries: LedgerEntry[], goals: Goal[], now = Date.now()): Promise<void> {
  const plans = plansOf({ ...stored(now), goals, entries });
  const pending: Array<Promise<void>> = [];

  // Budgets: marked whether or not the device can show a note (Notify.kt announceBudgets).
  const news = budgetNews(plans.budgets, budgetMarks());
  if (!same(news.marks, budgetMarks())) setBudgetMarks(news.marks);
  for (const budget of news.alerts) {
    pending.push(showNote(budgetAlertTitle(budget), budgetAlertBody(budget), budgetTag(budget.goal.id), OPEN_BUDGET));
  }
  // Back under the first threshold — a new window, a refiled receipt — has no note to keep standing.
  for (const budget of plans.budgets) if (budget.level < BudgetLevel.NEAR) pending.push(closeNote(budgetTag(budget.goal.id)));

  // Installments: nothing is marked while nothing can be shown — a reminder marked as said and
  // never seen is a payment she was not told of.
  const today = tehranDay(now);
  for (const plan of plans.installments) {
    // Gone when its day is: a «فرداست» still standing the day after is wrong.
    pending.push(closeNote(installmentTag(plan.plan.id), (data) => ((data as { due?: number } | null)?.due ?? today) < today));
  }
  const days = pref('installmentReminder');
  if (days >= 0 && plans.installments.length && canNotify()) {
    const said = pref('installmentMarks');
    const reminders = installmentNews(plans.installments, days, said, now);
    if (!same(reminders.marks, said)) setPref('installmentMarks', reminders.marks);
    for (const [progress, due] of reminders.due) {
      pending.push(showNote(
        installmentReminderTitle(progress.plan, due, today),
        installmentReminderBody(progress.plan, due),
        installmentTag(progress.plan.id),
        { ...OPEN_BUDGET, due },
      ));
    }
  }
  await Promise.all(pending);
}
