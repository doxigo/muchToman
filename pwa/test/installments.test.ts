/** InstallmentsTest.kt, literally: the due-date arithmetic, the plan's figures, and which rows are offered as payments. */
import { describe, expect, it } from 'vitest';
import { GoalKind, GoalPeriod } from '../src/goals';
import {
  MAX_INSTALLMENTS, encodeInstallmentLink, firstInstallmentDue, installmentCount, installmentDueOn, installmentInsight,
  installmentLeftFa, installmentLineFa, installmentLinks, installmentNews, installmentNoteFa, installmentPaidBy,
  installmentPayable, installmentProgress, installmentReminderBody, installmentReminderFa, installmentReminderTitle,
  installmentSummary, installmentsByDue, newInstallment, pressingInstallment, tomanFieldToRial,
} from '../src/installments';
import { buildStory } from '../src/reports';
import type { InstallmentLink } from '../src/installments';
import { jalaliDay, jalaliMonthsAfter, jalaliOf, tehranDayStart } from '../src/jalali';
import type { Decision, Goal, LedgerEntry } from '../src/model';
import { MAX_PLAUSIBLE_RIAL } from '../src/sms';
import { entry as row, goalRow } from './plans-fixture';

/** The 31st of شهریور — the last month with a 31st, so the next due date has to clamp. */
const first = jalaliDay(1405, 6, 31);

const entry = (day: number, rial: number, direction: 'in' | 'out' = 'out', owner = ''): LedgerEntry =>
  row(day, direction === 'out' ? -rial : rial, { categoryId: 'cat_shopping', merchant: 'جایی', owner });

const plan = (id = 'phone', payment = 10_000_000, count = 3): Goal => goalRow({
  id, nameFa: 'گوشی', targetRial: payment, kind: GoalKind.INSTALLMENT, period: GoalPeriod.MONTH,
  startsOn: first, endsOn: jalaliMonthsAfter(first, count - 1),
});

const links = (p: Goal, ...paid: Array<[string, number]>): Map<string, InstallmentLink> =>
  new Map(paid.map(([ref, rial]) => [ref, { planId: p.id, rial }]));

describe('installments', () => {
  it('a reminder comes once per due date, inside her days and waking hours', () => {
    const p = plan();
    const second = jalaliMonthsAfter(first, 1);
    const at = (day: number, hour: number) => tehranDayStart(day) + hour * 3_600_000;
    const unpaid = installmentProgress(p, new Map(), [], first - 1);

    const news = installmentNews([unpaid], 1, {}, at(first - 1, 10));
    expect(news.due).toEqual([[unpaid, first]]);
    expect(installmentReminderTitle(p, first, first - 1)).toBe('گوشی: سررسید قسط فرداست');
    expect(installmentNews([unpaid], 1, news.marks, at(first - 1, 16)).due).toEqual([]);
    expect(installmentNews([unpaid], 1, news.marks, at(first, 10)).due).toEqual([]);
    expect(installmentNews([unpaid], 1, news.marks, at(first, 10)).marks).toEqual(news.marks);
    expect(installmentNews([unpaid], 1, {}, at(first - 1, 3)).due).toEqual([]);
    expect(installmentNews([unpaid], 1, {}, at(first - 2, 10)).due).toEqual([]);
    expect(installmentNews([unpaid], -1, {}, at(first - 1, 10)).due).toEqual([]);

    const paid = installmentProgress(p, links(p, ['a', 10_000_000]), [], first - 1);
    expect(installmentNews([paid], 1, {}, at(first - 1, 10)).due).toEqual([]);
    const behind = installmentProgress(p, new Map(), [], second - 1);
    expect(installmentNews([behind], 1, news.marks, at(second - 1, 10)).due).toEqual([[behind, second]]);
  });

  it('a due date keeps its day where the month has one and clamps where it does not', () => {
    expect(jalaliOf(jalaliMonthsAfter(first, 1))).toEqual({ year: 1405, month: 7, day: 30 });
    expect(jalaliOf(jalaliMonthsAfter(first, 6))).toEqual({ year: 1405, month: 12, day: 29 });
    expect(jalaliOf(jalaliMonthsAfter(first, 7))).toEqual({ year: 1406, month: 1, day: 31 });
    expect(jalaliOf(jalaliMonthsAfter(first, -1))).toEqual({ year: 1405, month: 5, day: 31 });
  });

  it('the first due date is the next such day, today included', () => {
    const today = jalaliDay(1405, 7, 10);
    expect(firstInstallmentDue(10, today)).toBe(today);
    expect(firstInstallmentDue(15, today)).toBe(jalaliDay(1405, 7, 15));
    expect(firstInstallmentDue(5, today)).toBe(jalaliDay(1405, 8, 5));
    expect(firstInstallmentDue(31, jalaliDay(1405, 7, 20))).toBe(jalaliDay(1405, 7, 30));
  });

  it('a plan is refused unless every answer is one, and is private when it is stored', () => {
    const now = tehranDayStart(jalaliDay(1405, 7, 10)) + 1;
    expect(newInstallment('x', '  ', 10_000_000, 12, 15, now)).toBeNull();
    expect(newInstallment('x', 'گوشی', 0, 12, 15, now)).toBeNull();
    expect(newInstallment('x', 'گوشی', MAX_PLAUSIBLE_RIAL + 1, 12, 15, now)).toBeNull();
    expect(newInstallment('x', 'گوشی', 10_000_000, 0, 15, now)).toBeNull();
    expect(newInstallment('x', 'گوشی', 10_000_000, MAX_INSTALLMENTS + 1, 15, now)).toBeNull();
    expect(newInstallment('x', 'گوشی', 10_000_000, 12, 0, now)).toBeNull();
    expect(newInstallment('x', 'گوشی', 10_000_000, 12, 32, now)).toBeNull();

    const p = newInstallment('x', ' گوشی ', 10_000_000, 12, 15, now)!;
    expect(p.kind).toBe(GoalKind.INSTALLMENT);
    expect(p.nameFa).toBe('گوشی');
    expect(p.shared).toBe(false);
    expect(p.startsOn).toBe(jalaliDay(1405, 7, 15));
    expect(p.endsOn).toBe(jalaliDay(1406, 6, 15));
    expect(installmentCount(p)).toBe(12);
  });

  it('partial payments settle one due payment between them', () => {
    const p = plan();
    const progress = installmentProgress(p, links(p, ['a', 4_000_000], ['b', 6_000_000]), [], first);
    expect(progress.paidRial).toBe(10_000_000);
    expect(progress.paidCount).toBe(1);
    expect(progress.overdueRial).toBe(0);
    expect(progress.nextDue).toBe(installmentDueOn(p, 1));
  });

  it('a payment is late only once its own day has passed', () => {
    const p = plan();
    const paidOne = links(p, ['a', 10_000_000]);
    const second = installmentDueOn(p, 1);
    const dueToday = installmentProgress(p, paidOne, [], second);
    expect(dueToday.overdueRial).toBe(0);
    expect(dueToday.nextDue).toBe(second);
    expect(installmentProgress(p, paidOne, [], second + 1).overdueRial).toBe(10_000_000);
  });

  it('a link too large for the plan reads as paid off, never as a negative balance', () => {
    const p = plan();
    const progress = installmentProgress(p, links(p, ['a', MAX_PLAUSIBLE_RIAL], ['b', MAX_PLAUSIBLE_RIAL]), [entry(first, 10_000_000)], first);
    expect(progress.paidRial).toBe(30_000_000);
    expect(progress.done).toBe(true);
    expect(progress.share).toBe(1);
    expect(progress.nextDue).toBeNull();
    expect(progress.candidates).toEqual([]);
  });

  it('a payment still counts after the ledger has forgotten its message', () => {
    const p = plan();
    const live = entry(first, 10_000_000);
    const progress = installmentProgress(p, links(p, [live.txn.ref, 10_000_000], ['s:pruned:0', 10_000_000]), [live], first);
    expect(progress.paidRial).toBe(20_000_000);
    expect(progress.payments.map((e) => e.txn.ref)).toEqual([live.txn.ref]);
    expect(progress.olderRial).toBe(10_000_000);
  });

  it('payments offered are her own outgoing rows since the last due date, its exact amount first', () => {
    const p = plan();
    const olderExact = entry(first - 5, 10_000_000);
    const newerOther = entry(first, 3_000_000);
    const newerExact = entry(first, 10_000_000);
    const incoming = entry(first, 10_000_000, 'in');
    const lastMonths = entry(jalaliMonthsAfter(first, -1), 10_000_000);
    const his = entry(first, 10_000_000, 'out', 'b'.repeat(32));
    const transfer = { ...entry(first, 10_000_000), transfer: true };
    const paysOther = entry(first, 10_000_000);
    const progress = installmentProgress(
      p,
      new Map([[paysOther.txn.ref, { planId: 'fridge', rial: 10_000_000 }]]),
      [olderExact, newerOther, newerExact, incoming, lastMonths, his, transfer, paysOther],
      first,
    );
    expect(progress.candidates.map((e) => e.txn.ref)).toEqual([newerExact, olderExact, newerOther].map((e) => e.txn.ref));
  });

  it('only her own outgoing payments can pay a plan, from either end of the link', () => {
    expect(installmentPayable(entry(first, 10_000_000), '')).toBe(true);
    expect(installmentPayable(entry(first, 10_000_000, 'out', 'b'.repeat(32)), '')).toBe(false);
    expect(installmentPayable(entry(first, 10_000_000, 'in'), '')).toBe(false);
    expect(installmentPayable({ ...entry(first, 10_000_000), transfer: true }, '')).toBe(false);
    expect(installmentPayable({ ...entry(first, 10_000_000), duplicate: true }, '')).toBe(false);
  });

  it('a plan made from a payment starts on its day and counts it as the first installment', () => {
    const paidOn = first - 3;
    const p = newInstallment('p', 'گوشی', 10_000_000, 12, jalaliOf(paidOn).day, tehranDayStart(first), '', paidOn)!;
    expect(p.startsOn).toBe(paidOn);
    expect(installmentCount(p)).toBe(12);
    const payment = entry(paidOn, 10_000_000);
    const progress = installmentProgress(p, links(p, [payment.txn.ref, 10_000_000]), [payment], first);
    expect(progress.paidCount).toBe(1);
    expect(progress.overdueRial).toBe(0);
    expect(progress.nextDue).toBe(installmentDueOn(p, 1));
    expect(installmentPaidBy(payment.txn.ref, [progress])?.plan.id).toBe('p');
    expect(installmentPaidBy('s:elsewhere:0', [progress])).toBeNull();
  });

  it('only readable links to a plan that still exists are counted', () => {
    const live = plan('phone');
    const gone = { ...plan('tv'), deleted: true };
    const decision = (ref: string, value: string | null, deleted = false, kind: Decision['kind'] = 'installment'): Decision =>
      ({ id: `${kind}:${ref}`, ref, kind, value, createdAt: 0, updatedAt: 0, deleted, memberId: '', familyRef: '' });
    const found = installmentLinks([
      decision('a', encodeInstallmentLink({ planId: 'phone', rial: 10_000_000 })),
      decision('b', encodeInstallmentLink({ planId: 'tv', rial: 10_000_000 })),
      decision('c', 'phone:abc'),
      decision('d', 'phone:-5'),
      decision('e', null),
      decision('f', encodeInstallmentLink({ planId: 'phone', rial: 10_000_000 }), true),
      decision('g', 'phone:10000000', false, 'note'),
    ], [live, gone]);
    expect(found).toEqual(new Map([['a', { planId: 'phone', rial: 10_000_000 }]]));
  });

  it("the plan's words, the reminder's and the setting's", () => {
    const p = plan();
    expect(installmentReminderTitle(p, first, first)).toBe('گوشی: سررسید قسط امروزه');
    expect(installmentReminderTitle(p, first, first - 2)).toBe('گوشی: سررسید قسط پس‌فرداست');
    expect(installmentReminderTitle(p, first, first - 3)).toBe('گوشی: سررسید قسط ۳ روز دیگه‌ست');
    expect(installmentReminderBody(p, first)).toBe('۱ میلیون تومان • ۳۱ شهریور ۱۴۰۵');
    expect([-1, 0, 1, 3].map(installmentReminderFa)).toEqual(['خاموش', 'همون روز', '۱ روز قبل', '۳ روز قبل']);

    const unpaid = installmentProgress(p, new Map(), [], first);
    expect(installmentLineFa(unpaid)).toBe('ماهی ۱ میلیون تومان • ۰ از ۳ قسط');
    expect(installmentNoteFa(unpaid, first)).toEqual(['سررسید قسط اول امروزه.', false]);
    expect(installmentNoteFa(installmentProgress(p, new Map(), [], first - 1), first - 1)).toEqual(['قسط اول: ۳۱ شهریور ۱۴۰۵', false]);
    expect(installmentNoteFa(installmentProgress(p, links(p, ['a', 10_000_000]), [], first), first)).toEqual(['قسط دوم: ۳۰ مهر ۱۴۰۵', false]);
    expect(installmentNoteFa(installmentProgress(p, new Map(), [], first + 1), first + 1))
      .toEqual(['۱ میلیون تومان از قسط‌هایی که سررسیدشون گذشته، هنوز پرداخت نشده.', true]);
    expect(installmentNoteFa(installmentProgress(p, links(p, ['a', 30_000_000]), [], first), first)).toEqual(['همه‌ی قسط‌ها پرداخت شد.', true]);
  });

  it('plans are listed soonest payment first, a late one ahead and a paid-off one last', () => {
    const late = plan('late');
    const later = { ...plan('later'), startsOn: first + 10, endsOn: jalaliMonthsAfter(first + 10, 2) };
    const done = plan('done');
    const paid = links(done, ['a', 30_000_000]);
    const list = [done, later, late].map((g) => installmentProgress(g, paid, [], first + 5));
    expect(installmentsByDue(list).map((p) => p.plan.id)).toEqual(['late', 'later', 'done']);
  });

  it('the card says how many are left and when the last one falls due', () => {
    const p = plan();
    expect(installmentLeftFa(installmentProgress(p, links(p, ['a', 10_000_000]), [], first))).toBe('۲ از ۳ قسط مونده، تا ۳۰ آبان ۱۴۰۵');
    expect(installmentLeftFa(installmentProgress(p, links(p, ['a', 30_000_000]), [], first))).toBeNull();
  });

  it("the section adds up the running plans, and counts this month's payments paid off or not", () => {
    const phone = plan('phone');
    const tv = plan('tv', 5_000_000, 12);
    const old = plan('old');
    const paid = new Map([...links(phone, ['a', 10_000_000]), ...links(old, ['b', 30_000_000])]);
    const today = jalaliDay(1405, 6, 20);
    const all = [phone, tv, old].map((g) => installmentProgress(g, paid, [], today));
    expect(installmentSummary(all, today)).toEqual({
      monthlyRial: 15_000_000,
      leftRial: 20_000_000 + 60_000_000,
      lastDue: jalaliMonthsAfter(first, 11),
      dueThisMonth: 3,
      paidThisMonth: 2,
    });
    // One running plan is its own card already.
    expect(installmentSummary(all.filter((p) => p.plan.id !== 'tv'), today)).toBeNull();
    // A month none of them has a payment in says nothing about this month.
    expect(installmentSummary(all, jalaliMonthsAfter(first, 12))!.dueThisMonth).toBe(0);
  });

  it("home hears of a payment inside the reminder's window, or once it is late", () => {
    const p = plan();
    const unpaid = (today: number) => installmentProgress(p, new Map(), [], today);

    const tomorrow = pressingInstallment([unpaid(first - 1)], first - 1, 1)!;
    expect(tomorrow[1]).toBe(0);
    const insight = installmentInsight(tomorrow[0], tomorrow[1], first - 1);
    expect(insight.text).toBe('سررسید قسط «گوشی» فرداست.');
    expect(insight.why).toBe('قسطی که خودت ساختی: ماهی ۱ میلیون تومان، قسط اول از ۳، ۳۱ شهریور ۱۴۰۵.');
    expect(insight.tone).toBe('ATTENTION');

    expect(pressingInstallment([unpaid(first - 1)], first - 1, 0)).toBeNull();
    // Reminders off still leaves the day itself.
    expect(pressingInstallment([unpaid(first)], first, -1)![1]).toBe(0);
    const late = pressingInstallment([unpaid(first + 1)], first + 1, 1)!;
    expect(installmentInsight(late[0], late[1], first + 1).text).toBe('سررسید قسط «گوشی» گذشته.');
    // Paid ahead, the next one is a month off: nothing to say.
    expect(pressingInstallment([installmentProgress(p, links(p, ['a', 10_000_000]), [], first - 1)], first - 1, 3)).toBeNull();
  });

  it("a payment falling due outranks the review queue for home's one slot", () => {
    const progress = installmentProgress(plan(), new Map(), [], first - 1);
    const rows = [row(first - 3, -1_000_000, { review: true })];
    const story = buildStory(rows, 0, first - 1, { installments: [progress] });
    expect(story.attentionInstallment).toBe(progress);
    expect(story.attention!.text).toBe('سررسید قسط «گوشی» فرداست.');
    const quiet = buildStory(rows, 0, first - 1);
    expect(quiet.attentionInstallment).toBeNull();
    expect(quiet.attention!.text).toContain('منتظر');
  });

  it('a Toman field is whole Rial, or nothing this app would store', () => {
    expect(tomanFieldToRial('۱۲٬۵۰۰')).toBe(125_000);
    expect(tomanFieldToRial('0')).toBeNull();
    expect(tomanFieldToRial('abc')).toBeNull();
    expect(tomanFieldToRial(String(MAX_PLAUSIBLE_RIAL))).toBeNull();
  });
});
