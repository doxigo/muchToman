/**
 * Transfers and duplicates — the two ways one movement of money shows up as more than one
 * transaction, and the two ways a total goes quietly wrong if nothing notices (Links.kt).
 *
 * Every detector is a pure function of the transactions, and nothing guesses: a link counts only
 * when nothing about it is ambiguous — `auto` — and her verdict beats everything both ways. A near
 * miss is a non-auto candidate: it counts in nothing, and gives her verdict a pair to land on.
 */
import type { LinkDecision, Txn } from './model';

export const LinkKind = { TRANSFER: 'transfer', DUPLICATE: 'duplicate' } as const;
export const Verdict = { CONFIRMED: 'confirmed', REJECTED: 'rejected' } as const;

/** A pair the detectors propose, rebuilt on every derive. `aRef < bRef`. */
export interface LinkCandidate {
  aRef: string;
  bRef: string;
  kind: LinkDecision['kind'];
  /** Why, in a word, so the deck can say what it noticed rather than just assert it. */
  reason: string;
  /** True only when nothing about the match is ambiguous. */
  auto: boolean;
  /**
   * The leg that arrived second, which [hiddenRefs] strikes out — carried because refs are hashes
   * and their order against time is a coin flip. Blank only on a confirmation whose legs are gone.
   */
  laterRef: string;
}

/**
 * Her verdict on a pair. The id is `${kind}:${aRef}:${bRef}`, which is the phone's unique
 * (a_ref, b_ref, kind) index: without it `(a,b)` could be confirmed and rejected at once.
 */
export function linkDecision(aRef: string, bRef: string, kind: LinkDecision['kind'], verdict: LinkDecision['verdict'], now: number): LinkDecision {
  const [a, b] = aRef <= bRef ? [aRef, bRef] : [bRef, aRef];
  return { id: `${kind}:${a}:${b}`, aRef: a, bRef: b, kind, verdict, createdAt: now, updatedAt: now, deleted: false };
}

/** A fee band a bank may take out of a transfer without it stopping being the same money. */
export const TRANSFER_FEE_MAX_RIAL = 120_000;
/** Instant rails settle in seconds; anything past this is two separate movements. */
export const TRANSFER_WINDOW_MS = 15 * 60 * 1000;
/** پایا settles next business day and ساتنا in banking-hour cycles: too wide to ever trust silently. */
export const SLOW_RAIL_WINDOW_MS = 40 * 60 * 60 * 1000;
/** Read off either leg: the receiving bank writes «واریز پایا» while the sender says only «انتقال». */
const SLOW_RAILS = new Set(['paya', 'satna']);
/** Two identical purchases this close together are a real thing; only she may decide. */
export const DUPLICATE_WINDOW_MS = 90 * 1000;
/** Some banks re-print a contract number where a reference belongs: seven days, short of next month's instalment. */
export const DUPLICATE_REFNO_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;
/** A shared post-transaction balance settles a duplicate only this close — or a monthly rent would hide for ever. */
export const DUPLICATE_BALANCE_WINDOW_MS = 48 * 60 * 60 * 1000;

const byTime = (a: Txn, b: Txn): number => a.at - b.at || (a.ref < b.ref ? -1 : a.ref > b.ref ? 1 : 0);

function pair(a: Txn, b: Txn, kind: LinkCandidate['kind'], reason: string, auto: boolean): LinkCandidate {
  const [x, y] = a.ref <= b.ref ? [a.ref, b.ref] : [b.ref, a.ref];
  // The millisecond tie breaks on ref, so two devices deriving the same rows hide the same leg.
  const later = a.at > b.at || (a.at === b.at && a.ref > b.ref) ? a : b;
  return { aRef: x, bRef: y, kind, reason, auto, laterRef: later.ref };
}

function groupBy<T>(items: readonly T[], key: (item: T) => string): T[][] {
  const groups = new Map<string, T[]>();
  for (const item of items) {
    const k = key(item);
    const group = groups.get(k);
    if (group) group.push(item); else groups.set(k, [item]);
  }
  return [...groups.values()];
}

const tripleOf = (c: { aRef: string; bRef: string; kind: string }): string => `${c.aRef}\u0000${c.bRef}\u0000${c.kind}`;

function distinct(candidates: LinkCandidate[]): LinkCandidate[] {
  const seen = new Set<string>();
  return candidates.filter((c) => !seen.has(tripleOf(c)) && seen.add(tripleOf(c)));
}

/**
 * Duplicates, in this order: the bank's reference number within its window, then a shared
 * post-transaction balance within its window, then — never settled by the app — the same money
 * at the same merchant ninety seconds apart, because two identical taxi fares happen to people.
 */
export function findDuplicates(transactions: readonly Txn[]): LinkCandidate[] {
  const ordered = [...transactions].sort(byTime);

  const byRefNo: LinkCandidate[] = [];
  for (const matches of groupBy(ordered.filter((t) => t.refNo !== ''), (t) => `${t.bank}\u0000${t.refNo}`)) {
    for (let i = 0; i < matches.length; i++) {
      for (let j = i + 1; j < matches.length; j++) {
        if (matches[j].at - matches[i].at > DUPLICATE_REFNO_WINDOW_MS) break;
        const a = matches[i];
        const b = matches[j];
        const compatible = a.accountId === b.accountId &&
          a.direction != null && a.direction === b.direction &&
          a.amountRial != null && a.amountRial === b.amountRial &&
          a.signedRial != null && a.signedRial === b.signedRial &&
          (!a.mask.trim() || !b.mask.trim() || a.mask === b.mask);
        byRefNo.push(pair(a, b, LinkKind.DUPLICATE, 'refno', compatible));
      }
    }
  }

  const byBalance: LinkCandidate[] = [];
  const balanceGroups = groupBy(
    ordered.filter((t) => t.signedRial != null && t.balanceRial != null),
    (t) => `${t.accountId}\u0000${t.signedRial}\u0000${t.balanceRial}`,
  );
  for (const matches of balanceGroups) {
    for (let i = 0; i < matches.length; i++) {
      for (let j = i + 1; j < matches.length; j++) {
        if (matches[j].at - matches[i].at > DUPLICATE_BALANCE_WINDOW_MS) break;
        byBalance.push(pair(matches[i], matches[j], LinkKind.DUPLICATE, 'balance', true));
      }
    }
  }

  const nearby: LinkCandidate[] = [];
  for (let i = 0; i < ordered.length; i++) {
    const a = ordered[i];
    for (let j = i + 1; j < ordered.length; j++) {
      const b = ordered[j];
      if (b.at - a.at > DUPLICATE_WINDOW_MS) break;
      if (a.accountId === b.accountId && a.signedRial != null && a.signedRial === b.signedRial && a.merchantNorm === b.merchantNorm) {
        nearby.push(pair(a, b, LinkKind.DUPLICATE, 'near', false));
      }
    }
  }
  return distinct([...byRefNo, ...byBalance, ...nearby]);
}

function railOf(sent: Txn, received: Txn): string | null {
  if (SLOW_RAILS.has(sent.channel)) return sent.channel;
  if (SLOW_RAILS.has(received.channel)) return received.channel;
  return null;
}

/** The rows within [window] of a moment, by binary search over a time-sorted copy. */
function timeIndex(rows: readonly Txn[]): (at: number, window: number) => Txn[] {
  const ordered = [...rows].sort((a, b) => a.at - b.at);
  const firstAt = (pred: (t: Txn) => boolean): number => {
    let low = 0;
    let high = ordered.length;
    while (low < high) {
      const middle = (low + high) >>> 1;
      if (pred(ordered[middle])) low = middle + 1; else high = middle;
    }
    return low;
  };
  return (at, window) => ordered.slice(firstAt((t) => t.at < at - window), firstAt((t) => t.at <= at + window));
}

/**
 * Transfers between two accounts she owns. Both legs must be in the ledger: money to someone
 * else's account is spending, and treating it as a transfer would make that spending disappear.
 */
export function findTransfers(transactions: readonly Txn[]): LinkCandidate[] {
  // A box move never leaves Blu, so it has no leg at another bank.
  const legs = transactions.filter((t) => t.channel !== 'box');
  const outgoing = legs.filter((t) => t.direction === 'out' && t.amountRial != null);
  const incoming = legs.filter((t) => t.direction === 'in' && t.amountRial != null);
  const allTimes = timeIndex(incoming);
  const slowTimes = timeIndex(incoming.filter((t) => SLOW_RAILS.has(t.channel)));
  const instantTimes = timeIndex(incoming.filter((t) => !SLOW_RAILS.has(t.channel)));
  const candidates = outgoing.map((sent) => {
    const nearby = SLOW_RAILS.has(sent.channel)
      ? allTimes(sent.at, SLOW_RAIL_WINDOW_MS)
      : [...instantTimes(sent.at, TRANSFER_WINDOW_MS), ...slowTimes(sent.at, SLOW_RAIL_WINDOW_MS)];
    const matches = nearby.filter((received) => {
      const slow = railOf(sent, received) != null;
      if (received.accountId === sent.accountId) return false;
      if (Math.abs(received.at - sent.at) > (slow ? SLOW_RAIL_WINDOW_MS : TRANSFER_WINDOW_MS)) return false;
      // پایا and ساتنا debit their fee separately, so the two legs are exactly equal or unrelated.
      if (slow) return received.amountRial === sent.amountRial;
      const fee = sent.amountRial! - received.amountRial!;
      return fee >= 0 && fee <= TRANSFER_FEE_MAX_RIAL;
    });
    return { sent, matches };
  });
  // One counter-leg settles at most one pair, so uniqueness is checked from the receiving side
  // too: two equal payments out cannot both claim the one leg that came in.
  const claims = new Map<string, number>();
  for (const { matches } of candidates) for (const r of matches) claims.set(r.ref, (claims.get(r.ref) ?? 0) + 1);
  const out: LinkCandidate[] = [];
  for (const { sent, matches } of candidates) {
    if (!matches.length) continue;
    // Ref breaks a tie in distance, so the pair does not depend on the order rows arrived.
    const nearest = matches.reduce((best, t) => {
      const d = Math.abs(t.at - sent.at) - Math.abs(best.at - sent.at);
      return d < 0 || (d === 0 && t.ref < best.ref) ? t : best;
    });
    const rail = railOf(sent, nearest);
    const exact = nearest.amountRial === sent.amountRial;
    const unique = matches.length === 1 && claims.get(nearest.ref) === 1;
    out.push(pair(sent, nearest, LinkKind.TRANSFER, rail ?? (exact ? 'exact' : 'fee-band'), unique && exact && rail == null));
  }
  return out;
}

/** Every candidate, with her verdicts applied last: a rejection suppresses a pair for ever, a confirmation creates one. */
export function findLinks(transactions: readonly Txn[], decisions: readonly LinkDecision[]): LinkCandidate[] {
  const found = [...findDuplicates(transactions), ...findTransfers(transactions)];
  const live = decisions.filter((d) => !d.deleted);
  const rejected = new Set(live.filter((d) => d.verdict === Verdict.REJECTED).map(tripleOf));
  const confirmed = new Set(live.filter((d) => d.verdict === Verdict.CONFIRMED).map(tripleOf));
  const kept = distinct(found).filter((c) => !rejected.has(tripleOf(c)));
  const keptTriples = new Set(kept.map(tripleOf));

  // A confirmation may name legs the detectors never paired, so its later leg is looked up.
  const at = new Map(transactions.map((t) => [t.ref, t.at]));
  const hers = live
    .filter((d) => d.verdict === Verdict.CONFIRMED && !keptTriples.has(tripleOf(d)))
    .map((d): LinkCandidate => ({ aRef: d.aRef, bRef: d.bRef, kind: d.kind, reason: 'confirmed', auto: true, laterRef: laterOf(d.aRef, d.bRef, at) }));

  return [
    ...kept.map((c) => (confirmed.has(tripleOf(c)) ? { ...c, auto: true, reason: 'confirmed' } : c)),
    ...hers,
  ];
}

function laterOf(aRef: string, bRef: string, at: ReadonlyMap<string, number>): string {
  const aAt = at.get(aRef);
  const bAt = at.get(bRef);
  if (aAt == null || bAt == null) return '';
  return aAt > bAt || (aAt === bAt && aRef > bRef) ? aRef : bRef;
}

/** Both legs of every settled transfer, so reports leave them out of both sides. */
export function transferRefs(links: readonly LinkCandidate[]): Set<string> {
  return new Set(links.filter((l) => l.kind === LinkKind.TRANSFER && l.auto).flatMap((l) => [l.aRef, l.bRef]));
}

/** The later leg of every settled duplicate — hidden from reports, never deleted. */
export function hiddenRefs(links: readonly LinkCandidate[]): Set<string> {
  return new Set(links.filter((l) => l.kind === LinkKind.DUPLICATE && l.auto).map((l) => l.laterRef || l.bRef));
}
