/** LoansTest.kt, literally: links, balances in their own units, the side, and the holdings a loan moves. */
import { describe, expect, it } from 'vitest';
import { STATIC_CATALOG } from '../src/catalog';
import type { AssetType } from '../src/catalog';
import { jalaliDay } from '../src/jalali';
import {
  decodeLoanLink, encodeLoanLink, loanAfterFa, loanAmountFa, loanHoldingFor, loanHoldings, loanHoldingsRedo,
  loanHoldingsUndo, loanLinkRial, loanLinkable, loanPromiseFa, loanSideFa, loanSubFa, loanTotals, loanView, loanViews,
  loanWhatFa, newLoanPerson,
} from '../src/loans';
import type { LoanBook, LoanLink, LoanMove, LoanPerson } from '../src/loans';
import type { Holding, LedgerEntry, WalletLink } from '../src/model';
import { BUILTIN_CATEGORIES, CAT_LOAN, CAT_LOAN_BACK, categoryChoices } from '../src/rules';
import { MAX_PLAUSIBLE_RIAL } from '../src/sms';
import { entry as row } from './plans-fixture';

const today = jalaliDay(1405, 7, 5);
const type = (id: string): AssetType => STATIC_CATALOG.find((t) => t.id === id)!;
const coin = type('coin_emami');

const entry = (rial: number, direction: 'in' | 'out' | null = 'out', day = today, owner = ''): LedgerEntry => {
  const e = row(day, direction === 'out' ? -rial : rial, { categoryId: CAT_LOAN, category: 'قرض', merchant: 'کارت به کارت', owner });
  return { ...e, txn: { ...e.txn, direction } };
};
const person = (id: string, name: string, createdAt: number, promise: number | null = null): LoanPerson => ({ id, name, promise, createdAt });
const move = (id: string, personId: string, o: Partial<LoanMove>): LoanMove =>
  ({ id, personId, typeId: '', rial: 0, amount: 0, day: today, holdingKey: '', opening: false, ...o });
const book = (people: LoanPerson[], moves: LoanMove[] = []): LoanBook => ({ people, moves });
const holding = (typeId: string, amount: number, id: string, wallet: WalletLink | null = null): Holding =>
  ({ typeId, amount, excluded: false, wallet, label: '', id });

const mahdi = person('p1', 'مهدی', 1);
const hossein = person('p2', 'حسین', 2);

describe('loans', () => {
  it('a link survives its round trip and refuses what this build never wrote', () => {
    const link: LoanLink = { personId: '0190-abc', rial: -25_000_000 };
    expect(decodeLoanLink(encodeLoanLink(link))).toEqual(link);
    expect(decodeLoanLink('p1:0')).toBeNull();
    expect(decodeLoanLink('p1:lots')).toBeNull();
    expect(decodeLoanLink(':5')).toBeNull();
    expect(decodeLoanLink(`p1:${MAX_PLAUSIBLE_RIAL + 1}`)).toBeNull();
    expect(decodeLoanLink(`p1:-${MAX_PLAUSIBLE_RIAL + 1}`)).toBeNull();
    expect(decodeLoanLink('p1:-9223372036854775808')).toBeNull();
    expect(decodeLoanLink(null)).toBeNull();
  });

  it('money out is owed to her, money in is owed by her, and no direction is no link', () => {
    expect(loanLinkRial(entry(50_000_000, 'out'))).toBe(50_000_000);
    expect(loanLinkRial(entry(50_000_000, 'in'))).toBe(-50_000_000);
    expect(loanLinkRial(entry(50_000_000, null))).toBeNull();
    expect(loanLinkable(entry(1, 'in'), '')).toBe(true);
    expect(loanLinkable(entry(1, 'out', today, 'someone-else'), '')).toBe(false);
  });

  it("a balance is kept in what was lent and valued at today's rate", () => {
    const lent = entry(50_000_000, 'out', today - 50);
    const back = entry(25_000_000, 'in', today - 3);
    const links = new Map<string, LoanLink>([
      [lent.txn.ref, { personId: mahdi.id, rial: 50_000_000 }],
      [back.txn.ref, { personId: mahdi.id, rial: -25_000_000 }],
      // A row the ledger has since pruned still counts.
      ['s:gone:0', { personId: mahdi.id, rial: 1_000_000 }],
    ]);
    const coins = move('m1', mahdi.id, { typeId: 'coin_emami', amount: 2, day: today - 20, holdingKey: 'h' });
    const view = loanView(mahdi, book([mahdi], [coins]), links, [lent, back], { coin_emami: 235_500_000 });

    expect(view.rial).toBe(26_000_000);
    expect(view.units).toEqual(new Map([['coin_emami', 2]]));
    expect(view.toman).toBeCloseTo(2_600_000 + 471_000_000, 3);
    expect(view.side).toBe('OWED');
    expect(view.olderRial).toBe(1_000_000);
    expect(view.events).toHaveLength(3);
    expect(view.events[0].entry?.txn.ref).toBe(back.txn.ref);
    expect(loanWhatFa(view, type)).toBe('۲ سکه امامی و ۲٫۶ میلیون تومان');
  });

  it('money out to someone she owes pays it down and then turns it round', () => {
    const borrowed = entry(9_000_000, 'in');
    const links = new Map([[borrowed.txn.ref, { personId: hossein.id, rial: -9_000_000 }]]);
    const view = loanView(hossein, book([hossein]), links, [borrowed], {});
    expect(view.side).toBe('OWE');
    expect(loanSideFa(view.side)).toBe('بهش بدهکاری');
    expect(loanAfterFa(view, 50_000_000, type)).toBe('با این، حسین ۴٫۱ میلیون تومان بهت بدهکار می‌شه.');
    expect(loanAfterFa(view, 9_000_000, type)).toBe('با این، حسابت با حسین صاف می‌شه.');
  });

  it('an asset with no rate is named and never counted as zero', () => {
    const m = move('m', mahdi.id, { typeId: 'coin_emami', amount: 1 });
    const view = loanView(mahdi, book([mahdi], [m]), new Map(), [], {});
    expect(view.missing).toEqual(['coin_emami']);
    expect(view.side).toBe('OWED');
    const totals = loanTotals([view]);
    expect(totals.owedToman).toBe(0);
    expect(totals.owedPeople).toBe(1);
    expect(totals.missing).toEqual(['coin_emami']);
  });

  it('a settled account sorts last and says so', () => {
    const out = move('a', hossein.id, { rial: 10_000_000, day: today - 2 });
    const back = move('b', hossein.id, { rial: -10_000_000 });
    const owed = move('c', mahdi.id, { rial: 1_000_000 });
    const views = loanViews(book([hossein, mahdi], [out, back, owed]), new Map(), [], {});
    expect(views.map((v) => v.person.id)).toEqual([mahdi.id, hossein.id]);
    expect(views.at(-1)!.side).toBe('SETTLED');
    const totals = loanTotals(views);
    expect(totals.owedToman).toBe(100_000);
    expect(totals.owePeople).toBe(0);
  });

  it('lending coins takes them out of the drawer, and only coins she has', () => {
    const drawer = [holding('coin_emami', 4, 'h1'), holding('usd', 100, 'h2')];
    const [after, key] = loanHoldings(drawer, 'coin_emami', 2, () => 'new')!;
    expect(key).toBe('h1');
    expect(after.find((h) => h.id === 'h1')!.amount).toBe(2);
    expect(loanHoldings(drawer, 'coin_emami', 5, () => 'new')).toBeNull();
    // Coming back with nothing to land on makes the holding.
    const [grown, made] = loanHoldings(drawer, 'gold18', -3.5, () => 'new')!;
    expect(made).toBe('new');
    expect(grown.find((h) => h.id === 'new')!.amount).toBe(3.5);
    // Two holdings of one coin: the check names the fuller, which is the one lent from.
    const two = [holding('coin_emami', 1, 'a'), holding('coin_emami', 3, 'b')];
    expect(loanHoldingFor(two, 'coin_emami', true)?.id).toBe('b');
    expect(loanHoldings(two, 'coin_emami', 2, () => 'new')![1]).toBe('b');
    expect(loanHoldingFor(two, 'coin_emami', false)?.id).toBe('a');
    expect(loanHoldingFor(drawer, '', true)).toBeNull();
    // A wallet-tracked holding is the chain's figure, not one a loan may move.
    const wallet: WalletLink = { network: 'trc20', networkFa: 'TRC20', address: 'T...', contract: '', updatedAt: 0 };
    expect(loanHoldings([holding('usdt', 50, 'w', wallet)], 'usdt', 10, () => 'new')).toBeNull();
  });

  it('taking a move back puts the coins back, never below zero', () => {
    const drawer = [holding('coin_emami', 2, 'h1')];
    const lent = move('m', 'p', { typeId: 'coin_emami', amount: 2, holdingKey: 'h1' });
    expect(loanHoldingsUndo(drawer, lent)[0].amount).toBe(4);
    const returned = move('m', 'p', { typeId: 'coin_emami', amount: -5, holdingKey: 'h1' });
    expect(loanHoldingsUndo(drawer, returned)[0].amount).toBe(0);
    // Brought back with «برگردون»: the coins leave again.
    expect(loanHoldingsRedo(loanHoldingsUndo(drawer, lent), lent)[0].amount).toBe(2);
  });

  it('a promise reads from today, and only while something is owed', () => {
    const promised = { ...mahdi, promise: today - 3 };
    const owed = move('m', mahdi.id, { rial: 8_000_000, day: today - 30 });
    const view = loanView(promised, book([promised], [owed]), new Map(), [], {});
    expect(loanPromiseFa(view, today)).toEqual(['۳ روز از قرار گذشته', true]);
    expect(loanSubFa(view, today, type)).toEqual(['۳ روز از قرار گذشته', true]);
    const later = { ...view, person: { ...promised, promise: jalaliDay(1405, 8, 15) } };
    expect(loanPromiseFa(later, today)).toEqual(['قرار ۱۵ آبان', false]);
    expect(loanPromiseFa({ ...view, side: 'SETTLED' }, today)).toBeNull();
  });

  it('amounts are named the way she says them', () => {
    expect(loanAmountFa(coin, -2)).toBe('۲ سکه امامی');
    expect(loanAmountFa(type('usd'), 100)).toBe('۱۰۰ دلار آمریکا');
    expect(loanAmountFa(type('gold18'), 3.5)).toBe('۳٫۵ گرم طلای ۱۸ عیار');
  });

  it('borrowed money can be filed as قرض from the income grid', () => {
    const incoming = categoryChoices(BUILTIN_CATEGORIES, 'in').map((c) => c.id);
    expect(incoming).toContain(CAT_LOAN);
    expect(incoming).toContain(CAT_LOAN_BACK);
    expect(categoryChoices(BUILTIN_CATEGORIES, 'out').map((c) => c.id)).toContain(CAT_LOAN);
  });

  it('an empty name is not a person', () => {
    expect(newLoanPerson('x', '   ', null, 0)).toBeNull();
    expect(newLoanPerson('x', '  مهدی ', null, 0)?.name).toBe('مهدی');
  });
});
