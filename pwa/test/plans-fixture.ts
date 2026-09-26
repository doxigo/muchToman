/** The Kotlin tests' `entry(...)` fixture: one ledger row, every field the phone's has. */
import { tehranDayStart } from '../src/jalali';
import type { Goal, LedgerEntry } from '../src/model';

let n = 0;

export interface EntryOptions {
  category?: string;
  categoryId?: string;
  transfer?: boolean;
  duplicate?: boolean;
  review?: boolean;
  /** LedgerEntry.ownerMemberId — blank is «this device's», as the ledger fills it in. */
  owner?: string;
  ownerName?: string;
  /** Txn.ownerMemberId — blank exactly on rows read on this device. */
  txnOwner?: string;
  merchant?: string;
  direction?: 'in' | 'out';
  sharedWithFamily?: boolean;
}

export function entry(day: number, signed: number | null, o: EntryOptions = {}): LedgerEntry {
  n++;
  const ref = `s:${String(n).padStart(4, '0')}:0`;
  const merchant = o.merchant ?? '';
  return {
    txn: {
      ref, srcHash: ref, seq: 0, at: tehranDayStart(day), day,
      bank: 'SAMAN', accountId: 'SAMAN',
      direction: o.direction ?? (signed == null ? null : signed > 0 ? 'in' : 'out'),
      amountRial: signed == null ? null : Math.abs(signed), signedRial: signed,
      balanceRial: null, feeRial: null, mask: '', instrument: 'unknown',
      merchant, merchantNorm: merchant, refNo: '', printedAt: '',
      channel: 'unknown', unitPrinted: 'none', inferred: false,
      familyRef: '', ownerMemberId: o.txnOwner ?? '', sourceKind: 'sms',
    },
    categoryId: o.categoryId ?? 'cat_x',
    categoryFa: o.category ?? 'خرید',
    confidence: o.review ? 40 : 95,
    needsReview: o.review ?? false,
    duplicate: o.duplicate ?? false,
    transfer: o.transfer ?? false,
    ownerMemberId: o.owner ?? '',
    ownerName: o.ownerName ?? '',
    ownerAvatar: '',
    categoryEditorName: '',
    note: '',
    noteAuthorName: '',
    sharedWithFamily: o.sharedWithFamily ?? true,
  };
}

/** A goal row with the Kotlin constructor's defaults. */
export function goalRow(fields: Partial<Goal> & Pick<Goal, 'id' | 'nameFa' | 'targetRial' | 'kind' | 'period' | 'startsOn'>): Goal {
  return {
    categoryId: null, endsOn: null, createdAt: 0, updatedAt: 0, deleted: false, shared: false,
    ownerMemberId: '', editedByMemberId: '',
    ...fields,
  };
}
