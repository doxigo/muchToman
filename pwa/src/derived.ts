/**
 * Everything between the stored rows and the `LedgerEntry[]` every screen reads — Derived.kt,
 * with derived.db replaced by memory.
 *
 * The phone keeps a second database of parsed rows because it has thousands of messages and a
 * launch to survive. The browser has a few hundred pasted ones, so the whole derive — read every
 * message, link, classify, name — is one pure function run again whenever anything changes. That
 * keeps the phone's best property for free: nothing downstream can disagree with the rows it was
 * derived from, because there is no stored copy to drift.
 *
 * ponytail: full re-derive on every data version. Cache parseToRows per source id if a profile
 * ever shows it; a household's paste history is milliseconds today.
 */
import { useEffect, useState } from 'preact/hooks';
import { jalaliMonthStart, tehranDay } from './jalali';
import { findLinks, hiddenRefs, transferRefs } from './links';
import type { LinkCandidate } from './links';
import { CAT_TRANSFER, CAT_UNCATEGORISED, Confidence, DecisionKind, categoryUseOf, classify } from './rules';
import type { TxnClass } from './rules';
import { BANKS, MAX_PLAUSIBLE_RIAL, bankFa, isBank, merchantNorm, parseToRows, sha256Hex } from './sms';
import { dataVersion, pref, rows, useData } from './state';
import type {
  BalanceAnchor, Category, Decision, FamilyMember, FamilyTxn, LedgerEntry, LinkDecision, ManualTxn, Rule, Source, Txn,
} from './model';

export const manualRef = (id: string): string => `m:${id}`;

const localRefs = new Map<string, string>();
/** The compact local ref of a row another member published: `f:<sha256(familyRef)>`, memoised. */
export function familyLocalRef(familyRef: string): string {
  let ref = localRefs.get(familyRef);
  if (ref == null) localRefs.set(familyRef, (ref = `f:${sha256Hex(familyRef)}`));
  return ref;
}

/** A hand-entered transaction, in the same shape as one read from a message. */
export function manualToRow(row: ManualTxn): Txn {
  return {
    ref: manualRef(row.id),
    // No message behind it, so the id stands in — nothing downstream reads this but to look a body up.
    srcHash: row.id,
    seq: 0,
    at: row.at,
    day: row.day,
    bank: 'MANUAL',
    accountId: row.accountId ?? 'MANUAL',
    direction: row.amountRial > 0 ? 'in' : 'out',
    amountRial: Math.abs(row.amountRial),
    signedRial: row.amountRial,
    balanceRial: null,
    feeRial: null,
    mask: '',
    instrument: 'unknown',
    merchant: row.merchant,
    merchantNorm: merchantNorm(row.merchant),
    refNo: '',
    printedAt: '',
    channel: 'unknown',
    unitPrinted: 'none',
    inferred: false,
    familyRef: '',
    ownerMemberId: '',
    sourceKind: 'manual',
  };
}

/** A row another member published, without pretending it was entered by hand on this browser. */
export function familyToRow(row: FamilyTxn): Txn {
  return {
    ...manualToRow({
      id: row.id, at: row.at, day: row.day, amountRial: row.amountRial, accountId: null, categoryId: null,
      merchant: row.merchant, note: '', createdAt: 0, updatedAt: 0, deleted: false,
    }),
    ref: familyLocalRef(row.id),
    bank: row.bank,
    accountId: `family:${row.ownerMemberId}:${row.bank}`,
    familyRef: row.id,
    ownerMemberId: row.ownerMemberId,
    sourceKind: row.sourceKind,
  };
}

/** The ledger from [startsOn] on — every entry when that is 0, which is «از اول». */
export function startingFrom(entries: LedgerEntry[], startsOn: number): LedgerEntry[] {
  return startsOn <= 0 ? entries : entries.filter((e) => e.txn.day >= startsOn);
}

/** How many entries each Jalali month holds, keyed by the month's first day, oldest first. */
export function monthCounts(entries: readonly LedgerEntry[]): Array<[number, number]> {
  const counts = new Map<number, number>();
  for (const e of entries) {
    const month = jalaliMonthStart(e.txn.day);
    counts.set(month, (counts.get(month) ?? 0) + 1);
  }
  return [...counts].sort((a, b) => a[0] - b[0]);
}

export interface LedgerHealth {
  oldestDay: number | null;
  transactionCount: number;
  sourceCount: number;
  oldestSourceAt: number | null;
  lastIngestAt: number | null;
  derivedAt: number | null;
  deriveMs: number | null;
  /** The day the ledger is read from, as this view was read with it — 0 for all of it. */
  startsOn: number;
  /** How many entries that start leaves out of every screen. */
  setAside: number;
  /** The whole ledger per month, the start notwithstanding — what the start picker offers. */
  months: Array<[number, number]>;
}

// ---- balances --------------------------------------------------------------------------------

/** An account and what it holds, as the ledger works it out. */
export interface DerivedBalance {
  accountId: string;
  rial: number;
  at: number;
  /** False when nothing ever stated a balance, so it is a running sum and not a figure. */
  anchored: boolean;
}

const byTime = (a: Txn, b: Txn): number => a.at - b.at || (a.ref < b.ref ? -1 : a.ref > b.ref ? 1 : 0);

/**
 * What an account holds, worked out rather than accumulated. The newest evidence wins, a stated
 * balance or a figure she typed alike; the bank takes a tie, because its figure is arithmetic and
 * hers is a memory. Only what came strictly after the anchor is added on top of it.
 */
export function deriveBalance(accountId: string, transactions: readonly Txn[], typed: BalanceAnchor | null): DerivedBalance | null {
  if (!transactions.length && !typed) return null;
  const ordered = [...transactions].sort(byTime);
  const newestAt = Math.max(ordered.at(-1)?.at ?? 0, typed?.at ?? 0);
  const statedRow = [...ordered].reverse().find((t) => t.balanceRial != null);
  const stated = statedRow ? { rial: statedRow.balanceRial!, at: statedRow.at, ref: statedRow.ref } : null;
  const hers = typed ? { rial: typed.balanceRial, at: typed.at, ref: '' } : null;
  const anchor = stated && hers ? (hers.at > stated.at ? hers : stated) : (stated ?? hers);
  // Nothing ever stated a figure: the change since reading began, flagged and kept out of every total.
  if (!anchor) return { accountId, rial: ordered.reduce((sum, t) => sum + (t.signedRial ?? 0), 0), at: newestAt, anchored: false };
  const since = ordered
    .filter((t) => t.at > anchor.at || (t.at === anchor.at && t.ref > anchor.ref))
    .reduce((sum, t) => sum + (t.signedRial ?? 0), 0);
  return { accountId, rial: anchor.rial + since, at: newestAt, anchored: true };
}

/** One bank's balance, as the accounts sheet shows it. */
export interface BankAccountView {
  bank: string;
  bankFa: string;
  /** Whichever message named the account most recently — a label only, never a key. */
  mask: string;
  balanceRial: number;
  updatedAt: number;
  anchored: boolean;
  inferred: boolean;
  /** Whether the figure can be shown as fact rather than as something to check. */
  trusted: boolean;
  /** Switched out of the total, still tracked and listed. */
  disabled: boolean;
}

/** A stated balance under this is pocket money; over it, a hundred-fold jump stops being believable. */
const SPOOF_FLOOR_RIAL = 1_000_000_000;

type Account = Omit<BankAccountView, 'bankFa' | 'trusted' | 'disabled'>;

/**
 * Sms.kt `applyBankSms` behind Data.kt `foldBankSms`'s gates, over one message. The bank's own مانده
 * replaces what was held and anchors it; a message carrying none accumulates; nothing older than
 * what is known may change it. Refused outright: a figure past the plausibility bound, and one
 * stated balance claiming over a hundred times a known anchored one past pocket-money size —
 * likelier a misread figure than a windfall, and a real windfall keeps stating itself.
 */
function foldBankSms(existing: Account | undefined, t: Txn): Account | undefined {
  const delta = t.signedRial;
  if (t.balanceRial != null && Math.abs(t.balanceRial) > MAX_PLAUSIBLE_RIAL) return existing;
  if (delta != null && Math.abs(delta) > MAX_PLAUSIBLE_RIAL) return existing;
  if (t.balanceRial != null && existing?.anchored && existing.balanceRial > 0 &&
    t.balanceRial > existing.balanceRial * 100 && t.balanceRial > SPOOF_FLOOR_RIAL) return existing;
  if (existing && t.at < existing.updatedAt) return existing;
  const mask = t.mask.trim() ? t.mask : existing?.mask ?? '';
  if (t.balanceRial != null) {
    return { bank: t.bank, mask, balanceRial: t.balanceRial, updatedAt: t.at, inferred: t.inferred, anchored: true };
  }
  if (delta == null) return existing;
  return {
    bank: t.bank, mask,
    balanceRial: (existing?.balanceRial ?? 0) + delta,
    updatedAt: Math.max(t.at, existing?.updatedAt ?? 0),
    // Accumulating never un-marks a guess, and never turns a running total into an anchored one.
    inferred: t.inferred || existing?.inferred === true,
    anchored: existing?.anchored === true,
  };
}

/** The id of the tombstone [forgetAccount] leaves: nothing at or before its `at` counts for that bank. */
export const forgetMarkId = (bank: string): string => `forget:${bank}`;

/**
 * One account per bank, from the pasted messages and the figures she typed — the phone's prefs
 * fold, rebuilt from scratch each time rather than carried, since here the messages are all kept.
 *
 * Folded oldest first, a typed figure ahead of a message at the same millisecond so the bank takes
 * the tie as it does in [deriveBalance]. The later leg of a settled duplicate is left out: pasting
 * one message twice must not count its money twice, which the phone's seen-set guarantees there.
 * A bank she never named (OTHER) is not an account — it cannot be named on the sheet.
 */
export function bankAccountsOf(
  txns: readonly Txn[],
  anchors: readonly BalanceAnchor[],
  disabledBanks: readonly string[],
  hidden: ReadonlySet<string> = new Set(),
): BankAccountView[] {
  const floors = new Map(anchors.filter((a) => a.id === forgetMarkId(a.accountId)).map((a) => [a.accountId, a.at]));
  type Event = { at: number; order: number; txn?: Txn; anchor?: BalanceAnchor; bank: string };
  const events = [
    ...anchors.filter((a) => !a.deleted).map((a): Event => ({ at: a.at, order: 0, anchor: a, bank: a.accountId })),
    ...txns.filter((t) => t.sourceKind === 'sms' && t.ref.startsWith('s:') && !hidden.has(t.ref))
      .map((t): Event => ({ at: t.at, order: 1, txn: t, bank: t.bank })),
  ].filter((e) => isBank(e.bank) && e.bank !== 'OTHER' && e.at > (floors.get(e.bank) ?? -Infinity))
    .sort((a, b) => a.at - b.at || a.order - b.order || ((a.txn?.ref ?? '') < (b.txn?.ref ?? '') ? -1 : 1));
  const accounts = new Map<string, Account>();
  for (const e of events) {
    const existing = accounts.get(e.bank);
    // Her figure makes an account even where no message has yet: the ledger's allBalances reads it so.
    const next = e.anchor
      ? { bank: e.bank, mask: existing?.mask ?? '', balanceRial: e.anchor.balanceRial, updatedAt: e.anchor.at, inferred: false, anchored: true }
      : foldBankSms(existing, e.txn!);
    if (next) accounts.set(e.bank, next);
  }
  const disabled = new Set(disabledBanks);
  return [...accounts.values()]
    .map((a) => ({ ...a, bankFa: bankFa(a.bank), trusted: a.anchored && !a.inferred, disabled: disabled.has(a.bank) }))
    .sort((a, b) => bankOrder(a.bank) - bankOrder(b.bank));
}
const BANK_ORDER = new Map(BANKS.map((b, i) => [b.name, i]));
const bankOrder = (name: string): number => BANK_ORDER.get(name) ?? BANKS.length;

/**
 * What the tracked accounts add up to: the banks she has switched on, and only those a balance was
 * ever stated for. An unanchored account is a running sum, shown and left out, as an unpriced asset is.
 */
export function bankTotal(accounts: readonly BankAccountView[]): number {
  return accounts.filter((a) => a.anchored && !a.disabled).reduce((sum, a) => sum + a.balanceRial, 0);
}

// ---- the derive ------------------------------------------------------------------------------

export interface DeriveInput {
  sources: readonly Source[];
  manual: readonly ManualTxn[];
  familyTxns: readonly FamilyTxn[];
  familyMembers: readonly FamilyMember[];
  decisions: readonly Decision[];
  links: readonly LinkDecision[];
  categories: readonly Category[];
  rules: readonly Rule[];
  anchors: readonly BalanceAnchor[];
  /** This browser's member id in its household, blank until it pairs. */
  mineId: string;
  sharesSms: boolean;
  excludedBanks: readonly string[];
  disabledBanks: readonly string[];
  now: number;
}

/** Everything the ledger screens need, read in one pass (Derived.kt `LedgerView`). */
export interface LedgerView {
  entries: LedgerEntry[];
  /** The picker's list: live categories, by sort then name. */
  categories: Category[];
  /** Every category, archived ones included — what names a row and what the manager lists. */
  managedCategories: Category[];
  /** nameFa → the `CategoryGlyph` name she picked, archived categories included; the icons map the name. */
  marks: Record<string, string>;
  mineId: string;
  /** Everything she has to answer, biggest first. Uncapped: this is the deck and the badge. */
  review: LedgerEntry[];
  /** What she files under, for [categoryChoices] to order the picker. */
  categoryUse: Map<string, number>;
  health: LedgerHealth;
  bankAccounts: BankAccountView[];
  bankTotalRial: number;
  /** The whole ledger, the start notwithstanding — goals and installments read money that exists. */
  allEntries: LedgerEntry[];
  /** Every link candidate, for the transaction page and the refile that rejects a transfer. */
  links: LinkCandidate[];
}

const bySortThenName = (a: Category, b: Category): number =>
  a.sort - b.sort || (a.nameFa < b.nameFa ? -1 : a.nameFa > b.nameFa ? 1 : 0);

/** The ledger, from the stored rows — derive() and ledgerEntries() in one, pure. */
export function ledgerView(input: DeriveInput, startsOn = 0): LedgerView {
  const started = performance.now();
  const { mineId, now } = input;
  const ofKind = (kind: string): Decision[] => input.decisions.filter((d) => d.kind === kind && !d.deleted);

  // A message she deleted stays stored — evidence is kept — and the row it derives is dropped here,
  // at the one gate every screen, total, link and family publication reads through.
  const hiddenByHer = new Set(ofKind(DecisionKind.HIDE).map((d) => d.ref));
  const pinned = new Map(ofKind(DecisionKind.CATEGORY).filter((d) => d.value != null).map((d) => [d.ref, d.value!]));
  // The sender key a sender-keyed rule holds a row against: for a pasted message, the bank she picked.
  const addrKeys = new Map(input.sources.map((s) => [s.id, s.bank]));
  const rules = input.rules.filter((r) => !r.deleted);

  const family = input.familyTxns.filter((f) => !f.deleted);
  const all: Txn[] = [
    ...input.sources.flatMap((s) => parseToRows(s, now)).filter((t) => !hiddenByHer.has(t.ref)),
    ...input.manual.filter((m) => !m.deleted).map(manualToRow).filter((t) => !hiddenByHer.has(t.ref)),
    ...family.map(familyToRow),
  ];
  const links = findLinks(all, input.links);
  const transfers = transferRefs(links);
  for (const f of family) if (f.transfer) transfers.add(familyLocalRef(f.id));
  const classes = new Map<string, TxnClass>(
    all.map((t) => [t.ref, classify(t, rules, pinned.get(t.ref) ?? null, transfers, addrKeys.get(t.srcHash) ?? null)]),
  );

  const everyCategory = [...input.categories].sort(bySortThenName);
  const names = new Map(everyCategory.map((c) => [c.id, c.nameFa]));
  const members = new Map(input.familyMembers.filter((m) => !m.deleted).map((m) => [m.id, m]));
  const editors = new Map(ofKind(DecisionKind.CATEGORY).map((d) => [d.ref, d.memberId]));
  const notes = new Map(ofKind(DecisionKind.NOTE).filter((d) => d.value?.trim()).map((d) => [d.ref, d]));
  const hidden = hiddenRefs(links);
  const excluded = new Set(input.excludedBanks);

  const entries = [...all].sort((a, b) => -byTime(a, b)).map((txn): LedgerEntry => {
    const filed = classes.get(txn.ref);
    const owner = txn.ownerMemberId || mineId;
    const note = notes.get(txn.ref);
    return {
      txn,
      categoryId: filed?.categoryId ?? CAT_UNCATEGORISED,
      categoryFa: names.get(filed?.categoryId ?? '') ?? 'دسته‌بندی نشده',
      confidence: filed?.confidence ?? Confidence.NONE,
      needsReview: filed?.needsReview ?? true,
      duplicate: hidden.has(txn.ref),
      // A detector's pair or her own answer: the same flag to every total.
      transfer: transfers.has(txn.ref) || filed?.categoryId === CAT_TRANSFER,
      ownerMemberId: owner,
      ownerName: members.get(owner)?.name ?? '',
      ownerAvatar: members.get(owner)?.avatar ?? '',
      categoryEditorName: members.get(editors.get(txn.ref) ?? '')?.name ?? '',
      note: note?.value ?? '',
      // Somebody else's words on her row are not hers, and must not read as if they were.
      noteAuthorName: note && note.memberId && note.memberId !== mineId ? members.get(note.memberId)?.name ?? '' : '',
      sharedWithFamily: txn.familyRef !== '' ||
        (mineId !== '' && !excluded.has(txn.bank) && (txn.sourceKind !== 'sms' || input.sharesSms)),
    };
  });

  // Set aside here, at the view, and nowhere earlier: every balance still reads every message.
  const kept = startingFrom(entries, startsOn);
  const bankAccounts = bankAccountsOf(all, input.anchors, input.disabledBanks, hidden);
  const marks: Record<string, string> = {};
  for (const c of everyCategory) if (c.glyph.trim()) marks[c.nameFa] = c.glyph;
  const least = (xs: number[]): number | null => (xs.length ? xs.reduce((a, b) => Math.min(a, b)) : null);
  const most = (xs: number[]): number | null => (xs.length ? xs.reduce((a, b) => Math.max(a, b)) : null);
  return {
    entries: kept,
    categories: everyCategory.filter((c) => !c.archived),
    managedCategories: everyCategory,
    marks,
    mineId,
    review: kept.filter((e) => e.needsReview && !e.duplicate && !e.transfer)
      .sort((a, b) => (b.txn.amountRial ?? 0) - (a.txn.amountRial ?? 0)),
    categoryUse: categoryUseOf(kept, tehranDay(now)),
    health: {
      oldestDay: least(kept.map((e) => e.txn.day)),
      transactionCount: kept.length,
      sourceCount: input.sources.length,
      oldestSourceAt: least(input.sources.map((s) => s.at)),
      lastIngestAt: most(input.sources.map((s) => s.ingestedAt)),
      derivedAt: now,
      deriveMs: Math.round(performance.now() - started),
      startsOn,
      setAside: entries.length - kept.length,
      months: monthCounts(entries),
    },
    bankAccounts,
    bankTotalRial: bankTotal(bankAccounts),
    allEntries: entries,
    links,
  };
}

// ---- the store-backed view -------------------------------------------------------------------

let session = { mineId: '', sharesSms: false, excludedBanks: [] as readonly string[] };
let sessionVersion = 0;
const sessionListeners = new Set<() => void>();

/** Who this browser is in its household — set by the sync once it pairs. Blank until then. */
export function setMineId(mineId: string, sharesSms = false, excludedBanks: readonly string[] = []): void {
  session = { mineId, sharesSms, excludedBanks };
  sessionVersion++;
  for (const l of sessionListeners) l();
}
export const mineId = (): string => session.mineId;

let memo: { key: string; view: LedgerView } | null = null;

/** The ledger as the store holds it now, derived once per data version and shared by every reader. */
export function ledger(): LedgerView {
  const now = Date.now();
  const key = `${dataVersion()}:${sessionVersion}:${tehranDay(now)}`;
  if (memo?.key === key) return memo.view;
  const view = ledgerView({
    sources: rows('sources'),
    manual: rows('manual'),
    familyTxns: rows('familyTxns'),
    familyMembers: rows('familyMembers'),
    decisions: rows('decisions'),
    links: rows('links'),
    categories: rows('categories'),
    rules: rows('rules'),
    anchors: rows('anchors'),
    ...session,
    disabledBanks: pref('disabledBanks'),
    now,
  }, pref('ledgerStartsOn'));
  memo = { key, view };
  return view;
}

/** The ledger, re-rendering the caller whenever the store or the household changes. */
export function useLedger(): LedgerView {
  useData();
  const [, set] = useState(sessionVersion);
  useEffect(() => {
    const listener = () => set(sessionVersion);
    sessionListeners.add(listener);
    return () => { sessionListeners.delete(listener); };
  }, []);
  return ledger();
}

export const bankAccountsView = (): BankAccountView[] => ledger().bankAccounts;
