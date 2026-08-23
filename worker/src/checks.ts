/**
 * The plausibility invariants, and the other pure logic /rates and /wallet-balance lean on.
 *
 * A scrape that half-works is more dangerous than one that fails, because it produces a
 * confident wrong total. Absolute bands catch the grossest slips (the official IRR peg, a
 * parse landing near zero), but most single-asset slips are relative — a 10x digit slip on
 * gold looks fine on its own and absurd next to the dollar. So everything here is checked
 * against a neighbour before it is published, and whatever fails is DROPPED, never zeroed:
 * the phone already names a missing rate ("نرخ ندارد") instead of folding a guess into the
 * total, and `sources` says why it is missing.
 *
 * Every band below is dated, because bands rot. They are sized to catch factor-of-10 and
 * factor-of-1000 slips, not to referee real market moves — hence the wide margins.
 *
 * This file is deliberately free of fetch/caches/Request so it runs under plain node vitest.
 */

/**
 * The dollar's absolute rails. The official IRR peg would land near 4,200 Toman and a parse
 * slip lands near zero — both rejected rather than shown. 2026-08-22: the open market sits
 * near 187,000 Toman.
 */
export const PLAUSIBLE_USD_TOMAN = { min: 10_000, max: 10_000_000 };

/**
 * When both the bonbast-chain dollar and a Tehran USDT-Toman market answered, they must
 * agree. The Tehran premium on USDT is real but bounded — a few percent in calm weeks,
 * low double digits in bad ones — so ×1.25 is headroom, and anything past it means one of
 * the two is broken with no way to tell which. Publish neither. (2026-08-22: bonbast USD
 * ≈187k, USDT ≈190–200k.)
 */
export const USD_VS_USDT_MAX_RATIO = 1.25;

/**
 * gold18 Toman-per-gram divided by usd Toman is just the dollar price of a gram of 18k gold.
 * 2026-08-22: gold18 ≈17.8M, usd ≈187k → ratio ≈95, i.e. spot near $4,000/ozt. The band
 * [15, 600] corresponds to spot roughly $600–$25,000/ozt — far outside anything plausible
 * soon, and sized so a 10x slip in EITHER direction lands outside (95×10 = 950 > 600,
 * 95/10 = 9.5 < 15). A [10, 1000] band would let a 10x-high gold sneak under the ceiling.
 */
export const GOLD18_PER_USD = { min: 15, max: 600 };

/**
 * The bazaar مثقال is 4.6083 grams. Quoted per 17-عیار convention it works out to exactly
 * 4.3318 × gold18 (750/705 purity correction), about 6% under a naive 4.6083 × gold18 —
 * so a 15% tolerance around 4.6083 spans both conventions plus market noise, while a 10x
 * slip on either side is ~10x out.
 */
export const MESGHAL_GRAMS = 4.6083;
export const MESGHAL_MAX_RATIO = 1.15;

/**
 * Silver is much cheaper than gold and always has been. 2026-08-22: silver_999 ≈270k/g vs
 * gold18 ≈17.8M/g — a ratio near 1/66. The gold/silver ratio has spent the last century
 * between ~15 and ~130 (in fine terms), so per-gram silver_999 above gold18/20 or below
 * gold18/2000 is a slip, not a market.
 */
export const SILVER_VS_GOLD = { min: 1 / 2000, max: 1 / 20 };

/**
 * Fine-gold grams per سکه, from the mint's own specs: بهار آزادی and امامی are 8.13598 g
 * at .900 (7.32238 g fine), نیم and ربع are the half and quarter, and the گرمی is 1 g at
 * .900. Content in gold18 terms is fine / 0.75 × gold18-per-gram.
 */
export const COIN_FINE_GOLD_GRAMS: Record<string, number> = {
  coin_emami: 7.32238,
  coin_bahar: 7.32238,
  coin_nim: 3.66119,
  coin_rob: 1.8306,
  coin_gerami: 0.9,
};

/**
 * Each سکه must sit within a sane multiple of its own gold content. 2026-08-22: the امامی
 * trades ~15–50% over content, the small coins carry proportionally larger ضرب premiums
 * (the گرمی has run ~50–100%), and crisis premiums have touched ~+80–100%. [0.5, 2.5] is a
 * slip-catcher, not a market referee: no سکه has ever traded at half its melt or 2.5x it,
 * but a 10x slip is 4x outside either edge.
 */
export const COIN_CONTENT_BAND = { min: 0.5, max: 2.5 };

/**
 * By weight the سکه set must be non-increasing in price: امامی/بهار (8.14 g) ≥ نیم ≥ ربع ≥
 * گرمی. امامی and بهار weigh the same and the امامی usually edges ahead, so a 5% slack
 * keeps a normal-day near-tie from tripping the check; a 10x slip blows far past it.
 */
export const COIN_WEIGHT_ORDER = ['coin_emami', 'coin_bahar', 'coin_nim', 'coin_rob', 'coin_gerami'];
export const COIN_ORDER_SLACK = 0.05;

/**
 * سکه پارسیان rows are the same 18k gold in growing سوت sizes, so price must be
 * non-decreasing in size. Real neighbours are ~5% apart and never invert; a 2% slack only
 * forgives rounding.
 */
export const PARSIAN_ORDER_SLACK = 0.02;

/**
 * parsian_100 is 0.1 g of 18k plus a near-fixed اجرت that weighs heaviest on the smallest
 * coin. 2026-08-22: it trades ~24% over its gold, i.e. ≈0.124 × gold18. [0.08, 0.6] leaves
 * headroom for the اجرت growing and still catches a 10x slip on either side (1.24 > 0.6,
 * 0.0124 < 0.08).
 */
export const PARSIAN_100_VS_GOLD = { min: 0.08, max: 0.6 };

/**
 * When the same coin has both a Tehran Toman price and a USD price crossed through the
 * dollar rate, they must agree within ×1.3 — the Tehran crypto premium runs single digits
 * to ~20% in stressed weeks. Past that, one of the two chains is wrong for that coin and
 * there is no way to tell which: drop the coin and say so.
 */
export const CRYPTO_CROSS_MAX_RATIO = 1.3;

/**
 * Bitcoin's absolute USD rails, for the CoinGecko/Binance side. 2026-08-22: BTC has spent
 * the past year roughly $60k–130k. [5k, 5M] is deliberately loose — it exists to catch a
 * feed quoting satoshis, cents, or another asset entirely, not to have an opinion on the
 * price.
 */
export const PLAUSIBLE_BTC_USD = { min: 5_000, max: 5_000_000 };

/**
 * سکه پارسیان sizes tgju quotes, in سوت (1000 سوت to the gram), and which size stands in
 * for "one سوت": the 1 گرم coin first, because that is the reference size the market
 * itself quotes; then the largest still standing, since the اجرت shrinks with size and the
 * big coins sit closest to the gold in them.
 */
export const PARSIAN_SOOT = [100, 200, 300, 400, 500, 600, 700, 800, 900,
  1000, 1100, 1200, 1300, 1400, 1500];

export const PARSIAN_REFERENCE = [1000, ...[...PARSIAN_SOOT].reverse()];

/** The per-سوت rate the app's single سکه پارسیان row multiplies by. Null if no size survived. */
export function deriveParsianPerSoot(prices: Record<string, number>): number | null {
  const reference = PARSIAN_REFERENCE.find((s) => prices[`parsian_${s}`] != null);
  return reference == null ? null : prices[`parsian_${reference}`] / reference;
}

export type Drop = { id: string; reason: string };

/** True when the two prices are within maxRatio of each other (either direction). */
export function agreesWithin(a: number, b: number, maxRatio: number): boolean {
  if (!(a > 0) || !(b > 0)) return false;
  return (a > b ? a / b : b / a) <= maxRatio;
}

/**
 * The dollar's absolute-band verdict, phrased for the failover gate: `true` means usable,
 * a string is the reason it is not. Sitting in the gate rather than after selection means
 * a wrong-but-numeric source is recorded as failed and the chain advances to the next one,
 * instead of one bad scrape blocking a healthy fallback.
 */
export function usdBandVerdict(usd: number | null | undefined): true | string {
  if (usd == null) return 'response had no usd';
  if (usd < PLAUSIBLE_USD_TOMAN.min || usd > PLAUSIBLE_USD_TOMAN.max) {
    return `dollar rate implausible: ${usd} Toman`;
  }
  return true;
}

/** Bitcoin's absolute USD verdict, same shape as above. */
export function btcUsdVerdict(price: number): true | string {
  if (price >= PLAUSIBLE_BTC_USD.min && price <= PLAUSIBLE_BTC_USD.max) return true;
  return `USD quote ${price} outside [${PLAUSIBLE_BTC_USD.min}, ${PLAUSIBLE_BTC_USD.max}]`;
}

/**
 * The longest subsequence already in the given order (with slack), returned as the ids to
 * KEEP; everything else is the outlier set. This is how one 10x row in an otherwise clean
 * ordered set is identified without guessing: whichever rows agree with the most other
 * rows survive. O(n²), and n is 15 at most.
 */
export function longestOrderedSubset(
  entries: { id: string; value: number }[],
  direction: 'non-decreasing' | 'non-increasing',
  slack = 0,
): Set<string> {
  if (entries.length === 0) return new Set();
  const fits = direction === 'non-decreasing'
    ? (prev: number, next: number) => next >= prev * (1 - slack)
    : (prev: number, next: number) => next <= prev * (1 + slack);

  const length = entries.map(() => 1);
  const parent = entries.map(() => -1);
  let best = 0;
  for (let i = 0; i < entries.length; i++) {
    for (let j = 0; j < i; j++) {
      if (fits(entries[j].value, entries[i].value) && length[j] + 1 > length[i]) {
        length[i] = length[j] + 1;
        parent[i] = j;
      }
    }
    if (length[i] > length[best]) best = i;
  }

  const keep = new Set<string>();
  for (let i = best; i >= 0; i = parent[i]) keep.add(entries[i].id);
  return keep;
}

const round2 = (n: number) => Math.round(n * 100) / 100;

/**
 * Every relational invariant, applied in dependency order. Deletes what fails from `toman`
 * in place and returns the drops so `sources` can name each one — a failing asset is
 * dropped, never zeroed and never silently passed through.
 *
 * A check only runs when both of its operands exist: a missing anchor skips the check
 * rather than dropping the asset, so silver and پارسیان keep their existing independence —
 * the fiat chain going down still takes nothing else with it. But a DROPPED anchor is
 * treated as missing too, so nothing is ever validated against a number already judged
 * wrong.
 *
 * `usdtToman` is the Tehran USDT price from the crypto chain, the one independent referee
 * the dollar has.
 */
export function applyPlausibility(
  toman: Record<string, number>,
  usdtToman: number | null,
): Drop[] {
  const drops: Drop[] = [];
  const drop = (id: string, reason: string) => {
    if (toman[id] == null) return;
    delete toman[id];
    drops.push({ id, reason });
  };

  // The dollar first, because everything else cross-rates through it. The referee itself
  // must sit inside the absolute band to be trusted as one.
  const usdt = usdtToman != null &&
    usdtToman >= PLAUSIBLE_USD_TOMAN.min && usdtToman <= PLAUSIBLE_USD_TOMAN.max
    ? usdtToman
    : null;
  if (toman.usd != null && usdt != null && !agreesWithin(toman.usd, usdt, USD_VS_USDT_MAX_RATIO)) {
    // Prefer neither: there is no way to tell which of the two is broken, and a wrong
    // dollar multiplies into every USD-cross-rated crypto price besides.
    drop(
      'usd',
      `disagrees with the USDT-Toman market (${Math.round(usdt)}) beyond ×${USD_VS_USDT_MAX_RATIO}; ` +
        'publishing neither a dollar rate nor USD-cross-rated crypto',
    );
  }

  // Gold against whatever dollar reference survived. If the bonbast-chain dollar was just
  // dropped, the (band-checked) USDT price stands in — it is a dollar too.
  const dollarRef = toman.usd ?? usdt;
  if (toman.gold18 != null && dollarRef != null) {
    const ratio = toman.gold18 / dollarRef;
    if (ratio < GOLD18_PER_USD.min || ratio > GOLD18_PER_USD.max) {
      drop('gold18', `gold18/usd ratio ${round2(ratio)} outside [${GOLD18_PER_USD.min}, ${GOLD18_PER_USD.max}]`);
    }
  }
  const gold = toman.gold18 ?? null;

  // مثقال is 4.6083 grams of the same gold. Checked against surviving gold first; if gold
  // itself was dropped, its per-gram equivalent is held to the same dollar band instead —
  // both came off the same scrape, so one being 10x off makes the other suspect.
  if (toman.gold_mesghal != null) {
    if (gold != null) {
      if (!agreesWithin(toman.gold_mesghal, MESGHAL_GRAMS * gold, MESGHAL_MAX_RATIO)) {
        drop(
          'gold_mesghal',
          `mesghal/gold18 ratio ${round2(toman.gold_mesghal / gold)} not within 15% of ${MESGHAL_GRAMS}`,
        );
      }
    } else if (dollarRef != null) {
      const perGram = toman.gold_mesghal / MESGHAL_GRAMS / dollarRef;
      if (perGram < GOLD18_PER_USD.min || perGram > GOLD18_PER_USD.max) {
        drop('gold_mesghal', `mesghal-derived gold/usd ratio ${round2(perGram)} outside [${GOLD18_PER_USD.min}, ${GOLD18_PER_USD.max}]`);
      }
    }
  }

  // Silver is much cheaper than gold; both purities are the same order of magnitude, so
  // both get the same band. Checked only against gold that survived its own check.
  if (gold != null) {
    for (const id of ['silver_999', 'silver_925']) {
      const v = toman[id];
      if (v == null) continue;
      const ratio = v / gold;
      if (ratio <= SILVER_VS_GOLD.min || ratio >= SILVER_VS_GOLD.max) {
        drop(id, `silver/gold18 ratio ${ratio.toExponential(2)} outside (1/2000, 1/20)`);
      }
    }
  }

  // Each سکه against its own gold content, then the survivors against each other by weight.
  if (gold != null) {
    for (const [id, fine] of Object.entries(COIN_FINE_GOLD_GRAMS)) {
      const v = toman[id];
      if (v == null) continue;
      const content = (fine / 0.75) * gold;
      const ratio = v / content;
      if (ratio < COIN_CONTENT_BAND.min || ratio > COIN_CONTENT_BAND.max) {
        drop(id, `${round2(ratio)}× its gold content, outside [${COIN_CONTENT_BAND.min}, ${COIN_CONTENT_BAND.max}]`);
      }
    }
  }
  const coinsPresent = COIN_WEIGHT_ORDER
    .filter((id) => toman[id] != null)
    .map((id) => ({ id, value: toman[id] }));
  if (coinsPresent.length >= 2) {
    const keep = longestOrderedSubset(coinsPresent, 'non-increasing', COIN_ORDER_SLACK);
    for (const coin of coinsPresent) {
      if (!keep.has(coin.id)) drop(coin.id, 'breaks the by-weight ordering of the سکه set');
    }
  }

  // پارسیان rows must grow with their سوت size; the odd row out is the one that disagrees
  // with the most others. parsian_100 additionally gets an absolute anchor against gold,
  // which catches the whole set being uniformly 10x off (monotonicity alone cannot).
  const parsianRows = PARSIAN_SOOT
    .filter((s) => toman[`parsian_${s}`] != null)
    .map((s) => ({ id: `parsian_${s}`, value: toman[`parsian_${s}`] }));
  if (parsianRows.length >= 2) {
    const keep = longestOrderedSubset(parsianRows, 'non-decreasing', PARSIAN_ORDER_SLACK);
    for (const row of parsianRows) {
      if (!keep.has(row.id)) drop(row.id, 'breaks the non-decreasing سوت ordering');
    }
  }
  if (gold != null && toman.parsian_100 != null) {
    const ratio = toman.parsian_100 / gold;
    if (ratio < PARSIAN_100_VS_GOLD.min || ratio > PARSIAN_100_VS_GOLD.max) {
      drop('parsian_100', `parsian_100/gold18 ratio ${round2(ratio)} outside [${PARSIAN_100_VS_GOLD.min}, ${PARSIAN_100_VS_GOLD.max}]`);
    }
  }

  // The derived per-سوت rate must come from a row that survived, so it is re-derived here.
  if (toman.parsian != null) {
    const derived = deriveParsianPerSoot(toman);
    if (derived == null) drop('parsian', 'no surviving سوت row to derive the per-سوت rate from');
    else toman.parsian = derived;
  }

  return drops;
}

/** How the drops read in `sources` — every dropped asset named, capped so it stays a note. */
export function formatDrops(drops: Drop[]): string {
  if (drops.length === 0) return 'ok, every cross-check passed';
  const listed = drops.slice(0, 8).map((d) => `${d.id} (${d.reason})`).join('; ');
  return `dropped ${listed}${drops.length > 8 ? `; and ${drops.length - 8} more` : ''}`;
}

// ───────────────────────── reserved ids ─────────────────────────

/**
 * Every non-crypto id /rates publishes. A crypto ticker that collides with one of these —
 * a token coded PARSIAN, say — would silently overwrite a gold price with a coin price.
 * The fiat/gold/سکه ids come in from BONBAST_MAP so the two lists cannot drift.
 */
export function reservedTomanIds(fiatGoldCoinIds: Iterable<string>): Set<string> {
  const ids = new Set(['toman', 'silver_999', 'silver_925', 'parsian']);
  for (const id of fiatGoldCoinIds) ids.add(id);
  for (const soot of PARSIAN_SOOT) ids.add(`parsian_${soot}`);
  return ids;
}

// ───────────────────────── source timestamps ─────────────────────────

/**
 * tgju stamps its rows in Tehran local time, "YYYY-MM-DD HH:mm:ss". Parsed as +03:30; a
 * value that is not Gregorian-shaped (tgju also writes Jalali dates, year ~1400, which
 * must not be read as antiquity) returns null rather than a wrong epoch.
 */
export function tgjuStampMs(value: unknown): number | null {
  if (typeof value !== 'string') return null;
  const m = value.trim().match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?$/);
  if (!m) return null;
  const year = Number(m[1]);
  if (year < 2015 || year > 2100) return null;
  const ms = Date.parse(`${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6] ?? '00'}+03:30`);
  return Number.isFinite(ms) ? ms : null;
}

const STALE_AFTER_MS = 24 * 3600 * 1000;

/**
 * A note in words when the source's own stamp is older than a day — matching the app's
 * stale-rates-in-words rule. The top-level updatedAt is when THIS worker answered and the
 * phone depends on that meaning; staleness of the upstream is a sentence, not a timestamp
 * swap.
 */
export function staleNote(freshestMs: number | null, nowMs: number): string | null {
  if (freshestMs == null) return null;
  const age = nowMs - freshestMs;
  if (age <= STALE_AFTER_MS) return null;
  return `stale: the source's own freshest stamp is ${Math.round(age / 3600_000)}h old`;
}

// ───────────────────────── rate limiting ─────────────────────────

/**
 * A classic token bucket per key. It lives in one isolate's memory, so it is friction, not
 * a wall — Cloudflare runs many isolates and restarts them freely, and a determined abuser
 * gets a fresh bucket at each. That is fine: the point is to keep one misbehaving client
 * from turning /wallet-balance into a free blockchain-RPC relay, not to bill anyone.
 */
export class TokenBucket {
  private readonly buckets = new Map<string, { tokens: number; at: number }>();

  constructor(
    private readonly capacity: number,
    private readonly refillPerMs: number,
    private readonly maxKeys = 10_000,
  ) {}

  take(key: string, nowMs: number): { ok: true } | { ok: false; retryAfterSeconds: number } {
    let bucket = this.buckets.get(key);
    if (bucket == null) {
      if (this.buckets.size >= this.maxKeys) this.prune(nowMs);
      bucket = { tokens: this.capacity, at: nowMs };
      this.buckets.set(key, bucket);
    } else {
      bucket.tokens = Math.min(
        this.capacity,
        bucket.tokens + Math.max(0, nowMs - bucket.at) * this.refillPerMs,
      );
      bucket.at = nowMs;
    }
    if (bucket.tokens >= 1) {
      bucket.tokens -= 1;
      return { ok: true };
    }
    return {
      ok: false,
      retryAfterSeconds: Math.max(1, Math.ceil((1 - bucket.tokens) / this.refillPerMs / 1000)),
    };
  }

  /** Drops every bucket that has been idle long enough to be full again. */
  private prune(nowMs: number): void {
    const idleMs = this.capacity / this.refillPerMs;
    for (const [key, bucket] of this.buckets) {
      if (nowMs - bucket.at >= idleMs) this.buckets.delete(key);
    }
  }
}

// ───────────────────────── conditional requests ─────────────────────────

/**
 * A strong ETag from the body bytes. Iranian mobile data is metered and the coin catalogue
 * is the bulk of /rates; a 304 costs headers instead. 128 bits of SHA-256 is far beyond
 * what collision-across-ten-minute-caches needs.
 */
export async function bodyEtag(body: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(body));
  const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `"${hex.slice(0, 32)}"`;
}

/**
 * If-None-Match per RFC 9110: a comma-separated list, `*` matches anything, and the
 * comparison is weak — a `W/` prefix on either side is ignored for cache validation.
 */
export function etagMatches(ifNoneMatch: string | null, etag: string): boolean {
  if (!ifNoneMatch) return false;
  const bare = (tag: string) => (tag.startsWith('W/') ? tag.slice(2) : tag);
  const target = bare(etag);
  return ifNoneMatch
    .split(',')
    .map((tag) => tag.trim())
    .some((tag) => tag === '*' || bare(tag) === target);
}
