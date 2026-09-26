/**
 * «خودکار برای همه» — filing the whole review deck in one gesture (Filing.kt `autoFilePlan`),
 * worked out before anything is written. The notification half of Filing.kt has no browser twin.
 */
import { CAT_OTHER, CAT_SHOPPING_ID, CAT_UNCATEGORISED } from './rules';
import type { LedgerEntry } from './model';

export interface AutoFilePlan {
  /** Each card in the deck, paired with where it goes. */
  assignments: Array<[LedgerEntry, string]>;
  /** How many keep the category the rules already suggested. */
  suggested: number;
  /** How many withdrawals fall back to «خرید روزانه». */
  shopping: number;
  /** How many land in «سایر» because nothing else is known. */
  other: number;
  total: number;
}

/**
 * Three buckets, in order of how much the app knows: a row the rules guessed at keeps the guess, a
 * withdrawal nothing matched goes to «خرید روزانه», and anything else — money in with no rule, a
 * message with no direction — to «سایر». Each is an ordinary pinned decision, refilable by hand.
 */
export function autoFilePlan(review: readonly LedgerEntry[]): AutoFilePlan {
  const plan: AutoFilePlan = { assignments: [], suggested: 0, shopping: 0, other: 0, total: review.length };
  for (const entry of review) {
    let category: string;
    if (entry.categoryId !== CAT_UNCATEGORISED) { category = entry.categoryId; plan.suggested++; }
    else if (entry.txn.direction === 'out') { category = CAT_SHOPPING_ID; plan.shopping++; }
    else { category = CAT_OTHER; plan.other++; }
    plan.assignments.push([entry, category]);
  }
  return plan;
}
