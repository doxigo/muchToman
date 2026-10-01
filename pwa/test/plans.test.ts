/**
 * The actions over the store: keepBudget's convergence (FamilyBudgetSyncTest), the canonical
 * exclusion set (FamilyExclusionSyncTest), the link write, and the notifier saying things once.
 */
import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import { BudgetLevel, BudgetPeriod } from '../src/budget';
import { GoalKind } from '../src/goals';
import { jalaliDay, tehranDayStart } from '../src/jalali';
import type { Goal } from '../src/model';
import { entry, goalRow } from './plans-fixture';

let plans: typeof import('../src/plans');
let state: typeof import('../src/state');
let derived: typeof import('../src/derived');
beforeEach(async () => {
  vi.resetModules();
  globalThis.indexedDB = new IDBFactory();
  state = await import('../src/state');
  plans = await import('../src/plans');
  derived = await import('../src/derived');
});

const first = jalaliDay(1405, 5, 1);
const cap = (id: string, target: number, updatedAt = 0): Goal => goalRow({
  id, nameFa: 'رستوران و کافه', targetRial: target, kind: GoalKind.CAP, categoryId: 'cat_dining',
  period: 'jmonth', startsOn: first, createdAt: updatedAt, updatedAt, shared: true, ownerMemberId: 'a',
});

describe('plans', () => {
  it('keeping one budget tombstones its twins, stamped past all of them', () => {
    state.putAll('goals', [cap('one', 100, 50), cap('two', 200, 70), { ...cap('weekly', 300), period: 'week' }]);
    derived.setMineId('b');
    plans.keepBudget('one', 10);
    expect(state.row('goals', 'one')).toMatchObject({ deleted: false, updatedAt: 71, editedByMemberId: 'b' });
    expect(state.row('goals', 'two')).toMatchObject({ deleted: true, updatedAt: 71, editedByMemberId: 'b' });
    expect(state.row('goals', 'weekly')!.deleted).toBe(false);
  });

  it('an unchanged edit is not an edit; a real one forgets what was said about the old cap', () => {
    state.put('goals', { ...cap('b1', 100), shared: false });
    state.setPref('budgetMarks', [{ goalId: 'b1', windowStart: first, level: 1 }, { goalId: 'b2', windowStart: first, level: 2 }] as never);
    const before = state.dataVersion();
    plans.editBudget('b1', BudgetPeriod.MONTH, 100, false);
    expect(state.dataVersion()).toBe(before);
    plans.editBudget('b1', BudgetPeriod.MONTH, 200, false);
    expect(state.row('goals', 'b1')!.targetRial).toBe(200);
    expect(state.pref('budgetMarks')).toEqual([{ goalId: 'b2', windowStart: first, level: 2 }]);
  });

  it('a budget is only shared on a device with somebody to share with', () => {
    derived.setMineId('');
    plans.addBudget(null, BudgetPeriod.WEEK, 1_000, true);
    const [roof] = state.rows('goals');
    expect(roof).toMatchObject({ nameFa: 'کل خرج', categoryId: null, period: 'week', shared: false });
  });

  it('the exclusion set is canonical, so a received set republishes to the very same bytes', () => {
    expect(plans.safeExcludedCategoryIds(['cat_b', 'cat_a', 'cat_b', ' ', 'cat\u0000_a'])).toEqual(['cat_a', 'cat_b']);
    expect(plans.safeExcludedCategoryIds(['x'.repeat(200)])[0].length).toBe(80);
    expect(plans.safeExcludedCategoryIds(Array.from({ length: 500 }, (_, i) => `cat_${String(i + 1).padStart(4, '0')}`)).length).toBe(400);
  });

  it('an exclusion edit is written for the screens and stamped for the household', () => {
    derived.setMineId('m1');
    plans.setReportExcluded(['cat_z', 'cat_loan'], 1_000);
    expect(state.pref('reportExcluded')).toEqual(['cat_z', 'cat_loan']);
    expect(plans.readReportExclusions()).toEqual({ ids: ['cat_loan', 'cat_z'], updatedAt: 1_000, editedByMemberId: 'm1' });
    plans.setReportExcluded([], 500);
    expect(plans.readReportExclusions()!.updatedAt).toBe(1_001);
  });

  it('a link is one decision per transaction, moved rather than counted twice, and retracted', () => {
    const paid = entry(first, -10_000_000);
    expect(plans.writeInstallmentLink(paid, null)).toBe(false);
    expect(plans.writeInstallmentLink(paid, 'phone')).toBe(true);
    expect(plans.writeInstallmentLink(paid, 'phone')).toBe(false);
    plans.writeInstallmentLink(paid, 'fridge');
    expect(state.rows('decisions')).toHaveLength(1);
    expect(state.row('decisions', `installment:${paid.txn.ref}`)!.value).toBe('fridge:10000000');
    plans.writeInstallmentLink(paid, null);
    expect(state.row('decisions', `installment:${paid.txn.ref}`)).toMatchObject({ deleted: true, value: null });
  });

  it('a plan made from a payment is linked to it in the same write', () => {
    const paid = entry(first, -10_000_000);
    plans.addInstallmentFrom(paid, 'گوشی', 10_000_000, 12);
    const [plan] = state.rows('goals');
    expect(plan.startsOn).toBe(first);
    expect(state.row('decisions', `installment:${paid.txn.ref}`)!.value).toBe(`${plan.id}:10000000`);
  });

  it('an installment is read as one, and never as a savings goal', async () => {
    const { newInstallment } = await import('../src/installments');
    const now = tehranDayStart(first) + 1;
    const plan = newInstallment('plan', 'گوشی', 10_000_000, 3, 1, now)!;
    const trip = goalRow({ id: 'trip', nameFa: 'سفر', targetRial: 50_000_000, kind: GoalKind.SAVE, period: 'once', startsOn: first });
    // Linked to a message the ledger no longer holds, which is the case the copied amount is for.
    const link = { id: 'installment:s:gone:0', ref: 's:gone:0', kind: 'installment' as const, value: 'plan:10000000',
      createdAt: now, updatedAt: now, deleted: false, memberId: '', familyRef: '' };
    const view = plans.plansOf({ goals: [plan, trip], decisions: [link], entries: [], today: first });
    expect(view.goals.map((g) => g.goal.id)).toEqual(['trip']);
    expect(view.budgets).toEqual([]);
    expect(view.installments.map((i) => [i.plan.id, i.paidRial, i.overdueRial])).toEqual([['plan', 10_000_000, 0]]);
  });

  it('announces a crossing once, and shows it only when the browser may', async () => {
    const shown: Array<{ title: string; options: NotificationOptions }> = [];
    const reg = {
      showNotification: async (title: string, options: NotificationOptions) => { shown.push({ title, options }); },
      getNotifications: async () => [],
    };
    vi.stubGlobal('Notification', { permission: 'granted' });
    vi.stubGlobal('navigator', { serviceWorker: { getRegistration: async () => reg, ready: Promise.resolve(reg) } });
    try {
      // The budget note alone: the default voice would also ask after a spend five days old.
      state.setPref('quipTone', 'PLAIN');
      const goal = { ...cap('b1', 50_000_000), shared: false };
      const rows = [entry(first, -41_500_000, { categoryId: 'cat_dining', category: 'رستوران و کافه' })];
      const now = tehranDayStart(first + 5) + 10 * 3_600_000;
      await plans.announce(rows, rows, [goal], now);
      expect(shown.map((s) => s.title)).toEqual(['رستوران و کافه: ۸۳٪ بودجهٔ مرداد رفته']);
      expect(shown[0].options).toMatchObject({ tag: 'budget:b1', data: { tab: 'BUDGET' } });
      const version = state.dataVersion();
      await plans.announce(rows, rows, [goal], now);
      expect(shown).toHaveLength(1);
      // Nothing new said, nothing written: a subscriber re-running this cannot loop.
      expect(state.dataVersion()).toBe(version);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('with the app lock on, a note says only its public words', async () => {
    const shown: Array<{ title: string; options: NotificationOptions }> = [];
    const reg = {
      showNotification: async (title: string, options: NotificationOptions) => { shown.push({ title, options }); },
      getNotifications: async () => [],
    };
    vi.stubGlobal('Notification', { permission: 'granted' });
    vi.stubGlobal('navigator', { serviceWorker: { getRegistration: async () => reg, ready: Promise.resolve(reg) } });
    try {
      state.setPref('quipTone', 'PLAIN');
      state.setPref('lockEnabled', true);
      const goal = { ...cap('b1', 50_000_000), shared: false };
      const rows = [entry(first, -41_500_000, { categoryId: 'cat_dining', category: 'رستوران و کافه' })];
      await plans.announce(rows, rows, [goal], tehranDayStart(first + 5) + 10 * 3_600_000);
      // Notify.kt's budgetPublicTitle and publicBody, word for word.
      expect(shown.map((s) => [s.title, s.options.body])).toEqual([['خبری از بودجه', 'جزئیات توی برنامه']]);
      expect(shown[0].options).toMatchObject({ tag: 'budget:b1', data: { tab: 'BUDGET' } });
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('a budget note counts from the ledger start, as its card does', async () => {
    const shown: string[] = [];
    const reg = { showNotification: async (title: string) => { shown.push(title); }, getNotifications: async () => [] };
    vi.stubGlobal('Notification', { permission: 'granted' });
    vi.stubGlobal('navigator', { serviceWorker: { getRegistration: async () => reg, ready: Promise.resolve(reg) } });
    try {
      state.setPref('quipTone', 'PLAIN');
      const goal = { ...cap('b1', 50_000_000), shared: false };
      const dining = { categoryId: 'cat_dining', category: 'رستوران و کافه' };
      // 11.5m before the start she moved, 30m after: the card says ۶۰٪, under any note's threshold.
      const before = entry(first, -11_500_000, dining);
      const after = entry(first + 3, -30_000_000, dining);
      const now = tehranDayStart(first + 5) + 10 * 3_600_000;
      await plans.announce([before, after], [after], [goal], now);
      expect(shown).toEqual([]);
      // And no high-water mark raised past what the card shows.
      expect((state.pref('budgetMarks') as unknown as Array<{ level: number }>).every((m) => m.level === BudgetLevel.OK)).toBe(true);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('asks after a quiet ledger once a spell by default, with a line from the payload', async () => {
    const shown: Array<{ title: string; options: NotificationOptions }> = [];
    const reg = {
      showNotification: async (title: string, options: NotificationOptions) => { shown.push({ title, options }); },
      getNotifications: async () => [],
    };
    vi.stubGlobal('Notification', { permission: 'granted' });
    vi.stubGlobal('navigator', { serviceWorker: { getRegistration: async () => reg, ready: Promise.resolve(reg) } });
    try {
      state.setPref('rates', { updatedAt: 1, toman: {}, coins: [], quips: { quiet: [{ tone: 'witty', category: '', text: 'زنده‌ای؟' }] } });
      const rows = [entry(first, -500_000)];
      const now = tehranDayStart(first + 5) + 10 * 3_600_000;
      await plans.announce(rows, rows, [], now);
      expect(shown.map((s) => [s.title, s.options.body])).toEqual([['۵ روزه خرجی ندیدیم', `زنده‌ای؟\n${'اگه خرج کردی و اینجا نیست، «وضعیت دفتر» رو توی تنظیمات ببین.'}`]]);
      expect(shown[0].options).toMatchObject({ tag: 'quiet', data: { tab: 'LEDGER' } });
      await plans.announce(rows, rows, [], now + 3_600_000);
      expect(shown).toHaveLength(1);
      // An explicit ساده is kept, and says nothing.
      state.setPref('quietMark', 0);
      state.setPref('quipTone', 'PLAIN');
      await plans.announce(rows, rows, [], now);
      expect(shown).toHaveLength(1);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
