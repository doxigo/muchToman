import { describe, expect, it } from 'vitest';
import { DAY_MS, tehranDay } from '../src/jalali';
import type { Txn } from '../src/model';
import {
  LinkKind, SLOW_RAIL_WINDOW_MS, TRANSFER_FEE_MAX_RIAL, TRANSFER_WINDOW_MS, Verdict, findDuplicates, findLinks,
  findTransfers, hiddenRefs, linkDecision, transferRefs,
} from '../src/links';
import type { LinkCandidate } from '../src/links';

/** Links.kt, as ClassifyTest.kt and LinkRegressionTest.kt pin it. */

let n = 0;
function txn(o: { at?: number; signed?: number | null; balance?: number | null; account?: string; merchant?: string; channel?: string; refNo?: string; mask?: string; ref?: string } = {}): Txn {
  n++;
  const at = o.at ?? n * 1000;
  const ref = o.ref ?? `s:${String(n).padStart(4, '0')}:0`;
  const signed = o.signed ?? null;
  const account = o.account ?? 'SAMAN';
  return {
    ref, srcHash: ref, seq: 0, at, day: tehranDay(at), bank: account, accountId: account,
    direction: signed == null ? null : signed > 0 ? 'in' : 'out',
    amountRial: signed == null ? null : Math.abs(signed), signedRial: signed,
    balanceRial: o.balance ?? null, feeRial: null, mask: o.mask ?? '', instrument: 'unknown',
    merchant: o.merchant ?? '', merchantNorm: o.merchant ?? '', refNo: o.refNo ?? '', printedAt: '',
    channel: o.channel ?? 'unknown', unitPrinted: 'none', inferred: false, familyRef: '', ownerMemberId: '', sourceKind: 'sms',
  };
}

describe('duplicates', () => {
  it("settles one outright on the bank's own reference number", () => {
    const d = findDuplicates([
      txn({ at: 1000, signed: -5_000_000, refNo: '020/000016703' }),
      txn({ at: 9_000_000, signed: -5_000_000, refNo: '020/000016703' }),
    ]);
    expect(d).toHaveLength(1);
    expect(d[0]).toMatchObject({ kind: LinkKind.DUPLICATE, reason: 'refno', auto: true });
  });

  it('settles one when two purchases share a post-transaction balance', () => {
    const d = findDuplicates([txn({ at: 1000, signed: -5_000_000, balance: 90_000_000 }), txn({ at: 2000, signed: -5_000_000, balance: 90_000_000 })]);
    expect(d).toHaveLength(1);
    expect(d[0]).toMatchObject({ reason: 'balance', auto: true });
  });

  it('reads a monthly cycle back to the same balance as rent, not a duplicate', () => {
    const months = [0, 1, 2].map((i) => txn({ at: 1_000_000 + i * 30 * DAY_MS, signed: -80_000_000, balance: 120_000_000 }));
    expect(findDuplicates(months)).toEqual([]);
  });

  it('reads a reference number re-used across months as a cycle', () => {
    expect(findDuplicates([
      txn({ at: 1_000_000, signed: -5_000_000, refNo: '020/000016703' }),
      txn({ at: 1_000_000 + 30 * DAY_MS, signed: -5_000_000, refNo: '020/000016703' }),
    ])).toEqual([]);
  });

  it('asks about two identical taxi fares a minute apart, and never merges them', () => {
    const d = findDuplicates([txn({ at: 1000, signed: -500_000, merchant: 'اسنپ' }), txn({ at: 41_000, signed: -500_000, merchant: 'اسنپ' })]);
    expect(d).toHaveLength(1);
    expect(d[0]).toMatchObject({ reason: 'near', auto: false });
    expect(hiddenRefs(d).size).toBe(0);
  });

  it('never lets a reused reference hide different money', () => {
    const a = txn({ ref: 'a', at: 1, signed: -1_000_000, refNo: 'reference' });
    const incompatible = [
      // Another account at the same bank: the reference groups by bank, the match by account.
      { ...txn({ ref: 'b', at: 2, signed: 1_000_000, refNo: 'reference' }), accountId: 'OTHER' },
      txn({ ref: 'b', at: 2, signed: -2_000_000, refNo: 'reference' }),
      { ...txn({ ref: 'b', at: 2, signed: -1_000_000, refNo: 'reference' }), accountId: 'OTHER' },
      txn({ ref: 'b', at: 2, signed: 1_000_000, refNo: 'reference' }),
      { ...a, ref: 'b', at: 2, direction: null, signedRial: null },
    ];
    for (const b of incompatible) {
      const links = findDuplicates([a, b]);
      expect(links.length).toBeGreaterThan(0);
      expect(hiddenRefs(links).size).toBe(0);
      expect(links.some((l) => l.auto)).toBe(false);
    }
  });

  it('keeps matching references with contradictory card masks a question', () => {
    const a = txn({ ref: 'a', at: 1, signed: -1_000_000, refNo: 'reference', mask: '1234' });
    expect(hiddenRefs(findDuplicates([a, { ...a, ref: 'b', at: 2, mask: '5678' }])).size).toBe(0);
  });

  it('hides a compatible repeat but leaves a later instalment visible', () => {
    const a = txn({ ref: 'a', at: 1, signed: -1_000_000, refNo: 'reference' });
    expect(hiddenRefs(findDuplicates([a, { ...a, ref: 'b', at: 2 }]))).toEqual(new Set(['b']));
    expect(findDuplicates([a, { ...a, ref: 'c', at: 31 * DAY_MS }])).toEqual([]);
  });

  it('hides the later leg in time, whatever order the refs sort in', () => {
    const early = txn({ at: 1000, signed: -5_000_000, balance: 90_000_000, ref: 's:zzzz:0' });
    const later = txn({ at: 2000, signed: -5_000_000, balance: 90_000_000, ref: 's:aaaa:0' });
    expect([...hiddenRefs(findLinks([early, later], []))]).toEqual([later.ref]);
  });
});

describe('transfers', () => {
  it('finds one between her own accounts and brings both legs back', () => {
    const out = txn({ at: 1_000_000, signed: -50_000_000, account: 'SAMAN' });
    const into = txn({ at: 1_060_000, signed: 50_000_000, account: 'BLU' });
    const links = findTransfers([out, into]);
    expect(links).toHaveLength(1);
    expect(links[0]).toMatchObject({ kind: LinkKind.TRANSFER, reason: 'exact', auto: true });
    expect(transferRefs(links)).toEqual(new Set([out.ref, into.ref]));
  });

  it('finds a fee taken out of an instant transfer, but only as a question', () => {
    const out = txn({ at: 1_000_000, signed: -50_000_000, account: 'SAMAN' });
    const links = findTransfers([out, txn({ at: 1_060_000, signed: 49_940_000, account: 'BLU' })]);
    expect(links[0]).toMatchObject({ reason: 'fee-band', auto: false });
    // Past the fee band, or arriving as more than left, it is not the same money.
    expect(findTransfers([out, txn({ at: 1_060_000, signed: 50_000_000 - TRANSFER_FEE_MAX_RIAL - 1, account: 'BLU' })])).toEqual([]);
    expect(findTransfers([out, txn({ at: 1_060_000, signed: 50_000_001, account: 'BLU' })])).toEqual([]);
  });

  it('gives an instant rail only its fifteen minutes, both ways', () => {
    const out = txn({ at: 10 * 3600_000, signed: -75_000_000, account: 'SAMAN', channel: 'card' });
    expect(findTransfers([out, txn({ at: out.at + TRANSFER_WINDOW_MS, signed: 75_000_000, account: 'BLU' })])).toHaveLength(1);
    expect(findTransfers([out, txn({ at: out.at - TRANSFER_WINDOW_MS, signed: 75_000_000, account: 'BLU' })])).toHaveLength(1);
    expect(findTransfers([out, txn({ at: out.at + TRANSFER_WINDOW_MS + 1, signed: 75_000_000, account: 'BLU' })])).toEqual([]);
    expect(findTransfers([out, txn({ at: out.at + 6 * 3600_000, signed: 75_000_000, account: 'BLU' })])).toEqual([]);
  });

  it('never trusts paya or satna, and reads the rail off either leg', () => {
    const paya = findTransfers([
      txn({ at: 1_000_000, signed: -50_000_000, account: 'SAMAN', channel: 'paya' }),
      txn({ at: 1_000_000 + 30 * 3600_000, signed: 50_000_000, account: 'BLU' }),
    ]);
    expect(paya[0]).toMatchObject({ reason: 'paya', auto: false });
    const received = findTransfers([
      txn({ at: 1_000_000, signed: -75_000_000, account: 'SAMAN', channel: 'transfer' }),
      txn({ at: 1_000_000 + 6 * 3600_000, signed: 75_000_000, account: 'BLU', channel: 'paya' }),
    ]);
    expect(received[0]).toMatchObject({ reason: 'paya', auto: false });
    const satna = findTransfers([
      txn({ at: 1_000_000, signed: -75_000_000, account: 'SAMAN', channel: 'satna' }),
      txn({ at: 1_000_000 + 4 * 3600_000, signed: 75_000_000, account: 'BLU' }),
    ]);
    expect(satna[0]).toMatchObject({ reason: 'satna', auto: false });
    // A slow rail is exact or unrelated: its fee is debited separately.
    expect(findTransfers([
      txn({ at: 1_000_000, signed: -75_000_000, account: 'SAMAN', channel: 'paya' }),
      txn({ at: 1_000_000 + 3600_000, signed: 74_990_000, account: 'BLU' }),
    ])).toEqual([]);
    // And forty hours is its limit.
    expect(findTransfers([
      txn({ at: 1_000_000, signed: -75_000_000, account: 'SAMAN', channel: 'paya' }),
      txn({ at: 1_000_000 + SLOW_RAIL_WINDOW_MS + 1, signed: 75_000_000, account: 'BLU' }),
    ])).toEqual([]);
  });

  it('reads money sent to someone else as spending, not a transfer', () => {
    const out = txn({ at: 1_000_000, signed: -50_000_000, account: 'SAMAN' });
    expect(findTransfers([out])).toEqual([]);
    expect(findTransfers([out, txn({ at: 1_060_000, signed: 50_000_000, account: 'SAMAN' })])).toEqual([]);
  });

  it('never links two possible counter-legs automatically, and takes the nearest', () => {
    const out = txn({ at: 1_000_000, signed: -50_000_000, account: 'SAMAN' });
    const a = txn({ at: 1_030_000, signed: 50_000_000, account: 'BLU' });
    const b = txn({ at: 1_060_000, signed: 50_000_000, account: 'REFAH' });
    const links = findTransfers([out, a, b]);
    expect(links).toHaveLength(1);
    expect(links[0].auto).toBe(false);
    expect([links[0].aRef, links[0].bRef]).toContain(a.ref);
  });

  it('never lets two equal payments out both claim the one leg that came in', () => {
    const links = findTransfers([
      txn({ at: 1_000_000, signed: -50_000_000, account: 'SAMAN' }),
      txn({ at: 1_040_000, signed: -50_000_000, account: 'REFAH' }),
      txn({ at: 1_080_000, signed: 50_000_000, account: 'BLU' }),
    ]);
    expect(links.some((l) => l.auto)).toBe(false);
  });

  it('never takes a box move for half of a transfer', () => {
    const intoBox = txn({ ref: 'box', at: 1_000, signed: -5_000_000, account: 'BLU', channel: 'box' });
    const salary = txn({ ref: 'pay', at: 61_000, signed: 5_000_000, account: 'SAMAN' });
    expect(findTransfers([intoBox, salary])).toEqual([]);
    expect(findTransfers([{ ...intoBox, channel: 'unknown' }, salary])).toHaveLength(1);
  });

  it('matches brute force over mixed rails, boundaries and competing legs', () => {
    let seed = 54321;
    const random = (): number => ((seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31);
    const pick = (k: number): number => Math.floor(random() * k);
    for (let round = 0; round < 100; round++) {
      const rows = Array.from({ length: 80 }, (_, i) => txn({
        ref: `${round}-${i}`, at: pick(100 * 3_600_000),
        signed: (random() < 0.5 ? 1 : -1) * (1 + pick(5)) * 100_000,
        account: `account-${pick(3)}`, channel: ['unknown', 'paya', 'satna', 'box'][pick(4)],
      }));
      expect(asSet(findTransfers(rows))).toEqual(asSet(bruteTransfers(rows)));
    }
    for (const window of [TRANSFER_WINDOW_MS, SLOW_RAIL_WINDOW_MS]) {
      for (const distance of [-window - 1, -window, window, window + 1]) {
        const outgoing = txn({ ref: 'out', at: 200_000_000, signed: -500_000 });
        const incoming = txn({ ref: 'in', at: outgoing.at + distance, signed: 500_000, account: 'OTHER', channel: window === SLOW_RAIL_WINDOW_MS ? 'paya' : 'unknown' });
        expect(findTransfers([outgoing, incoming])).toEqual(bruteTransfers([outgoing, incoming]));
      }
    }
  });
});

describe('her verdicts', () => {
  it('always win, in both directions', () => {
    const a = txn({ at: 1000, signed: -5_000_000, balance: 90_000_000 });
    const b = txn({ at: 2000, signed: -5_000_000, balance: 90_000_000 });
    expect(findLinks([a, b], [])).toHaveLength(1);
    expect(findLinks([a, b], [linkDecision(b.ref, a.ref, LinkKind.DUPLICATE, Verdict.REJECTED, 1)])).toEqual([]);
    const invented = findLinks([a, b], [linkDecision('s:x:0', 's:y:0', LinkKind.TRANSFER, Verdict.CONFIRMED, 1)]);
    const transfer = invented.find((l) => l.kind === LinkKind.TRANSFER)!;
    expect(transfer).toMatchObject({ auto: true, reason: 'confirmed', laterRef: '' });
    // A confirmation lifts a deck card into a settled fact.
    const fare1 = txn({ at: 1000, signed: -500_000, merchant: 'اسنپ' });
    const fare2 = txn({ at: 41_000, signed: -500_000, merchant: 'اسنپ' });
    const settled = findLinks([fare1, fare2], [linkDecision(fare1.ref, fare2.ref, LinkKind.DUPLICATE, Verdict.CONFIRMED, 1)]);
    expect(settled[0]).toMatchObject({ auto: true, reason: 'confirmed' });
    expect(hiddenRefs(settled)).toEqual(new Set([fare2.ref]));
    // A retracted verdict is no verdict.
    expect(findLinks([a, b], [{ ...linkDecision(a.ref, b.ref, LinkKind.DUPLICATE, Verdict.REJECTED, 1), deleted: true }])).toHaveLength(1);
  });

  it('are keyed by the pair, so one pair cannot be confirmed and rejected at once', () => {
    expect(linkDecision('s:b', 's:a', LinkKind.TRANSFER, Verdict.REJECTED, 1).id)
      .toBe(linkDecision('s:a', 's:b', LinkKind.TRANSFER, Verdict.CONFIRMED, 2).id);
  });

  it('leave detection independent of order', () => {
    const rows = [
      txn({ at: 1_000_000, signed: -50_000_000, account: 'SAMAN' }),
      txn({ at: 1_060_000, signed: 50_000_000, account: 'BLU' }),
      txn({ at: 3_000_000, signed: -5_000_000, refNo: 'R1' }),
      txn({ at: 4_000_000, signed: -5_000_000, refNo: 'R1' }),
    ];
    expect(asSet(findLinks([...rows].reverse(), []))).toEqual(asSet(findLinks(rows, [])));
  });
});

const asSet = (links: LinkCandidate[]): Set<string> => new Set(links.map((l) => JSON.stringify(l)));

function bruteTransfers(all: Txn[]): LinkCandidate[] {
  const rail = (a: Txn, b: Txn): string | null => [a.channel, b.channel].find((c) => c === 'paya' || c === 'satna') ?? null;
  const rows = all.filter((t) => t.channel !== 'box');
  const candidates = rows.filter((t) => t.direction === 'out' && t.amountRial != null).map((sent) => ({
    sent,
    matches: rows.filter((r) => {
      const slow = rail(sent, r) != null;
      return r.direction === 'in' && r.amountRial != null && r.accountId !== sent.accountId &&
        Math.abs(r.at - sent.at) <= (slow ? SLOW_RAIL_WINDOW_MS : TRANSFER_WINDOW_MS) &&
        (slow ? r.amountRial === sent.amountRial : r.amountRial <= sent.amountRial! && sent.amountRial! - r.amountRial <= TRANSFER_FEE_MAX_RIAL);
    }),
  }));
  const claims = new Map<string, number>();
  for (const c of candidates) for (const r of c.matches) claims.set(r.ref, (claims.get(r.ref) ?? 0) + 1);
  return candidates.flatMap(({ sent, matches }) => {
    if (!matches.length) return [];
    const nearest = [...matches].sort((x, y) => Math.abs(x.at - sent.at) - Math.abs(y.at - sent.at) || (x.ref < y.ref ? -1 : 1))[0];
    const slow = rail(sent, nearest);
    const exact = sent.amountRial === nearest.amountRial;
    const [a, b] = [sent.ref, nearest.ref].sort();
    const later = sent.at > nearest.at || (sent.at === nearest.at && sent.ref > nearest.ref) ? sent : nearest;
    return [{ aRef: a, bRef: b, kind: 'transfer' as const, reason: slow ?? (exact ? 'exact' : 'fee-band'),
      auto: matches.length === 1 && claims.get(nearest.ref) === 1 && exact && slow == null, laterRef: later.ref }];
  });
}
