/**
 * Her corrections to a row — its figure, its day, whose it was — and a row broken into parts.
 * Edits.kt, line for line: all four are decisions, laid over the row where the ledger is read
 * (`ledgerView`), never where it is derived, so the balance still reads the bank's own مانده.
 */
import { DAY_MS, tehranDayStart } from './jalali';
import { CAT_TRANSFER } from './rules';
import { MAX_PLAUSIBLE_RIAL } from './sms';
import type { LedgerEntry, SplitPart, Txn } from './model';

/** The most parts a row is broken into. A receipt, not a ledger inside a ledger. */
export const MAX_SPLIT_PARTS = 8;

export type SplitSpec = Array<[string, number]>;

/**
 * A split value read back: `cat_a:1200000,cat_b:300000`, Rial per part. Refused whole — an empty
 * list, so the row reads as one — for fewer than two parts, a part of nothing, one category twice,
 * or انتقال, which is not a spending answer and would take the row out of every total.
 */
export function parseSplit(value: string | null | undefined): SplitSpec {
  if (!value?.trim()) return [];
  const parts: SplitSpec = [];
  for (const piece of value.split(',')) {
    const cut = piece.lastIndexOf(':');
    const rial = cut > 0 && /^-?\d+$/.test(piece.slice(cut + 1)) ? Number(piece.slice(cut + 1)) : NaN;
    if (!Number.isSafeInteger(rial)) return [];
    parts.push([piece.slice(0, cut), rial]);
  }
  const valid = parts.length >= 2 && parts.length <= MAX_SPLIT_PARTS &&
    parts.every(([id, rial]) => id.trim() !== '' && id !== CAT_TRANSFER && rial >= 1 && rial <= MAX_PLAUSIBLE_RIAL) &&
    new Set(parts.map(([id]) => id)).size === parts.length;
  return valid ? parts : [];
}

export const splitValue = (parts: SplitSpec): string => parts.map(([id, rial]) => `${id}:${rial}`).join(',');

/** The biggest part, which is what the row is filed as for everything that reads one category. */
export const splitLead = (parts: SplitSpec): string | null =>
  parts.reduce<[string, number] | null>((best, p) => (!best || p[1] > best[1] ? p : best), null)?.[0] ?? null;

/** The parts, named — or none, when the split no longer adds up to the row's figure. */
export function splitOf(value: string | null | undefined, amountRial: number | null, names: ReadonlyMap<string, string>): SplitPart[] {
  const parts = parseSplit(value);
  if (!parts.length || amountRial == null || parts.reduce((sum, [, r]) => sum + r, 0) !== amountRial) return [];
  return parts.map(([categoryId, rial]) => ({ categoryId, categoryFa: names.get(categoryId) ?? 'دسته‌بندی نشده', rial }));
}

/** The row as she corrected it, keeping its minute and its direction. */
export function editedTxn(txn: Txn, amountRial: number | null, day: number | null): Txn {
  let edited = txn;
  if (amountRial != null && amountRial > 0 && txn.amountRial != null) {
    edited = { ...edited, amountRial, signedRial: txn.signedRial == null ? null : txn.signedRial < 0 ? -amountRial : amountRial };
  }
  if (day != null && day !== txn.day) {
    const minute = Math.min(Math.max(txn.at - tehranDayStart(txn.day), 0), DAY_MS - 1);
    edited = { ...edited, day, at: tehranDayStart(day) + minute };
  }
  return edited;
}

/** One entry per part, each filed and sized as its part — what `spendable` hands every total. */
export function splitParts(entry: LedgerEntry): LedgerEntry[] {
  if (!entry.split?.length) return [entry];
  return entry.split.map((part) => ({
    ...entry,
    categoryId: part.categoryId,
    categoryFa: part.categoryFa,
    txn: {
      ...entry.txn,
      amountRial: part.rial,
      signedRial: entry.txn.signedRial == null ? null : entry.txn.signedRial < 0 ? -part.rial : part.rial,
    },
    split: [],
  }));
}

/** Whether this device may correct the row: its own, typed or pasted. Another member's is theirs. */
export const editable = (entry: LedgerEntry): boolean =>
  (entry.txn.ref.startsWith('m:') || entry.txn.ref.startsWith('s:')) && entry.txn.amountRial != null;

/** Whether the row can be broken into parts: any row that moved money and is not a transfer. */
export const splittable = (entry: LedgerEntry): boolean =>
  !entry.duplicate && !entry.transfer && (entry.txn.amountRial ?? 0) > 1;
