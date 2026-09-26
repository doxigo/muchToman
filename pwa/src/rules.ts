/**
 * Classification, which is a table of rules and nothing else — Rules.kt.
 *
 * No model, no scoring, no learned weights. "Teach it once and it never asks again" is a row being
 * written, and anything that inferred would quietly change its mind about money she already
 * checked. [categoryUseOf] is the one thing here that learns, and only to order the picker.
 */
import { batch, load, putAll, rows } from './state';
import { uuid7 } from './sync';
import type { Category, CategoryKindId, LedgerEntry, Rule, Txn } from './model';

// ---- categories ----------------------------------------------------------------------------

/** What a category does to a report. `transfer` is the one that must never count either way. */
export const CategoryKind = { EXPENSE: 'expense', INCOME: 'income', TRANSFER: 'transfer' } as const;

export const CAT_UNCATEGORISED = 'cat_uncategorised';
export const CAT_TRANSFER = 'cat_transfer';
export const CAT_SPOUSE = 'cat_spouse';
/** «سایر», offered both ways. The id says income because that is the only side it shipped on. */
export const CAT_OTHER = 'cat_income_other';
export const CAT_FEES = 'cat_fees';
export const CAT_CASH = 'cat_cash';
export const CAT_INCOME = 'cat_income';
export const CAT_LOAN = 'cat_loan';
export const CAT_LOAN_BACK = 'cat_loan_back';
/** قسط و وام: filing a payment here asks which installment plan it paid. */
export const CAT_INSTALMENT = 'cat_instalment';
export const CAT_BILLS_ID = 'cat_bills';
export const CAT_SHOPPING_ID = 'cat_shopping';

const shipped = (id: string, nameFa: string, kind: CategoryKindId, sort: number, archived = false): Category =>
  ({ id, parentId: null, nameFa, kind, sort, builtin: true, archived, updatedAt: 0, glyph: '' });

/**
 * The categories the app ships with, in the order a household generally reaches for them. `sort`
 * is the opening position only — [categoryChoices] floats what she uses to the front. The three
 * at the end are the ones both grids share, and they stay at the end of both.
 */
export const BUILTIN_CATEGORIES: readonly Category[] = [
  shipped(CAT_UNCATEGORISED, 'دسته‌بندی نشده', 'expense', 0),

  // the weekly ones: the first row of the spending grid
  shipped('cat_groceries', 'خواربار', 'expense', 10),
  shipped('cat_dining', 'رستوران و کافه', 'expense', 20),
  shipped('cat_transport', 'حمل و نقل', 'expense', 30),
  shipped('cat_shopping', 'خرید روزانه', 'expense', 40),

  // the monthly ones
  shipped('cat_bills', 'قبض‌ها', 'expense', 50),
  shipped('cat_internet', 'اینترنت', 'expense', 60),
  shipped('cat_car', 'خودرو', 'expense', 70),
  shipped('cat_health', 'سلامت', 'expense', 80),
  shipped('cat_home', 'خانه و کاشانه', 'expense', 90),
  shipped('cat_clothing', 'مد و پوشاک', 'expense', 100),
  // No shipped rule behind a قسط: it arrives as an ordinary برداشت, so it earns one the first
  // time she files one and says «همیشه».
  shipped(CAT_INSTALMENT, 'قسط و وام', 'expense', 110),
  shipped('cat_ride_credit', 'بازپرداخت اسنپ و تپسی', 'expense', 115),
  shipped('cat_atina', 'خرج اتینا', 'expense', 120),
  shipped('cat_sport', 'ورزش', 'expense', 125),

  // the occasional ones
  shipped('cat_savings', 'پس‌انداز و سرمایه', 'expense', 130),
  shipped('cat_gifts', 'هدیه و نیکوکاری', 'expense', 140),
  shipped('cat_beauty', 'زیبایی', 'expense', 150),
  shipped('cat_salon', 'آرایشگاه', 'expense', 152),
  shipped('cat_cosmetics', 'آرایشی و بهداشتی', 'expense', 154),
  shipped('cat_culture', 'فرهنگی و هنری', 'expense', 160),
  shipped('cat_tobacco', 'دخانیات', 'expense', 170),
  // EXPENSE and INCOME are the only kinds that stay visible, and money lent is her decision.
  shipped(CAT_LOAN, 'قرض', 'expense', 180),
  shipped('cat_travel', 'سفر', 'expense', 185),
  // After سفر rather than beside خواربار: in the middle they would move every cell after them
  // and undo the hue each one was given against its neighbours.
  shipped('cat_sweets', 'شیرینی', 'expense', 186),
  shipped('cat_meat', 'گوشت و مرغ', 'expense', 187),
  shipped('cat_juice', 'آبمیوه بستنی', 'expense', 188),

  // what the app files for her, which she rarely has to pick
  shipped(CAT_CASH, 'برداشت نقدی', 'expense', 190),
  shipped(CAT_FEES, 'کارمزد', 'expense', 200),

  // Retired, archived rather than deleted so every row filed under it still says what it was.
  shipped('cat_send', 'انتقال وجه', 'expense', 205, true),

  // the income grid — درآمد first, because it is what `rule_income` files every incoming message as
  shipped(CAT_INCOME, 'درآمد', 'income', 210),
  shipped('cat_salary', 'حقوق', 'income', 220),
  shipped('cat_sales', 'فروش', 'income', 230),
  shipped('cat_bonus', 'پاداش', 'income', 240),
  shipped('cat_invest_income', 'سود سرمایه‌گذاری', 'income', 250),
  shipped(CAT_LOAN_BACK, 'پس‌گرفتن قرض', 'income', 260),

  // the three both grids end with; what a row counts as is read off the sign, never the kind
  shipped(CAT_SPOUSE, 'همسر', 'expense', 270),
  shipped(CAT_OTHER, 'سایر', 'income', 280),
  shipped(CAT_TRANSFER, 'انتقال بین حساب‌ها', 'transfer', 290),
];

/**
 * The categories whose money is neither earned nor spent, and the word the report calls each by.
 * A قرض out and back is one movement told in two halves; money to a husband is money the household
 * still has. Declaration order is reading order: «قرض و همسر».
 */
export const PASS_THROUGH_CATEGORIES: ReadonlyMap<string, string> = new Map([
  [CAT_LOAN, 'قرض'],
  [CAT_LOAN_BACK, 'قرض'],
  [CAT_SPOUSE, 'همسر'],
]);

/**
 * A category she made herself. `sort = 500`, past every shipped one; offered on both sides of the
 * ledger whichever she made it on. [glyph] is a `CategoryGlyph` name.
 */
export function customCategory(nameFa: string, kind: CategoryKindId, glyph: string, now: number): Category {
  return {
    id: `cat_${uuid7(now)}`, parentId: null, nameFa: nameFa.trim(), kind, sort: 500,
    builtin: false, archived: false, updatedAt: now, glyph,
  };
}

/** Hers, or one that arrived from another phone — never a shipped one. The TRANSFER guard is for a synced row. */
export const offeredBothWays = (category: Category): boolean =>
  !category.builtin && category.kind !== CategoryKind.TRANSFER;

/** Half of a category's weight is gone this many days later: a month's habits plus a fortnight. */
const USE_HALF_LIFE = 45;
/** Weight below which a category has not earned the front of the grid — two recent rows, roughly. */
const USE_PROMOTES = 2;

/**
 * How much each category is actually being used, the recent past counting for more. Duplicates and
 * transfer legs are not money moving, and «دسته‌بندی نشده» is the absence of an answer.
 */
export function categoryUseOf(entries: readonly LedgerEntry[], today: number, halfLife = USE_HALF_LIFE): Map<string, number> {
  const use = new Map<string, number>();
  for (const entry of entries) {
    if (entry.duplicate || entry.transfer) continue;
    const id = entry.categoryId;
    if (!id.trim() || id === CAT_UNCATEGORISED) continue;
    // A row dated in the future counts as today rather than as more than today.
    const age = Math.max(today - entry.txn.day, 0);
    use.set(id, (use.get(id) ?? 0) + 2 ** (-age / halfLife));
  }
  return use;
}

/**
 * The categories worth offering for one transaction, the ones she uses first. انتقال, همسر and
 * سایر ignore direction by id, hers ignore it too, and an unknown direction gets everything bar
 * the transfer kind. Only [use] reorders, and سایر and انتقال never move: they are ways out.
 */
export function categoryChoices(
  categories: readonly Category[],
  direction: string | null,
  use: ReadonlyMap<string, number> = new Map(),
): Category[] {
  return categories
    .filter((it) => it.id !== CAT_UNCATEGORISED && !it.archived && (
      it.id === CAT_TRANSFER || it.id === CAT_SPOUSE || it.id === CAT_OTHER ? true
      : offeredBothWays(it) ? true
      : direction === 'in' ? it.kind === CategoryKind.INCOME
      : direction === 'out' ? it.kind === CategoryKind.EXPENSE
      : it.kind !== CategoryKind.TRANSFER
    ))
    // Stable, so everything that has not earned a promotion holds the shipped order.
    .sort((a, b) => promotionOf(b, use) - promotionOf(a, use));
}

function promotionOf(category: Category, use: ReadonlyMap<string, number>): number {
  if (category.id === CAT_OTHER || category.id === CAT_TRANSFER) return 0;
  const weight = use.get(category.id) ?? 0;
  return weight >= USE_PROMOTES ? weight : 0;
}

// ---- rules -----------------------------------------------------------------------------------

export const Priority = { PINNED: 1000, USER_EXACT_MERCHANT: 900, USER_OTHER: 800, SHIPPED: 500 } as const;

/** A label for which kind of evidence matched, not a probability. */
export const Confidence = {
  USER_PINNED: 100, RULE_EXACT: 95, RULE_NARROW: 85, RULE_LIKE: 75,
  BUILTIN_EXACT: 70, BUILTIN_LIKE: 55, CHANNEL_ONLY: 40, NONE: 0,
} as const;

/** Below this a transaction goes to the deck and is asked about exactly once. */
export const REVIEW_BELOW = 70;

const rule = (id: string, priority: number, categoryId: string, p: Partial<Rule> = {}): Rule => ({
  id, priority, categoryId, pMerchantNorm: null, pMerchantLike: null, pAddrKey: null, pBank: null,
  pChannel: null, pDirection: null, pMinRial: null, pMaxRial: null, pInstrument: null,
  enabled: true, builtin: false, originRef: null, createdAt: 0, updatedAt: 0, deleted: false, ...p,
});

/**
 * What the app ships knowing: channels the parser identified outright, landing under the review
 * threshold so each shape is confirmed once and never asked about again.
 */
export const BUILTIN_RULES: readonly Rule[] = [
  rule('rule_atm', Priority.SHIPPED, CAT_CASH, { pChannel: 'atm', builtin: true }),
  rule('rule_fee', Priority.SHIPPED, CAT_FEES, { pChannel: 'fee', builtin: true }),
  rule('rule_bill', Priority.SHIPPED, CAT_BILLS_ID, { pChannel: 'bill', builtin: true }),
  rule('rule_pos', Priority.SHIPPED, CAT_SHOPPING_ID, { pChannel: 'pos', pDirection: 'out', builtin: true }),
  rule('rule_income', Priority.SHIPPED, CAT_INCOME, { pDirection: 'in', builtin: true }),
];

/** How many predicates a rule names. More specific wins a tie on priority. */
export function specificity(r: Rule): number {
  return [r.pMerchantNorm, r.pMerchantLike, r.pAddrKey, r.pBank, r.pChannel, r.pDirection, r.pMinRial, r.pMaxRial, r.pInstrument]
    .filter((p) => p != null).length;
}

/**
 * A conjunction: every predicate that is not null must match. A rule that names a sender does not
 * fire when the sender is unknown — a manual or family row — because "unknown" is not "any".
 */
export function ruleMatches(r: Rule, txn: Txn, addrKey: string | null = null): boolean {
  if (!r.enabled || r.deleted) return false;
  if (r.pMerchantNorm != null && txn.merchantNorm !== r.pMerchantNorm) return false;
  if (r.pMerchantLike != null && !txn.merchantNorm.includes(r.pMerchantLike)) return false;
  if (r.pAddrKey != null && addrKey !== r.pAddrKey) return false;
  if (r.pBank != null && txn.bank !== r.pBank) return false;
  if (r.pChannel != null && txn.channel !== r.pChannel) return false;
  if (r.pDirection != null && txn.direction !== r.pDirection) return false;
  if (r.pInstrument != null && txn.instrument !== r.pInstrument) return false;
  if (r.pMinRial != null && (txn.amountRial == null || txn.amountRial < r.pMinRial)) return false;
  if (r.pMaxRial != null && (txn.amountRial == null || txn.amountRial > r.pMaxRial)) return false;
  return true;
}

/** How a transaction was filed, and whether she still has to look at it. */
export interface TxnClass {
  ref: string;
  categoryId: string;
  ruleId: string | null;
  confidence: number;
  needsReview: boolean;
}

function confidenceOf(r: Rule): number {
  if (r.builtin && r.pMerchantNorm != null) return Confidence.BUILTIN_EXACT;
  if (r.builtin && r.pMerchantLike != null) return Confidence.BUILTIN_LIKE;
  if (r.builtin) return Confidence.CHANNEL_ONLY;
  if (r.pMerchantNorm != null) return Confidence.RULE_EXACT;
  if (r.pMerchantLike != null) return Confidence.RULE_LIKE;
  if (specificity(r) >= 2) return Confidence.RULE_NARROW;
  return Confidence.RULE_LIKE;
}

/**
 * File one transaction. [pinned] is her decision about this exact row and beats everything; then
 * a settled transfer and a Blu box move are bookkeeping; a مانده announcement has nothing to file;
 * otherwise the highest priority wins, then specificity, recency, and id — the last a sync
 * requirement, so two phones never file the same row differently.
 */
export function classify(
  txn: Txn,
  rules: readonly Rule[],
  pinned: string | null = null,
  transferRefs: ReadonlySet<string> = new Set(),
  addrKey: string | null = null,
): TxnClass {
  if (pinned != null) return { ref: txn.ref, categoryId: pinned, ruleId: null, confidence: Confidence.USER_PINNED, needsReview: false };
  if (transferRefs.has(txn.ref)) {
    return { ref: txn.ref, categoryId: CAT_TRANSFER, ruleId: null, confidence: Confidence.USER_PINNED, needsReview: false };
  }
  // Ahead of the rules: a rule she made for Blu's other messages would file the way back out of a box as income.
  if (txn.channel === 'box') {
    return { ref: txn.ref, categoryId: CAT_TRANSFER, ruleId: null, confidence: Confidence.BUILTIN_EXACT, needsReview: false };
  }
  // No amount means no money moved: the row exists for its مانده, and the deck must not ask.
  if (txn.amountRial == null) {
    return { ref: txn.ref, categoryId: CAT_UNCATEGORISED, ruleId: null, confidence: Confidence.NONE, needsReview: false };
  }
  let winner: Rule | null = null;
  for (const r of rules) {
    if (!ruleMatches(r, txn, addrKey)) continue;
    if (!winner || compareRules(r, winner) > 0) winner = r;
  }
  if (!winner) return { ref: txn.ref, categoryId: CAT_UNCATEGORISED, ruleId: null, confidence: Confidence.NONE, needsReview: true };
  const confidence = confidenceOf(winner);
  return { ref: txn.ref, categoryId: winner.categoryId, ruleId: winner.id, confidence, needsReview: confidence < REVIEW_BELOW };
}

function compareRules(a: Rule, b: Rule): number {
  return a.priority - b.priority || specificity(a) - specificity(b) || a.updatedAt - b.updatedAt ||
    (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
}

/**
 * «همیشه» — one row, applied to everything past and future on the next derive. Keyed on the
 * merchant when the message named one; otherwise on the sender and the channel together.
 */
export function ruleFrom(txn: Txn, categoryId: string, addrKey: string, now: number, id = uuid7(now)): Rule {
  const hasMerchant = txn.merchantNorm !== '';
  return rule(id, hasMerchant ? Priority.USER_EXACT_MERCHANT : Priority.USER_OTHER, categoryId, {
    pMerchantNorm: hasMerchant ? txn.merchantNorm : null,
    pAddrKey: hasMerchant ? null : addrKey,
    pBank: hasMerchant ? null : txn.bank,
    pChannel: hasMerchant || txn.channel === 'unknown' ? null : txn.channel,
    pDirection: txn.direction,
    originRef: txn.ref,
    createdAt: now,
    updatedAt: now,
  });
}

// ---- decisions she made ----------------------------------------------------------------------

/**
 * The longest note the app keeps. Two sides agree on it — the field and the sync — or a receiver
 * would truncate somebody else's words and publish the truncation back at them.
 */
export const MAX_NOTE_CHARS = 200;

export const DecisionKind = {
  CATEGORY: 'category', NOTE: 'note', HIDE: 'hide', WORTH_IT: 'worth_it', ACCOUNT: 'account',
  EXCLUDE: 'exclude', INSTALLMENT: 'installment',
} as const;

// ---- seeding ---------------------------------------------------------------------------------

/**
 * Put the shipped categories and rules in place, and keep them current: replaced by id, so a build
 * that renames one takes effect, while a shipped category she renamed or re-marked — told apart by
 * its stored glyph — keeps her name, mark and stamp. Archiving is how a builtin retires.
 */
export function seedBuiltins(now = Date.now()): void {
  const existing = new Map(rows('categories').map((c) => [c.id, c]));
  batch(() => {
    putAll('categories', BUILTIN_CATEGORIES.map((c) => {
      const mine = existing.get(c.id);
      const edited = mine && mine.glyph.trim() ? mine : null;
      return {
        ...c,
        nameFa: edited?.nameFa ?? c.nameFa,
        glyph: edited?.glyph ?? '',
        archived: c.archived || mine?.archived === true,
        // An edit keeps its own stamp: the household compares it, and one renewed every launch
        // would outrank a newer edit from another phone.
        updatedAt: edited?.updatedAt ?? now,
      };
    }));
    putAll('rules', BUILTIN_RULES.map((r) => ({ ...r, createdAt: now, updatedAt: now })));
  });
}

let seeding: Promise<void> | null = null;
/** Seeds once per session, after the store has loaded — seeding before would put back her renames. */
export function ensureSeeded(): Promise<void> {
  return (seeding ??= load().then(() => seedBuiltins()));
}
