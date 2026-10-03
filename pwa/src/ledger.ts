/**
 * What she does to the ledger — MainActivity.kt's AppVm, minus the view model. Every action writes
 * through state.ts and the ledger re-derives on the next read; there is no derive to call.
 *
 * Every answer the household can see carries this browser's member id, and every stamp is
 * `max(now, previous + 1)`: a hand-set clock behind the other phone's must not lose her edit to
 * last-write-wins.
 */
import { editable, parseSplit, splitLead, splitValue } from './edits';
import type { SplitSpec } from './edits';
import { tehranDay } from './jalali';
import { LinkKind, Verdict, linkDecision } from './links';
import { CAT_TRANSFER, DecisionKind, MAX_NOTE_CHARS, customCategory, ruleFrom } from './rules';
import { MAX_PLAUSIBLE_RIAL } from './sms';
import { forgetMarkId, ledger, manualRef, mineId } from './derived';
import { bodyToStore, severalMessages } from './paste';
import type { Bundled } from './paste';
import { pastedMoment, sourceId } from './sms';
import { batch, pref, put, row, rows, setPref } from './state';
import { familyTxnId, uuid7 } from './sync';
import type { Category, CategoryKindId, Decision, LedgerEntry, Source, Txn } from './model';

const stamp = (previous: { updatedAt: number } | undefined, now = Date.now()): number =>
  Math.max(now, (previous?.updatedAt ?? 0) + 1);

/** The row's household id — its own when it came from another member, else the one this browser publishes it under. */
function familyRefOf(txn: Txn): string {
  const mine = mineId();
  return txn.familyRef || (mine ? familyTxnId(mine, txn.ref) : '');
}

function decision(ref: string, kind: Decision['kind'], value: string | null, at: number, familyRef: string, previous?: Decision): Decision {
  return {
    id: `${kind}:${ref}`, ref, kind, value, createdAt: previous?.createdAt ?? at, updatedAt: at,
    deleted: false, memberId: mineId(), familyRef,
  };
}

/** The sender a sender-keyed rule would hold: for a pasted message, the bank she picked; nothing otherwise. */
const addrKeyOf = (txn: Txn): string => row('sources', txn.srcHash)?.bank ?? '';

/**
 * File one transaction, and — «همیشه» — everything like it, past and future: one rule and the next
 * derive is the whole mechanism. Filing a transfer leg under a real category is her saying the
 * detector was wrong, so the pair dies; filing it under انتقال says it was right.
 */
export function categorise(entry: LedgerEntry, categoryId: string, learnSimilar: boolean): void {
  const ref = entry.txn.ref;
  const previous = row('decisions', `${DecisionKind.CATEGORY}:${ref}`);
  const now = stamp(previous);
  batch(() => {
    if (entry.transfer && categoryId !== CAT_TRANSFER) {
      for (const link of ledger().links) {
        if (link.kind !== LinkKind.TRANSFER || !link.auto || (link.aRef !== ref && link.bRef !== ref)) continue;
        put('links', linkDecision(link.aRef, link.bRef, LinkKind.TRANSFER, Verdict.REJECTED, now));
      }
    }
    // A fresh answer, not an edit of the old one: the phone REPLACEs on (ref, kind).
    put('decisions', decision(ref, DecisionKind.CATEGORY, categoryId, now, familyRefOf(entry.txn)));
    // One category is the row whole again.
    answer(entry.txn, DecisionKind.SPLIT, null);
    if (learnSimilar) put('rules', ruleFrom(entry.txn, categoryId, addrKeyOf(entry.txn), now));
  });
}

/**
 * «برای موارد مشابه» switched on a row already filed: its category is taught as a rule now, as a
 * pick with the switch on would. Off takes back every rule taught from this row.
 */
export function learnSimilarNow(entry: LedgerEntry, on: boolean): void {
  if (on) return categorise(entry, entry.categoryId, true);
  const now = Date.now();
  batch(() => {
    for (const rule of rows('rules')) {
      if (rule.originRef === entry.txn.ref && !rule.deleted) put('rules', { ...rule, deleted: true, updatedAt: Math.max(now, rule.updatedAt + 1) });
    }
  });
}

/** File the whole backlog at once — see `autoFilePlan`. Every row an ordinary pinned decision. */
export function categoriseAll(assignments: ReadonlyArray<readonly [LedgerEntry, string]>): void {
  if (!assignments.length) return;
  let at = Date.now();
  batch(() => {
    for (const [entry, categoryId] of assignments) {
      const previous = row('decisions', `${DecisionKind.CATEGORY}:${entry.txn.ref}`);
      at = Math.max(at + 1, (previous?.updatedAt ?? 0) + 1);
      put('decisions', decision(entry.txn.ref, DecisionKind.CATEGORY, categoryId, at, familyRefOf(entry.txn), previous));
    }
  });
}

/**
 * Her note on one transaction; blank takes it back. It lands on the one (ref, kind) row even when
 * that row was cleared, so a note taken back and written again cannot race itself.
 */
export function setNote(entry: LedgerEntry, text: string): void {
  const clean = text.trim().slice(0, MAX_NOTE_CHARS);
  const previous = row('decisions', `${DecisionKind.NOTE}:${entry.txn.ref}`);
  if (!previous && !clean) return;
  if (previous && (previous.value ?? '') === clean) return;
  const now = stamp(previous);
  put('decisions', { ...decision(entry.txn.ref, DecisionKind.NOTE, clean, now, familyRefOf(entry.txn), previous), deleted: !clean });
}

/**
 * A transaction she typed in herself. The row and its answers land together: the category an
 * ordinary pinned decision on `m:<id>`, the note on the same rail as any other row's.
 */
export function addManualTxn(signedRial: number, categoryId: string | null, merchant: string, note: string, at: number): void {
  if (signedRial === 0) return;
  const now = Date.now();
  const id = uuid7(now);
  const ref = manualRef(id);
  const mine = mineId();
  const familyRef = mine ? familyTxnId(mine, ref) : '';
  batch(() => {
    put('manual', {
      id, at, day: tehranDay(at), amountRial: signedRial, accountId: null, categoryId,
      merchant: merchant.trim().slice(0, 60), note: '', createdAt: now, updatedAt: now, deleted: false,
    });
    if (categoryId != null) put('decisions', decision(ref, DecisionKind.CATEGORY, categoryId, now, familyRef));
    const cleanNote = note.trim().slice(0, MAX_NOTE_CHARS);
    if (cleanNote) put('decisions', decision(ref, DecisionKind.NOTE, cleanNote, now, familyRef));
  });
}

/**
 * Takes back a row of her own. A typed one is tombstoned; a pasted one cannot be — the message is
 * evidence — so a HIDE decision drops the row it derives. Another member's row (`f:`) is not hers.
 */
export function deleteTxn(entry: LedgerEntry): void {
  const ref = entry.txn.ref;
  if (ref.startsWith('m:')) {
    const manual = row('manual', ref.slice(2));
    if (entry.txn.sourceKind !== 'manual' || !manual) return;
    put('manual', { ...manual, deleted: true, updatedAt: stamp(manual) });
    return;
  }
  if (!ref.startsWith('s:')) return;
  const previous = row('decisions', `${DecisionKind.HIDE}:${ref}`);
  put('decisions', decision(ref, DecisionKind.HIDE, '1', stamp(previous), entry.txn.familyRef));
}

/** The undo: the tombstone or the hide is taken back with a newer stamp, and the row returns. */
export function restoreTxn(ref: string): void {
  if (ref.startsWith('m:')) {
    const manual = row('manual', ref.slice(2));
    if (manual?.deleted) put('manual', { ...manual, deleted: false, updatedAt: stamp(manual) });
    return;
  }
  if (!ref.startsWith('s:')) return;
  const previous = row('decisions', `${DecisionKind.HIDE}:${ref}`);
  if (!previous) return;
  put('decisions', { ...previous, value: null, deleted: true, updatedAt: stamp(previous), memberId: mineId() });
}

/**
 * One answer about one row, stamped past the one it replaces — null takes it back, on the same
 * (ref, kind) row, so a retracted answer is re-answered rather than raced.
 */
function answer(txn: Txn, kind: Decision['kind'], value: string | null): void {
  const previous = row('decisions', `${kind}:${txn.ref}`);
  if (value == null && (!previous || previous.deleted)) return;
  put('decisions', { ...decision(txn.ref, kind, value, stamp(previous), familyRefOf(txn), previous), deleted: value == null });
}

/**
 * Her corrections to one of her own rows — figure, day, whose it was; null leaves that one as it
 * stands (Edits.kt `editRow`). The day stops at today, and a row put back to its own member takes
 * the attribution back rather than storing it.
 */
export function editTxn(entry: LedgerEntry, rial: number | null, day: number | null, memberId: string | null): void {
  if (!editable(entry)) return;
  const txn = entry.txn;
  batch(() => {
    if (rial != null && rial >= 1 && rial <= MAX_PLAUSIBLE_RIAL && rial !== txn.amountRial) answer(txn, DecisionKind.AMOUNT, String(rial));
    if (day != null) {
      const moved = Math.min(day, tehranDay(Date.now()));
      if (moved !== txn.day) answer(txn, DecisionKind.DAY, String(moved));
    }
    if (memberId != null && memberId !== entry.ownerMemberId) {
      const own = memberId === (txn.ownerMemberId || mineId());
      answer(txn, DecisionKind.MEMBER, own ? null : memberId);
    }
  });
}

/** Back to the figure and day the row was read or typed with. Whose it was stays. */
export function revertTxnEdits(entry: LedgerEntry): void {
  batch(() => {
    answer(entry.txn, DecisionKind.AMOUNT, null);
    answer(entry.txn, DecisionKind.DAY, null);
  });
}

/**
 * The row in parts — or, with none, whole again — filed under its biggest part either way
 * (Edits.kt `splitRow`). False, writing nothing, for parts that do not add up to the row.
 */
export function splitTxn(entry: LedgerEntry, parts: SplitSpec): boolean {
  const parsed = parseSplit(splitValue(parts));
  const split = parsed.reduce((sum, [, r]) => sum + r, 0) === entry.txn.amountRial ? parsed : [];
  if (parts.length && !split.length) return false;
  batch(() => {
    answer(entry.txn, DecisionKind.CATEGORY, splitLead(split) ?? entry.categoryId);
    answer(entry.txn, DecisionKind.SPLIT, split.length ? splitValue(split) : null);
  });
  return true;
}

// ---- categories --------------------------------------------------------------------------------

/** A category of her own. Nothing is re-filed: a new answer becomes available, no answer changes. */
export function addCategory(nameFa: string, kind: CategoryKindId, glyph: string): Category {
  const category = customCategory(nameFa, kind, glyph, Date.now());
  put('categories', category);
  return category;
}

/**
 * Her name and mark for a category, shipped or hers. The mark is stored even when she kept it —
 * on a shipped category that is what makes the rename survive seeding.
 */
export function editCategory(category: Category, nameFa: string, glyph: string): void {
  const current = row('categories', category.id) ?? category;
  put('categories', { ...current, nameFa: nameFa.trim(), glyph, updatedAt: stamp(current) });
}

/** «حذف» on a category: archived, never deleted. No new stamp — archiving is hers alone, not the household's. */
export function toggleCategoryArchived(category: Category): void {
  const current = row('categories', category.id) ?? category;
  put('categories', { ...current, archived: !current.archived });
}

// ---- accounts --------------------------------------------------------------------------------

/** What an account really holds, in Rial; everything pasted after it builds on it. */
export function setBankBalance(bank: string, balanceRial: number): void {
  const now = Date.now();
  put('anchors', {
    id: uuid7(now), accountId: bank, at: now, balanceRial, source: 'user', createdAt: now, updatedAt: now, deleted: false,
  });
}

/**
 * Drops an account that was read wrong. Its messages stay — they are the ledger — but nothing at or
 * before this moment counts toward its balance again: it starts from zero, unanchored, at the next
 * message. The mark is a tombstone row, which every reader of live anchors already skips.
 */
export function forgetAccount(bank: string): void {
  const now = Date.now();
  put('anchors', {
    id: forgetMarkId(bank), accountId: bank, at: now, balanceRial: 0, source: 'user', createdAt: now, updatedAt: now, deleted: true,
  });
}

/** A bank switched off stays tracked and listed; it just leaves the total. */
export function toggleBankDisabled(bank: string): void {
  const disabled = pref('disabledBanks');
  setPref('disabledBanks', disabled.includes(bank) ? disabled.filter((b) => b !== bank) : [...disabled, bank]);
}

// ---- messages and the ledger's start -------------------------------------------------------------

/**
 * A message she pasted, stored as the phone stores an inbox row: only what `bodyToStore` keeps —
 * a one-time code, or a body that names no money, is refused with null, and so is a paste of
 * several messages at once. [at] defaults to the time the bank printed, else now. The same message
 * pasted twice, dated or not, is not stored twice: the one already there comes back.
 */
export async function addPastedSms(text: string, bank: string, at?: number): Promise<Source | null> {
  if (severalMessages(text)) return null;
  const body = bodyToStore(text);
  if (body == null) return null;
  const now = Date.now();
  const moment = at ?? pastedMoment(body, now) ?? now;
  const id = await sourceId(bank, body, moment);
  // An undated body hashes with the moment it was pasted, so pasting it again — or the Shortcut
  // handing it over again — made a second source, counted twice wherever no مانده or reference
  // pairs the two. The same bank and the same words are the same message, whenever pasted.
  const existing = row('sources', id) ?? rows('sources').find((s) => s.bank === bank && s.body.trim() === body.trim());
  const source = existing ?? { id, bank, body, at: moment, ingestedAt: now };
  batch(() => {
    if (!existing) put('sources', source);
    setPref('lastPasteBank', bank);
  });
  return source;
}

/**
 * A Shortcut's bundle (paste.ts `readBundle`), each message stored by addPastedSms — the same
 * refusals and the same dedupe, so pasting the same bundle again adds nothing. A record whose
 * sender [bankFor] cannot name is not stored. Returns how many messages were new.
 *
 * ponytail: one write per message, each a re-render; one batch for the lot if a long bundle ever
 * shows it.
 */
export async function addPastedBundle(records: Bundled[], bankFor: (sender: string) => string | null): Promise<number> {
  const before = rows('sources').length;
  for (const r of records) {
    const bank = bankFor(r.sender);
    if (bank) await addPastedSms(r.body, bank, r.at ?? undefined);
  }
  return rows('sources').length - before;
}

/** Where the ledger starts. Nothing is deleted or re-read; «از اول» (0) brings every row back as it was. */
export function setLedgerStartsOn(day: number): void {
  setPref('ledgerStartsOn', day);
}
