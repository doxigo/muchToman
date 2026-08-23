import { describe, expect, it } from 'vitest';
import {
  agreesWithin,
  applyPlausibility,
  bodyEtag,
  btcUsdVerdict,
  deriveParsianPerSoot,
  etagMatches,
  formatDrops,
  longestOrderedSubset,
  PARSIAN_SOOT,
  reservedTomanIds,
  staleNote,
  tgjuStampMs,
  TokenBucket,
  usdBandVerdict,
} from '../src/checks';
import { BONBAST_MAP, firstOf, TGJU_MAP } from '../src/index';

/**
 * Every invariant is exercised against the slip it exists to catch — a factor of 10 or 1000
 * on one asset — using 2026-08 market magnitudes (usd ≈187k Toman, gold18 ≈17.8M/g). None of
 * these tests touch the network: what they check is the judgement, not the scrape.
 */

const parsianPrice = (soot: number) => soot * 17_800 * 1.1 + 400_000; // gold + fixed اجرت

function fixture(): Record<string, number> {
  const toman: Record<string, number> = {
    usd: 187_000,
    gold18: 17_800_000,
    gold_mesghal: 77_100_000, // 4.3318 × gold18, the 17-عیار مثقال convention
    silver_999: 270_000,
    silver_925: 250_000,
    coin_emami: 210_000_000,
    coin_bahar: 205_000_000,
    coin_nim: 115_000_000,
    coin_rob: 65_000_000,
    coin_gerami: 33_000_000,
  };
  for (const soot of PARSIAN_SOOT) toman[`parsian_${soot}`] = parsianPrice(soot);
  toman.parsian = parsianPrice(1000) / 1000;
  return toman;
}

const USDT = 195_000; // a plausible Tehran premium over the 187k dollar

const ids = (drops: { id: string }[]) => drops.map((d) => d.id).sort();

describe('applyPlausibility', () => {
  it('passes a normal market untouched', () => {
    const toman = fixture();
    const before = { ...toman };
    expect(applyPlausibility(toman, USDT)).toEqual([]);
    expect(toman).toEqual(before);
  });

  it('drops the dollar when it disagrees with the USDT market, and only the dollar', () => {
    const toman = fixture();
    toman.usd = 90_000; // more than ×1.25 away from 195k — one of the two is broken
    const drops = applyPlausibility(toman, USDT);
    expect(ids(drops)).toEqual(['usd']);
    expect(toman.usd).toBeUndefined();
    // gold is then judged against the USDT dollar instead, and it is fine
    expect(toman.gold18).toBe(17_800_000);
  });

  it('keeps a lone in-band dollar when there is no USDT referee', () => {
    const toman = fixture();
    expect(applyPlausibility(toman, null)).toEqual([]);
    expect(toman.usd).toBe(187_000);
  });

  it('ignores an implausible USDT price as a referee', () => {
    const toman = fixture();
    expect(applyPlausibility(toman, 195)).toEqual([]); // 1000x-down USDT judges nothing
    expect(toman.usd).toBe(187_000);
  });

  it('catches gold 10x high (a [10,1000] band would not: 95×10 = 950)', () => {
    const toman = fixture();
    toman.gold18 = 178_000_000;
    const drops = applyPlausibility(toman, USDT);
    expect(ids(drops)).toEqual(['gold18']);
    // mesghal survives on its dollar fallback; silver has no trusted gold and is skipped
    expect(toman.gold_mesghal).toBe(77_100_000);
    expect(toman.silver_999).toBe(270_000);
  });

  it('catches gold 10x low', () => {
    const toman = fixture();
    toman.gold18 = 1_780_000;
    expect(ids(applyPlausibility(toman, USDT))).toContain('gold18');
  });

  it('catches a 10x مثقال against gold', () => {
    const toman = fixture();
    toman.gold_mesghal = 771_000_000;
    expect(ids(applyPlausibility(toman, USDT))).toEqual(['gold_mesghal']);
  });

  it('catches a 1000x مثقال even when gold itself is missing, via the dollar', () => {
    const toman = fixture();
    delete toman.gold18;
    toman.gold_mesghal = 77_100_000_000;
    expect(ids(applyPlausibility(toman, USDT))).toEqual(['gold_mesghal']);
  });

  it('catches silver quoted 10x high and 1000x low', () => {
    const high = fixture();
    high.silver_999 = 2_700_000; // above gold18/20
    expect(ids(applyPlausibility(high, USDT))).toEqual(['silver_999']);

    const low = fixture();
    low.silver_999 = 270; // below gold18/2000
    expect(ids(applyPlausibility(low, USDT))).toEqual(['silver_999']);
  });

  it('skips the silver check rather than dropping silver when gold is absent', () => {
    const toman = fixture();
    delete toman.gold18;
    delete toman.gold_mesghal;
    expect(applyPlausibility(toman, USDT)).toEqual([]);
    expect(toman.silver_999).toBe(270_000);
  });

  it('drops a سکه priced 10x off its gold content', () => {
    const toman = fixture();
    toman.coin_emami = 21_000_000; // 0.12× its melt
    const drops = applyPlausibility(toman, USDT);
    expect(ids(drops)).toEqual(['coin_emami']);
    expect(drops[0].reason).toContain('gold content');
    expect(toman.coin_bahar).toBe(205_000_000); // the rest of the set stands
  });

  it('drops the سکه that breaks the weight ordering when there is no gold to anchor on', () => {
    const toman = fixture();
    delete toman.gold18;
    toman.coin_nim = 250_000_000; // a نیم above the امامی
    const drops = applyPlausibility(toman, USDT);
    expect(ids(drops)).toEqual(['coin_nim']);
  });

  it('tolerates a near-tie between امامی and بهار', () => {
    const toman = fixture();
    delete toman.gold18;
    toman.coin_bahar = toman.coin_emami * 1.02; // بهار 2% ahead on a normal day
    expect(applyPlausibility(toman, USDT)).toEqual([]);
  });

  it('drops the one پارسیان row that breaks the سوت ordering, not its neighbours', () => {
    const toman = fixture();
    toman.parsian_700 = parsianPrice(700) * 10;
    const drops = applyPlausibility(toman, USDT);
    expect(ids(drops)).toEqual(['parsian_700']);
    expect(toman.parsian_600).toBe(parsianPrice(600));
    expect(toman.parsian_800).toBe(parsianPrice(800));
    // the derived per-سوت rate still comes from the surviving 1000 سوت reference
    expect(toman.parsian).toBeCloseTo(parsianPrice(1000) / 1000, 6);
  });

  it('catches parsian_100 10x low via its gold anchor (ordering alone cannot see it)', () => {
    const toman = fixture();
    toman.parsian_100 = parsianPrice(100) / 10; // still the smallest, still "ordered"
    expect(ids(applyPlausibility(toman, USDT))).toEqual(['parsian_100']);
  });

  it('re-derives the per-سوت rate from survivors when the reference row is the bad one', () => {
    const toman = fixture();
    toman.parsian_1000 = parsianPrice(1000) * 10;
    const drops = applyPlausibility(toman, USDT);
    expect(ids(drops)).toEqual(['parsian_1000']);
    expect(toman.parsian).toBeCloseTo(parsianPrice(1500) / 1500, 6);
  });
});

describe('dollar and bitcoin bands', () => {
  it('rejects the official-peg dollar and a parse-slip dollar, keeps the market one', () => {
    expect(usdBandVerdict(4_200)).toContain('implausible');
    expect(usdBandVerdict(20_000_000)).toContain('implausible');
    expect(usdBandVerdict(null)).toBe('response had no usd');
    expect(usdBandVerdict(187_000)).toBe(true);
  });

  it('rejects bitcoin quoted in cents or as satoshis, keeps a market price', () => {
    expect(btcUsdVerdict(115_000)).toBe(true);
    expect(btcUsdVerdict(115)).toContain('outside'); // quoted in "hundreds"
    expect(btcUsdVerdict(11_500_000)).toContain('outside'); // cents
  });

  it('judges cross-chain agreement symmetrically', () => {
    expect(agreesWithin(12_000_000_000, 6_000_000_000, 1.3)).toBe(false);
    expect(agreesWithin(6_000_000_000, 12_000_000_000, 1.3)).toBe(false);
    expect(agreesWithin(200, 210, 1.3)).toBe(true);
    expect(agreesWithin(0, 210, 1.3)).toBe(false);
  });
});

describe('failover on implausible sources', () => {
  it('records a wrong-but-numeric source as failed and advances the chain', async () => {
    const got = await firstOf(
      [
        { name: 'bad', run: async () => ({ prices: { usd: 4_200 } }) },
        { name: 'good', run: async () => ({ prices: { usd: 187_000 } }) },
      ],
      (v) => usdBandVerdict(v.prices.usd),
    );
    expect(got.via).toBe('good');
    expect(got.value.prices.usd).toBe(187_000);
    expect(got.failures).toHaveLength(1);
    expect(got.failures[0]).toContain('bad');
    expect(got.failures[0]).toContain('implausible');
  });

  it('still fails the whole chain when every source is implausible', async () => {
    await expect(
      firstOf(
        [{ name: 'only', run: async () => ({ prices: { usd: 4_200 } }) }],
        (v) => usdBandVerdict(v.prices.usd),
      ),
    ).rejects.toThrow('implausible');
  });
});

describe('longestOrderedSubset', () => {
  it('identifies a spiked head without condemning everything after it', () => {
    const keep = longestOrderedSubset(
      [
        { id: 'a', value: 5 },
        { id: 'b', value: 60 },
        { id: 'c', value: 6 },
        { id: 'd', value: 7 },
        { id: 'e', value: 8 },
      ],
      'non-decreasing',
    );
    expect([...keep].sort()).toEqual(['a', 'c', 'd', 'e']);
  });

  it('handles empty and single-element inputs', () => {
    expect(longestOrderedSubset([], 'non-decreasing').size).toBe(0);
    expect(longestOrderedSubset([{ id: 'x', value: 1 }], 'non-increasing').has('x')).toBe(true);
  });
});

describe('reserved ids', () => {
  const reserved = reservedTomanIds(Object.keys(BONBAST_MAP));

  it('covers everything /rates publishes outside crypto, not just BONBAST_MAP', () => {
    for (const id of [
      'toman', 'usd', 'gold18', 'gold_mesghal', 'coin_emami',
      'silver_999', 'silver_925', 'parsian', 'parsian_100', 'parsian_1500',
    ]) {
      expect(reserved.has(id), id).toBe(true);
    }
  });

  it('blocks a token coded PARSIAN from shadowing the gold coin', () => {
    expect(reserved.has('PARSIAN'.toLowerCase())).toBe(true);
  });

  it('leaves real crypto tickers alone', () => {
    expect(reserved.has('btc')).toBe(false);
    expect(reserved.has('usdt')).toBe(false);
  });
});

describe('source map sanity', () => {
  it('bonbast and tgju cover the same asset ids, so failover never changes coverage', () => {
    expect(Object.keys(BONBAST_MAP).sort()).toEqual(Object.keys(TGJU_MAP).sort());
  });

  it('no two ids share one upstream field (per-10/per-100 quirks must be resolved first)', () => {
    for (const map of [BONBAST_MAP, TGJU_MAP]) {
      const fields = Object.values(map);
      expect(new Set(fields).size).toBe(fields.length);
    }
  });
});

describe('token bucket', () => {
  const perMs = 30 / (5 * 60 * 1000); // the /wallet-balance shape: 30 per five minutes

  it('allows a burst up to capacity, then answers with a retry time', () => {
    const bucket = new TokenBucket(30, perMs);
    for (let i = 0; i < 30; i++) {
      expect(bucket.take('1.2.3.4', 0).ok).toBe(true);
    }
    const denied = bucket.take('1.2.3.4', 0);
    expect(denied.ok).toBe(false);
    if (!denied.ok) expect(denied.retryAfterSeconds).toBe(10); // one token every 10s
  });

  it('refills with time', () => {
    const bucket = new TokenBucket(30, perMs);
    for (let i = 0; i < 30; i++) bucket.take('ip', 0);
    expect(bucket.take('ip', 0).ok).toBe(false);
    expect(bucket.take('ip', 10_000).ok).toBe(true); // one token back
    expect(bucket.take('ip', 10_000).ok).toBe(false);
    // a full window later the whole burst is available again
    for (let i = 0; i < 30; i++) {
      expect(bucket.take('ip', 400_000).ok).toBe(true);
    }
    expect(bucket.take('ip', 400_000).ok).toBe(false);
  });

  it('keeps callers separate', () => {
    const bucket = new TokenBucket(1, perMs);
    expect(bucket.take('a', 0).ok).toBe(true);
    expect(bucket.take('a', 0).ok).toBe(false);
    expect(bucket.take('b', 0).ok).toBe(true);
  });
});

describe('etag', () => {
  it('is deterministic, strong-format, and body-sensitive', async () => {
    const a1 = await bodyEtag('{"toman":{"usd":187000}}');
    const a2 = await bodyEtag('{"toman":{"usd":187000}}');
    const b = await bodyEtag('{"toman":{"usd":187001}}');
    expect(a1).toBe(a2);
    expect(a1).not.toBe(b);
    expect(a1).toMatch(/^"[0-9a-f]{32}"$/);
  });

  it('matches per RFC 9110: lists, *, and weak comparison', async () => {
    const etag = await bodyEtag('body');
    expect(etagMatches(etag, etag)).toBe(true);
    expect(etagMatches(`"nope", ${etag}`, etag)).toBe(true);
    expect(etagMatches('*', etag)).toBe(true);
    expect(etagMatches(`W/${etag}`, etag)).toBe(true);
    expect(etagMatches('"nope"', etag)).toBe(false);
    expect(etagMatches(null, etag)).toBe(false);
  });
});

describe('source timestamps', () => {
  it('parses tgju stamps as Tehran time', () => {
    expect(tgjuStampMs('2026-08-22 14:05:01')).toBe(Date.parse('2026-08-22T14:05:01+03:30'));
    expect(tgjuStampMs('2026-08-22T14:05')).toBe(Date.parse('2026-08-22T14:05:00+03:30'));
  });

  it('refuses Jalali dates and garbage rather than misreading them as antiquity', () => {
    expect(tgjuStampMs('1405-05-31 14:05:01')).toBeNull();
    expect(tgjuStampMs('yesterday')).toBeNull();
    expect(tgjuStampMs(42)).toBeNull();
    expect(tgjuStampMs(null)).toBeNull();
  });

  it('notes staleness in words only past a day', () => {
    const now = Date.parse('2026-08-22T12:00:00Z');
    expect(staleNote(now - 25 * 3600_000, now)).toContain('stale');
    expect(staleNote(now - 1 * 3600_000, now)).toBeNull();
    expect(staleNote(null, now)).toBeNull();
  });
});

describe('parsian derivation and drop notes', () => {
  it('prefers the 1 گرم reference, then the largest survivor', () => {
    expect(deriveParsianPerSoot({ parsian_1000: 20_000_000, parsian_1500: 29_770_000 }))
      .toBe(20_000);
    expect(deriveParsianPerSoot({ parsian_200: 4_000_000, parsian_1500: 29_770_000 }))
      .toBeCloseTo(29_770_000 / 1500, 6);
    expect(deriveParsianPerSoot({})).toBeNull();
  });

  it('names every drop, capped so sources stays a note', () => {
    expect(formatDrops([])).toContain('ok');
    const many = Array.from({ length: 12 }, (_, i) => ({ id: `c${i}`, reason: 'r' }));
    const note = formatDrops(many);
    expect(note).toContain('c0 (r)');
    expect(note).toContain('and 4 more');
    expect(note).not.toContain('c9 (');
  });
});
