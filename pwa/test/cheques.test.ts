/** ChequesTest.kt, literally: the warning in words, the reminder's once-per-date, and the date as she types it. */
import { describe, expect, it } from 'vitest';
import {
  chequeDateDigits, chequeNews, chequeReminderBody, chequeReminderTitle, chequeWarningFa, chequeWhenFa, newCheque,
  openCheques, parseChequeDate, pressingCheque,
} from '../src/cheques';
import type { ChequeAccount } from '../src/cheques';
import { GoalKind } from '../src/goals';
import { jalaliDay, tehranDayStart } from '../src/jalali';
import type { Goal } from '../src/model';
import { buildStory } from '../src/reports';

const due = jalaliDay(1405, 9, 15);

const cheque = (id = 'rent', rial = 500_000_000, day = due, name = 'اجاره', at = 0): Goal =>
  newCheque(id, name, rial, day, 'MELLAT', at)!;

/** ۳۰ میلیون تومان, stated by the bank. */
const mellat: ChequeAccount = { bank: 'MELLAT', balanceRial: 300_000_000, anchored: true };

describe('cheques', () => {
  it('a short account says how much it holds and how much is missing', () => {
    const c = cheque();
    expect(chequeWarningFa(c, [c], [mellat], due - 1, 1)).toBe('تو حساب بانک ملت ۳۰ میلیون تومان هست؛ ۲۰ میلیون کمه.');
    // Further off than her window: nothing yet. Covered: nothing at all.
    expect(chequeWarningFa(c, [c], [mellat], due - 2, 1)).toBeNull();
    expect(chequeWarningFa(c, [c], [{ ...mellat, balanceRial: 500_000_000 }], due, 1)).toBeNull();
  });

  it('an unknown balance is said as unknown, never as nothing in the account', () => {
    const c = cheque();
    const unknown = 'موجودی حساب بانک ملت رو نمی‌دونیم؛ مطمئن شو ۵۰ میلیون تومان توش هست.';
    expect(chequeWarningFa(c, [c], [], due, 1)).toBe(unknown);
    // A running sum of the messages read is not a balance.
    expect(chequeWarningFa(c, [c], [{ ...mellat, anchored: false }], due, 1)).toBe(unknown);
  });

  it('on the day and past it the warning stands, reminders off or not', () => {
    const c = cheque();
    expect(chequeWhenFa(due, due)).toBe('امروز');
    expect(chequeWhenFa(due, due - 3)).toBe('۳ روز مونده');
    expect(chequeWhenFa(due, due + 2)).toBe('۲ روز گذشته');
    const short = 'تو حساب بانک ملت ۳۰ میلیون تومان هست؛ ۲۰ میلیون کمه.';
    expect(chequeWarningFa(c, [c], [mellat], due, -1)).toBe(short);
    expect(chequeWarningFa(c, [c], [mellat], due + 2, 1)).toBe(short);
  });

  it('cheques on one account are covered together, the earlier one first', () => {
    const first = cheque('a', 200_000_000, due, 'اجاره', 1);
    const second = cheque('b', 200_000_000, due, 'اجاره', 2);
    const open = openCheques([second, first]);
    expect(open).toEqual([first, second]);
    expect(chequeWarningFa(first, open, [mellat], due, 1)).toBeNull();
    expect(chequeWarningFa(second, open, [mellat], due, 1))
      .toBe('تو حساب بانک ملت ۳۰ میلیون تومان هست؛ با چک‌های زودتر همین حساب، ۱۰ میلیون کمه.');
    // Home leads with the one that cannot be covered.
    expect(pressingCheque(open, [mellat], due, 1)).toEqual(second);
  });

  it('home\'s attention card is the cheque, warning and all', () => {
    const c = cheque();
    const story = buildStory([], 0, due - 1, { cheques: [c], accounts: [mellat] });
    expect(story.attentionCheque).toEqual(c);
    expect(story.attention?.text).toBe('سررسید چک «اجاره» فرداست. تو حساب بانک ملت ۳۰ میلیون تومان هست؛ ۲۰ میلیون کمه.');
    expect(buildStory([], 0, due - 3, { cheques: [c], accounts: [mellat] }).attentionCheque).toBeNull();
  });

  it('a passed cheque leaves the list, and other shapes were never on it', () => {
    const passed = { ...cheque('p'), endsOn: due };
    const plan = { ...cheque('i'), kind: GoalKind.INSTALLMENT };
    const gone = { ...cheque('d'), deleted: true };
    expect(openCheques([passed, plan, gone, cheque()])).toEqual([cheque()]);
    expect(newCheque('x', '', 0, due, 'MELLAT', 0)).toBeNull();
    expect(newCheque('x', '', 10, due, '', 0)).toBeNull();
  });

  it('a reminder comes once per date, inside her days and waking hours', () => {
    const c = cheque();
    const at = (day: number, hour: number) => tehranDayStart(day) + hour * 3_600_000;
    const news = chequeNews([c], 1, {}, at(due - 1, 10));
    expect(news).toEqual({ due: [c], marks: { [c.id]: due } });
    expect(chequeNews([c], 1, news.marks, at(due, 10)).due).toEqual([]);
    expect(chequeNews([c], 1, {}, at(due - 1, 3)).due).toEqual([]);
    expect(chequeNews([c], -1, {}, at(due, 10)).due).toEqual([]);
    expect(chequeReminderTitle(c, due - 1)).toBe('سررسید چک «اجاره» فرداست');
    expect(chequeReminderTitle(cheque('x', 1, due, ' '), due)).toBe('سررسید چک بانک ملت امروزه');
    expect(chequeReminderBody(c, [c], [{ ...mellat, balanceRial: 900_000_000 }], due, 1)).toBe('۵۰ میلیون تومان از بانک ملت • ۱۵ آذر ۱۴۰۵');
  });

  it('the date is typed as printed and refused when it is not one', () => {
    expect(parseChequeDate('۱۴۰۵۰۹۱۵')).toBe(due);
    expect(parseChequeDate('1405/09/15')).toBe(due);
    expect(chequeDateDigits(due)).toBe('14050915');
    expect(parseChequeDate('14050731')).toBeNull(); // مهر has thirty days
    expect(parseChequeDate('1405091')).toBeNull();
    expect(parseChequeDate('14051315')).toBeNull();
  });
});
