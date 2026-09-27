/**
 * Loans.kt, one to one, plus MainActivity.kt's loan actions — «طلب و بدهی»: who she lent money to,
 * and who she owes.
 *
 * Three stores, each already the right one for its part:
 *  - **People and what she typed** live in one pref, `loans` ([LoanBook]), beside the holdings: a
 *    person is a name and a promise date, and a [LoanMove] is the one thing no bank reports — cash
 *    handed over, two coins lent out of the drawer, a balance from before the app.
 *  - **What the bank reported** is a `loan` decision on the transaction, exactly as a قسط payment
 *    is an `installment` one: the card-to-card to Mahdi is already in the ledger, and typing it here
 *    as well would count it twice with nothing to say which was real.
 *
 * A balance is owed **in what was lent**: two coins lent are two coins owed, whatever a coin fetches
 * the day he returns them, so the units are kept apart and Toman is only ever today's valuation of
 * them. The sign is one convention everywhere: positive is money that went to them (they owe her),
 * negative is money that came from them. That is why filing never asks «lent or repaid?».
 *
 * Beside the total, never in it: what is lent may not come back, and «چقدر تومن دارم» is money she
 * holds. Lending two coins takes them out of دارایی — see [loanHoldings] — so the hero drops by what
 * left the drawer and the loan appears next to it.
 *
 * Always private: the pref is backed up but never synced, and the sync reads `category` and `note`
 * decisions by name, so a `loan` one never leaves the browser.
 *
 * ponytail: a person whose parts pull in opposite directions is placed by the net of today's value
 * and each part is read in that one direction. Per-part words are the upgrade if a real ledger ever
 * mixes them.
 */
import { TOMAN_ID } from './catalog';
import type { AssetType } from './catalog';
import { newHoldingId, setHoldings } from './data';
import { MONTHS, faCompact, faDigits, faHeld, faNumber, tomanOf } from './format';
import { jalaliOf, tehranDay } from './jalali';
import { holdingKey } from './model';
import type { Decision, Holding, LedgerEntry, Txn } from './model';
import { CAT_LOAN, CAT_LOAN_BACK, DecisionKind } from './rules';
import { MAX_PLAUSIBLE_RIAL, bankFa } from './sms';
import { batch, pref, put, row, setPref } from './state';
import { uuid7 } from './sync';

/** A name that fits on a row. */
export const MAX_LOAN_NAME = 32;

/** Below this a unit balance is float dust from adding and taking the same amount back. */
const UNIT_EPSILON = 1e-9;

export interface LoanPerson {
  id: string;
  name: string;
  /** The Tehran day he said he would settle by, or null. */
  promise: number | null;
  createdAt: number;
}

/**
 * One thing she wrote down that no bank will report. Exactly one of [rial] and [amount] is used:
 * [rial] when [typeId] is blank (cash, or a balance from before the app), [amount] otherwise.
 */
export interface LoanMove {
  id: string;
  personId: string;
  /** Blank for Rial; otherwise the asset it was in — `coin_emami`, `usd`. */
  typeId: string;
  /** Signed whole Rial. Positive: it went to them. */
  rial: number;
  /** Signed units of [typeId]. Positive: it went to them. */
  amount: number;
  day: number;
  /** The holding this came out of or went into — kept so taking the move back can put the coins back. */
  holdingKey: string;
  /** A balance she carried in when she added the person: «از قبل», not something that happened. */
  opening: boolean;
}

export interface LoanBook { people: LoanPerson[]; moves: LoanMove[] }

const blank = (s: string): boolean => s.trim() === '';

/**
 * What a `loan` decision's value holds: whose, and how much, signed. Copied onto the link for
 * InstallmentLink's reason — the ledger forgets pruned sources, and a loan summed off live rows
 * would quietly shrink as the months went by.
 */
export interface LoanLink { personId: string; rial: number }

/** `<personId>:<signed rial>`. A person id is a uuid7, which has no colon in it. */
export function encodeLoanLink(link: LoanLink): string {
  return `${link.personId}:${link.rial}`;
}

/** Null for anything this build did not write, so one bad row cannot poison a balance. */
export function decodeLoanLink(value: string | null | undefined): LoanLink | null {
  if (value == null) return null;
  const at = value.lastIndexOf(':');
  const personId = at < 0 ? '' : value.slice(0, at);
  const digits = value.slice(at + 1);
  // Kotlin's toLongOrNull: an optional sign and decimal digits, nothing else.
  const rial = /^[+-]?\d+$/.test(digits) ? Number(digits) : null;
  if (!personId || rial == null || rial === 0 || Math.abs(rial) > MAX_PLAUSIBLE_RIAL) return null;
  return { personId, rial };
}

/** Every link, by the transaction it is on. Links to a deleted person are dropped by [loanViews]. */
export function loanLinks(decisions: readonly Decision[]): Map<string, LoanLink> {
  const out = new Map<string, LoanLink>();
  for (const d of decisions) {
    if (d.kind !== DecisionKind.LOAN || d.deleted) continue;
    const link = decodeLoanLink(d.value);
    if (link) out.set(d.ref, link);
  }
  return out;
}

/** The link this row would make: its amount, signed by which way it went. Null with no direction. */
export function loanLinkRial(entry: LedgerEntry): number | null {
  const amount = entry.txn.amountRial;
  if (amount == null || amount <= 0) return null;
  if (entry.txn.direction === 'out') return amount;
  if (entry.txn.direction === 'in') return -amount;
  return null;
}

/**
 * Whether this row can be linked to a person: hers, with an amount and a direction, and neither a
 * duplicate nor a move between her own accounts. Both directions.
 */
export function loanLinkable(entry: LedgerEntry, mineId: string): boolean {
  return loanLinkRial(entry) != null && !entry.duplicate && !entry.transfer && entry.ownerMemberId === mineId;
}

/** Filed under one of the two قرض categories, which is what raises «به کی؟». */
export const isLoanCategory = (categoryId: string): boolean => categoryId === CAT_LOAN || categoryId === CAT_LOAN_BACK;

// ─────────────────────────── balances ───────────────────────────

export type LoanSide = 'OWED' | 'OWE' | 'SETTLED';

/** One line of a person's history: a linked bank row, or a move she wrote down. */
export interface LoanEvent {
  day: number;
  /** Blank for Rial. */
  typeId: string;
  rial: number;
  amount: number;
  entry: LedgerEntry | null;
  move: LoanMove | null;
}

export interface LoanView {
  person: LoanPerson;
  /** Net Rial across links and cash moves. */
  rial: number;
  /** Net units by asset, zeros dropped, in the order they were first lent. */
  units: Map<string, number>;
  /** Today's Toman value of what could be priced, signed. */
  toman: number;
  /** Assets in [units] with no rate — left out of [toman] and named, never zero. */
  missing: string[];
  side: LoanSide;
  /** Newest first: the linked rows the ledger still holds, and every move. */
  events: LoanEvent[];
  /** What links to rows the ledger has since forgotten still count for. */
  olderRial: number;
}

/** Which way an account leans. Only unpriced units left: their own sign says which way. */
function sideOf(rial: number, units: ReadonlyMap<string, number>, toman: number): LoanSide {
  if (rial === 0 && units.size === 0) return 'SETTLED';
  if (toman > 0) return 'OWED';
  if (toman < 0) return 'OWE';
  let sum = 0;
  for (const v of units.values()) sum += v;
  return sum >= 0 ? 'OWED' : 'OWE';
}

/** A rate map lookup that cannot land on Object.prototype. */
const rateOf = (rates: Readonly<Record<string, number>>, id: string): number | null => {
  const rate = Object.hasOwn(rates, id) ? rates[id] : null;
  return rate != null && rate > 0 && Number.isFinite(rate) ? rate : null;
};

// ponytail: the Kotlin clamp is ±1e17, past Number's exact integers; sums inside 2^53 are exact,
// and anything near the clamp is already a typo the decoder refused.
const RIAL_CLAMP = MAX_PLAUSIBLE_RIAL * 1000;

export function loanView(
  person: LoanPerson,
  book: LoanBook,
  links: ReadonlyMap<string, LoanLink>,
  entries: readonly LedgerEntry[],
  rates: Readonly<Record<string, number>>,
): LoanView {
  const mine = new Map([...links].filter(([, link]) => link.personId === person.id));
  const moves = book.moves.filter((m) => m.personId === person.id);
  const held = entries.filter((e) => mine.has(e.txn.ref));
  const heldRefs = new Set(held.map((e) => e.txn.ref));
  // Clamped as it adds, so a pathological pile of links saturates rather than running away.
  let rial = 0;
  for (const r of [...[...mine.values()].map((l) => l.rial), ...moves.filter((m) => blank(m.typeId)).map((m) => m.rial)]) {
    rial = Math.min(Math.max(rial + r, -RIAL_CLAMP), RIAL_CLAMP);
  }
  const sums = new Map<string, number>();
  for (const m of moves) if (!blank(m.typeId)) sums.set(m.typeId, (sums.get(m.typeId) ?? 0) + m.amount);
  const units = new Map([...sums].filter(([, v]) => Math.abs(v) > UNIT_EPSILON));
  let toman = tomanOf(rial);
  const missing: string[] = [];
  for (const [type, amount] of units) {
    const rate = rateOf(rates, type);
    if (rate == null) missing.push(type); else toman += amount * rate;
  }
  const events: LoanEvent[] = [
    ...held.map((e) => ({ day: e.txn.day, typeId: '', rial: mine.get(e.txn.ref)!.rial, amount: 0, entry: e, move: null })),
    ...moves.map((m) => ({ day: m.day, typeId: m.typeId, rial: m.rial, amount: m.amount, entry: null, move: m })),
  ];
  let olderRial = 0;
  for (const [ref, link] of mine) if (!heldRefs.has(ref)) olderRial += link.rial;
  return {
    person,
    rial,
    units,
    toman,
    missing,
    side: sideOf(rial, units, toman),
    // Stable, as Kotlin's sortedByDescending is.
    events: events.sort((a, b) => b.day - a.day),
    olderRial,
  };
}

/** Every person, the biggest balance first; settled ones last, newest-made first among them. */
export function loanViews(
  book: LoanBook,
  links: ReadonlyMap<string, LoanLink>,
  entries: readonly LedgerEntry[],
  rates: Readonly<Record<string, number>>,
): LoanView[] {
  return book.people.map((p) => loanView(p, book, links, entries, rates))
    .sort((a, b) => Number(a.side === 'SETTLED') - Number(b.side === 'SETTLED') ||
      Math.abs(b.toman) - Math.abs(a.toman) || b.person.createdAt - a.person.createdAt);
}

/** «طلبت» and «بدهیت»: what the two sides come to today, and how many people on each. */
export interface LoanTotals {
  owedToman: number;
  oweToman: number;
  owedPeople: number;
  owePeople: number;
  /** Assets somebody owes in that have no rate, so neither figure counts them. */
  missing: string[];
  isEmpty: boolean;
}

export function loanTotals(views: readonly LoanView[]): LoanTotals {
  const owed = views.filter((v) => v.side === 'OWED');
  const owe = views.filter((v) => v.side === 'OWE');
  return {
    owedToman: owed.reduce((sum, v) => sum + Math.max(v.toman, 0), 0),
    oweToman: owe.reduce((sum, v) => sum + Math.max(-v.toman, 0), 0),
    owedPeople: owed.length,
    owePeople: owe.length,
    missing: [...new Set(views.flatMap((v) => v.missing))],
    isEmpty: owed.length === 0 && owe.length === 0,
  };
}

// ─────────────────────────── making and changing ───────────────────────────

/** A new person, or null when the name is empty. */
export function newLoanPerson(id: string, name: string, promise: number | null, now: number): LoanPerson | null {
  const clean = name.trim().slice(0, MAX_LOAN_NAME);
  if (!clean) return null;
  return { id, name: clean, promise, createdAt: now };
}

/**
 * Her holdings after [amount] of [typeId] left them (positive: lent out) or came into them
 * (negative: borrowed, or paid back) — and the key of the holding that moved.
 *
 * Only hand-typed holdings move: one tracked from a wallet is the chain's figure. Lending takes from
 * the first of them that holds enough; coming in lands on the first of them, or on a new one when she
 * has none. Null when she is lending more than any one of them holds.
 *
 * ponytail: lending from one holding at a time. Two coin holdings each too small to cover the loan
 * alone refuse it; spread the loan across them when somebody actually keeps coins in two places.
 */
export function loanHoldings(
  holdings: readonly Holding[],
  typeId: string,
  amount: number,
  newId: () => string,
): [holdings: Holding[], key: string] | null {
  if (!Number.isFinite(amount) || amount === 0) return null;
  const manual = holdings.filter((h) => h.typeId === typeId && h.wallet == null);
  if (amount > 0) {
    const from = manual.find((h) => h.amount + UNIT_EPSILON >= amount);
    if (!from) return null;
    const key = holdingKey(from);
    return [holdings.map((h) => (holdingKey(h) === key ? { ...h, amount: Math.max(h.amount - amount, 0) } : h)), key];
  }
  const into = manual[0];
  if (!into) {
    const fresh: Holding = { typeId, amount: -amount, excluded: false, wallet: null, label: '', id: newId() };
    return [[...holdings, fresh], holdingKey(fresh)];
  }
  const key = holdingKey(into);
  return [holdings.map((h) => (holdingKey(h) === key ? { ...h, amount: h.amount - amount } : h)), key];
}

/** What a move did to its holding, in the holding's own units: Toman for cash, else the asset's. */
const holdingDelta = (move: LoanMove): number => (blank(move.typeId) ? tomanOf(move.rial) : move.amount);

/**
 * Her holdings with [move]'s change taken back, for when she deletes the move. Never below zero:
 * coins she has since sold are gone, and taking back a loan cannot conjure them.
 */
export function loanHoldingsUndo(holdings: readonly Holding[], move: LoanMove): Holding[] {
  if (blank(move.holdingKey)) return [...holdings];
  const delta = holdingDelta(move);
  return holdings.map((h) => (holdingKey(h) === move.holdingKey ? { ...h, amount: Math.max(h.amount + delta, 0) } : h));
}

/** [loanHoldingsUndo] undone: the move's change applied again, when she brings it back. */
export function loanHoldingsRedo(holdings: readonly Holding[], move: LoanMove): Holding[] {
  if (blank(move.holdingKey)) return [...holdings];
  const delta = holdingDelta(move);
  return holdings.map((h) => (holdingKey(h) === move.holdingKey ? { ...h, amount: Math.max(h.amount - delta, 0) } : h));
}

/**
 * Which holding a move in this unit would change: cash moves the «پول نقد» holding, anything else its
 * own asset's. Null when there is none to change — lending coins she never entered, or cash when she
 * does not count her cash here.
 *
 * Lending names the fullest of them, which is the one [loanHoldings] will manage to take from if any
 * can — so «فقط ۱ سکه» is never said while a second holding has three.
 */
export function loanHoldingFor(holdings: readonly Holding[], typeId: string, giving: boolean): Holding | null {
  const asset = blank(typeId) ? TOMAN_ID : typeId;
  const manual = holdings.filter((h) => h.typeId === asset && h.wallet == null);
  if (!giving) return manual[0] ?? null;
  // Kotlin's maxByOrNull: the first of the largest.
  const fullest = manual.reduce<Holding | null>((best, h) => (best == null || h.amount > best.amount ? h : best), null);
  return fullest && fullest.amount > 0 ? fullest : null;
}

// ─────────────────────────── words ───────────────────────────

/** «بهت بدهکاره», «بهش بدهکاری», «حسابتون صافه» — the side in her words, never in colour alone. */
export function loanSideFa(side: LoanSide): string {
  if (side === 'OWED') return 'بهت بدهکاره';
  if (side === 'OWE') return 'بهش بدهکاری';
  return 'حسابتون صافه';
}

/**
 * One asset's amount in words: «۲ سکه امامی», «۳٫۵ گرم طلای ۱۸ عیار», «۱۰۰ دلار آمریکا». A counted
 * thing takes its own name, a fiat whose name starts with its unit says it once, and everything else
 * is the amount, the unit and the name.
 */
export function loanAmountFa(type: AssetType, amount: number): string {
  const n = faHeld(Math.abs(amount), type.dec);
  return type.unitFa === 'عدد' || type.fa.startsWith(type.unitFa) ? `${n} ${type.fa}` : `${n} ${type.unitFa} ${type.fa}`;
}

/** «۲٫۵ میلیون تومان». */
export const loanRialFa = (rial: number): string => `${faCompact(tomanOf(Math.abs(rial)))} تومان`;

/** Everything one person owes or is owed, in its own units: «۲ سکه امامی و ۲٫۵ میلیون تومان». */
export function loanWhatFa(view: LoanView, type: (id: string) => AssetType): string {
  return [...[...view.units].map(([id, amount]) => loanAmountFa(type(id), amount)), ...(view.rial !== 0 ? [loanRialFa(view.rial)] : [])]
    .join(' و ');
}

/**
 * The promise, from today: «قرار ۱۵ آبان», «قرار امروزه», «۳ روز از قرار گذشته». Null with no promise,
 * and once the account is settled. The second value says it is overdue — the one case worth caution.
 */
export function loanPromiseFa(view: LoanView, today: number): [text: string, overdue: boolean] | null {
  const day = view.person.promise;
  if (day == null || view.side === 'SETTLED') return null;
  if (day < today) return [`${faNumber(today - day)} روز از قرار گذشته`, true];
  if (day === today) return ['قرار امروزه', false];
  return [`قرار ${faDayMonth(day, today)}`, false];
}

/** «۱۵ آبان», with the year only when it is not this one. */
export function faDayMonth(day: number, today: number): string {
  const d = jalaliOf(day);
  const sameYear = d.year === jalaliOf(today).year;
  return `${faNumber(d.day)} ${MONTHS[d.month - 1]}` + (sameYear ? '' : ` ${faDigits(String(d.year))}`);
}

/**
 * The row's one line under the name: an overdue promise first, because it is the one thing that asks
 * something of her; then what is owed when it is more than Toman; then the promise.
 */
export function loanSubFa(view: LoanView, today: number, type: (id: string) => AssetType): [text: string, overdue: boolean] | null {
  const promise = loanPromiseFa(view, today);
  if (promise?.[1] === true) return promise;
  if (view.units.size > 0) return [loanWhatFa(view, type), false];
  return promise;
}

/**
 * What linking this much to this person would leave, said before she commits: «با این، حسین ۴٫۱
 * میلیون تومان بهت بدهکار می‌شه.» Only the Toman part moves; the sentence is the whole account after it.
 */
export function loanAfterFa(view: LoanView, deltaRial: number, type: (id: string) => AssetType): string {
  const after: LoanView = { ...view, rial: view.rial + deltaRial, toman: view.toman + tomanOf(deltaRial) };
  const side = sideOf(after.rial, after.units, after.toman);
  const name = view.person.name;
  const what = loanWhatFa(after, type);
  if (side === 'OWED') return `با این، ${name} ${what} بهت بدهکار می‌شه.`;
  if (side === 'OWE') return `با این، ${what} به ${name} بدهکار می‌شی.`;
  return `با این، حسابت با ${name} صاف می‌شه.`;
}

/**
 * What one transaction is called on its own — Timeline.kt's txnTitleFa, here too so this module
 * need not import a screen.
 */
export function txnTitleFa(txn: Txn): string {
  return txn.merchant.trim() || (txn.sourceKind === 'manual' ? 'مورد دستی' : bankFa(txn.bank));
}

/** Money out of her hands, for a move: its Rial for cash, its units otherwise. */
const movedOut = (move: LoanMove): boolean => (blank(move.typeId) ? move.rial > 0 : move.amount > 0);

/** A trail line's title: «دادی», «گرفتی», «از قبل», or the bank row's own name. */
export function loanEventTitleFa(event: LoanEvent, type: (id: string) => AssetType): string {
  if (event.entry) return txnTitleFa(event.entry.txn);
  const move = event.move;
  if (!move) return '';
  const out = movedOut(move);
  if (move.opening) return out ? 'از قبل بهت بدهکار بود' : 'از قبل بهش بدهکار بودی';
  const what = blank(move.typeId) ? 'نقد' : loanAmountFa(type(move.typeId), move.amount);
  return out ? `${what} دادی` : `${what} گرفتی`;
}

/** Under the title: where it came from. */
export function loanEventSubFa(event: LoanEvent): string | null {
  if (event.entry) return event.entry.categoryFa;
  const move = event.move;
  if (!move || move.opening) return null;
  if (!blank(move.holdingKey)) return movedOut(move) ? 'از دارایی‌هات' : 'به دارایی‌هات';
  return 'دستی';
}

// ─────────────────────────── her writes (MainActivity.kt) ───────────────────────────
//
// The book is one pref; a holding a move touches goes through data.ts's own persistence, so the
// hero total moves with it. Every write is synchronous and lands in one re-render.

const saveLoans = (book: LoanBook): void => setPref('loans', book);

/**
 * A person, with the balance she carried in with them — [opening]'s id, person and day are filled in
 * here. Returns the new id so the screen can open the person it made.
 */
export function addLoanPerson(name: string, promise: number | null, opening: Pick<LoanMove, 'typeId' | 'rial' | 'amount'> | null): string | null {
  const now = Date.now();
  const person = newLoanPerson(uuid7(now), name, promise, now);
  if (!person) return null;
  const book = pref('loans');
  const carried: LoanMove[] = opening
    ? [{ ...opening, id: uuid7(now), personId: person.id, day: tehranDay(now), opening: true, holdingKey: '' }]
    : [];
  saveLoans({ people: [...book.people, person], moves: [...book.moves, ...carried] });
  return person.id;
}

export function editLoanPerson(id: string, name: string, promise: number | null): void {
  const clean = name.trim().slice(0, MAX_LOAN_NAME);
  if (!clean) return;
  const book = pref('loans');
  saveLoans({ ...book, people: book.people.map((p) => (p.id === id ? { ...p, name: clean, promise } : p)) });
}

/**
 * The person and everything she wrote down about them. The bank rows she linked keep their
 * decisions, orphaned — [loanViews] skips them, and bringing the person back brings them back.
 * Holdings are not touched: coins lent to someone she deletes stay wherever they are.
 */
export function deleteLoanPerson(id: string): [person: LoanPerson, moves: LoanMove[]] | null {
  const book = pref('loans');
  const person = book.people.find((p) => p.id === id);
  if (!person) return null;
  const moves = book.moves.filter((m) => m.personId === id);
  saveLoans({ people: book.people.filter((p) => p !== person), moves: book.moves.filter((m) => m.personId !== id) });
  return [person, moves];
}

export function restoreLoanPerson(person: LoanPerson, moves: LoanMove[]): void {
  const book = pref('loans');
  if (book.people.some((p) => p.id === person.id)) return; // undo tapped twice
  saveLoans({ people: [...book.people, person], moves: [...book.moves, ...moves] });
}

/**
 * Something handed over or taken back that no bank reported: [giving] is money to them. [rial] for
 * cash (blank [typeId]), [amount] for anything else. With [moveHolding] the same amount leaves or
 * joins her دارایی — false when she is lending more than the holding has, and then nothing is written.
 */
export function addLoanMove(personId: string, typeId: string, rial: number, amount: number, giving: boolean, moveHolding: boolean): boolean {
  const now = Date.now();
  const sign = giving ? 1 : -1;
  const cash = blank(typeId);
  let moved: [Holding[], string] | null = null;
  if (moveHolding) {
    moved = loanHoldings(pref('holdings'), cash ? TOMAN_ID : typeId, (cash ? tomanOf(rial) : amount) * sign, newHoldingId);
    if (!moved) return false;
  }
  const move: LoanMove = {
    id: uuid7(now), personId, typeId,
    rial: cash ? rial * sign : 0,
    amount: cash ? 0 : amount * sign,
    day: tehranDay(now), holdingKey: moved?.[1] ?? '', opening: false,
  };
  batch(() => {
    if (moved) setHoldings(moved[0]);
    const book = pref('loans');
    saveLoans({ ...book, moves: [...book.moves, move] });
  });
  return true;
}

/** Takes a move back, and its coins back into the holding they left. See [loanHoldingsUndo]. */
export function deleteLoanMove(id: string): LoanMove | null {
  const book = pref('loans');
  const move = book.moves.find((m) => m.id === id);
  if (!move) return null;
  batch(() => {
    if (!blank(move.holdingKey)) setHoldings(loanHoldingsUndo(pref('holdings'), move));
    saveLoans({ ...book, moves: book.moves.filter((m) => m !== move) });
  });
  return move;
}

export function restoreLoanMove(move: LoanMove): void {
  const book = pref('loans');
  if (book.moves.some((m) => m.id === move.id)) return;
  batch(() => {
    if (!blank(move.holdingKey)) setHoldings(loanHoldingsRedo(pref('holdings'), move));
    saveLoans({ ...book, moves: [...book.moves, move] });
  });
}

/**
 * Says whose money this row was, or with null that it was nobody's — writeInstallmentLink's shape:
 * one decision per row, so linking it to a second person moves it rather than counting it twice.
 * False when there was nothing to change.
 */
export function setLoanLink(entry: LedgerEntry, personId: string | null): boolean {
  const rial = loanLinkRial(entry);
  const value = personId != null && rial != null ? encodeLoanLink({ personId, rial }) : null;
  const id = `${DecisionKind.LOAN}:${entry.txn.ref}`;
  const previous = row('decisions', id);
  if (value == null && (!previous || previous.deleted)) return false;
  if (previous && !previous.deleted && previous.value === value) return false;
  const now = Math.max(Date.now(), (previous?.updatedAt ?? 0) + 1);
  put('decisions', {
    id,
    ref: entry.txn.ref,
    kind: DecisionKind.LOAN,
    value,
    createdAt: previous?.createdAt ?? now,
    updatedAt: now,
    deleted: value == null,
    memberId: '',
    familyRef: '',
  });
  return true;
}

/** A person made from the row she is filing, and the row linked to them, in one go. */
export function addLoanPersonFrom(entry: LedgerEntry, name: string): string | null {
  let id: string | null = null;
  batch(() => {
    id = addLoanPerson(name, null, null);
    if (id) setLoanLink(entry, id);
  });
  return id;
}
