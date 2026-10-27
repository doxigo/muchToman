/**
 * «خودکار برای همه» — filing the whole review deck in one gesture (Filing.kt `autoFilePlan`),
 * worked out before anything is written. The notification half of Filing.kt has no browser twin.
 */
import { CAT_OTHER, CAT_UNCATEGORISED } from './rules';
import type { LedgerEntry } from './model';

export interface AutoFilePlan {
  /** Each card in the deck, paired with where it goes. */
  assignments: Array<[LedgerEntry, string]>;
  /** How many keep the category the rules already suggested. */
  suggested: number;
  /** How many land in «سایر» because nothing else is known. */
  other: number;
  total: number;
}

/**
 * Two buckets: a row the rules suggested keeps the suggestion, and everything else goes to «سایر».
 * A withdrawal nothing matched is not a daily purchase just because most spending is. Each is an
 * ordinary pinned decision, refilable by hand.
 */
export function autoFilePlan(review: readonly LedgerEntry[]): AutoFilePlan {
  const assignments = review.map((entry): [LedgerEntry, string] =>
    [entry, entry.categoryId !== CAT_UNCATEGORISED ? entry.categoryId : CAT_OTHER]);
  const suggested = review.filter((e) => e.categoryId !== CAT_UNCATEGORISED).length;
  return { assignments, suggested, other: review.length - suggested, total: review.length };
}
